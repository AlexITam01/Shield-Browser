package com.shieldbrowser.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.roundToInt

/**
 * In-app PDF viewer (no new dependency).
 *
 * Renders one page at a time with [PdfRenderer]. The page bitmap is shown in an
 * ImageView inside a ScrollView + HorizontalScrollView so it can be panned when
 * zoomed. Pinch zoom re-renders the page at the new zoom level; prev/next buttons
 * change pages. The file is expected in cacheDir (written there by MainActivity
 * after downloading it); it is not deleted here so the user can reopen it.
 */
class PdfViewerActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "pdf_path"
    }

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var pageIndex = 0
    private var zoom = 1f
    private var pendingZoom = 1f

    private lateinit var imageView: ImageView
    private lateinit var pageLabel: TextView
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var scrollView: ScrollView
    private lateinit var hScrollView: HorizontalScrollView
    private lateinit var scaleDetector: ScaleGestureDetector

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrEmpty()) {
            toast("No PDF to open")
            finish()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            toast("PDF file not found")
            finish()
            return
        }
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
        } catch (e: Exception) {
            toast("Couldn't open PDF: ${e.message ?: e.javaClass.simpleName}")
            finish()
            return
        }
        pageCount = renderer?.pageCount ?: 0
        if (pageCount == 0) {
            toast("This PDF has no pages")
            finish()
            return
        }
        buildUi()
        showPage(0)
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(8), dp(8), dp(8))

        val close = Button(this).apply {
            text = "×"
            textSize = 22f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        bar.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))

        pageLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        bar.addView(pageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        prevBtn = Button(this).apply {
            text = "◀"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setOnClickListener { if (pageIndex > 0) showPage(pageIndex - 1) }
        }
        bar.addView(prevBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        nextBtn = Button(this).apply {
            text = "▶"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setOnClickListener { if (pageIndex < pageCount - 1) showPage(pageIndex + 1) }
        }
        bar.addView(nextBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        root.addView(bar)

        imageView = ImageView(this)
        imageView.setBackgroundColor(Color.WHITE)
        imageView.scaleType = ImageView.ScaleType.FIT_XY

        hScrollView = HorizontalScrollView(this)
        hScrollView.addView(imageView)
        scrollView = ScrollView(this)
        scrollView.addView(
            hScrollView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pendingZoom = zoom
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pendingZoom = (pendingZoom * detector.scaleFactor).coerceIn(1f, 4f)
                val visual = pendingZoom / zoom
                imageView.scaleX = visual
                imageView.scaleY = visual
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                zoom = pendingZoom
                imageView.scaleX = 1f
                imageView.scaleY = 1f
                renderPage()
            }
        })
    }

    /** Feed pinch gestures to the scale detector; swallow moves while pinching so the page doesn't scroll. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::scaleDetector.isInitialized) {
            scaleDetector.onTouchEvent(ev)
            if (scaleDetector.isInProgress) return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showPage(index: Int) {
        pageIndex = index
        zoom = 1f
        pendingZoom = 1f
        imageView.scaleX = 1f
        imageView.scaleY = 1f
        renderPage()
        updateNav()
    }

    private fun updateNav() {
        pageLabel.text = "${pageIndex + 1} / $pageCount"
        prevBtn.isEnabled = pageIndex > 0
        nextBtn.isEnabled = pageIndex < pageCount - 1
        prevBtn.alpha = if (pageIndex > 0) 1f else 0.4f
        nextBtn.alpha = if (pageIndex < pageCount - 1) 1f else 0.4f
    }

    /**
     * Renders the current page to a bitmap. Display size is the screen width times
     * the zoom factor (height follows the page aspect); the bitmap is rendered at
     * 2x display size for crispness, capped at 4096px on a side to avoid OOM.
     * Runs on the UI thread; a single page render is fast enough for v0.3.0.
     */
    private fun renderPage() {
        val r = renderer ?: return
        if (pageIndex !in 0 until pageCount) return
        val page = r.openPage(pageIndex)
        try {
            if (page.width <= 0 || page.height <= 0) return
            val screenW = resources.displayMetrics.widthPixels
            val dispW = (screenW * zoom).roundToInt().coerceAtLeast(1)
            val dispH = (dispW * page.height.toFloat() / page.width.toFloat()).roundToInt().coerceAtLeast(1)
            var bw = dispW * 2
            var bh = dispH * 2
            val maxDim = 4096
            if (maxOf(bw, bh) > maxDim) {
                val s = maxDim.toFloat() / maxOf(bw, bh)
                bw = (bw * s).roundToInt().coerceAtLeast(1)
                bh = (bh * s).roundToInt().coerceAtLeast(1)
            }
            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val old = (imageView.drawable as? BitmapDrawable)?.bitmap
            imageView.setImageBitmap(bitmap)
            if (old != null && !old.isRecycled && old != bitmap) old.recycle()
            imageView.layoutParams = FrameLayout.LayoutParams(dispW, dispH)
            imageView.requestLayout()
        } finally {
            page.close()
        }
    }

    override fun onDestroy() {
        if (::imageView.isInitialized) {
            val old = (imageView.drawable as? BitmapDrawable)?.bitmap
            if (old != null && !old.isRecycled) old.recycle()
            imageView.setImageDrawable(null)
        }
        try { renderer?.close() } catch (e: Exception) { /* ignore */ }
        try { pfd?.close() } catch (e: Exception) { /* ignore */ }
        renderer = null
        pfd = null
        super.onDestroy()
    }
}

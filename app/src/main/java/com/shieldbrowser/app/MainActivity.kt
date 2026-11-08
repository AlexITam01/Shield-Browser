package com.shieldbrowser.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class Palette(val name: String, val bg: String, val fg: String, val accent: String)

private val PRESETS = listOf(
    Palette("Midnight", "#0F1220", "#E6E9F5", "#7C9CFF"),
    Palette("Forest", "#0E1A14", "#E3F1E8", "#4ADE80"),
    Palette("Sunset", "#1F1210", "#FFEDE3", "#FF7A59"),
    Palette("Paper", "#F6F3EC", "#1D1D1B", "#0B6E4F")
)

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var blocker: TrackerBlocker
    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var web: WebView
    private lateinit var shieldBtn: Button
    private val buttons = mutableListOf<Button>()

    private var pal = PRESETS[0]
    private val ui = Handler(Looper.getMainLooper())
    private val blockedCount = AtomicInteger(0)
    private val badgeQueued = AtomicBoolean(false)
    private val sessionSalt = UUID.randomUUID().toString()
    private var canvasScript = ""
    private var docStartSupported = false

    @Volatile
    private var blockerOn = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("shield", Context.MODE_PRIVATE)
        blocker = TrackerBlocker(assets)
        blockerOn = prefs.getBoolean("blocker", true)
        pal = Palette(
            "Saved",
            prefs.getString("bg", PRESETS[0].bg) ?: PRESETS[0].bg,
            prefs.getString("fg", PRESETS[0].fg) ?: PRESETS[0].fg,
            prefs.getString("accent", PRESETS[0].accent) ?: PRESETS[0].accent
        )

        buildUi()
        configureWebView()
        applyTheme()
        updateShield()

        val data = intent?.dataString
        if (data != null) navigate(data) else loadStart()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { navigate(it) }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun navButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 18f
        b.setBackgroundColor(Color.TRANSPARENT)
        b.stateListAnimator = null
        b.minWidth = 0
        b.minimumWidth = 0
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        buttons.add(b)
        return b
    }

    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(10), dp(8), dp(10), dp(8))

        urlBar = EditText(this)
        urlBar.hint = "Search or enter address"
        urlBar.setSingleLine()
        urlBar.setSelectAllOnFocus(true)
        urlBar.imeOptions = EditorInfo.IME_ACTION_GO
        urlBar.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBar.setPadding(dp(16), 0, dp(16), 0)
        urlBar.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                navigate(urlBar.text.toString())
                hideKeyboard()
                true
            } else {
                false
            }
        }
        topBar.addView(urlBar)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE
        progress.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))

        web = WebView(this)
        web.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.CENTER_VERTICAL
        bottomBar.addView(navButton("◀") { if (web.canGoBack()) web.goBack() })
        bottomBar.addView(navButton("▶") { if (web.canGoForward()) web.goForward() })
        bottomBar.addView(navButton("⟳") { web.reload() })
        shieldBtn = navButton("🛡") { toggleBlocker() }
        bottomBar.addView(shieldBtn)
        bottomBar.addView(navButton("🔋") { toggleLite() })
        bottomBar.addView(navButton("🎨") { pickTheme() })

        root.addView(topBar)
        root.addView(progress)
        root.addView(web)
        root.addView(bottomBar)
        setContentView(root)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    // ------------------------------------------------------------ Theme

    private fun withAlpha(c: Int, a: Int): Int = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun luma(hex: String): Double {
        val c = Color.parseColor(hex)
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val bg = Color.parseColor(pal.bg)
        val fg = Color.parseColor(pal.fg)
        val ac = Color.parseColor(pal.accent)

        root.setBackgroundColor(bg)
        topBar.setBackgroundColor(bg)
        bottomBar.setBackgroundColor(bg)
        web.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val bar = GradientDrawable()
        bar.cornerRadius = dp(22).toFloat()
        bar.setColor(withAlpha(ac, 40))
        bar.setStroke(dp(1), ac)
        urlBar.background = bar
        urlBar.setTextColor(fg)
        urlBar.setHintTextColor(withAlpha(fg, 120))

        progress.progressTintList = ColorStateList.valueOf(ac)
        buttons.forEach { it.setTextColor(ac) }

        val light = luma(pal.bg) > 0.5
        var flags = window.decorView.systemUiVisibility
        flags = if (light) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun setPalette(p: Palette) {
        pal = p
        prefs.edit().putString("bg", p.bg).putString("fg", p.fg).putString("accent", p.accent).apply()
        applyTheme()
    }

    private fun pickTheme() {
        val names = PRESETS.map { it.name } + "Custom…"
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < PRESETS.size) setPalette(PRESETS[which]) else customTheme()
            }
            .show()
    }

    private fun customTheme() {
        promptHex("Background color (hex)", pal.bg) { bg ->
            promptHex("Accent color (hex)", pal.accent) { accent ->
                val fg = if (luma(bg) > 0.5) "#111111" else "#F2F2F2"
                setPalette(Palette("Custom", bg, fg, accent))
            }
        }
    }

    private fun promptHex(title: String, default: String, done: (String) -> Unit) {
        val input = EditText(this)
        input.setText(default)
        input.setSingleLine()
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val raw = input.text.toString().trim()
                val hex = if (raw.startsWith("#")) raw else "#$raw"
                try {
                    Color.parseColor(hex)
                    done(hex)
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, "Invalid color", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ----------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.safeBrowsingEnabled = true

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, false)

        // Canvas fingerprint shield: runs before page scripts when the WebView supports it.
        canvasScript = assets.open("canvas_shield.js").bufferedReader().use { it.readText() }
            .replace("__SALT__", sessionSalt)
        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(web, canvasScript, setOf("*"))
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (!blockerOn || request == null || request.isForMainFrame) return null
                if (blocker.isBlocked(request.url.host)) {
                    blockedCount.incrementAndGet()
                    scheduleBadge()
                    return WebResourceResponse(
                        "text/plain", "utf-8", 204, "No Content",
                        emptyMap(), ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                return when (u.scheme) {
                    "http" -> {
                        view?.loadUrl(u.buildUpon().scheme("https").build().toString())
                        true
                    }
                    "https", "about", "data", "blob" -> false
                    else -> true // block intent:, file:, custom app schemes
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                blockedCount.set(0)
                updateShield()
                if (!docStartSupported) view?.evaluateJavascript(canvasScript, null)
                if (url != null && url.startsWith("http") && !urlBar.hasFocus()) {
                    urlBar.setText(url)
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
    }

    private fun navigate(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val url = when {
            q.startsWith("https://") -> q
            q.startsWith("http://") -> "https://" + q.removePrefix("http://")
            q.contains(' ') || !q.contains('.') -> "https://duckduckgo.com/?q=" + Uri.encode(q)
            else -> "https://$q"
        }
        web.loadUrl(url)
    }

    private fun loadStart() {
        val html = """
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;
                   justify-content:center;background:${pal.bg};color:${pal.fg};font-family:sans-serif}
              h1{font-weight:300;letter-spacing:2px;margin:0 0 24px}
              input{width:80%;max-width:420px;padding:14px 18px;border-radius:26px;font-size:16px;
                    border:1px solid ${pal.accent};background:transparent;color:${pal.fg};outline:none}
              p{opacity:.5;font-size:12px;margin-top:20px}
            </style></head><body>
            <h1 style="color:${pal.accent}">SHIELD</h1>
            <form action="https://duckduckgo.com/" method="get">
              <input name="q" placeholder="Search privately" autocomplete="off">
            </form>
            <p>Trackers blocked · HTTPS only</p>
            </body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // ------------------------------------------------ Shield / battery

    private fun scheduleBadge() {
        if (badgeQueued.compareAndSet(false, true)) {
            // Batched: at most one UI update per half second, to avoid waking the UI thread constantly.
            ui.postDelayed({
                badgeQueued.set(false)
                updateShield()
            }, 500)
        }
    }

    private fun updateShield() {
        shieldBtn.text = if (blockerOn) "🛡 ${blockedCount.get()}" else "🛡 off"
    }

    private fun toggleBlocker() {
        blockerOn = !blockerOn
        prefs.edit().putBoolean("blocker", blockerOn).apply()
        blockedCount.set(0)
        updateShield()
        Toast.makeText(this, if (blockerOn) "Tracker blocking on" else "Tracker blocking off", Toast.LENGTH_SHORT).show()
        web.reload()
    }

    private fun liteActive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return prefs.getBoolean("lite", false) || pm.isPowerSaveMode
    }

    private fun applyLite() {
        web.settings.blockNetworkImage = liteActive()
    }

    private fun toggleLite() {
        prefs.edit().putBoolean("lite", !prefs.getBoolean("lite", false)).apply()
        applyLite()
        val on = prefs.getBoolean("lite", false)
        Toast.makeText(
            this,
            if (on) "Lite mode on: images off" else "Lite mode off (still auto-on in Battery Saver)",
            Toast.LENGTH_SHORT
        ).show()
        web.reload()
    }

    // --------------------------------------------------------- Lifecycle

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.resumeTimers()
        applyLite() // auto lite mode when the system Battery Saver is on
    }

    override fun onPause() {
        // Stop page JS timers and rendering in the background: the biggest battery win.
        web.onPause()
        web.pauseTimers()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}

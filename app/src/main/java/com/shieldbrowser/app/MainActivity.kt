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
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
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

class Tab(val web: WebView) {
    var title: String = ""
    var url: String = ""
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val REQ_VPN = 41
private const val REQ_PICK = 42
private const val MAX_TABS = 10

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var blocker: TrackerBlocker
    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var webHolder: FrameLayout
    private lateinit var scrim: View
    private lateinit var rail: LinearLayout
    private lateinit var railScroll: ScrollView
    private lateinit var tabList: LinearLayout
    private lateinit var shieldBtn: Button
    private lateinit var tabsBtn: Button
    private val buttons = mutableListOf<Button>()

    private val tabs = mutableListOf<Tab>()
    private var activeIndex = 0
    private val current: Tab get() = tabs[activeIndex]
    private val web: WebView get() = current.web

    private var pal = PRESETS[0]
    private val ui = Handler(Looper.getMainLooper())
    private val blockedCount = AtomicInteger(0)
    private val badgeQueued = AtomicBoolean(false)
    private val sessionSalt = UUID.randomUUID().toString()
    private var canvasScript = ""
    private var docStartSupported = false

    private var railOpen = false
    private val railW: Int get() = dp(76)

    private var gx = 0f
    private var gy = 0f
    private var gestureMode = 0 // 0 none, 1 may open rail, 2 may close rail, 3 swallowing

    @Volatile
    private var blockerOn = true

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

        canvasScript = assets.open("canvas_shield.js").bufferedReader().use { it.readText() }
            .replace("__SALT__", sessionSalt)
        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        buildUi()
        applyTheme()

        val first = createTab()
        activeIndex = tabs.indexOf(first)
        showActive()
        val data = intent?.dataString
        if (data != null) navigate(data) else loadStart(first)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val data = intent?.dataString ?: return
        openNewTab(data)
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
        progress.layoutParams = LinearLayout.LayoutParams(MATCH, dp(3))

        // Content area: web view + dim layer + vertical tab rail on the right.
        content = FrameLayout(this)
        content.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)

        webHolder = FrameLayout(this)
        content.addView(webHolder, FrameLayout.LayoutParams(MATCH, MATCH))

        scrim = View(this)
        scrim.setBackgroundColor(Color.parseColor("#66000000"))
        scrim.visibility = View.GONE
        scrim.setOnClickListener { closeRail() }
        content.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        rail = LinearLayout(this)
        rail.orientation = LinearLayout.VERTICAL
        rail.visibility = View.GONE
        rail.isClickable = true // swallow touches so they don't reach the page underneath

        val plus = navButton("+") { addTabFromRail() }
        plus.textSize = 26f
        plus.layoutParams = LinearLayout.LayoutParams(MATCH, dp(56))
        rail.addView(plus)

        tabList = LinearLayout(this)
        tabList.orientation = LinearLayout.VERTICAL
        railScroll = ScrollView(this)
        railScroll.isVerticalScrollBarEnabled = false
        railScroll.addView(tabList, ViewGroup.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        rail.addView(railScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val railLp = FrameLayout.LayoutParams(railW, MATCH)
        railLp.gravity = Gravity.END
        content.addView(rail, railLp)

        if (Build.VERSION.SDK_INT >= 29) {
            // Stop the system "back" gesture from stealing a swipe near the right edge (middle strip).
            content.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                val h = b - t
                val w = r - l
                v.systemGestureExclusionRects = listOf(
                    Rect(w - dp(56), maxOf(0, h / 2 - dp(100)), w, minOf(h, h / 2 + dp(100)))
                )
            }
        }

        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.CENTER_VERTICAL
        bottomBar.addView(navButton("◀") { if (web.canGoBack()) web.goBack() })
        bottomBar.addView(navButton("▶") { if (web.canGoForward()) web.goForward() })
        bottomBar.addView(navButton("⟳") { web.reload() })
        shieldBtn = navButton("🛡") { toggleBlocker() }
        bottomBar.addView(shieldBtn)
        tabsBtn = navButton("1") { if (railOpen) closeRail() else openRail() }
        tabsBtn.textSize = 15f
        bottomBar.addView(tabsBtn)
        bottomBar.addView(navButton("⋮") { showMenu() })

        root.addView(topBar)
        root.addView(progress)
        root.addView(content)
        root.addView(bottomBar)
        setContentView(root)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    private fun showKeyboard() {
        urlBar.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(urlBar, 0)
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
        tabs.forEach { it.web.setBackgroundColor(bg) }
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

        val box = GradientDrawable()
        box.cornerRadius = dp(8).toFloat()
        box.setColor(Color.TRANSPARENT)
        box.setStroke(dp(2), ac)
        tabsBtn.background = InsetDrawable(box, dp(14), dp(10), dp(14), dp(10))

        val railBg = GradientDrawable()
        val r = dp(18).toFloat()
        railBg.cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        railBg.setColor(bg)
        railBg.setStroke(dp(1), withAlpha(ac, 140))
        rail.background = railBg

        val light = luma(pal.bg) > 0.5
        var flags = window.decorView.systemUiVisibility
        flags = if (light) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        window.decorView.systemUiVisibility = flags

        refreshRail(-1)
        updateShield()
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
                    toast("Invalid color")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------- Tabs

    private fun createTab(): Tab {
        val w = WebView(this)
        w.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        val tab = Tab(w)
        configureWebView(tab)
        tabs.add(tab)
        return tab
    }

    /** Makes tabs[activeIndex] the visible one; pauses the rest. */
    private fun showActive() {
        val w = current.web
        if (webHolder.childCount != 1 || webHolder.getChildAt(0) !== w) {
            webHolder.removeAllViews()
            webHolder.addView(w)
        }
        tabs.forEachIndexed { i, t -> if (i == activeIndex) t.web.onResume() else t.web.onPause() }
        w.setBackgroundColor(Color.parseColor(pal.bg))
        syncUrlBar()
        blockedCount.set(0)
        updateShield()
        updateTabsBtn()
        applyLite()
    }

    private fun switchTo(i: Int) {
        if (i !in tabs.indices || i == activeIndex) return
        activeIndex = i
        showActive()
        refreshRail(-1)
    }

    private fun openNewTab(url: String?) {
        if (tabs.size >= MAX_TABS) {
            toast("Tab limit reached ($MAX_TABS)")
            return
        }
        val t = createTab()
        activeIndex = tabs.indexOf(t)
        showActive()
        if (url != null) navigate(url) else loadStart(t)
        refreshRail(activeIndex)
    }

    private fun addTabFromRail() {
        if (tabs.size >= MAX_TABS) {
            toast("Tab limit reached ($MAX_TABS)")
            return
        }
        openNewTab(null)
        closeRail()
        showKeyboard()
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        if (webHolder.indexOfChild(t.web) >= 0) webHolder.removeView(t.web)
        t.web.destroy()
        if (tabs.isEmpty()) {
            val n = createTab()
            activeIndex = tabs.indexOf(n)
            showActive()
            loadStart(n)
        } else {
            if (i < activeIndex) activeIndex-- else if (i == activeIndex) activeIndex = minOf(i, tabs.size - 1)
            showActive()
        }
        refreshRail(-1)
    }

    private fun tabLabel(t: Tab): String {
        val host = try { Uri.parse(t.url).host } catch (e: Exception) { null }
        val src = host?.removePrefix("www.") ?: return "S"
        return src.firstOrNull()?.uppercaseChar()?.toString() ?: "S"
    }

    private fun updateTabsBtn() {
        tabsBtn.text = tabs.size.toString()
    }

    private fun syncUrlBar() {
        val u = current.url
        urlBar.setText(if (u.startsWith("http")) u else "")
    }

    /** Rebuilds the vertical rail. Tiles at index >= animateFrom slide in from the right. */
    private fun refreshRail(animateFrom: Int) {
        if (!::tabList.isInitialized) return
        tabList.removeAllViews()
        val ac = Color.parseColor(pal.accent)
        val fg = Color.parseColor(pal.fg)
        tabs.forEachIndexed { i, t ->
            val active = i == activeIndex
            val tile = FrameLayout(this)
            val lp = LinearLayout.LayoutParams(dp(56), dp(56))
            lp.setMargins(dp(10), dp(6), dp(10), dp(6))
            tile.layoutParams = lp

            val shape = GradientDrawable()
            shape.cornerRadius = dp(14).toFloat()
            if (active) {
                shape.setColor(withAlpha(ac, 70))
                shape.setStroke(dp(2), ac)
            } else {
                shape.setColor(withAlpha(fg, 25))
                shape.setStroke(dp(1), withAlpha(fg, 60))
            }
            tile.background = shape

            val letter = TextView(this)
            letter.text = tabLabel(t)
            letter.textSize = 22f
            letter.gravity = Gravity.CENTER
            letter.setTextColor(if (active) ac else fg)
            tile.addView(letter, FrameLayout.LayoutParams(MATCH, MATCH))

            val x = TextView(this)
            x.text = "×"
            x.textSize = 16f
            x.gravity = Gravity.CENTER
            x.setTextColor(withAlpha(fg, 210))
            x.setOnClickListener { closeTab(i) }
            tile.addView(x, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END))

            tile.setOnClickListener {
                switchTo(i)
                closeRail()
            }
            tabList.addView(tile)

            if (animateFrom in 0..i) {
                tile.translationX = dp(90).toFloat()
                tile.alpha = 0f
                tile.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setStartDelay((i - animateFrom) * 40L)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun openRail() {
        if (railOpen) return
        railOpen = true
        hideKeyboard()
        refreshRail(0)
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        rail.visibility = View.VISIBLE
        rail.translationX = railW.toFloat()
        rail.animate().translationX(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closeRail() {
        if (!railOpen) return
        railOpen = false
        scrim.animate().alpha(0f).setDuration(150).withEndAction {
            if (!railOpen) scrim.visibility = View.GONE
        }.start()
        rail.animate().translationX(railW.toFloat()).setDuration(180).withEndAction {
            if (!railOpen) rail.visibility = View.GONE
        }.start()
    }

    // Swipe left near the right edge opens the rail; swipe right while it is open closes it.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gx = ev.rawX
                gy = ev.rawY
                val w = resources.displayMetrics.widthPixels
                gestureMode = when {
                    railOpen -> 2
                    ev.rawX > w - dp(56) -> 1
                    onBar(bottomBar, ev) -> 1 // also works along the bottom bar (the system back gesture can't steal it)
                    else -> 0
                }
            }
            MotionEvent.ACTION_MOVE -> if (gestureMode == 1 || gestureMode == 2) {
                val dx = ev.rawX - gx
                val dy = ev.rawY - gy
                if (Math.abs(dx) > dp(24) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    val opens = gestureMode == 1 && dx < 0
                    val closes = gestureMode == 2 && dx > 0
                    if (opens || closes) {
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        if (opens) openRail() else closeRail()
                        gestureMode = 3
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val swallowed = gestureMode == 3
                gestureMode = 0
                if (swallowed) return true
            }
        }
        if (gestureMode == 3) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun onBar(v: View, ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width &&
            ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
    }

    // ----------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(tab: Tab) {
        val w = tab.web
        val s = w.settings
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
        cookies.setAcceptThirdPartyCookies(w, false)

        if (docStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(w, canvasScript, setOf("*"))
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (!blockerOn || request == null || request.isForMainFrame) return null
                if (blocker.isBlocked(request.url.host)) {
                    if (tabs.getOrNull(activeIndex) === tab) {
                        blockedCount.incrementAndGet()
                        scheduleBadge()
                    }
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
                tab.url = url ?: ""
                if (!docStartSupported) view?.evaluateJavascript(canvasScript, null)
                if (tabs.getOrNull(activeIndex) === tab) {
                    blockedCount.set(0)
                    updateShield()
                    if (!urlBar.hasFocus()) syncUrlBar()
                }
                if (railOpen) refreshRail(-1)
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tabs.getOrNull(activeIndex) !== tab) return
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.title = title ?: ""
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

    private fun loadStart(tab: Tab) {
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
        tab.url = ""
        tab.web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
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
        val base = if (blockerOn) "🛡 ${blockedCount.get()}" else "🛡 off"
        shieldBtn.text = if (VpnController.isUp()) "$base 🔒" else base
    }

    private fun toggleBlocker() {
        blockerOn = !blockerOn
        prefs.edit().putBoolean("blocker", blockerOn).apply()
        blockedCount.set(0)
        updateShield()
        toast(if (blockerOn) "Tracker blocking on" else "Tracker blocking off")
        web.reload()
    }

    private fun liteActive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return prefs.getBoolean("lite", false) || pm.isPowerSaveMode
    }

    private fun applyLite() {
        val lite = liteActive()
        tabs.forEach { it.web.settings.blockNetworkImage = lite }
    }

    private fun toggleLite() {
        val on = !prefs.getBoolean("lite", false)
        prefs.edit().putBoolean("lite", on).apply()
        applyLite()
        toast(if (on) "Lite mode on: images off" else "Lite mode off (still auto-on in Battery Saver)")
        web.reload()
    }

    private fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach { it.web.clearCache(true) }
        toast("Cookies, site data and cache cleared")
    }

    // --------------------------------------------------------------- VPN

    private val configFile: File get() = File(filesDir, "wg.conf")

    private fun showMenu() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add("New tab" to { addTabFromRail() })
        actions.add((if (VpnController.isUp()) "VPN: disconnect" else "VPN: connect") to { toggleVpn() })
        actions.add("VPN: import WireGuard config" to { importConfig() })
        actions.add("Theme" to { pickTheme() })
        actions.add((if (prefs.getBoolean("lite", false)) "Lite mode: turn off" else "Lite mode: turn on") to { toggleLite() })
        actions.add("Clear cookies & site data" to { clearData() })
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun importConfig() {
        AlertDialog.Builder(this)
            .setTitle("WireGuard config")
            .setItems(arrayOf("Choose a .conf file", "Paste config text")) { _, which ->
                if (which == 0) pickConfigFile() else pasteConfig()
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pickConfigFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, REQ_PICK)
    }

    private fun pasteConfig() {
        val input = EditText(this)
        input.hint = "[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\n…"
        input.minLines = 6
        input.gravity = Gravity.TOP
        input.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        AlertDialog.Builder(this)
            .setTitle("Paste WireGuard config")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> saveConfig(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun readConfigFrom(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text == null) toast("Couldn't read that file") else saveConfig(text)
        } catch (e: Exception) {
            toast("Couldn't read that file")
        }
    }

    private fun saveConfig(text: String) {
        if (text.length > 65536) {
            toast("That file is too large for a WireGuard config")
            return
        }
        try {
            VpnController.parse(text) // validates before saving
            configFile.writeText(text)
            toast("Config saved. Use ⋮ > VPN: connect")
        } catch (e: Exception) {
            toast("Not a valid WireGuard config: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleVpn() {
        if (VpnController.isUp()) {
            Thread {
                try {
                    VpnController.disconnect(this)
                } catch (e: Exception) {
                    // ignore: state is re-read below
                }
                runOnUiThread {
                    updateShield()
                    toast("VPN disconnected")
                }
            }.start()
            return
        }
        if (!configFile.exists()) {
            toast("Import a WireGuard config first")
            importConfig()
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQ_VPN) else startVpn()
    }

    private fun startVpn() {
        toast("Connecting…")
        Thread {
            val err = try {
                VpnController.connect(this, configFile)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                toast(if (err != null) "VPN failed: $err" else "VPN connected")
                updateShield()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> if (resultCode == RESULT_OK) startVpn() else toast("VPN permission denied")
            REQ_PICK -> if (resultCode == RESULT_OK) data?.data?.let { readConfigFrom(it) }
        }
    }

    // --------------------------------------------------------- Lifecycle

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.resumeTimers()
        applyLite() // auto lite mode when the system Battery Saver is on
        updateShield()
    }

    override fun onPause() {
        // Stop page JS timers and rendering in the background: the biggest battery win.
        web.onPause()
        web.pauseTimers()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        tabs.forEach {
            (it.web.parent as? ViewGroup)?.removeView(it.web)
            it.web.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            railOpen -> closeRail()
            web.canGoBack() -> web.goBack()
            tabs.size > 1 -> closeTab(activeIndex)
            else -> super.onBackPressed()
        }
    }
}

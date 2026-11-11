package com.freebrowser.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Clean front end. All the censorship bypass (DoH, TLS split, relay fallback, ad blocking) runs
 * silently in the background (see LocalProxy / WsSocket) - there are no bypass options in the UI.
 */
class MainActivity : Activity() {

    private class Tab(val web: WebView) {
        var title: String = ""
        var url: String = ""
    }

    /** A download in progress (or failed). Finished downloads live in [Store]. */
    private class Dl(val id: Long, val name: String, val url: String) {
        @Volatile var status = 0 // 0 running, 2 failed
        @Volatile var done = 0L
        @Volatile var total = -1L
        @Volatile var cancel = false
    }

    private class RowViews(val root: LinearLayout, val title: TextView, val sub: TextView, val column: LinearLayout)

    private val ink = Color.parseColor("#202124")
    private val grey = Color.parseColor("#5F6368")
    private val accent = Color.parseColor("#1A73E8")
    private val pillColor = Color.parseColor("#F1F3F4")

    private lateinit var root: FrameLayout
    private lateinit var main: LinearLayout
    private lateinit var holder: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabCount: TextView
    private lateinit var starBtn: ImageView
    private lateinit var backBtn: ImageView
    private lateinit var fwdBtn: ImageView

    private val store by lazy { Store(this) }
    private val prefs by lazy { getSharedPreferences("s", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())

    private val proxy = LocalProxy()
    private var proxyPort = 0
    private var ready = false
    private var pendingUrl: String? = null

    private val tabs = ArrayList<Tab>()
    private lateinit var current: Tab
    private val web: WebView get() = current.web

    private val active = CopyOnWriteArrayList<Dl>()

    private var desktop = false
    private var defaultUa = ""
    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // everything below is automatic - no user options
        Config.mode = 3
        Config.adBlock = true
        applyRelay()

        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        buildUi()

        pendingUrl = intent?.data?.toString()
        proxy.start { port ->
            runOnUiThread {
                proxyPort = port
                applyProxyAndLoad()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val u = intent?.data?.toString() ?: return
        if (ready) newTab(u, true) else pendingUrl = u
    }

    override fun onPause() {
        if (tabs.isNotEmpty()) web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (tabs.isNotEmpty()) web.onResume()
    }

    override fun onDestroy() {
        proxy.stop()
        for (t in tabs) {
            holder.removeView(t.web)
            t.web.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> hideCustom()
            tabs.isEmpty() -> super.onBackPressed()
            web.canGoBack() -> web.goBack()
            tabs.size > 1 -> closeTab(current)
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ small view helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun ripple(v: View, borderless: Boolean) {
        val tv = TypedValue()
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
        theme.resolveAttribute(attr, tv, true)
        v.setBackgroundResource(tv.resourceId)
    }

    private fun iconView(res: Int, tint: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private fun badge(text: String): TextView = TextView(this).apply {
        this.text = (text.trim().firstOrNull() ?: '•').uppercaseChar().toString()
        gravity = Gravity.CENTER
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(accent)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E8F0FE"))
        }
    }

    private fun iconBadge(res: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(accent)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E8F0FE"))
        }
    }

    private fun makeRow(leading: View, title: String, sub: String, trailing: View?, onClick: () -> Unit): RowViews {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(6), dp(10))
        }
        ripple(row, false)
        row.setOnClickListener { onClick() }
        row.addView(leading, LinearLayout.LayoutParams(dp(40), dp(40)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        val t = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ink)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val s = TextView(this).apply {
            text = sub
            textSize = 12f
            setTextColor(grey)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(t)
        col.addView(s)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) row.addView(trailing, LinearLayout.LayoutParams(dp(44), dp(44)))
        return RowViews(row, t, s, col)
    }

    private fun emptyText(msg: String): TextView = TextView(this).apply {
        text = msg
        textSize = 14f
        setTextColor(grey)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(48), dp(16), dp(48))
    }

    private fun header(title: String, actionLabel: String?, onAction: (() -> Unit)?, onClose: () -> Unit): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(8), dp(6))
        }
        h.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ink)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (actionLabel != null && onAction != null) {
            h.addView(TextView(this).apply {
                text = actionLabel
                textSize = 14f
                setTextColor(accent)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { onAction() }
            })
        }
        val x = iconView(R.drawable.ic_close, grey)
        x.setOnClickListener { onClose() }
        h.addView(x, LinearLayout.LayoutParams(dp(44), dp(44)))
        return h
    }

    /** Bottom sheet with rounded top corners. */
    private fun sheetDialog(content: View, fullHeight: Boolean): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val r = dp(22).toFloat()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        wrap.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (fullHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        d.setContentView(wrap)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            val h = if (fullHeight) (resources.displayMetrics.heightPixels * 0.82).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
        }
        return d
    }

    // ------------------------------------------------------------------ main UI

    private fun buildUi() {
        root = FrameLayout(this)
        main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // address pill: [ url ........ star reload ]
        urlBar = EditText(this).apply {
            hint = "ওয়েবসাইট বা সার্চ লিখুন"
            setHintTextColor(Color.parseColor("#80868B"))
            setTextColor(ink)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 15f
            background = null
            setPadding(0, 0, 0, 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    go(v.text.toString())
                    true
                } else false
            }
        }
        starBtn = iconView(R.drawable.ic_star, grey)
        starBtn.setOnClickListener { toggleBookmark() }
        val reload = iconView(R.drawable.ic_reload, grey)
        reload.setOnClickListener { if (tabs.isNotEmpty()) web.reload() }

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(4), 0)
            background = GradientDrawable().apply {
                setColor(pillColor)
                cornerRadius = dp(26).toFloat()
            }
        }
        pill.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        pill.addView(starBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        pill.addView(reload, LinearLayout.LayoutParams(dp(42), dp(42)))

        val top = FrameLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(6)) }
        top.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        main.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
            progressTintList = ColorStateList.valueOf(accent)
        }
        main.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        holder = FrameLayout(this)
        main.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        main.addView(
            View(this).apply { setBackgroundColor(Color.parseColor("#E3E3E3")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        )

        // bottom navigation: back, forward, home, tabs, menu
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        backBtn = iconView(R.drawable.ic_back, ink)
        fwdBtn = iconView(R.drawable.ic_forward, ink)
        val homeBtn = iconView(R.drawable.ic_home, ink)
        val menuBtn = iconView(R.drawable.ic_more, ink)
        tabCount = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(ink)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setStroke(dp(2), ink)
                cornerRadius = dp(6).toFloat()
            }
        }
        bottom.addView(cell(backBtn) { if (tabs.isNotEmpty() && web.canGoBack()) web.goBack() })
        bottom.addView(cell(fwdBtn) { if (tabs.isNotEmpty() && web.canGoForward()) web.goForward() })
        bottom.addView(cell(homeBtn) { if (tabs.isNotEmpty()) loadHome(current) })
        bottom.addView(cell(tabCount, 26) { showTabs() })
        bottom.addView(cell(menuBtn) { showMenu() })
        main.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        root.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun cell(v: View, sizeDp: Int = 46, onClick: () -> Unit): FrameLayout {
        val c = FrameLayout(this)
        c.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        ripple(c, true)
        c.setOnClickListener { onClick() }
        c.addView(v, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER))
        return c
    }

    private fun updateNav() {
        val has = tabs.isNotEmpty()
        backBtn.alpha = if (has && web.canGoBack()) 1f else 0.3f
        fwdBtn.alpha = if (has && web.canGoForward()) 1f else 0.3f
    }

    private fun isBookmarked(url: String): Boolean = store.bookmarks().any { it.url == url }

    private fun updateStar() {
        val u = if (tabs.isEmpty()) "" else current.url
        val on = u.startsWith("http") && isBookmarked(u)
        starBtn.setImageResource(if (on) R.drawable.ic_star_on else R.drawable.ic_star)
        starBtn.setColorFilter(if (on) Color.parseColor("#F9AB00") else grey)
    }

    private fun toggleBookmark() {
        if (tabs.isEmpty()) return
        val u = current.url
        if (!u.startsWith("http")) {
            toast("এই পেজ বুকমার্ক করা যাবে না")
            return
        }
        if (isBookmarked(u)) {
            store.removeBookmark(u)
            toast("বুকমার্ক সরানো হয়েছে")
        } else {
            store.addBookmark(Store.Item(current.title.ifBlank { u }, u))
            toast("বুকমার্ক হয়েছে")
        }
        updateStar()
    }

    // ------------------------------------------------------------------ tabs

    private fun newTab(url: String?, load: Boolean): Tab {
        val w = WebView(this)
        val t = Tab(w)
        setupWeb(t)
        tabs.add(t)
        switchTo(t)
        if (load) {
            if (url != null) w.loadUrl(url) else loadHome(t)
        }
        return t
    }

    private fun switchTo(t: Tab) {
        if (::current.isInitialized && current !== t) current.web.onPause()
        current = t
        holder.removeAllViews()
        holder.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        t.web.onResume()
        urlBar.setText(displayUrl(t.url))
        progress.visibility = View.INVISIBLE
        tabCount.text = tabs.size.toString()
        updateNav()
        updateStar()
    }

    private fun closeTab(t: Tab) {
        if (!tabs.contains(t)) return
        if (tabs.size == 1) newTab(null, true)
        val idx = tabs.indexOf(t)
        tabs.remove(t)
        if (t === current) switchTo(tabs[minOf(idx, tabs.size - 1)])
        tabCount.text = tabs.size.toString()
        holder.removeView(t.web)
        t.web.destroy()
    }

    private fun showTabs() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        lateinit var dlg: Dialog
        fun rebuild() {
            list.removeAllViews()
            for (t in tabs.toList()) {
                val label = t.title.ifBlank { "নতুন ট্যাব" }
                val close = iconView(R.drawable.ic_close, grey)
                close.setOnClickListener {
                    closeTab(t)
                    rebuild()
                }
                val r = makeRow(badge(label), label, displayUrl(t.url).ifBlank { "হোম" }, close) {
                    switchTo(t)
                    dlg.dismiss()
                }
                if (t === current) r.root.setBackgroundColor(Color.parseColor("#E8F0FE"))
                list.addView(r.root)
            }
        }
        rebuild()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header("ট্যাব", "+ নতুন", {
                dlg.dismiss()
                newTab(null, true)
            }, { dlg.dismiss() }))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        dlg = sheetDialog(content, true)
        dlg.show()
    }

    // ------------------------------------------------------------------ menu / bookmarks / history

    private class MenuEntry(val icon: Int, val label: String, val on: Boolean, val action: () -> Unit)

    private fun showMenu() {
        lateinit var dlg: Dialog
        val entries = listOf(
            MenuEntry(R.drawable.ic_add, "নতুন ট্যাব", false) { newTab(null, true) },
            MenuEntry(R.drawable.ic_bookmarks, "বুকমার্ক", false) { showBookmarks() },
            MenuEntry(R.drawable.ic_download, "ডাউনলোড", false) { showDownloads() },
            MenuEntry(R.drawable.ic_history, "হিস্ট্রি", false) { showHistory() },
            MenuEntry(R.drawable.ic_desktop, "ডেস্কটপ সাইট", desktop) { toggleDesktop() },
            MenuEntry(R.drawable.ic_share, "শেয়ার", false) { shareLink() }
        )
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(20), dp(12), dp(24))
        }
        for (rowItems in entries.chunked(3)) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            for (e in rowItems) {
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                val circle = ImageView(this).apply {
                    setImageResource(e.icon)
                    setColorFilter(if (e.on) Color.WHITE else ink)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(15), dp(15), dp(15), dp(15))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (e.on) accent else pillColor)
                    }
                }
                item.addView(circle, LinearLayout.LayoutParams(dp(54), dp(54)))
                item.addView(TextView(this).apply {
                    text = e.label
                    textSize = 12f
                    setTextColor(ink)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
                item.setOnClickListener {
                    dlg.dismiss()
                    e.action()
                }
                line.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(line)
        }
        dlg = sheetDialog(grid, false)
        dlg.show()
    }

    private fun showBookmarks() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        lateinit var dlg: Dialog
        fun rebuild() {
            list.removeAllViews()
            val items = store.bookmarks()
            if (items.isEmpty()) list.addView(emptyText("কোনো বুকমার্ক নেই\nপেজে গিয়ে অ্যাড্রেস বারের ☆ চাপুন"))
            for (b in items) {
                val del = iconView(R.drawable.ic_delete, grey)
                del.setOnClickListener {
                    store.removeBookmark(b.url)
                    rebuild()
                    updateStar()
                }
                val r = makeRow(badge(b.title.ifBlank { b.url }), b.title.ifBlank { b.url }, b.url, del) {
                    dlg.dismiss()
                    if (tabs.isNotEmpty()) web.loadUrl(b.url)
                }
                list.addView(r.root)
            }
        }
        rebuild()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header("বুকমার্ক", null, null) { dlg.dismiss() })
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        dlg = sheetDialog(content, true)
        dlg.show()
    }

    private fun showHistory() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        lateinit var dlg: Dialog
        fun rebuild() {
            list.removeAllViews()
            val items = store.history()
            if (items.isEmpty()) list.addView(emptyText("হিস্ট্রি খালি"))
            for (h in items) {
                val del = iconView(R.drawable.ic_delete, grey)
                del.setOnClickListener {
                    store.removeHistory(h.url)
                    rebuild()
                }
                val r = makeRow(badge(h.title.ifBlank { h.url }), h.title.ifBlank { h.url }, h.url, del) {
                    dlg.dismiss()
                    if (tabs.isNotEmpty()) web.loadUrl(h.url)
                }
                list.addView(r.root)
            }
        }
        rebuild()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header("হিস্ট্রি", "সব মুছুন", {
                store.clearHistory()
                rebuild()
            }, { dlg.dismiss() }))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        dlg = sheetDialog(content, true)
        dlg.show()
    }

    private fun toggleDesktop() {
        desktop = !desktop
        for (t in tabs) t.web.settings.userAgentString = if (desktop) desktopUa else defaultUa
        web.reload()
        toast(if (desktop) "ডেস্কটপ সাইট চালু" else "ডেস্কটপ সাইট বন্ধ")
    }

    private fun shareLink() {
        if (tabs.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, web.url ?: "")
        }
        startActivity(Intent.createChooser(i, "শেয়ার করুন"))
    }

    private fun copyText(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", s))
        toast("কপি হয়েছে")
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(t: Tab) {
        val w = t.web
        val s = w.settings
        if (defaultUa.isEmpty()) defaultUa = s.userAgentString
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        if (desktop) s.userAgentString = desktopUa

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime) }
        w.setOnLongClickListener { v -> handleLongPress(v as WebView) }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                when (u.scheme) {
                    "http", "https", "about", "data", "blob", "javascript" -> return false
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (_: Exception) {
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (Config.adBlock && AdBlock.isAd(request.url.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                t.url = url ?: ""
                if (t === current) {
                    if (!urlBar.hasFocus()) urlBar.setText(displayUrl(t.url))
                    progress.visibility = View.VISIBLE
                    updateNav()
                    updateStar()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                t.url = url ?: ""
                if (t.title.isBlank()) t.title = view.title ?: ""
                if (t === current) {
                    if (!urlBar.hasFocus()) urlBar.setText(displayUrl(t.url))
                    progress.visibility = View.INVISIBLE
                    updateNav()
                    updateStar()
                }
                if (url != null && url.startsWith("http")) {
                    store.addHistory(Store.Item(view.title?.ifBlank { url } ?: url, url))
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val desc = error.description?.toString() ?: ""
                if (desc.contains("ERR_ABORTED")) return
                showError(view, request.url.toString(), desc)
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (t !== current) return
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                t.title = title ?: ""
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false // popup without a tap: blocked
                val nt = newTab(null, false)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = nt.web
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                val x = tabs.firstOrNull { it.web === window }
                if (x != null) closeTab(x)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                view.setBackgroundColor(Color.BLACK)
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                main.visibility = View.GONE
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }

            override fun onHideCustomView() {
                hideCustom()
            }
        }
    }

    private fun hideCustom() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        main.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    private fun handleLongPress(w: WebView): Boolean {
        val r = w.hitTestResult
        val extra = r.extra ?: return false
        return when (r.type) {
            WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                linkMenu(extra, true)
                true
            }
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                linkMenu(extra, false)
                true
            }
            else -> false
        }
    }

    private fun linkMenu(url: String, image: Boolean) {
        val labels = if (image) arrayOf("ছবি ডাউনলোড", "নতুন ট্যাবে খুলুন", "লিংক কপি")
        else arrayOf("নতুন ট্যাবে খুলুন", "ডাউনলোড", "লিংক কপি")
        AlertDialog.Builder(this).setItems(labels) { _, which ->
            when (labels[which]) {
                "ছবি ডাউনলোড", "ডাউনলোড" -> startDownload(url, web.settings.userAgentString, null, null)
                "নতুন ট্যাবে খুলুন" -> newTab(url, true)
                else -> copyText(url)
            }
        }.show()
    }

    // ------------------------------------------------------------------ proxy + navigation

    private fun applyProxyAndLoad() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$proxyPort").build()
            ProxyController.getInstance().setProxyOverride(
                cfg,
                { r -> runOnUiThread(r) },
                {
                    ready = true
                    loadInitial()
                }
            )
        } else {
            toast("WebView আপডেট করুন, তাহলে সব সাইট খুলবে")
            ready = true
            loadInitial()
        }
    }

    private fun loadInitial() {
        if (tabs.isNotEmpty()) return
        val u = pendingUrl
        pendingUrl = null
        newTab(u, true)
    }

    private fun loadHome(t: Tab) {
        t.title = ""
        t.web.loadDataWithBaseURL("about:home", homeHtml(), "text/html", "UTF-8", null)
    }

    private fun go(input: String) {
        val s = input.trim()
        if (s == "about:relay") { // hidden setup screen, not shown anywhere in the UI
            urlBar.clearFocus()
            askRelay()
            return
        }
        if (s.startsWith("about:diag")) { // hidden self-test: shows which bypass method works for a site
            urlBar.clearFocus()
            runDiag(s.removePrefix("about:diag").trim())
            return
        }
        if (tabs.isEmpty()) return
        val u = toUrl(s)
        if (u == null) loadHome(current) else web.loadUrl(u)
        urlBar.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun toUrl(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
        if (!s.contains(' ') && (s.contains('.') || s.startsWith("localhost"))) return "https://$s"
        return "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
    }

    private fun displayUrl(url: String?): String =
        if (url == null || url.startsWith("about:") || url.startsWith("data:")) "" else url

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun showError(view: WebView, url: String, desc: String) {
        val html = "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>body{font-family:sans-serif;padding:32px 24px;color:#202124;text-align:center}" +
                "a.b{display:inline-block;margin-top:18px;padding:12px 28px;border-radius:24px;background:#1a73e8;color:#fff;text-decoration:none}" +
                "p{color:#5f6368;font-size:14px;word-break:break-all}</style></head><body>" +
                "<h2>পেজ খোলা যায়নি</h2><p>" + esc(desc) + "</p><p>" + esc(url) + "</p>" +
                "<a class=\"b\" href=\"" + esc(url) + "\">আবার চেষ্টা করুন</a></body></html>"
        view.loadDataWithBaseURL("about:error", html, "text/html", "UTF-8", null)
    }

    private fun homeHtml(): String {
        val bm = store.bookmarks()
        val items: List<Store.Item> = if (bm.isNotEmpty()) bm.take(12) else listOf(
            Store.Item("Google", "https://www.google.com"),
            Store.Item("YouTube", "https://www.youtube.com"),
            Store.Item("Facebook", "https://www.facebook.com"),
            Store.Item("Wikipedia", "https://www.wikipedia.org")
        )
        val sb = StringBuilder()
        for (b in items) {
            val letter = (b.title.trim().firstOrNull() ?: '•').uppercaseChar().toString()
            val name = b.title.ifBlank { b.url }
            sb.append("<a href=\"").append(esc(b.url)).append("\"><span class=\"i\">")
                .append(esc(letter)).append("</span>").append(esc(name.take(14))).append("</a>")
        }
        return HOME_HTML.replace("%TILES%", sb.toString())
    }

    // ------------------------------------------------------------------ relay (set up once via "about:relay", otherwise from RelayDefaults)

    private fun applyRelay() {
        var u = (prefs.getString("relayUrl", RelayDefaults.URL) ?: "").trim()
        if (u.isNotEmpty()) {
            u = when {
                u.startsWith("wss://", true) -> "https://" + u.substring(6)
                u.startsWith("ws://", true) -> "http://" + u.substring(5)
                u.startsWith("http", true) -> u
                else -> "https://$u"
            }
        }
        Config.relayUrl = if (u.isEmpty()) null else u
        Config.relayKey = prefs.getString("relayKey", RelayDefaults.KEY) ?: ""
        Config.relayAlways = prefs.getBoolean("relayAlways", false)
    }

    private fun askRelay() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val urlEt = EditText(this).apply {
            hint = "https://xxx.workers.dev"
            setSingleLine(true)
            setText(prefs.getString("relayUrl", RelayDefaults.URL) ?: "")
        }
        val keyEt = EditText(this).apply {
            hint = "KEY"
            setSingleLine(true)
            setText(prefs.getString("relayKey", RelayDefaults.KEY) ?: "")
        }
        val always = CheckBox(this).apply {
            text = "সব সাইট রিলে দিয়ে খুলুন"
            isChecked = prefs.getBoolean("relayAlways", false)
        }
        box.addView(urlEt)
        box.addView(keyEt)
        box.addView(always)
        AlertDialog.Builder(this)
            .setTitle("Relay")
            .setView(box)
            .setPositiveButton("সেভ") { _, _ ->
                prefs.edit()
                    .putString("relayUrl", urlEt.text.toString().trim())
                    .putString("relayKey", keyEt.text.toString().trim())
                    .putBoolean("relayAlways", always.isChecked)
                    .apply()
                applyRelay()
                toast(if (Config.relayUrl != null) "সেভ হয়েছে" else "বন্ধ")
                if (tabs.isNotEmpty()) web.reload()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    // ------------------------------------------------------------------ hidden self-test ("about:diag" or "about:diag example.com")

    private fun runDiag(host0: String) {
        val host = host0.ifBlank { "www.pornhub.com" }
        toast("টেস্ট চলছে, ১-২ মিনিট অপেক্ষা করুন")
        val sb = StringBuilder()
        val savedMode = Config.mode
        val savedRelay = Config.relayUrl
        val savedAlways = Config.relayAlways
        val port = proxyPort
        Thread {
            fun show() {
                val html = "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
                        "<body style=\"font-family:monospace;font-size:13px;padding:14px;white-space:pre-wrap;word-break:break-all\">" +
                        esc(sb.toString()) + "</body></html>"
                runOnUiThread {
                    if (tabs.isNotEmpty()) web.loadDataWithBaseURL("about:diag", html, "text/html", "UTF-8", null)
                }
            }

            fun add(line: String) {
                sb.append(line).append('\n')
                show()
            }

            fun fetch(): String {
                val t0 = System.currentTimeMillis()
                return try {
                    val p = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
                    val c = URL("https://$host/").openConnection(p) as HttpURLConnection
                    c.connectTimeout = 9000
                    c.readTimeout = 9000
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("User-Agent", defaultUa)
                    val code = c.responseCode
                    c.disconnect()
                    "OK HTTP $code (" + (System.currentTimeMillis() - t0) + " ms)"
                } catch (e: Exception) {
                    "FAIL " + e.javaClass.simpleName + ": " + e.message + " (" + (System.currentTimeMillis() - t0) + " ms)"
                }
            }

            try {
                add("Host: $host")
                add("Relay: " + (savedRelay ?: "NOT SET"))
                val ips = try {
                    Doh.resolve(host)
                } catch (e: Exception) {
                    emptyList()
                }
                add("DoH IPs: " + ips.joinToString { it.hostAddress ?: "?" })
                val sysIps = try {
                    InetAddress.getAllByName(host).joinToString { it.hostAddress ?: "?" }
                } catch (e: Exception) {
                    "FAIL " + e.javaClass.simpleName
                }
                add("System DNS IPs: $sysIps")
                for (ip in ips.take(2)) {
                    val t0 = System.currentTimeMillis()
                    val r = try {
                        Socket().use { sk -> sk.connect(InetSocketAddress(ip, 443), 5000) }
                        "connected (" + (System.currentTimeMillis() - t0) + " ms)"
                    } catch (e: Exception) {
                        "FAIL " + e.javaClass.simpleName
                    }
                    add("TCP " + ip.hostAddress + ":443 -> " + r)
                }
                Config.relayUrl = null
                val tests = listOf("plain" to 0, "split" to 1, "record-split" to 2, "split+case" to 4, "record+case" to 5)
                for ((label, m) in tests) {
                    Config.mode = m
                    add("[$label] " + fetch())
                }
                Config.relayUrl = savedRelay
                if (savedRelay != null) {
                    Config.mode = 3
                    Config.relayAlways = true
                    add("[relay] " + fetch())
                } else {
                    add("[relay] SKIPPED (relay not set)")
                }
            } finally {
                Config.mode = savedMode
                Config.relayUrl = savedRelay
                Config.relayAlways = savedAlways
                add("--- done ---")
            }
        }.start()
    }

    // ------------------------------------------------------------------ downloads (go through the same bypass proxy)

    private fun fmtSize(b: Long): String = when {
        b < 0 -> ""
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http")) {
            toast("এই ডাউনলোড সাপোর্ট করে না")
            return
        }
        val name = URLUtil.guessFileName(url, cd, mime)
        val d = Dl(System.nanoTime(), name, url)
        active.add(d)
        toast("ডাউনলোড শুরু: $name")
        val cookie = CookieManager.getInstance().getCookie(url)
        val port = proxyPort
        Thread {
            var tmpUri: Uri? = null
            var tmpFile: File? = null
            try {
                val p = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
                val conn = URL(url).openConnection(p) as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 40000
                if (ua != null) conn.setRequestProperty("User-Agent", ua)
                if (cookie != null) conn.setRequestProperty("Cookie", cookie)
                conn.connect()
                if (conn.responseCode !in 200..299) throw IOException("HTTP " + conn.responseCode)
                d.total = conn.contentLengthLong
                val type = (if (!mime.isNullOrBlank()) mime else conn.contentType?.substringBefore(';'))
                    ?: URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"

                val input = conn.inputStream
                val out: OutputStream
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, type)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("save failed")
                    tmpUri = u
                    out = contentResolver.openOutputStream(u) ?: throw IOException("save failed")
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    var f = File(dir, name)
                    var n = 1
                    while (f.exists()) {
                        f = File(dir, "($n) $name")
                        n++
                    }
                    tmpFile = f
                    out = FileOutputStream(f)
                }
                try {
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (d.cancel) throw IOException("cancelled")
                        out.write(buf, 0, n)
                        d.done += n
                    }
                } finally {
                    try {
                        out.close()
                    } catch (_: Exception) {
                    }
                    try {
                        input.close()
                    } catch (_: Exception) {
                    }
                }

                val finalUri: String
                val tu = tmpUri
                if (Build.VERSION.SDK_INT >= 29 && tu != null) {
                    val values = ContentValues()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(tu, values, null, null)
                    finalUri = tu.toString()
                } else {
                    finalUri = tmpFile?.absolutePath ?: throw IOException("save failed")
                }
                store.addDownload(Store.DlRec(name, finalUri, type, d.done))
                runOnUiThread {
                    active.remove(d)
                    toast("ডাউনলোড শেষ: $name")
                }
            } catch (e: Exception) {
                try {
                    val tu = tmpUri
                    if (tu != null) contentResolver.delete(tu, null, null)
                    tmpFile?.delete()
                } catch (_: Exception) {
                }
                if (d.cancel) {
                    runOnUiThread { active.remove(d) }
                } else {
                    d.status = 2
                    runOnUiThread { toast("ডাউনলোড ব্যর্থ: $name") }
                }
            }
        }.start()
    }

    private fun openRec(r: Store.DlRec) {
        try {
            val uri: Uri = if (r.uri.startsWith("content:")) Uri.parse(r.uri)
            else FileProvider.getUriForFile(this, "$packageName.fp", File(r.uri))
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, r.mime.ifBlank { "*/*" })
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (_: Exception) {
            toast("ফাইল খোলা যাচ্ছে না")
        }
    }

    private fun deleteRec(r: Store.DlRec) {
        try {
            if (r.uri.startsWith("content:")) contentResolver.delete(Uri.parse(r.uri), null, null)
            else File(r.uri).delete()
        } catch (_: Exception) {
        }
        store.removeDownload(r.uri)
    }

    private fun showDownloads() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        lateinit var dlg: Dialog
        val bars = HashMap<Long, Pair<ProgressBar, TextView>>()
        var lastKey = ""

        fun key(): String = active.joinToString(",") { "${it.id}:${it.status}" } + "|" + store.downloads().size

        fun rebuild() {
            list.removeAllViews()
            bars.clear()
            val recs = store.downloads()
            if (active.isEmpty() && recs.isEmpty()) {
                list.addView(emptyText("কোনো ডাউনলোড নেই\nভিডিও বা ফাইলের লিংকে চাপলে এখানে আসবে"))
            }
            for (d in active) {
                val x = iconView(R.drawable.ic_close, grey)
                x.setOnClickListener {
                    d.cancel = true
                    active.remove(d)
                    rebuild()
                }
                val r = makeRow(iconBadge(R.drawable.ic_download), d.name, "", x) {
                    if (d.status == 2) {
                        active.remove(d)
                        startDownload(d.url, null, null, null)
                        rebuild()
                    }
                }
                if (d.status == 2) {
                    r.sub.text = "ব্যর্থ হয়েছে — আবার চেষ্টা করতে চাপুন"
                    r.sub.setTextColor(Color.parseColor("#D93025"))
                } else {
                    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000
                        progressTintList = ColorStateList.valueOf(accent)
                    }
                    r.column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
                    bars[d.id] = Pair(bar, r.sub)
                }
                list.addView(r.root)
            }
            for (rec in recs) {
                val del = iconView(R.drawable.ic_delete, grey)
                del.setOnClickListener {
                    deleteRec(rec)
                    rebuild()
                }
                val r = makeRow(iconBadge(R.drawable.ic_download), rec.name, fmtSize(rec.size), del) { openRec(rec) }
                list.addView(r.root)
            }
        }

        fun updateBars() {
            for (d in active) {
                val pair = bars[d.id] ?: continue
                val total = d.total
                if (total > 0) {
                    pair.first.progress = (d.done * 1000 / total).toInt()
                    pair.second.text = "${d.done * 100 / total}%  •  ${fmtSize(d.done)} / ${fmtSize(total)}"
                } else {
                    pair.first.isIndeterminate = true
                    pair.second.text = fmtSize(d.done)
                }
            }
        }

        rebuild()
        lastKey = key()
        updateBars()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header("ডাউনলোড", null, null) { dlg.dismiss() })
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        dlg = sheetDialog(content, true)
        dlg.show()

        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                val k = key()
                if (k != lastKey) {
                    lastKey = k
                    rebuild()
                }
                updateBars()
                ui.postDelayed(this, 500)
            }
        }
        ui.postDelayed(tick, 500)
    }

    companion object {
        private const val HOME_HTML = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;font-family:sans-serif;background:#fff;color:#202124;text-align:center;padding:56px 16px}
h1{font-size:30px;margin:0 0 28px;font-weight:600}
form{max-width:480px;margin:0 auto 36px}
input{width:100%;box-sizing:border-box;padding:15px 20px;font-size:16px;border:none;background:#f1f3f4;border-radius:28px;outline:none}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:18px 10px;max-width:480px;margin:0 auto}
.g a{display:block;color:#202124;text-decoration:none;font-size:12px;word-break:break-word}
.i{display:block;width:54px;height:54px;line-height:54px;margin:0 auto 8px;border-radius:50%;background:#e8f0fe;color:#1a73e8;font-size:22px;font-weight:bold}
</style></head><body>
<h1>Open Browser</h1>
<form action="https://www.google.com/search" method="get"><input name="q" placeholder="সার্চ করুন" autocomplete="off"></form>
<div class="g">%TILES%</div>
</body></html>"""
    }
}

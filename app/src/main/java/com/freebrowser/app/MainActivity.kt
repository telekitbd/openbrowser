package com.freebrowser.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLEncoder

class MainActivity : Activity() {

    private class Tab(val web: WebView, val priv: Boolean) {
        var title: String = ""
        var url: String = ""
    }

    private lateinit var root: FrameLayout
    private lateinit var main: LinearLayout
    private lateinit var holder: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabCount: TextView

    private val store by lazy { Store(this) }
    private val prefs by lazy { getSharedPreferences("s", MODE_PRIVATE) }

    private val proxy = LocalProxy()
    private var proxyPort = 0
    private var ready = false
    private var pendingUrl: String? = null

    private val tabs = ArrayList<Tab>()
    private lateinit var current: Tab
    private val web: WebView get() = current.web

    private var desktop = false
    private var defaultUa = ""
    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.mode = prefs.getInt("mode2", 3)
        Config.adBlock = prefs.getBoolean("adblock", true)
        parseUpstream(prefs.getString("upstream", "") ?: "")

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
        if (ready) newTab(u, false, true) else pendingUrl = u
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

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun ripple(v: View) {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        v.setBackgroundResource(tv.resourceId)
    }

    private fun buildUi() {
        root = FrameLayout(this)
        main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // address bar + reload
        urlBar = EditText(this).apply {
            hint = "ওয়েবসাইট বা সার্চ লিখুন"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 15f
            setPadding(dp(16), 0, dp(16), 0)
            setSelectAllOnFocus(true)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F3F4"))
                cornerRadius = dp(22).toFloat()
            }
            setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    go(v.text.toString())
                    true
                } else false
            }
        }
        val reload = TextView(this).apply {
            text = "↻"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#444444"))
            ripple(this)
            setOnClickListener { if (tabs.isNotEmpty()) web.reload() }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(6), dp(8))
            addView(urlBar, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(reload, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        main.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        main.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        holder = FrameLayout(this)
        main.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val line = View(this).apply { setBackgroundColor(Color.parseColor("#DDDDDD")) }
        main.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        // bottom bar
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        bottom.addView(navBtn("‹", 32f) { if (tabs.isNotEmpty() && web.canGoBack()) web.goBack() })
        bottom.addView(navBtn("›", 32f) { if (tabs.isNotEmpty() && web.canGoForward()) web.goForward() })
        bottom.addView(navBtn("⌂", 24f) { if (tabs.isNotEmpty()) loadHome(current) })

        tabCount = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.parseColor("#444444"))
            background = GradientDrawable().apply {
                setStroke(dp(2), Color.parseColor("#444444"))
                cornerRadius = dp(5).toFloat()
            }
        }
        val tabCell = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            ripple(this)
            setOnClickListener { showTabs() }
            addView(tabCount, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        bottom.addView(tabCell)

        val menuBtn = navBtn("⋮", 26f) { }
        menuBtn.setOnClickListener { showMenu(menuBtn) }
        bottom.addView(menuBtn)
        main.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        root.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun navBtn(label: String, size: Float, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#444444"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            ripple(this)
            setOnClickListener { onClick() }
        }
    }

    // ------------------------------------------------------------------ tabs

    private fun newTab(url: String?, priv: Boolean, load: Boolean): Tab {
        val w = WebView(this)
        val t = Tab(w, priv)
        setupWeb(t)
        tabs.add(t)
        switchTo(t)
        if (load) {
            if (url != null) w.loadUrl(url) else loadHome(t)
        }
        return t
    }

    private fun switchTo(t: Tab) {
        if (tabs.isNotEmpty() && ::current.isInitialized && current !== t) current.web.onPause()
        current = t
        holder.removeAllViews()
        holder.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        t.web.onResume()
        urlBar.setText(displayUrl(t.url))
        progress.visibility = View.GONE
        tabCount.text = tabs.size.toString()
        if (t.priv) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun closeTab(t: Tab) {
        if (!tabs.contains(t)) return
        if (tabs.size == 1) newTab(null, false, true)
        val idx = tabs.indexOf(t)
        tabs.remove(t)
        if (t === current) switchTo(tabs[minOf(idx, tabs.size - 1)])
        tabCount.text = tabs.size.toString()
        holder.removeView(t.web)
        t.web.destroy()
        if (t.priv && tabs.none { it.priv }) wipeData()
    }

    private fun wipeData() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        if (tabs.isNotEmpty()) web.clearCache(true)
    }

    private fun showTabs() {
        val labels = ArrayList<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        fun rebuild() {
            labels.clear()
            for (t in tabs) {
                val mark = (if (t === current) "● " else "") + (if (t.priv) "🔒 " else "")
                labels.add(mark + t.title.ifBlank { "নতুন ট্যাব" })
            }
            adapter.notifyDataSetChanged()
        }
        rebuild()
        val lv = ListView(this)
        lv.adapter = adapter
        val dlg = AlertDialog.Builder(this)
            .setTitle("ট্যাব (চেপে ধরলে বন্ধ হবে)")
            .setView(lv)
            .setPositiveButton("নতুন ট্যাব") { _, _ -> newTab(null, false, true) }
            .setNeutralButton("প্রাইভেট") { _, _ -> newTab(null, true, true) }
            .setNegativeButton("বন্ধ", null)
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            switchTo(tabs[pos])
            dlg.dismiss()
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            closeTab(tabs[pos])
            rebuild()
            true
        }
        dlg.show()
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
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                t.url = url ?: ""
                if (t.title.isBlank()) t.title = view.title ?: ""
                if (t === current) {
                    if (!urlBar.hasFocus()) urlBar.setText(displayUrl(t.url))
                    progress.visibility = View.GONE
                }
                if (!t.priv && url != null && url.startsWith("http")) {
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
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                t.title = title ?: ""
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false // popup without a tap: blocked
                val nt = newTab(null, t.priv, false)
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
            Toast.makeText(this, "WebView আপডেট করুন, নাহলে বাইপাস কাজ করবে না", Toast.LENGTH_LONG).show()
            ready = true
            loadInitial()
        }
    }

    private fun loadInitial() {
        if (tabs.isNotEmpty()) return
        val u = pendingUrl
        pendingUrl = null
        newTab(u, false, true)
    }

    private fun loadHome(t: Tab) {
        t.title = ""
        t.web.loadDataWithBaseURL("about:home", homeHtml(), "text/html", "UTF-8", null)
    }

    private fun go(input: String) {
        if (tabs.isEmpty()) return
        val u = toUrl(input)
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
                "<style>body{font-family:sans-serif;padding:24px;color:#333}a{color:#1a73e8}</style></head><body>" +
                "<h2>পেজ খোলা যায়নি</h2><p>" + esc(desc) + "</p>" +
                "<p style=\"word-break:break-all;color:#777\">" + esc(url) + "</p>" +
                "<p><a href=\"" + esc(url) + "\">আবার চেষ্টা করুন</a></p>" +
                "<p style=\"color:#777;font-size:14px\">সাইট না খুললে মেনু → বাইপাস মোড \"অটো\" করুন, " +
                "অথবা মেনু → আপস্ট্রিম প্রক্সি দিন।</p>" +
                "</body></html>"
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
        for (it in items) {
            val letter = (it.title.trim().firstOrNull() ?: '•').uppercaseChar().toString()
            val name = it.title.ifBlank { it.url }
            sb.append("<a href=\"").append(esc(it.url)).append("\"><span class=\"i\">")
                .append(esc(letter)).append("</span>").append(esc(name.take(14))).append("</a>")
        }
        return HOME_HTML.replace("%TILES%", sb.toString())
    }

    // ------------------------------------------------------------------ menu + dialogs

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, "নতুন ট্যাব")
        m.add(0, 2, 1, "প্রাইভেট ট্যাব")
        m.add(0, 3, 2, "বুকমার্ক করুন")
        m.add(0, 4, 3, "বুকমার্ক তালিকা")
        m.add(0, 5, 4, "হিস্ট্রি")
        m.add(0, 6, 5, "ডেস্কটপ সাইট").apply {
            isCheckable = true
            isChecked = desktop
        }
        m.add(0, 7, 6, "অ্যাড ব্লক").apply {
            isCheckable = true
            isChecked = Config.adBlock
        }
        m.add(0, 8, 7, "বাইপাস মোড")
        m.add(0, 9, 8, "আপস্ট্রিম প্রক্সি")
        m.add(0, 10, 9, "লিংক কপি করুন")
        m.add(0, 11, 10, "শেয়ার করুন")
        m.add(0, 12, 11, "ক্যাশ ও কুকি মুছুন")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> newTab(null, false, true)
                2 -> newTab(null, true, true)
                3 -> addBookmark()
                4 -> showBookmarks()
                5 -> showHistory()
                6 -> toggleDesktop()
                7 -> toggleAdBlock()
                8 -> chooseMode()
                9 -> askUpstream()
                10 -> copyLink()
                11 -> shareLink()
                12 -> clearData()
            }
            true
        }
        pm.show()
    }

    private fun addBookmark() {
        val u = web.url ?: ""
        if (!u.startsWith("http")) {
            Toast.makeText(this, "এই পেজ বুকমার্ক করা যাবে না", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = store.addBookmark(Store.Item(current.title.ifBlank { u }, u))
        Toast.makeText(this, if (ok) "বুকমার্ক হয়েছে" else "আগেই বুকমার্ক করা আছে", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarks() {
        showItems("বুকমার্ক (চেপে ধরলে মুছবে)", store.bookmarks(), { store.removeBookmark(it.url) }, null)
    }

    private fun showHistory() {
        showItems("হিস্ট্রি (চেপে ধরলে মুছবে)", store.history(), { store.removeHistory(it.url) }, "সব মুছুন" to { store.clearHistory() })
    }

    private fun showItems(
        title: String,
        items: MutableList<Store.Item>,
        onDelete: (Store.Item) -> Unit,
        extra: Pair<String, () -> Unit>?
    ) {
        if (items.isEmpty()) {
            Toast.makeText(this, "খালি", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = ArrayList<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        fun rebuild() {
            labels.clear()
            for (x in items) labels.add(x.title.ifBlank { x.url } + "\n" + x.url.take(60))
            adapter.notifyDataSetChanged()
        }
        rebuild()
        val lv = ListView(this)
        lv.adapter = adapter
        val b = AlertDialog.Builder(this).setTitle(title).setView(lv).setNegativeButton("বন্ধ", null)
        if (extra != null) b.setNeutralButton(extra.first) { _, _ -> extra.second() }
        val dlg = b.create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            web.loadUrl(items[pos].url)
            dlg.dismiss()
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            onDelete(items[pos])
            items.removeAt(pos)
            rebuild()
            true
        }
        dlg.show()
    }

    private fun toggleDesktop() {
        desktop = !desktop
        for (t in tabs) t.web.settings.userAgentString = if (desktop) desktopUa else defaultUa
        web.reload()
    }

    private fun toggleAdBlock() {
        Config.adBlock = !Config.adBlock
        prefs.edit().putBoolean("adblock", Config.adBlock).apply()
        Toast.makeText(this, if (Config.adBlock) "অ্যাড ব্লক চালু" else "অ্যাড ব্লক বন্ধ", Toast.LENGTH_SHORT).show()
        web.reload()
    }

    private fun chooseMode() {
        val items = arrayOf(
            "অটো (প্রস্তাবিত)",
            "স্ট্যান্ডার্ড",
            "শক্তিশালী",
            "শুধু DoH (বাইপাস বন্ধ)"
        )
        val modes = intArrayOf(3, 1, 2, 0)
        val cur = modes.indexOf(Config.mode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("বাইপাস মোড")
            .setSingleChoiceItems(items, cur) { d, which ->
                Config.mode = modes[which]
                prefs.edit().putInt("mode2", Config.mode).apply()
                d.dismiss()
                web.reload()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun askUpstream() {
        val et = EditText(this).apply {
            hint = "যেমন socks5://1.2.3.4:1080 বা 1.2.3.4:8080"
            setSingleLine(true)
            setText(prefs.getString("upstream", "") ?: "")
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("আপস্ট্রিম প্রক্সি (ঐচ্ছিক)")
            .setMessage("কোনো সাইট সরাসরি না খুললে তখন এই প্রক্সি দিয়ে চেষ্টা করবে। খালি রাখলে বন্ধ থাকবে।")
            .setView(et)
            .setPositiveButton("সেভ") { _, _ ->
                val v = et.text.toString().trim()
                prefs.edit().putString("upstream", v).apply()
                parseUpstream(v)
                Toast.makeText(this, if (Config.upstreamHost != null) "প্রক্সি সেট হয়েছে" else "প্রক্সি বন্ধ", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun parseUpstream(v0: String) {
        var v = v0.trim()
        var type = 0
        if (v.startsWith("socks5://", true)) {
            type = 1
            v = v.substring(9)
        } else if (v.startsWith("http://", true)) {
            v = v.substring(7)
        }
        val c = v.lastIndexOf(':')
        val p = if (c > 0) v.substring(c + 1).toIntOrNull() else null
        if (p == null) {
            Config.upstreamHost = null
            return
        }
        Config.upstreamPort = p
        Config.upstreamType = type
        Config.upstreamHost = v.substring(0, c)
    }

    private fun copyLink() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", web.url ?: ""))
        Toast.makeText(this, "লিংক কপি হয়েছে", Toast.LENGTH_SHORT).show()
    }

    private fun shareLink() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, web.url ?: "")
        }
        startActivity(Intent.createChooser(i, "শেয়ার করুন"))
    }

    private fun clearData() {
        web.clearCache(true)
        web.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        Toast.makeText(this, "মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ downloads (go through the same proxy)

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http")) {
            Toast.makeText(this, "এই ডাউনলোড সাপোর্ট করে না", Toast.LENGTH_SHORT).show()
            return
        }
        val name = URLUtil.guessFileName(url, cd, mime)
        Toast.makeText(this, "ডাউনলোড শুরু: $name", Toast.LENGTH_SHORT).show()
        val cookie = CookieManager.getInstance().getCookie(url)
        val port = proxyPort
        Thread {
            try {
                val p = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
                val conn = URL(url).openConnection(p) as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                if (ua != null) conn.setRequestProperty("User-Agent", ua)
                if (cookie != null) conn.setRequestProperty("Cookie", cookie)
                conn.connect()
                if (conn.responseCode !in 200..299) throw IOException("HTTP " + conn.responseCode)
                val type = mime ?: conn.contentType ?: "application/octet-stream"
                conn.inputStream.use { input ->
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, type)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("save failed")
                        contentResolver.openOutputStream(uri)!!.use { out -> input.copyTo(out) }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    } else {
                        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                        File(dir, name).outputStream().use { out -> input.copyTo(out) }
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "ডাউনলোড শেষ: $name (Downloads)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "ডাউনলোড ব্যর্থ: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    companion object {
        private const val HOME_HTML = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;font-family:sans-serif;background:#fff;color:#202124;text-align:center;padding:40px 16px}
h1{font-size:28px;margin:0 0 6px}
p{color:#5f6368;margin:0 0 24px;font-size:14px}
form{max-width:480px;margin:0 auto 28px}
input{width:100%;box-sizing:border-box;padding:14px 18px;font-size:16px;border:1px solid #dadce0;border-radius:28px;outline:none}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:14px 10px;max-width:480px;margin:0 auto}
.g a{display:block;color:#202124;text-decoration:none;font-size:12px;word-break:break-word}
.i{display:block;width:52px;height:52px;line-height:52px;margin:0 auto 6px;border-radius:16px;background:#e8f0fe;color:#1a73e8;font-size:22px;font-weight:bold}
</style></head><body>
<h1>ফ্রি ব্রাউজার</h1>
<p>ব্লক করা সাইট খুলুন, VPN ছাড়াই</p>
<form action="https://www.google.com/search" method="get"><input name="q" placeholder="সার্চ করুন" autocomplete="off"></form>
<div class="g">%TILES%</div>
</body></html>"""
    }
}

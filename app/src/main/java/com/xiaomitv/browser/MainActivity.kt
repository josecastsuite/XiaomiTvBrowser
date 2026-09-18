package com.xiaomitv.browser
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
data class Tab(var webView: WebView, var titleView: TextView)
class MainActivity : AppCompatActivity() {
    private lateinit var tabsContainer: LinearLayout
    private lateinit var addressBar: EditText
    private val tabs = mutableListOf<Tab>()
    private var currentIndex = -1
    private val webContainerFrame by lazy { findViewById<FrameLayout>(R.id.webContainer) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        autoClearCacheIfNeeded()
        tabsContainer = findViewById(R.id.tabsContainer)
        addressBar = findViewById(R.id.addressBar)
        findViewById<TextView>(R.id.btnNewTab).setOnClickListener { newTab("https://www.google.com") }
        findViewById<TextView>(R.id.btnBack).setOnClickListener { currentWeb()?.goBack() }
        findViewById<TextView>(R.id.btnForward).setOnClickListener { currentWeb()?.goForward() }
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener { currentWeb()?.reload() }
        addressBar.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_GO) { loadUrlFromBar(); true } else false }
        newTab("https://www.google.com")
    }
    private fun autoClearCacheIfNeeded() {
        val prefs = getSharedPreferences("tv_browser_prefs", MODE_PRIVATE)
        val lastClear = prefs.getLong("last_cache_clear", 0L)
        val now = System.currentTimeMillis()
        val oneMonth = 30L * 24 * 60 * 60 * 1000
        if (now - lastClear > oneMonth) {
            try { cacheDir.deleteRecursively() } catch (e: Exception) {}
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            prefs.edit().putLong("last_cache_clear", now).apply()
        }
    }
    private fun currentWeb(): WebView? = if (currentIndex in tabs.indices) tabs[currentIndex].webView else null
    private fun newTab(url: String) {
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    if (view == currentWeb()) { addressBar.setText(view?.url); updateTabTitle() }
                }
            }
            webChromeClient = WebChromeClient()
        }
        val tabView = TextView(this).apply {
            text = " Yeni Sekme "
            setPadding(32, 12, 32, 12)
            textSize = 13f
            setBackgroundColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            setOnClickListener { switchTo(tabs.indexOfFirst { it.titleView == this }) }
            setOnLongClickListener { closeTab(tabs.indexOfFirst { it.titleView == this }); true }
        }
        val tab = Tab(webView, tabView)
        tabs.add(tab)
        tabsContainer.addView(tabView)
        switchTo(tabs.size - 1)
        webView.loadUrl(url)
    }
    private fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        currentIndex = index
        webContainerFrame.removeAllViews()
        webContainerFrame.addView(tabs[index].webView)
        addressBar.setText(tabs[index].webView.url ?: "")
        tabs.forEachIndexed { i, t -> t.titleView.setBackgroundColor(if (i == index) Color.WHITE else Color.parseColor("#C9D0DA")) }
        updateTabTitle()
    }
    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        tabsContainer.removeView(tab.titleView)
        tab.webView.destroy()
        tabs.removeAt(index)
        if (tabs.isEmpty()) newTab("https://www.google.com") else switchTo((index - 1).coerceAtLeast(0))
    }
    private fun updateTabTitle() {
        val web = currentWeb() ?: return
        val title = web.title?.take(12) ?: "Google"
        tabs[currentIndex].titleView.text = " $title X"
    }
    private fun loadUrlFromBar() {
        var url = addressBar.text.toString().trim()
        if (!url.startsWith("http")) {
            if (url.contains(".")) url = "https://$url" else url = "https://www.google.com/search?q=$url"
        }
        currentWeb()?.loadUrl(url)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && currentWeb()?.canGoBack() == true) { currentWeb()?.goBack(); return true }
        return super.onKeyDown(keyCode, event)
    }
}

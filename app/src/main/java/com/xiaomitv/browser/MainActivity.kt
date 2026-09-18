package com.xiaomitv.browser
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

data class Tab(var webView: WebView, var titleView: TextView)

class MainActivity : AppCompatActivity() {
    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        private const val DESKTOP_VIEWPORT_WIDTH_DP = 1366
        private const val CURSOR_SPEED_MULTIPLIER = 1.5f
    }

    private lateinit var tabsContainer: LinearLayout
    private lateinit var addressBar: AutoCompleteTextView
    private val tabs = mutableListOf<Tab>()
    private var currentIndex = -1
    private val webContainerFrame by lazy { findViewById<FrameLayout>(R.id.webContainer) }
    private val rootContainer by lazy { findViewById<FrameLayout>(R.id.rootContainer) }
    private val cursorView by lazy { findViewById<ImageView>(R.id.cursorPointer) }
    private val btnNewTab by lazy { findViewById<TextView>(R.id.btnNewTab) }
    private val btnBackView by lazy { findViewById<TextView>(R.id.btnBack) }
    private val btnForwardView by lazy { findViewById<TextView>(R.id.btnForward) }
    private val btnRefreshView by lazy { findViewById<TextView>(R.id.btnRefresh) }
    private var cursorX = 0f
    private var cursorY = 0f
    private var hoveredView: View? = null

    private val desktopScalePercent: Int by lazy {
        val screenWidthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        ((screenWidthDp / DESKTOP_VIEWPORT_WIDTH_DP) * 100).toInt().coerceIn(30, 150)
    }

    private val heldDirections = mutableSetOf<Int>()
    private var moveTicks = 0
    private val cursorHandler = Handler(Looper.getMainLooper())
    private val cursorTick = object : Runnable {
        override fun run() {
            if (heldDirections.isEmpty()) return
            moveTicks++
            val density = resources.displayMetrics.density
            val speed = (6 + minOf(moveTicks / 4, 18)) * density * CURSOR_SPEED_MULTIPLIER
            heldDirections.toList().forEach { applyCursorDelta(it, speed) }
            cursorHandler.postDelayed(this, 16)
        }
    }

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val suggestions = linkedSetOf<String>()
    private lateinit var suggestionAdapter: ArrayAdapter<String>

    private var pendingPermissionRequest: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request == null) return@registerForActivityResult
        val stillMissing = request.resources.any { res ->
            val perm = androidPermissionFor(res)
            perm != null && ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (stillMissing) request.deny() else request.grant(request.resources)
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        autoClearCacheIfNeeded()
        tabsContainer = findViewById(R.id.tabsContainer)
        addressBar = findViewById(R.id.addressBar)
        setupAddressBarSuggestions()
        btnNewTab.setOnClickListener { newTab("https://www.google.com") }
        btnBackView.setOnClickListener { currentWeb()?.goBack() }
        btnForwardView.setOnClickListener { currentWeb()?.goForward() }
        btnRefreshView.setOnClickListener { currentWeb()?.reload() }
        addressBar.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_GO) { loadUrlFromBar(); true } else false }
        addressBar.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) stopAllCursorMove() }
        newTab("https://www.google.com")
        showCursorAtCenter()
    }

    private fun setupAddressBarSuggestions() {
        val prefs = getSharedPreferences("tv_browser_prefs", MODE_PRIVATE)
        suggestions.addAll(prefs.getStringSet("visited_urls", emptySet()) ?: emptySet())
        suggestionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions.toMutableList())
        addressBar.setAdapter(suggestionAdapter)
    }

    private fun rememberVisited(url: String) {
        if (!suggestions.add(url)) return
        while (suggestions.size > 50) suggestions.remove(suggestions.first())
        suggestionAdapter.clear()
        suggestionAdapter.addAll(suggestions)
        getSharedPreferences("tv_browser_prefs", MODE_PRIVATE).edit()
            .putStringSet("visited_urls", suggestions.toSet()).apply()
    }

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        val needed = request.resources.mapNotNull { androidPermissionFor(it) }.distinct()
        if (needed.isEmpty()) {
            request.grant(request.resources)
            return
        }
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            request.grant(request.resources)
        } else {
            pendingPermissionRequest = request
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun errorPageHtml(description: String?): String {
        val safeDesc = (description ?: "Bilinmeyen hata").replace("<", "&lt;").replace(">", "&gt;")
        return "<html><body style=\"font-family:sans-serif;text-align:center;padding-top:80px;color:#555;\">" +
            "<h2>Sayfa yüklenemedi</h2><p>$safeDesc</p></body></html>"
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    @Suppress("DEPRECATION")
    private fun exitImmersiveMode() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
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
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = DESKTOP_USER_AGENT
            setInitialScale(desktopScalePercent)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    if (view == currentWeb()) { addressBar.setText(view?.url); updateTabTitle() }
                    if (u != null && u.startsWith("http")) rememberVisited(u)
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        view?.loadDataWithBaseURL(null, errorPageHtml(error?.description?.toString()), "text/html", "UTF-8", null)
                    }
                }
                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    view?.loadDataWithBaseURL(null, errorPageHtml(description), "text/html", "UTF-8", null)
                }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Güvenli bağlantı uyarısı")
                        .setMessage("Bu sitenin güvenlik sertifikası doğrulanamadı. Yine de devam etmek istiyor musunuz?")
                        .setPositiveButton("Devam Et") { _, _ -> handler?.proceed() }
                        .setNegativeButton("Vazgeç") { _, _ -> handler?.cancel() }
                        .setCancelable(false)
                        .show()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { handlePermissionRequest(request) }
                }
                override fun onShowFileChooser(
                    webView: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        if (fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    return try {
                        filePickerLauncher.launch(Intent.createChooser(intent, "Dosya seç"))
                        true
                    } catch (e: Exception) {
                        filePathCallback = null
                        false
                    }
                }
                override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                    if (customView != null || view == null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    rootContainer.addView(
                        view,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    )
                    enterImmersiveMode()
                }
                override fun onHideCustomView() {
                    val view = customView ?: return
                    rootContainer.removeView(view)
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    exitImmersiveMode()
                }
            }
        }
        val tabView = TextView(this).apply {
            text = " Yeni Sekme "
            setPadding(32, 12, 32, 12)
            textSize = 13f
            setBackgroundColor(Color.WHITE)
            isClickable = true
            setOnClickListener { switchTo(tabs.indexOfFirst { it.titleView == this }) }
            setOnLongClickListener { closeTab(tabs.indexOfFirst { it.titleView == this }); true }
        }
        val tab = Tab(webView, tabView)
        tabs.add(tab)
        tabsContainer.addView(tabView, tabsContainer.indexOfChild(btnNewTab))
        switchTo(tabs.size - 1)
        webView.loadUrl(url)
    }
    private fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        currentIndex = index
        webContainerFrame.removeAllViews()
        webContainerFrame.addView(tabs[index].webView)
        addressBar.setText(tabs[index].webView.url ?: "")
        refreshTabColors()
        updateTabTitle()
        tabs[index].webView.requestFocus()
    }
    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        if (hoveredView === tab.titleView) hoveredView = null
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
        if (keyCode == KeyEvent.KEYCODE_BACK && customView != null) {
            customViewCallback?.onCustomViewHidden()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && addressBar.isFocused) {
            addressBar.clearFocus()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && currentWeb()?.canGoBack() == true) { currentWeb()?.goBack(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!addressBar.isFocused) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> startCursorMove(event.keyCode)
                        KeyEvent.ACTION_UP -> stopCursorMove(event.keyCode)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) performCursorClick()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showCursorAtCenter() {
        cursorView.visibility = View.VISIBLE
        cursorView.post {
            cursorX = (rootContainer.width / 2f) - (cursorView.width / 2f)
            cursorY = (rootContainer.height / 2f) - (cursorView.height / 2f)
            cursorView.x = cursorX
            cursorView.y = cursorY
            updateHoverState()
        }
    }

    private fun startCursorMove(keyCode: Int) {
        val isNew = heldDirections.add(keyCode)
        if (isNew && heldDirections.size == 1) {
            moveTicks = 0
            applyCursorDelta(keyCode, 14 * CURSOR_SPEED_MULTIPLIER * resources.displayMetrics.density)
            cursorHandler.removeCallbacks(cursorTick)
            cursorHandler.postDelayed(cursorTick, 120)
        }
    }

    private fun stopCursorMove(keyCode: Int) {
        heldDirections.remove(keyCode)
        if (heldDirections.isEmpty()) {
            cursorHandler.removeCallbacks(cursorTick)
            moveTicks = 0
        }
    }

    private fun stopAllCursorMove() {
        heldDirections.clear()
        cursorHandler.removeCallbacks(cursorTick)
        moveTicks = 0
    }

    private fun applyCursorDelta(keyCode: Int, step: Float) {
        val web = currentWeb() ?: return
        if (cursorView.width == 0 || rootContainer.height == 0) return
        val boundLeft = 0f
        val boundTop = 0f
        val boundRight = boundLeft + rootContainer.width - cursorView.width
        val boundBottom = boundTop + rootContainer.height - cursorView.height
        var nx = cursorX
        var ny = cursorY
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> nx -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> nx += step
            KeyEvent.KEYCODE_DPAD_UP -> ny -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> ny += step
        }
        val overContent = cursorY >= webContainerFrame.top
        if (nx < boundLeft) { if (overContent) web.scrollBy((-step).toInt(), 0); nx = boundLeft }
        if (nx > boundRight) { if (overContent) web.scrollBy(step.toInt(), 0); nx = boundRight }
        if (ny > boundBottom) { web.scrollBy(0, step.toInt()); ny = boundBottom }
        if (ny < boundTop) ny = boundTop
        cursorX = nx
        cursorY = ny
        cursorView.x = cursorX
        cursorView.y = cursorY
        updateHoverState()
    }

    private fun rectRelativeToRoot(view: View): Rect {
        val viewLoc = IntArray(2)
        view.getLocationOnScreen(viewLoc)
        val rootLoc = IntArray(2)
        rootContainer.getLocationOnScreen(rootLoc)
        val left = viewLoc[0] - rootLoc[0]
        val top = viewLoc[1] - rootLoc[1]
        return Rect(left, top, left + view.width, top + view.height)
    }

    private fun pointInView(x: Float, y: Float, view: View): Boolean {
        val r = rectRelativeToRoot(view)
        return x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
    }

    private fun hoverCandidates(): List<View> =
        listOf(btnBackView, btnForwardView, btnRefreshView, btnNewTab, addressBar) + tabs.map { it.titleView }

    private fun updateHoverState() {
        val hotX = cursorX + cursorView.width / 2f
        val hotY = cursorY + cursorView.height / 2f
        val newHover = hoverCandidates().firstOrNull { pointInView(hotX, hotY, it) }
        if (newHover === hoveredView) return
        hoveredView?.isSelected = false
        hoveredView = newHover
        hoveredView?.isSelected = true
        refreshTabColors()
    }

    private fun refreshTabColors() {
        tabs.forEachIndexed { i, t ->
            t.titleView.setBackgroundColor(
                when {
                    t.titleView === hoveredView -> Color.parseColor("#4285F4")
                    i == currentIndex -> Color.WHITE
                    else -> Color.parseColor("#C9D0DA")
                }
            )
        }
    }

    private fun performCursorClick() {
        val hovered = hoveredView
        if (hovered != null) {
            if (hovered === addressBar) {
                addressBar.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT)
            } else {
                hovered.performClick()
            }
            return
        }
        val web = currentWeb() ?: return
        val localX = cursorX + cursorView.width / 2f
        val localY = cursorY - webContainerFrame.top + cursorView.height / 2f
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, localX, localY, 0)
        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, localX, localY, 0)
        web.dispatchTouchEvent(down)
        web.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }
}

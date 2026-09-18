package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View.OnFocusChangeListener
import android.view.View.OnKeyListener
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.databinding.ViewActionbarBinding
import com.phlox.tvwebbrowser.utils.Utils

class ActionBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val vb = ViewActionbarBinding.inflate( LayoutInflater.from(context),this)
    var callback: Callback? = null
    private var extendedAddressBarMode = false

    interface Callback {
        fun closeWindow()
        fun showDownloads()
        fun showFavorites()
        fun showHistory()
        fun showSettings()
        fun initiateVoiceSearch()
        fun search(text: String)
        fun onExtendedAddressBarMode()
        fun onUrlInputDone()
        fun toggleIncognitoMode()
        fun onTopNavigateBack()
        fun onTopNavigateForward()
        fun onTopRefresh()
    }

    private val etUrlFocusChangeListener = OnFocusChangeListener { _, focused ->
        if (focused) {
            enterExtendedAddressBarMode()

            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            imm.showSoftInput(vb.etUrl, InputMethodManager.SHOW_IMPLICIT)
            postDelayed(//workaround an android TV bug
                {
                    vb.etUrl.selectAll()
                }, 500)
        }
    }

    private val etUrlKeyListener = OnKeyListener { view, i, keyEvent ->
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
                    callback?.search(vb.etUrl.text.toString())
                    dismissExtendedAddressBarMode()
                    callback?.onUrlInputDone()
                }
                return@OnKeyListener true
            }
        }
        false
    }

    init {
        init()
    }

    fun init() {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL

        if (isInEditMode) return

        vb.ibTopBack.setOnClickListener { callback?.onTopNavigateBack() }
        vb.ibTopForward.setOnClickListener { callback?.onTopNavigateForward() }
        vb.ibTopRefresh.setOnClickListener { callback?.onTopRefresh() }
        vb.ibFavorites.setOnClickListener { callback?.showFavorites() }
        vb.ibOverflowMenu.setOnClickListener { showOverflowMenu(it) }

        vb.etUrl.onFocusChangeListener = etUrlFocusChangeListener

        vb.etUrl.setOnKeyListener(etUrlKeyListener)
    }

    private fun showOverflowMenu(anchor: android.view.View) {
        val popup = androidx.appcompat.widget.PopupMenu(context, anchor)
        val menu = popup.menu
        if (!Utils.isFireTV(context)) {
            menu.add(0, MENU_VOICE_SEARCH, 0, R.string.voice_search)
        }
        menu.add(0, MENU_HISTORY, 0, R.string.history)
        menu.add(0, MENU_DOWNLOADS, 0, R.string.downloads)
        menu.add(0, MENU_INCOGNITO, 0, R.string.incognito_mode)
        menu.add(0, MENU_ABOUT, 0, R.string.version_and_updates)
        menu.add(0, MENU_CLOSE, 0, R.string.close_application)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_VOICE_SEARCH -> callback?.initiateVoiceSearch()
                MENU_HISTORY -> callback?.showHistory()
                MENU_DOWNLOADS -> callback?.showDownloads()
                MENU_INCOGNITO -> callback?.toggleIncognitoMode()
                MENU_ABOUT -> showAboutDialog()
                MENU_CLOSE -> callback?.closeWindow()
            }
            true
        }
        popup.show()
    }

    private fun showAboutDialog() {
        val message = android.text.Html.fromHtml(
            context.getString(R.string.web_browser_optimized_for_tvs) +
                "<br><br><u>https://github.com/truefedex/tv-bro</u>",
            android.text.Html.FROM_HTML_MODE_LEGACY
        )
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.app_name_short)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    fun setAddressBoxText(text: String) {
        if (text == Config.HOME_PAGE_URL) {
            vb.etUrl.setText("")
        } else {
            vb.etUrl.setText(text)
        }
    }

    fun setAddressBoxTextColor(color: Int) {
        vb.etUrl.setTextColor(color)
    }

    private fun enterExtendedAddressBarMode() {
        if (extendedAddressBarMode) return
        extendedAddressBarMode = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ImageButton) {
                child.visibility = GONE
            }
        }
        TransitionManager.beginDelayedTransition(this)
        callback?.onExtendedAddressBarMode()
    }

    fun dismissExtendedAddressBarMode() {
        if (!extendedAddressBarMode) return
        extendedAddressBarMode = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ImageButton) {
                child.visibility = VISIBLE
            }
        }
    }

    fun catchFocus() {
        vb.ibTopBack.requestFocus()
    }

    companion object {
        private const val MENU_VOICE_SEARCH = 1
        private const val MENU_HISTORY = 2
        private const val MENU_DOWNLOADS = 3
        private const val MENU_INCOGNITO = 4
        private const val MENU_ABOUT = 6
        private const val MENU_CLOSE = 5
    }
}
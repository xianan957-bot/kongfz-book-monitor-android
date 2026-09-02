package com.xianan.kongfzmonitor

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class ItemWebViewActivity : Activity() {
    companion object {
        const val EXTRA_ITEM_URL = "item_url"
    }

    private lateinit var webView: WebView
    private var loginWarningShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val itemUrl = intent.getStringExtra(EXTRA_ITEM_URL)
        if (itemUrl.isNullOrBlank() || !isKongfzUrl(itemUrl)) {
            Toast.makeText(this, "商品链接无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    if (!loginWarningShown && url?.lowercase()?.contains("login") == true) {
                        loginWarningShown = true
                        Toast.makeText(
                            this@ItemWebViewActivity,
                            "登录状态可能已失效，请重新登录孔夫子",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        setContentView(webView)
        if (savedInstanceState == null) {
            webView.loadUrl(itemUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Android API; retained for WebView navigation on supported devices")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun isKongfzUrl(rawUrl: String): Boolean {
        return try {
            val host = java.net.URI(rawUrl).host?.lowercase() ?: return false
            host == "kongfz.com" || host.endsWith(".kongfz.com")
        } catch (_: Exception) {
            false
        }
    }
}

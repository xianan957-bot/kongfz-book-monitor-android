package com.xianan.kongfzmonitor

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.net.URI

class ItemWebViewActivity : Activity() {
    companion object {
        const val EXTRA_ITEM_URL = "item_url"
        const val EXTRA_AUTO_CHECKOUT = "auto_checkout"
        private const val AUTO_CHECKOUT_DELAY_MS = 1_200L
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private var loginWarningShown = false
    private val autoCheckoutRequested by lazy {
        intent.getBooleanExtra(EXTRA_AUTO_CHECKOUT, false)
    }
    private var autoCheckoutAttempted = false

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
            settings.userAgentString = DESKTOP_USER_AGENT
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
                    if (autoCheckoutRequested && isItemDetailUrl(url) && !autoCheckoutAttempted) {
                        autoCheckoutAttempted = true
                        view?.postDelayed({ triggerAutoCheckout() }, AUTO_CHECKOUT_DELAY_MS)
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

    private fun triggerAutoCheckout() {
        if (!::webView.isInitialized) return

        webView.evaluateJavascript(
            """
            (function() {
                var button = document.querySelector('.go-buy');
                if (!button) return 'no-buy-button';
                if (button.getAttribute('data-kongfz-auto-clicked') === '1') {
                    return 'already-clicked';
                }
                button.setAttribute('data-kongfz-auto-clicked', '1');
                button.click();
                return 'clicked';
            })();
            """.trimIndent(),
        ) { result ->
            if (result == "\"no-buy-button\"") {
                Toast.makeText(this, "未找到立即购买入口，可能被网站拦截", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isItemDetailUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        return try {
            val uri = URI(rawUrl)
            val host = uri.host?.lowercase() ?: return false
            val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
            host == "book.kongfz.com" && segments.size >= 2 &&
                segments[0].toLongOrNull() != null && segments[1].toLongOrNull() != null
        } catch (_: Exception) {
            false
        }
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

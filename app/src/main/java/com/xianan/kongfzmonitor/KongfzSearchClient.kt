package com.xianan.kongfzmonitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the official Kongfz search page in WebView and reads the rendered list.
 *
 * The service still calls this synchronously from its worker thread, but all
 * WebView work stays on Android's main thread as required by the WebView API.
 */
class KongfzSearchClient(context: Context) {
    companion object {
        private const val SEARCH_PAGE_BASE_URL = "https://search.kongfz.com/product/"
        private const val FETCH_TIMEOUT_MS = 30_000L
        private const val FIRST_COLLECTION_DELAY_MS = 1_000L
        private const val COLLECTION_RETRY_DELAY_MS = 500L
        private const val MAX_COLLECTION_ATTEMPTS = 16

        /**
         * Selectors are based on the current official search page. The page's
         * JavaScript owns the request and renders these nodes after the result
         * response arrives; no private API is called by the app anymore.
         */
        private const val EXTRACT_RENDERED_ITEMS_SCRIPT = """
            (function() {
              function text(node) {
                return node ? (node.innerText || node.textContent || '').replace(/\s+/g, ' ').trim() : '';
              }

              function absoluteUrl(raw) {
                if (!raw) return '';
                try { return new URL(raw, location.href).href; } catch (_) { return ''; }
              }

              function itemIdFromUrl(raw) {
                try {
                  var parts = new URL(raw, location.href).pathname.split('/').filter(Boolean);
                  for (var i = parts.length - 1; i >= 0; i -= 1) {
                    if (/^\d+$/.test(parts[i])) return parts[i];
                  }
                } catch (_) {}
                return '';
              }

              var abnormal = document.querySelector('.abnormal-view');
              var abnormalText = text(abnormal);
              var verificationRequired = !!document.querySelector('#captcha-button, #captcha-element') ||
                /自动请求|验证后|搜索次数已达到上限|访问频率/.test(abnormalText);
              var loginPage = /(^|\/)login(?:\/|\?|$)/i.test(location.pathname) ||
                /请登录后|登录后再/.test(abnormalText);
              var loading = !!document.querySelector('.produc-list-skeleton, .produc-list-text-skeleton, .product-item-skeleton');
              var nodes = Array.prototype.slice.call(document.querySelectorAll('.product-item-wrap'));
              var items = nodes.map(function(node) {
                var titleLink = node.querySelector('.item-name .item-link, .item-name a, a.item-link');
                var rawUrl = titleLink ? titleLink.getAttribute('href') : '';
                var itemUrl = absoluteUrl(rawUrl);
                var priceNode = node.querySelector('.price-info, .price-int, .row-price__value');
                var qualityNode = node.querySelector('.quality-info, .row-quality');
                var shopNode = node.querySelector('.shop-name');
                return {
                  itemId: itemIdFromUrl(itemUrl),
                  itemUrl: itemUrl,
                  title: text(titleLink || node.querySelector('.item-name')),
                  priceText: text(priceNode),
                  condition: text(qualityNode),
                  shop: text(shopNode)
                };
              }).filter(function(item) {
                return item.itemId && item.itemUrl && item.title && item.priceText;
              });

              var state = items.length > 0 ? 'ready' :
                (verificationRequired ? 'verification' :
                  (loginPage ? 'login' : (loading ? 'loading' : 'empty')));
              return {
                state: state,
                message: abnormalText,
                items: items
              };
            })()
        """
    }

    private val webViewContext: Context = context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var webView: WebView? = null
    private var activeRequest: PendingRequest? = null

    init {
        mainHandler.post { ensureWebView() }
    }

    /**
     * Blocks only the service worker. WebView creation, loading and DOM
     * extraction are dispatched to the main thread.
     */
    fun fetch(config: MonitorConfig): List<KongfzItem> {
        check(!closed.get()) { "孔夫子搜索 WebView 已关闭" }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "孔夫子搜索不能在 Android 主线程同步执行"
        }

        val keyword = config.keyword.trim()
        if (keyword.isBlank()) return emptyList()

        val request = PendingRequest()
        val searchUrl = buildSearchUrl(keyword)
        mainHandler.post { startFetch(request, searchUrl) }

        if (!request.latch.await(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            mainHandler.post {
                if (activeRequest === request) {
                    activeRequest = null
                    webView?.stopLoading()
                    request.completeError(TimeoutException("孔夫子官方搜索页面加载超时"))
                }
            }
            throw IOException("孔夫子官方搜索页面加载超时")
        }

        request.error?.let { throw it }
        return request.items.orEmpty()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return

        mainHandler.post {
            activeRequest?.completeError(IOException("孔夫子搜索 WebView 已关闭"))
            activeRequest = null
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }

        val view = WebView(webViewContext)
        return view.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            webViewClient = searchWebViewClient
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }
            webView = this
        }
    }

    private val searchWebViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val request = activeRequest ?: return
            if (view == null || view !== webView || !isOfficialSearchUrl(url)) return
            scheduleCollection(request, 0)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame != true) return
            val active = activeRequest ?: return
            val description = error?.description?.toString().orEmpty()
            complete(
                active,
                IOException(
                    "孔夫子官方搜索页面加载失败" +
                        (description.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                ),
            )
        }
    }

    private fun startFetch(request: PendingRequest, searchUrl: String) {
        if (closed.get()) {
            request.completeError(IOException("孔夫子搜索 WebView 已关闭"))
            return
        }

        activeRequest?.completeError(IOException("上一轮孔夫子搜索已取消"))
        activeRequest = request

        try {
            CookieManager.getInstance().flush()
            ensureWebView().loadUrl(searchUrl)
        } catch (error: Exception) {
            complete(request, error)
        }
    }

    private fun scheduleCollection(request: PendingRequest, attempt: Int) {
        if (activeRequest !== request || closed.get()) return
        if (attempt > MAX_COLLECTION_ATTEMPTS) {
            complete(request, IOException("孔夫子官方搜索结果加载超时"))
            return
        }

        val delay = if (attempt == 0) FIRST_COLLECTION_DELAY_MS else COLLECTION_RETRY_DELAY_MS
        mainHandler.postDelayed({
            if (activeRequest !== request || closed.get()) return@postDelayed
            val view = webView ?: run {
                complete(request, IOException("孔夫子搜索 WebView 不可用"))
                return@postDelayed
            }
            view.evaluateJavascript(EXTRACT_RENDERED_ITEMS_SCRIPT) { rawResult ->
                handleCollectionResult(request, rawResult, attempt)
            }
        }, delay)
    }

    private fun handleCollectionResult(request: PendingRequest, rawResult: String, attempt: Int) {
        if (activeRequest !== request || closed.get()) return

        val payload = decodeJavascriptObject(rawResult)
        if (payload == null) {
            scheduleCollection(request, attempt + 1)
            return
        }

        when (payload.optString("state")) {
            "ready" -> complete(request, parseItems(payload.optJSONArray("items")))
            "empty" -> complete(request, emptyList())
            "login" -> complete(
                request,
                KongfzLoginRequiredException(
                    payload.optString("message").ifBlank { "孔夫子登录状态已失效" },
                ),
            )
            "verification" -> complete(
                request,
                KongfzVerificationRequiredException(
                    payload.optString("message").ifBlank { "孔夫子官方搜索要求完成验证" },
                ),
            )
            "loading" -> scheduleCollection(request, attempt + 1)
            else -> complete(
                request,
                IOException(
                    payload.optString("message").ifBlank { "孔夫子官方搜索页面返回异常" },
                ),
            )
        }
    }

    private fun complete(request: PendingRequest, result: List<KongfzItem>) {
        if (activeRequest !== request) return
        activeRequest = null
        request.completeResult(result)
    }

    private fun complete(request: PendingRequest, error: Exception) {
        if (activeRequest !== request) return
        activeRequest = null
        request.completeError(error)
    }

    private fun buildSearchUrl(keyword: String): String {
        val encodedKeyword = URLEncoder.encode(keyword, Charsets.UTF_8.name())
        return "$SEARCH_PAGE_BASE_URL?keyword=$encodedKeyword" +
            "&dataType=0&sortType=3" +
            "&actionPath=keyword%2CdataType%2CsortType&page=1&userArea=1006e6"
    }

    private fun isOfficialSearchUrl(rawUrl: String?): Boolean {
        return try {
            val uri = URI(rawUrl ?: return false)
            uri.scheme == "https" && uri.host?.lowercase() == "search.kongfz.com" &&
                uri.path?.startsWith("/product") == true
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeJavascriptObject(rawResult: String): JSONObject? {
        return try {
            when (val value = JSONTokener(rawResult).nextValue()) {
                is JSONObject -> value
                is String -> JSONObject(value)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseItems(itemArray: JSONArray?): List<KongfzItem> {
        if (itemArray == null) return emptyList()

        val result = ArrayList<KongfzItem>(itemArray.length())
        for (index in 0 until itemArray.length()) {
            val item = itemArray.optJSONObject(index) ?: continue
            parseItem(item)?.let(result::add)
        }
        return result
    }

    private fun parseItem(json: JSONObject): KongfzItem? {
        val itemId = json.optString("itemId").trim()
        val itemUrl = json.optString("itemUrl").trim()
        val title = json.optString("title").trim()
        val price = parsePrice(json.optString("priceText"))
        if (itemId.isBlank() || title.isBlank() || price == null || !isKongfzUrl(itemUrl)) return null

        return KongfzItem(
            itemId = itemId,
            itemUrl = itemUrl,
            title = title,
            price = price,
            condition = json.optString("condition").trim(),
            shop = json.optString("shop").trim(),
        )
    }

    private fun parsePrice(raw: String): Double? {
        return raw
            .replace("￥", "")
            .replace("¥", "")
            .replace(",", "")
            .trim()
            .toDoubleOrNull()
    }

    private fun isKongfzUrl(rawUrl: String): Boolean {
        return try {
            val host = URI(rawUrl).host?.lowercase() ?: return false
            host == "kongfz.com" || host.endsWith(".kongfz.com")
        } catch (_: Exception) {
            false
        }
    }

    private class PendingRequest {
        val latch = CountDownLatch(1)
        @Volatile var items: List<KongfzItem>? = null
        @Volatile var error: Exception? = null

        fun completeResult(value: List<KongfzItem>) {
            items = value
            latch.countDown()
        }

        fun completeError(value: Exception) {
            error = value
            latch.countDown()
        }
    }
}

class KongfzLoginRequiredException(message: String) : IOException(message)

class KongfzVerificationRequiredException(message: String) : IOException(message)

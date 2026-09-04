package com.xianan.kongfzmonitor

import android.text.Html
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

class KongfzSearchClient {
    fun fetch(config: MonitorConfig): List<KongfzItem> {
        val keyword = URLEncoder.encode(config.keyword.trim(), Charsets.UTF_8.name())
        val requestUrl = URL(
            "https://search.kongfz.com/pc-gw/search-web/client/pc/product/keyword/list" +
                "?keyword=$keyword&dataType=0&sortType=3" +
                "&actionPath=keyword%2CdataType%2CsortType&page=1&userArea=1006e6"
        )

        val connection = (requestUrl.openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty(
                "Referer",
                "https://search.kongfz.com/product/?keyword=$keyword&dataType=0&sortType=3"
            )
            CookieManager.getInstance().getCookie(requestUrl.toString())
                ?.takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("Cookie", it) }
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("孔夫子搜索页面返回 HTTP $code")
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(body)
            val requestSucceeded = when (val status = root.opt("status")) {
                is Number -> status.toInt() == 1
                is Boolean -> status
                else -> false
            }
            if (!requestSucceeded) {
                val data = root.optJSONObject("data")
                val rejectAction = data?.optString("requestRejectAction").orEmpty()
                val errType = root.optString("errType")
                if (rejectAction == "GO_LOGIN" || errType == "102") {
                    throw KongfzLoginRequiredException(
                        data?.optString("requestRejectCause")
                            ?.takeIf { it.isNotBlank() }
                            ?: "孔夫子登录状态已失效"
                    )
                }
                throw IllegalStateException(
                    root.optString("message").ifBlank { "孔夫子搜索接口返回失败" }
                )
            }

            val data = root.optJSONObject("data")
                ?: throw IllegalStateException("孔夫子搜索响应缺少 data 字段")
            val itemResponse = data.optJSONObject("itemResponse")
                ?: throw IllegalStateException("孔夫子搜索响应缺少 itemResponse 字段")
            val itemList = itemResponse.optJSONArray("list") ?: return emptyList()

            val result = ArrayList<KongfzItem>(itemList.length())
            for (index in 0 until itemList.length()) {
                val item = itemList.optJSONObject(index) ?: continue
                parseItem(item)?.let(result::add)
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun parseItem(json: JSONObject): KongfzItem? {
        val itemId = firstString(json, "itemId", "itemid", "id")
        if (itemId.isBlank()) return null

        val shopId = firstString(json, "shopid", "shopId")
        val explicitUrl = firstUrl(json)
        val itemUrl = when {
            explicitUrl != null -> explicitUrl
            shopId.isNotBlank() -> "https://book.kongfz.com/$shopId/$itemId/"
            else -> return null
        }
        if (!isKongfzUrl(itemUrl)) return null

        val titleRaw = firstString(json, "title", "itemName", "itemname_snippet", "itemname")
        val title = Html.fromHtml(titleRaw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        if (title.isBlank()) return null

        val price = parsePrice(json) ?: return null

        return KongfzItem(
            itemId = itemId,
            itemUrl = itemUrl,
            title = title,
            price = price,
            condition = firstString(
                json,
                "qualityText",
                "qualityname",
                "qualityName",
                "quality",
                "condition",
            ),
            shop = firstString(json, "shopName", "shopname"),
        )
    }

    private fun firstUrl(json: JSONObject): String? {
        val link = json.opt("link")
        val raw = when (link) {
            is JSONObject -> link.optString("pc").trim()
            is String -> link.trim()
            else -> ""
        }.ifBlank { firstString(json, "itemUrl", "itemurl", "url") }

        return when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> null
        }
    }

    private fun parsePrice(json: JSONObject): Double? {
        val numericPrice = json.opt("price")
        if (numericPrice is Number) return numericPrice.toDouble()

        return firstString(json, "price", "priceText", "salePrice")
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

    private fun firstString(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val value = json.optString(key, "").trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }
}

class KongfzLoginRequiredException(message: String) : IOException(message)

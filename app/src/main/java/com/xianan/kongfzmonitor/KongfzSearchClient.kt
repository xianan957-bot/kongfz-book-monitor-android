package com.xianan.kongfzmonitor

import android.text.Html
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

class KongfzSearchClient {
    fun fetch(config: MonitorConfig): List<KongfzItem> {
        val keyword = URLEncoder.encode(config.keyword.trim(), Charsets.UTF_8.name())
        val requestUrl = URL(
            "https://search.kongfz.com/product_result/" +
                "?key=$keyword&status=0&hasStock=1&order=100&ajaxdata=4&contentname=content"
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
            setRequestProperty("Referer", "https://www.kongfz.com/")
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
            val data = root.optJSONObject("data")
                ?: throw IllegalStateException("孔夫子搜索响应缺少 data 字段")
            val itemList = data.optJSONArray("itemList") ?: return emptyList()

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
        val itemId = firstString(json, "itemid", "itemId", "id")
        if (itemId.isBlank()) return null

        val shopId = firstString(json, "shopid", "shopId")
        val explicitUrl = firstString(json, "itemurl", "itemUrl", "url")
        val itemUrl = when {
            explicitUrl.startsWith("https://") || explicitUrl.startsWith("http://") -> explicitUrl
            explicitUrl.startsWith("//") -> "https:$explicitUrl"
            shopId.isNotBlank() -> "https://book.kongfz.com/$shopId/$itemId/"
            else -> return null
        }

        val titleRaw = firstString(json, "itemname_snippet", "itemname", "title", "itemName")
        val title = Html.fromHtml(titleRaw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        if (title.isBlank()) return null

        val price = firstString(json, "price", "salePrice")
            .replace("￥", "")
            .replace(",", "")
            .trim()
            .toDoubleOrNull()

        return KongfzItem(
            itemId = itemId,
            itemUrl = itemUrl,
            title = title,
            price = price,
            condition = firstString(json, "qualityname", "qualityName", "quality", "condition"),
            shop = firstString(json, "shopname", "shopName"),
        )
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

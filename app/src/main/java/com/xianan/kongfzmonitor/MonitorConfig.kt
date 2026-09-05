package com.xianan.kongfzmonitor

import android.content.Context

data class MonitorConfig(
    val keyword: String,
    val author: String,
    val publisher: String,
    val maxPrice: Double?,
    val condition: String,
    val shop: String,
    val intervalSeconds: Int,
    val monitoring: Boolean,
)

class MonitorConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences("monitor_config", Context.MODE_PRIVATE)

    fun load(): MonitorConfig = MonitorConfig(
        keyword = prefs.getString("keyword", "") ?: "",
        author = prefs.getString("author", "") ?: "",
        publisher = prefs.getString("publisher", "") ?: "",
        maxPrice = prefs.getString("max_price", null)?.toDoubleOrNull(),
        condition = prefs.getString("condition", "") ?: "",
        shop = prefs.getString("shop", "") ?: "",
        intervalSeconds = prefs.getInt("interval_seconds", 5).coerceIn(5, 15),
        monitoring = prefs.getBoolean("monitoring", false),
    )

    fun save(config: MonitorConfig) {
        prefs.edit()
            .putString("keyword", config.keyword.trim())
            .putString("author", config.author.trim())
            .putString("publisher", config.publisher.trim())
            .putString("max_price", config.maxPrice?.toString())
            .putString("condition", config.condition.trim())
            .putString("shop", config.shop.trim())
            .putInt("interval_seconds", config.intervalSeconds.coerceIn(5, 15))
            .putBoolean("monitoring", config.monitoring)
            .apply()
    }

    fun setMonitoring(monitoring: Boolean) {
        prefs.edit().putBoolean("monitoring", monitoring).apply()
    }
}

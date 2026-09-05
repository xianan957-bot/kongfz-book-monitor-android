package com.xianan.kongfzmonitor

data class KongfzItem(
    val itemId: String,
    val itemUrl: String,
    val title: String,
    val author: String,
    val publisher: String,
    val price: Double?,
    val condition: String,
    val shop: String,
)

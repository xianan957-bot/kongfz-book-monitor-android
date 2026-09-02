package com.xianan.kongfzmonitor

data class KongfzItem(
    val itemId: String,
    val itemUrl: String,
    val title: String,
    val price: Double?,
    val condition: String,
    val shop: String,
)

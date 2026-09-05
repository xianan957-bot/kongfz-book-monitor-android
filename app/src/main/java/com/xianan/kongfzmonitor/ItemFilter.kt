package com.xianan.kongfzmonitor

object ItemFilter {
    fun matches(item: KongfzItem, config: MonitorConfig): Boolean {
        val keyword = config.keyword.trim()
        if (keyword.isNotEmpty()) {
            val searchableText = listOf(
                item.title,
                item.author,
                item.publisher,
                item.condition,
                item.shop,
            ).joinToString(" ")
            if (!searchableText.contains(keyword, ignoreCase = true)) return false
        }

        val expectedAuthor = config.author.trim()
        if (expectedAuthor.isNotEmpty() && !containsText(item.author, expectedAuthor)) {
            return false
        }

        val expectedPublisher = config.publisher.trim()
        if (expectedPublisher.isNotEmpty() && !containsText(item.publisher, expectedPublisher)) {
            return false
        }

        config.maxPrice?.let { maxPrice ->
            val price = item.price ?: return false
            if (price > maxPrice) return false
        }

        val expectedShop = config.shop.trim()
        if (expectedShop.isNotEmpty() && !item.shop.trim().equals(expectedShop, ignoreCase = true)) {
            return false
        }

        val expectedCondition = config.condition.trim()
        if (expectedCondition.isNotEmpty() && !conditionMatches(item.condition, expectedCondition)) {
            return false
        }

        return true
    }

    private fun containsText(actual: String, expected: String): Boolean {
        return actual.trim().contains(expected.trim(), ignoreCase = true)
    }

    private fun conditionMatches(actual: String, expected: String): Boolean {
        val actualNormalized = actual.replace(" ", "")
        val expectedNormalized = expected.replace(" ", "")

        if (expectedNormalized.endsWith("以上")) {
            val thresholdText = expectedNormalized.removeSuffix("以上")
            val actualGrade = parseGrade(actualNormalized)
            val thresholdGrade = parseGrade(thresholdText)
            if (actualGrade != null && thresholdGrade != null) {
                return actualGrade >= thresholdGrade
            }
        }

        return actualNormalized.equals(expectedNormalized, ignoreCase = true)
    }

    private fun parseGrade(value: String): Double? {
        if (value.contains("全新")) return 10.0
        val normalized = value.removeSuffix("品")
        return when (normalized) {
            "十", "10" -> 10.0
            "九五", "9.5", "95" -> 9.5
            "九", "9" -> 9.0
            "八五", "8.5", "85" -> 8.5
            "八", "8" -> 8.0
            "七五", "7.5", "75" -> 7.5
            "七", "7" -> 7.0
            "六五", "6.5", "65" -> 6.5
            "六", "6" -> 6.0
            "五五", "5.5", "55" -> 5.5
            "五", "5" -> 5.0
            else -> null
        }
    }
}

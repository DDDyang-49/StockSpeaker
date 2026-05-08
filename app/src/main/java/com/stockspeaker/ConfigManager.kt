package com.stockspeaker

import android.content.Context

data class AppConfig(
    val stockCode: String = "600519",
    val speakInterval: Int = 15,
    val largeOrderThreshold: Int = 500,
    val speakPrice: Boolean = true,
    val speakPct: Boolean = true,
    val speakCurrentHand: Boolean = true,
    val speakAmount: Boolean = true,
    val speakVolRatio: Boolean = true,
    val speakSpeed: Boolean = false,
    val speakLargeOrders: Boolean = true
)

class ConfigManager(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun load(): AppConfig {
        return AppConfig(
            stockCode = prefs.getString("stock_code", "600519") ?: "600519",
            speakInterval = prefs.getInt("speak_interval", 15),
            largeOrderThreshold = prefs.getInt("large_order_threshold", 500),
            speakPrice = prefs.getBoolean("speak_price", true),
            speakPct = prefs.getBoolean("speak_pct", true),
            speakCurrentHand = prefs.getBoolean("speak_current_hand", true),
            speakAmount = prefs.getBoolean("speak_amount", true),
            speakVolRatio = prefs.getBoolean("speak_vol_ratio", true),
            speakSpeed = prefs.getBoolean("speak_speed", false),
            speakLargeOrders = prefs.getBoolean("speak_large_orders", true)
        )
    }

    fun save(config: AppConfig) {
        prefs.edit()
            .putString("stock_code", config.stockCode)
            .putInt("speak_interval", config.speakInterval)
            .putInt("large_order_threshold", config.largeOrderThreshold)
            .putBoolean("speak_price", config.speakPrice)
            .putBoolean("speak_pct", config.speakPct)
            .putBoolean("speak_current_hand", config.speakCurrentHand)
            .putBoolean("speak_amount", config.speakAmount)
            .putBoolean("speak_vol_ratio", config.speakVolRatio)
            .putBoolean("speak_speed", config.speakSpeed)
            .putBoolean("speak_large_orders", config.speakLargeOrders)
            .apply()
    }
}

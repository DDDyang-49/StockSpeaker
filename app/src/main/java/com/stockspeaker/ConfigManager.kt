package com.stockspeaker

import android.content.Context

// ── AI 提供商预设 ──

data class AiProviderInfo(
    val id: String,
    val displayName: String,
    val url: String,
    val model: String
)

val AI_PROVIDERS = listOf(
    AiProviderInfo("deepseek", "DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
    AiProviderInfo("edgefn", "EdgeFn", "https://api.edgefn.net/v1/chat/completions", "Qwen3-235B-A22B-2507"),
    AiProviderInfo("openai", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini")
)

data class AppConfig(
    val stockCode: String = "600519",
    val speakInterval: Int = 15,
    val largeOrderThreshold: Int = 500,
    val speedAlertThreshold: Double = 0.5,
    val speakPrice: Boolean = true,
    val speakPct: Boolean = true,
    val speakCurrentHand: Boolean = true,
    val speakAmount: Boolean = true,
    val speakVolRatio: Boolean = true,
    val speakSpeed: Boolean = false,
    val speakLargeOrders: Boolean = true,
    // AI 配置
    val aiEnabled: Boolean = false,
    val aiApiKey: String = "",
    val aiProvider: String = "deepseek",
    val aiApiUrl: String = "https://api.deepseek.com/v1/chat/completions",
    val aiModel: String = "deepseek-chat",
    val aiSummaryInterval: Int = 5
)

class ConfigManager(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun load(): AppConfig {
        return AppConfig(
            stockCode = prefs.getString("stock_code", "600519") ?: "600519",
            speakInterval = prefs.getInt("speak_interval", 15),
            largeOrderThreshold = prefs.getInt("large_order_threshold", 500),
            speedAlertThreshold = prefs.getFloat("speed_alert_threshold", 0.5f).toDouble(),
            speakPrice = prefs.getBoolean("speak_price", true),
            speakPct = prefs.getBoolean("speak_pct", true),
            speakCurrentHand = prefs.getBoolean("speak_current_hand", true),
            speakAmount = prefs.getBoolean("speak_amount", true),
            speakVolRatio = prefs.getBoolean("speak_vol_ratio", true),
            speakSpeed = prefs.getBoolean("speak_speed", false),
            speakLargeOrders = prefs.getBoolean("speak_large_orders", true),
            aiEnabled = prefs.getBoolean("ai_enabled", false),
            aiApiKey = prefs.getString("ai_api_key", "") ?: "",
            aiProvider = prefs.getString("ai_provider", "deepseek") ?: "deepseek",
            aiApiUrl = prefs.getString("ai_api_url", "https://api.deepseek.com/v1/chat/completions") ?: "https://api.deepseek.com/v1/chat/completions",
            aiModel = prefs.getString("ai_model", "deepseek-chat") ?: "deepseek-chat",
            aiSummaryInterval = prefs.getInt("ai_summary_interval", 5)
        )
    }

    fun save(config: AppConfig) {
        prefs.edit()
            .putString("stock_code", config.stockCode)
            .putInt("speak_interval", config.speakInterval)
            .putInt("large_order_threshold", config.largeOrderThreshold)
            .putFloat("speed_alert_threshold", config.speedAlertThreshold.toFloat())
            .putBoolean("speak_price", config.speakPrice)
            .putBoolean("speak_pct", config.speakPct)
            .putBoolean("speak_current_hand", config.speakCurrentHand)
            .putBoolean("speak_amount", config.speakAmount)
            .putBoolean("speak_vol_ratio", config.speakVolRatio)
            .putBoolean("speak_speed", config.speakSpeed)
            .putBoolean("speak_large_orders", config.speakLargeOrders)
            .putBoolean("ai_enabled", config.aiEnabled)
            .putString("ai_api_key", config.aiApiKey)
            .putString("ai_provider", config.aiProvider)
            .putString("ai_api_url", config.aiApiUrl)
            .putString("ai_model", config.aiModel)
            .putInt("ai_summary_interval", config.aiSummaryInterval)
            .apply()
    }
}

package com.stockspeaker

import android.content.Context

// ── AI 提供商预设 ──

data class AiProviderInfo(
    val id: String,
    val displayName: String,
    val url: String,
    val model: String,
    val thinkingModel: String = ""  // 思考模式模型（空表示无独立思考模型）
)

val AI_PROVIDERS = listOf(
    AiProviderInfo("deepseek", "DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-v4-flash", "deepseek-reasoner"),
    AiProviderInfo("edgefn", "EdgeFn", "https://api.edgefn.net/v1/chat/completions", "Qwen3-235B-A22B-2507")
)

data class ApiKeyEntry(val key: String, val note: String = "")

data class AppConfig(
    val stockCode: String = "603960",
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
    val speakTransactionDetail: Boolean = false,
    // AI 配置
    val aiEnabled: Boolean = false,
    val aiApiKey: String = "",
    val aiProvider: String = "edgefn",
    val aiApiUrl: String = "https://api.edgefn.net/v1/chat/completions",
    val aiModel: String = "Qwen3-235B-A22B-2507",
    val aiThinkingModel: String = "",
    val aiSummaryInterval: Int = 5,
    // 辅 AI 配置（资金面，可用便宜模型）
    val aiTwoEnabled: Boolean = false,
    val aiTwoApiKey: String = "",
    val aiTwoProvider: String = "edgefn",
    val aiTwoApiUrl: String = "https://api.edgefn.net/v1/chat/completions",
    val aiTwoModel: String = "Qwen3-235B-A22B-2507",
    val aiTwoThinkingModel: String = "",
    // 运行状态
    val monitoringActive: Boolean = false,
    val stockSector: String = ""
)

class ConfigManager(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun load(): AppConfig {
        return AppConfig(
            stockCode = prefs.getString("stock_code", "603960") ?: "603960",
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
            speakTransactionDetail = prefs.getBoolean("speak_transaction_detail", false),
            aiEnabled = prefs.getBoolean("ai_enabled", false),
            aiApiKey = prefs.getString("ai_api_key", "") ?: "",
            aiProvider = prefs.getString("ai_provider", "edgefn") ?: "edgefn",
            aiApiUrl = prefs.getString("ai_api_url", "https://api.edgefn.net/v1/chat/completions") ?: "https://api.edgefn.net/v1/chat/completions",
            aiModel = prefs.getString("ai_model", "Qwen3-235B-A22B-2507") ?: "Qwen3-235B-A22B-2507",
            aiThinkingModel = prefs.getString("ai_thinking_model", "") ?: "",
            aiSummaryInterval = prefs.getInt("ai_summary_interval", 5),
            aiTwoEnabled = prefs.getBoolean("ai_two_enabled", false),
            aiTwoApiKey = prefs.getString("ai_two_api_key", "") ?: "",
            aiTwoProvider = prefs.getString("ai_two_provider", "edgefn") ?: "edgefn",
            aiTwoApiUrl = prefs.getString("ai_two_api_url", "https://api.edgefn.net/v1/chat/completions") ?: "https://api.edgefn.net/v1/chat/completions",
            aiTwoModel = prefs.getString("ai_two_model", "Qwen3-235B-A22B-2507") ?: "Qwen3-235B-A22B-2507",
            aiTwoThinkingModel = prefs.getString("ai_two_thinking_model", "") ?: "",
            monitoringActive = prefs.getBoolean("monitoring_active", false),
            stockSector = prefs.getString("stock_sector", "") ?: ""
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
            .putBoolean("speak_transaction_detail", config.speakTransactionDetail)
            .putBoolean("ai_enabled", config.aiEnabled)
            .putString("ai_api_key", config.aiApiKey)
            .putString("ai_provider", config.aiProvider)
            .putString("ai_api_url", config.aiApiUrl)
            .putString("ai_model", config.aiModel)
            .putString("ai_thinking_model", config.aiThinkingModel)
            .putInt("ai_summary_interval", config.aiSummaryInterval)
            .putBoolean("ai_two_enabled", config.aiTwoEnabled)
            .putString("ai_two_api_key", config.aiTwoApiKey)
            .putString("ai_two_provider", config.aiTwoProvider)
            .putString("ai_two_api_url", config.aiTwoApiUrl)
            .putString("ai_two_model", config.aiTwoModel)
            .putString("ai_two_thinking_model", config.aiTwoThinkingModel)
            .putBoolean("monitoring_active", config.monitoringActive)
            .putString("stock_sector", config.stockSector)
            .apply()
    }

    fun setMonitoringActive(active: Boolean) {
        prefs.edit().putBoolean("monitoring_active", active).apply()
    }

    // ── API Key 历史记录（最多5条） ──

    fun getApiKeyHistory(): List<ApiKeyEntry> {
        val raw = prefs.getString("api_key_history", "") ?: ""
        return raw.split("|||").filter { it.isNotBlank() }.map {
            val parts = it.split("|", limit = 2)
            ApiKeyEntry(parts[0], parts.getOrElse(1) { "" })
        }
    }

    fun addApiKeyToHistory(key: String, note: String = "") {
        if (key.isBlank()) return
        val entry = "$key|$note"
        val history = getApiKeyHistory().toMutableList()
        history.removeAll { it.key == key }
        history.add(0, ApiKeyEntry(key, note))
        if (history.size > 5) repeat(history.size - 5) { history.removeAt(history.size - 1) }
        prefs.edit().putString("api_key_history", history.joinToString("|||") { "${it.key}|${it.note}" }).apply()
    }

    // ── 股票代码历史记录（最多10条，存"代码|名称"） ──

    fun getStockCodeHistory(): List<Pair<String, String>> {
        val raw = prefs.getString("stock_code_history", "") ?: ""
        return raw.split("|||").filter { it.isNotBlank() }.map {
            val parts = it.split("|", limit = 2)
            Pair(parts[0], parts.getOrElse(1) { parts[0] })
        }
    }

    fun addStockCodeToHistory(code: String, name: String) {
        if (code.isBlank()) return
        val entry = "$code|$name"
        val list = getStockCodeHistory().toMutableList()
        list.removeAll { it.first == code }
        list.add(0, Pair(code, name))
        if (list.size > 10) repeat(list.size - 10) { list.removeAt(list.size - 1) }
        prefs.edit().putString("stock_code_history", list.joinToString("|||") { "${it.first}|${it.second}" }).apply()
    }
}

package com.stockspeaker

import android.content.Context

data class AppConfig(
    val stockCode: String = "",
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
    // 唯一 AI：固定 SenseNova，Key 由 BuildConfig 注入
    val aiEnabled: Boolean = false,
    val aiSummaryInterval: Int = 5,
    // 运行状态
    val monitoringActive: Boolean = false,
    val stockSector: String = "",
    // v1.1.0: 新数据源开关（北向已砍——A股盘中不再披露实时北向资金）
    val fundFlowEnabled: Boolean = true,
    val dragonTigerEnabled: Boolean = true,
    val conceptAutoDetect: Boolean = true,
    val alertLimitDistance: Boolean = true
)

class ConfigManager(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    init {
        prefs.edit()
            .remove("ai_api_key").remove("api_key_history")
            .remove("ai_provider").remove("ai_api_url").remove("ai_model").remove("ai_thinking_model")
            .remove("ai_two_enabled").remove("ai_two_api_key").remove("ai_two_provider")
            .remove("ai_two_api_url").remove("ai_two_model").remove("ai_two_thinking_model")
            .apply()
    }

    fun load(): AppConfig {
        // 内存缓存：2秒高频轮询避免反复读SharedPreferences磁盘IO
        val now = System.currentTimeMillis()
        val cached = cache
        if (cached != null && now - cacheTime < 2000) return cached
        val config = readFromPrefs()
        cache = config
        cacheTime = now
        return config
    }

    private fun readFromPrefs(): AppConfig {
        return AppConfig(
            stockCode = prefs.getString("stock_code", "") ?: "",
            speakInterval = prefs.getInt("speak_interval", 15),
            largeOrderThreshold = prefs.getInt("large_order_threshold", 500),
            speedAlertThreshold = prefs.getFloat("speed_alert_threshold", 0.5f).toDouble(),
            speakPrice = true,
            speakPct = prefs.getBoolean("speak_pct", true),
            speakCurrentHand = prefs.getBoolean("speak_current_hand", true),
            speakAmount = prefs.getBoolean("speak_amount", true),
            speakVolRatio = prefs.getBoolean("speak_vol_ratio", true),
            speakSpeed = prefs.getBoolean("speak_speed", false),
            speakLargeOrders = prefs.getBoolean("speak_large_orders", true),
            speakTransactionDetail = prefs.getBoolean("speak_transaction_detail", false),
            aiEnabled = prefs.getBoolean("ai_enabled", false),
            aiSummaryInterval = prefs.getInt("ai_summary_interval", 5),
            monitoringActive = prefs.getBoolean("monitoring_active", false),
            stockSector = prefs.getString("stock_sector", "") ?: "",
            fundFlowEnabled = prefs.getBoolean("fund_flow_enabled", true),
            dragonTigerEnabled = prefs.getBoolean("dragon_tiger_enabled", true),
            conceptAutoDetect = prefs.getBoolean("concept_auto_detect", true),
            alertLimitDistance = prefs.getBoolean("alert_limit_distance", true)
        )
    }

    fun save(config: AppConfig) {
        cache = config  // 即时更新缓存，避免下次load()重新读磁盘
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
            .putInt("ai_summary_interval", config.aiSummaryInterval)
            .putBoolean("monitoring_active", config.monitoringActive)
            .putString("stock_sector", config.stockSector)
            .putBoolean("fund_flow_enabled", config.fundFlowEnabled)
            .putBoolean("dragon_tiger_enabled", config.dragonTigerEnabled)
            .putBoolean("concept_auto_detect", config.conceptAutoDetect)
            .putBoolean("alert_limit_distance", config.alertLimitDistance)
            .apply()
    }

    fun setMonitoringActive(active: Boolean) {
        prefs.edit().putBoolean("monitoring_active", active).apply()
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

    companion object {
        private var cache: AppConfig? = null
        private var cacheTime: Long = 0L
    }
}

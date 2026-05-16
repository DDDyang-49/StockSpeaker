package com.stockspeaker

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ── 资金流向数据类 ──

data class FundFlowData(
    val mainForce: Double = 0.0,         // 主力净流入(万元)，正=流入
    val retail: Double = 0.0,            // 散户净流入(万元)
    val superLarge: Double = 0.0,        // 超大单净流入(万元)
    val large: Double = 0.0,             // 大单净流入(万元)
    val latestPrice: Double = 0.0,       // 最新成交价
    val mainForceRatio: Double = 0.0,    // 主力净流入占总成交比(%)
    val consecutiveIn: Int = 0,          // 连续N分钟主力同向
    val direction: String = ""           // "主力持续买入"/"主力持续卖出"/"主力分歧"/"资金平静"
) {
    /** 主力净流入口语化（万元→亿/万自适应） */
    val mainForceStr: String
        get() {
            val abs = kotlin.math.abs(mainForce)
            return when {
                abs >= 10000 -> "${"%.2f".format(mainForce / 10000)}亿"
                abs >= 100 -> "${"%.0f".format(mainForce)}万"
                abs > 0 -> "${"%.0f".format(mainForce)}万"
                else -> "0"
            }
        }

    /** 方向标签（阈值降低，避免小资金股票频繁触发"主力观望"） */
    val directionLabel: String
        get() = when {
            mainForce > 5000 -> "主力大幅买入"
            mainForce > 1000 -> "主力持续买入"
            mainForce > 500 -> "主力买入"
            mainForce > 100 -> "主力小幅买入"
            mainForce > 30 -> "主力偏多"
            mainForce < -5000 -> "主力大幅卖出"
            mainForce < -1000 -> "主力持续卖出"
            mainForce < -500 -> "主力卖出"
            mainForce < -100 -> "主力小幅卖出"
            mainForce < -30 -> "主力偏空"
            else -> "资金平静"
        }

    val isEmpty: Boolean get() = mainForce == 0.0 && retail == 0.0
}

// ═══════════════════════════════════════════
// 东方财富 — 个股资金流向抓取器
// 日级累计数据，每30秒拉一次
// 仅交易时段调用
// ═══════════════════════════════════════════

object FundFlowFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 东财市场代码：1=沪市, 0=深市(含创业板) */
    private fun getEastMoneySecid(code: String): String = when {
        code.startsWith("6") -> "1.$code"
        else -> "0.$code"  // 深市主板/创业板 + 北交所
    }

    fun fetch(code: String): FundFlowData {
        if (code.isBlank()) return FundFlowData()
        try {
            val secid = getEastMoneySecid(code)
            // 日级资金流向，取最新 1 条（即今天累计）
            val url = "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get?" +
                    "lmt=1&klt=101&secid=$secid" +
                    "&fields1=f1,f2,f3,f7" +
                    "&fields2=f51,f52,f53,f54,f55,f56"
            val req = Request.Builder().url(url)
                .header("Referer", "https://data.eastmoney.com/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return FundFlowData()
            val json = JSONObject(body)
            val klines = json.optJSONObject("data")?.optJSONArray("klines")
                ?: return FundFlowData()
            if (klines.length() == 0) return FundFlowData()

            // kline 格式：日期,主力净流入(元),超大单净流入(元),大单净流入(元),中单净流入(元),小单净流入(元)
            val lastKline = klines.optString(klines.length() - 1) ?: return FundFlowData()
            val parts = lastKline.split(",")
            if (parts.size < 6) return FundFlowData()

            // 东财返回单位为"元"，转为"万元"保持与原有播报口径一致
            val mainForce = (parts[1].toDoubleOrNull() ?: 0.0) / 10000.0
            val superLarge = (parts[2].toDoubleOrNull() ?: 0.0) / 10000.0
            val large = (parts[3].toDoubleOrNull() ?: 0.0) / 10000.0
            val medium = (parts[4].toDoubleOrNull() ?: 0.0) / 10000.0
            val small = (parts[5].toDoubleOrNull() ?: 0.0) / 10000.0
            val retail = medium + small

            // 主力占总成交比（近似，用主力绝对值 / 全部资金绝对值之和）
            val allAbs = kotlin.math.abs(mainForce) + kotlin.math.abs(retail)
            val mainForceRatio = if (allAbs > 0) kotlin.math.abs(mainForce) / allAbs * 100 else 0.0

            // 方向判断（日累计数据，基于累计净流入幅度的语义判断）
            val direction = when {
                mainForce > 5000 -> "主力大幅买入"
                mainForce > 1000 -> "主力持续买入"
                mainForce > 500 -> "主力买入"
                mainForce > 100 -> "主力小幅买入"
                mainForce < -5000 -> "主力大幅卖出"
                mainForce < -1000 -> "主力持续卖出"
                mainForce < -500 -> "主力卖出"
                mainForce < -100 -> "主力小幅卖出"
                kotlin.math.abs(mainForce) < 50 -> "资金平静"
                mainForce > 0 -> "主力偏多"
                else -> "主力偏空"
            }

            return FundFlowData(
                mainForce = mainForce,
                retail = retail,
                superLarge = superLarge,
                large = large,
                latestPrice = 0.0,
                mainForceRatio = mainForceRatio,
                consecutiveIn = 0,  // 日级数据无分钟级连续判断
                direction = direction
            )
        } catch (_: Exception) {
            return FundFlowData()
        }
    }
}

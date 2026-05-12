package com.stockspeaker

import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    /** 方向标签 */
    val directionLabel: String
        get() = when {
            mainForce > 500 -> "主力买入"
            mainForce > 100 -> "主力小幅买入"
            mainForce < -500 -> "主力卖出"
            mainForce < -100 -> "主力小幅卖出"
            else -> "主力观望"
        }

    val isEmpty: Boolean get() = mainForce == 0.0 && retail == 0.0
}

// ═══════════════════════════════════════════
// 百度股市通 — 个股资金流向抓取器
// 零鉴权，每30秒拉一次（分钟级粒度）
// 仅交易时段调用
// ═══════════════════════════════════════════

object FundFlowFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun fetch(code: String): FundFlowData {
        if (code.isBlank()) return FundFlowData()
        try {
            val today = dateFmt.format(Date())
            val url = "https://finance.pae.baidu.com/vapi/v1/fundflow" +
                    "?code=$code&market=ab&date=$today&finClientType=pc"
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Origin", "https://gushitong.baidu.com")
                .header("Referer", "https://gushitong.baidu.com/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return FundFlowData()

            // 响应格式：分号分隔的分钟级数据
            // time,mainForce,retail,super,large,price;0931,100.5,50.2,30.1,20.3,15.50;...
            val entries = body.trim().split(";").filter { it.isNotBlank() }
            if (entries.isEmpty()) return FundFlowData()

            // 取最近3分钟数据判断趋势
            val recent = entries.takeLast(minOf(3, entries.size))
            val parsed = recent.map { entry ->
                val parts = entry.split(",")
                if (parts.size >= 5) {
                    listOf(
                        parts.getOrElse(1) { "0" }.toDoubleOrNull() ?: 0.0,  // mainForce
                        parts.getOrElse(2) { "0" }.toDoubleOrNull() ?: 0.0,  // retail
                        parts.getOrElse(3) { "0" }.toDoubleOrNull() ?: 0.0,  // super
                        parts.getOrElse(4) { "0" }.toDoubleOrNull() ?: 0.0,  // large
                        parts.getOrElse(5) { "0" }.toDoubleOrNull() ?: 0.0   // price
                    )
                } else listOf(0.0, 0.0, 0.0, 0.0, 0.0)
            }

            // 最新一分钟数据
            val latest = parsed.lastOrNull() ?: listOf(0.0, 0.0, 0.0, 0.0, 0.0)
            val mainForce = latest[0]
            val retail = latest[1]
            val superLarge = latest[2]
            val large = latest[3]
            val latestPrice = latest[4]

            // 计算主力占比
            val total = kotlin.math.abs(mainForce) + kotlin.math.abs(retail)
            val mainForceRatio = if (total > 0) kotlin.math.abs(mainForce) / total * 100 else 0.0

            // 判断连续同向分钟数
            var consecutiveIn = 0
            var lastSign = 0
            for (i in parsed.size - 1 downTo 0) {
                val sign = when {
                    parsed[i][0] > 50 -> 1
                    parsed[i][0] < -50 -> -1
                    else -> 0
                }
                if (sign == 0) break
                if (lastSign == 0) lastSign = sign
                if (sign == lastSign) consecutiveIn++ else break
            }

            // 方向判断
            val direction = when {
                consecutiveIn >= 5 && mainForce > 0 -> "主力持续买入"
                consecutiveIn >= 5 && mainForce < 0 -> "主力持续卖出"
                consecutiveIn >= 3 && mainForce > 0 -> "主力偏多"
                consecutiveIn >= 3 && mainForce < 0 -> "主力偏空"
                mainForce > 500 -> "主力突击买入"
                mainForce < -500 -> "主力突击卖出"
                kotlin.math.abs(mainForce) < 50 -> "资金平静"
                mainForce > 0 -> "主力分歧偏买"
                else -> "主力分歧偏卖"
            }

            return FundFlowData(
                mainForce, retail, superLarge, large, latestPrice,
                mainForceRatio, consecutiveIn, direction
            )
        } catch (_: Exception) {
            return FundFlowData()
        }
    }
}

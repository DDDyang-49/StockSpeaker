package com.stockspeaker

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class StockData(
    val name: String = "",
    val code: String = "",
    val price: Double = 0.0,
    val changePct: Double = 0.0,
    val totalVol: Int = 0,
    val amountWan: Double = 0.0,
    val volRatio: Double = 0.0,
    val bids: List<Pair<Double, Int>> = emptyList(),
    val asks: List<Pair<Double, Int>> = emptyList()
) {
    val amountStr: String
        get() = if (amountWan > 10000) "${"%.2f".format(amountWan / 10000)}亿" else "${"%.2f".format(amountWan)}万"

    val largeBids: List<String>
        get() = bids.mapIndexedNotNull { i, (p, v) ->
            if (v > 0 && p > 0) "买${i + 1}排${v}手" else null
        }

    val largeAsks: List<String>
        get() = asks.mapIndexedNotNull { i, (p, v) ->
            if (v > 0 && p > 0) "卖${i + 1}排${v}手" else null
        }

    val largeBidsSpeak: List<String>
        get() = bids.mapIndexedNotNull { i, (p, v) ->
            if (v > 0 && p > 0) "买${i + 1}${v}手托单" else null
        }

    val largeAsksSpeak: List<String>
        get() = asks.mapIndexedNotNull { i, (p, v) ->
            if (v > 0 && p > 0) "卖${i + 1}${v}手压单" else null
        }
}

// ── 行情背景数据（消息面+资金流向，暂无免费API时使用Mock） ──

data class MarketContext(
    val newsSentiment: String = "中性",      // 利好/利空/中性
    val newsHeadline: String = "",            // 最新相关新闻标题
    val fundFlow: String = "主力净流入",      // 主力/散户/游资
    val fundFlowAmount: String = "0亿",       // 净流入金额
    val alertStats: String = ""               // 本时段异动统计（由外部传入）
)

// ── 模拟数据生成（可根据股票代码和时段生成不同的假数据） ──

private val mockNewsPool = listOf(
    "板块轮动加速，资金高低切换明显",
    "北向资金今日净买入，权重股获青睐",
    "行业政策面利好频出，市场情绪回暖",
    "量能持续萎缩，短线博弈加剧",
    "大单资金午后异动，游资活跃度提升",
    "主力资金净流入板块龙头，跟风盘增多",
    "市场分歧加大，多空博弈激烈",
    "外围市场走强，情绪传导至A股"
)

fun generateMockContext(code: String, changePct: Double): MarketContext {
    val sentiment = when {
        changePct > 2.0 -> "利好"
        changePct < -2.0 -> "利空"
        else -> "中性"
    }
    val fundDir = if (changePct > 0) "主力净流入" else "主力净流出"
    val amount = if (changePct > 0) "${"%.1f".format(Math.abs(changePct) * 1.5)}亿" else "${"%.1f".format(Math.abs(changePct) * 1.2)}亿"
    val newsIdx = code.hashCode().ushr(16) % mockNewsPool.size
    val headline = mockNewsPool[(newsIdx + (System.currentTimeMillis() / 60000).toInt()) % mockNewsPool.size]

    return MarketContext(
        newsSentiment = sentiment,
        newsHeadline = headline,
        fundFlow = fundDir,
        fundFlowAmount = amount
    )
}

object StockFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun getMarketPrefix(code: String): String = when {
        code.startsWith("6") -> "sh$code"
        code.startsWith("0") || code.startsWith("3") -> "sz$code"
        code.startsWith("8") || code.startsWith("4") -> "bj$code"
        else -> "sh$code"
    }

    fun fetch(code: String, threshold: Int = 500): StockData? {
        if (code.isBlank()) return null
        try {
            val url = "https://qt.gtimg.cn/q=${getMarketPrefix(code)}"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            return parse(body, threshold)
        } catch (e: Exception) {
            return null
        }
    }

    private fun parse(rawData: String, threshold: Int): StockData? {
        val lines = rawData.trim().split(";")
        for (line in lines) {
            if (line.isBlank() || "=" !in line) continue
            val body = line.substringAfter("=").trim('"')
            val arr = body.split("~")
            if (arr.size < 50) continue

            val name = arr[1]
            val code = arr[2]
            val price = arr[3].toDoubleOrNull() ?: continue
            val changePct = arr[32].toDoubleOrNull() ?: 0.0
            val totalVol = arr[6].toIntOrNull() ?: 0
            val amountWan = arr[37].toDoubleOrNull() ?: 0.0
            val volRatio = arr[49].toDoubleOrNull() ?: 0.0

            val bids = (9..17 step 2).map { i ->
                val p = arr[i].toDoubleOrNull() ?: 0.0
                val v = arr[i + 1].toIntOrNull() ?: 0
                Pair(p, if (v >= threshold) v else 0)
            }

            val asks = (19..27 step 2).map { i ->
                val p = arr[i].toDoubleOrNull() ?: 0.0
                val v = arr[i + 1].toIntOrNull() ?: 0
                Pair(p, if (v >= threshold) v else 0)
            }

            return StockData(name, code, price, changePct, totalVol, amountWan, volRatio, bids, asks)
        }
        return null
    }
}

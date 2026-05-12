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
    val turnover: Double = 0.0,
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
    val newsSentiment: String = "中性",
    val newsHeadline: String = "",
    val fundFlow: String = "主力净流入",
    val fundFlowAmount: String = "0亿",
    val alertStats: String = "",
    val stockSector: String = ""
)

object StockFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** 根据股票名称搜索代码，返回 (代码, 名称) 或 null */
    fun searchStock(keyword: String): Pair<String, String>? {
        if (keyword.isBlank()) return null
        // 纯数字直接当作代码
        if (keyword.all { it.isDigit() }) return Pair(keyword, keyword)
        try {
            val url = "https://smartbox.gtimg.cn/s3/?q=${java.net.URLEncoder.encode(keyword, "UTF-8")}&t=all"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string() ?: return null
                // 格式: ..."1^股票名~代码~市场代码~..."
                val match = Regex("~(\\d{6})~").find(body)
                if (match != null) {
                    val code = match.groupValues[1]
                    // 提取名称
                    val nameMatch = Regex("\\^(.*?)~$code").find(body)
                    val name = nameMatch?.groupValues?.get(1) ?: code
                    return Pair(code, name)
                }
            }
        } catch (_: Exception) {}
        return null
    }

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
            response.use { resp ->
                val body = resp.body?.string() ?: return null
                return parse(body, threshold)
            }
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
            val turnover = arr[38].toDoubleOrNull() ?: 0.0

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

            return StockData(name, code, price, changePct, totalVol, amountWan, volRatio, turnover, bids, asks)
        }
        return null
    }

    /** 拉取近N日日K线，返回格式化文本供AI分析 */
    fun fetchDailyHistoryText(code: String, count: Int = 5): String {
        try {
            val prefix = getMarketPrefix(code)
            val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$prefix,day,,,$count,qfq"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string() ?: return ""
                val json = body.substringAfter("kline_dayqfq=").trim()
                val dayPattern = Regex("\"qfqday\":\\[(.*?)\\]")
                val match = dayPattern.find(json) ?: return ""
                val entries = match.groupValues[1].split("],[").takeLast(count)
                val lines = mutableListOf<String>()
                for (entry in entries) {
                    val parts = entry.trim('"', '[', ']').split(",").map { it.trim('"') }
                    if (parts.size >= 5) {
                        val date = parts[0].takeLast(5)
                        val open = parts[1].toDoubleOrNull() ?: continue
                        val close = parts[4].toDoubleOrNull() ?: continue
                        val high = parts[3].toDoubleOrNull() ?: 0.0
                        val low = parts[2].toDoubleOrNull() ?: 0.0
                        val chg = ((close - open) / open * 100).let { if (it >= 0) "+${"%.1f".format(it)}" else "${"%.1f".format(it)}" }
                        lines.add("$date O${"%.2f".format(open)} H${"%.2f".format(high)} L${"%.2f".format(low)} C${"%.2f".format(close)}($chg%)")
                    }
                }
                return lines.joinToString("；")
            }
        } catch (_: Exception) { return "" }
    }

    /** 拉取上证指数实时数据 */
    fun fetchShanghaiIndexText(): String {
        try {
            val url = "https://qt.gtimg.cn/q=sh000001"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string() ?: return ""
                val line = body.split(";").firstOrNull { "=" in it } ?: return ""
                val arr = line.substringAfter("=").trim('"').split("~")
                if (arr.size < 50) return ""
                val price = arr[3].toDoubleOrNull() ?: return ""
                val pct = arr[32].toDoubleOrNull() ?: 0.0
                val dir = if (pct > 0) "+" else ""
                return "上证${price}(${dir}${"%.2f".format(pct)}%)"
            }
        } catch (_: Exception) { return "" }
    }
}

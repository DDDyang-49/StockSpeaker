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

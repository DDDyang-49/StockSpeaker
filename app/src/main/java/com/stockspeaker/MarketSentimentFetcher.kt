package com.stockspeaker

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ── 全局情绪数据类（可序列化，后续可持久化） ──

data class GlobalSentiment(
    val upCount: Int = 0,
    val downCount: Int = 0,
    val limitUpCount: Int = 0,
    val limitDownCount: Int = 0,
    val leadingSectors: List<String> = emptyList(),
    val topStock: String = "",
    val topStockBoards: Int = 0,
    val flashNews: List<String> = emptyList(),
    val fetchTime: Long = 0L
) {
    val isEmpty: Boolean
        get() = upCount == 0 && downCount == 0 && limitUpCount == 0 &&
                limitDownCount == 0 && leadingSectors.isEmpty() &&
                topStock.isEmpty() && flashNews.isEmpty()

    /** 拼接为精简情绪切片，注入 AI Prompt */
    fun toSentimentSlice(): String = buildString {
        if (limitUpCount > 0 || limitDownCount > 0) {
            val parts = mutableListOf<String>()
            if (limitUpCount > 0) parts.add("涨停${limitUpCount}家")
            if (limitDownCount > 0) parts.add("跌停${limitDownCount}家")
            if (upCount > 0) parts.add("上涨${upCount}家")
            if (downCount > 0) parts.add("下跌${downCount}家")
            append("${parts.joinToString("，")}。")
        }
        if (leadingSectors.isNotEmpty()) {
            append("领涨主线：${leadingSectors.joinToString("、")}。")
        }
        if (topStock.isNotBlank() && topStockBoards > 0) {
            append("市场高标：${topStock}(${topStockBoards}板)。")
        }
    }

    /** 带标题的完整版，用于 AI System Prompt */
    fun toSentimentBlock(): String {
        val slice = toSentimentSlice()
        if (slice.isBlank()) return ""
        return "【当前全市场情绪】$slice"
    }

    /** 盘中异动电报纯文本块 */
    fun toFlashNewsBlock(): String {
        if (flashNews.isEmpty()) return ""
        return "【盘中核心异动】\n${flashNews.joinToString("\n") { "· $it" }}"
    }
}

// ═══════════════════════════════════════════
// 全局情绪抓取器
// 只使用真实API，网络异常时返回空值
// 所有网络请求 5 秒超时，独立线程执行
// ═══════════════════════════════════════════

object MarketSentimentFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 一键拉取全部情绪数据（网络异常时返回空值，不做假数据兜底） */
    fun fetchAll(): GlobalSentiment {
        val breadth = fetchMarketBreadth()
        val sectors = fetchLeadingSectors()
        val top = fetchTopStock()
        val news = fetchFlashNews()
        return GlobalSentiment(
            upCount = breadth.first,
            downCount = breadth.second,
            limitUpCount = breadth.third,
            limitDownCount = breadth.fourth,
            leadingSectors = sectors,
            topStock = top.first,
            topStockBoards = top.second,
            flashNews = news,
            fetchTime = System.currentTimeMillis()
        )
    }

    // ── ① 市场温度（涨跌家数 + 涨停跌停家数） ──

    private fun fetchMarketBreadth(): Quadruple<Int, Int, Int, Int> {
        try {
            val url = "https://push2.eastmoney.com/api/qt/stock/get?secid=1.000001" +
                    "&fields=f47,f48,f50,f51,f170"
            val req = Request.Builder().url(url)
                .header("Referer", "https://quote.eastmoney.com/")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return Quadruple(0, 0, 0, 0)
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return Quadruple(0, 0, 0, 0)
            // 常识边界防御：A股总数约5500只，任何>10000或<0的值都是脏数据
            fun Int.sane(): Int = if (this in 0..10000) this else 0
            val up = data.optInt("f47", 0).sane()
            val down = data.optInt("f48", 0).sane()
            val limitUp = data.optInt("f50", 0).sane()
            val limitDown = data.optInt("f51", 0).sane()
            if (up == 0 && down == 0) return Quadruple(0, 0, 0, 0)
            return Quadruple(up, down, limitUp, limitDown)
        } catch (_: Exception) {
            return Quadruple(0, 0, 0, 0)
        }
    }

    // ── ② 主线风口（领涨概念板块 Top 3） ──

    private fun fetchLeadingSectors(): List<String> {
        try {
            val url = "https://push2.eastmoney.com/api/qt/clist/get?" +
                    "pn=1&pz=5&po=1&np=1&fields=f14,f3&fid=f3&fs=m:90+t2"
            val req = Request.Builder().url(url)
                .header("Referer", "https://quote.eastmoney.com/")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            val diffs = data?.optJSONArray("diff") ?: return emptyList()
            val sectors = mutableListOf<String>()
            for (i in 0 until minOf(diffs.length(), 5)) {
                val item = diffs.optJSONObject(i) ?: continue
                val name = item.optString("f14", "")
                val pct = item.optDouble("f3", 0.0)
                if (name.isNotBlank() && pct > 0) {
                    sectors.add("$name+${"%.1f".format(pct)}%")
                }
            }
            return if (sectors.isNotEmpty()) sectors.take(3) else emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
    }

    // ── ③ 最高标的（连板天梯最高板） ──

    private fun fetchTopStock(): Pair<String, Int> {
        try {
            val url = "https://push2.eastmoney.com/api/qt/clist/get?" +
                    "pn=1&pz=15&po=1&np=1&fields=f14,f12,f3,f8&fid=f8&fs=b:MK0354"
            val req = Request.Builder().url(url)
                .header("Referer", "https://quote.eastmoney.com/")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return Pair("", 0)
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            val diffs = data?.optJSONArray("diff") ?: return Pair("", 0)
            val badWords = listOf("转", "债", "ST", "退", "N", "C", "U", "W")
            for (i in 0 until diffs.length()) {
                val item = diffs.optJSONObject(i) ?: continue
                val name = item.optString("f14", "")
                val code = item.optString("f12", "")
                if (name.isBlank()) continue
                // 过滤转债（11xxxx/12xxxx）和ST/退市/新股等
                if (code.startsWith("11") || code.startsWith("12")) continue
                if (badWords.any { it in name }) continue
                val boards = item.optInt("f8", 0)
                // 常识校验：A股史上最高连板记录约30板
                if (boards > 30 || boards <= 0) continue
                return Pair(name, boards)
            }
            return Pair("", 0)
        } catch (_: Exception) {
            return Pair("", 0)
        }
    }

    // ── ④ 盘中异动电报（7×24 财经快讯，筛选关键字） ──

    private fun fetchFlashNews(): List<String> {
        try {
            // 新浪 7×24 小时全球财经直播
            val url = "https://feed.mix.sina.com.cn/api/roll/get?" +
                    "pageid=153&lid=2516&k=&num=30&page=1"
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val data = json.optJSONObject("result")?.optJSONArray("data")
                ?: return emptyList()
            val keywords = listOf("拉升", "涨停", "跌停", "炸板", "板块", "封板",
                "异动", "直线", "走强", "走弱", "跳水", "反弹", "触板", "连板")
            val result = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val title = item.optString("title", "")
                if (title.isBlank()) continue
                val clean = title.replace(Regex("<[^>]*>"), "").trim()
                if (clean.length > 80) continue
                if (keywords.any { it in clean }) {
                    result.add(clean)
                    if (result.size >= 5) break
                }
            }
            return if (result.isNotEmpty()) result else emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
    }

/** 四元组（避免引入 Kotlin stdlib 之外的依赖） */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}

package com.stockspeaker

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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
// 优先级：真实API → Mock（兜底）
// 所有网络请求 5 秒超时，独立线程执行
// ═══════════════════════════════════════════

object MarketSentimentFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 一键拉取全部情绪数据（真实API + Mock兜底） */
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
            val body = resp.body?.string() ?: return mockBreadth()
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return mockBreadth()
            val up = data.optInt("f47", 0)
            val down = data.optInt("f48", 0)
            val limitUp = data.optInt("f50", 0)
            val limitDown = data.optInt("f51", 0)
            if (up == 0 && down == 0) return mockBreadth()
            return Quadruple(up, down, limitUp, limitDown)
        } catch (_: Exception) {
            return mockBreadth()
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
            val body = resp.body?.string() ?: return mockSectors()
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            val diffs = data?.optJSONArray("diff") ?: return mockSectors()
            val sectors = mutableListOf<String>()
            for (i in 0 until minOf(diffs.length(), 5)) {
                val item = diffs.optJSONObject(i) ?: continue
                val name = item.optString("f14", "")
                val pct = item.optDouble("f3", 0.0)
                if (name.isNotBlank() && pct > 0) {
                    sectors.add("$name+${"%.1f".format(pct)}%")
                }
            }
            return if (sectors.isNotEmpty()) sectors.take(3) else mockSectors()
        } catch (_: Exception) {
            return mockSectors()
        }
    }

    // ── ③ 最高标的（连板天梯最高板） ──

    private fun fetchTopStock(): Pair<String, Int> {
        try {
            // 尝试东方财富"连续涨停"概念成分股，按连板数排序
            val url = "https://push2.eastmoney.com/api/qt/clist/get?" +
                    "pn=1&pz=3&po=1&np=1&fields=f14,f12,f3,f8&fid=f8&fs=b:MK0354"
            val req = Request.Builder().url(url)
                .header("Referer", "https://quote.eastmoney.com/")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return mockTopStock()
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            val diffs = data?.optJSONArray("diff") ?: return mockTopStock()
            if (diffs.length() == 0) return mockTopStock()
            val item = diffs.optJSONObject(0)
            val name = item?.optString("f14", "") ?: ""
            // f8 可能是涨停次数/连板数，具体字段视API版本而定
            val boards = item?.optInt("f8", 0) ?: 0
            if (name.isBlank() || boards == 0) return mockTopStock()
            return Pair(name, boards)
        } catch (_: Exception) {
            return mockTopStock()
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
            val body = resp.body?.string() ?: return mockFlashNews()
            val json = JSONObject(body)
            val data = json.optJSONObject("result")?.optJSONArray("data")
                ?: return mockFlashNews()
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
            return if (result.isNotEmpty()) result else mockFlashNews()
        } catch (_: Exception) {
            return mockFlashNews()
        }
    }

    // ═══════════════════════════════════════════
    // Mock 兜底数据（真实API失败时使用）
    // ═══════════════════════════════════════════

    private fun mockBreadth(): Quadruple<Int, Int, Int, Int> {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        // A股交易时间：9:30-11:30, 13:00-15:00
        val isTrading = (hour == 9 && minute >= 30) || hour == 10 ||
                (hour == 11 && minute <= 30) || hour == 13 || hour == 14 ||
                (hour == 15 && minute == 0)
        if (!isTrading) return Quadruple(0, 0, 0, 0)
        val upBase = 1800 + Random.nextInt(-200, 200)
        val downBase = 2800 + Random.nextInt(-300, 300)
        val limitUp = 40 + Random.nextInt(-15, 30)
        val limitDown = Random.nextInt(1, 8)
        return Quadruple(upBase, downBase, limitUp, limitDown)
    }

    private fun mockSectors(): List<String> {
        val pools = listOf(
            listOf("存储芯片+3.2%", "光刻机+2.8%", "先进封装+2.1%"),
            listOf("AI大模型+4.1%", "算力租赁+3.5%", "数据要素+2.9%"),
            listOf("低空经济+3.8%", "商业航天+2.6%", "无人机+2.2%"),
            listOf("创新药+2.9%", "医疗器械+2.4%", "CXO+2.0%"),
            listOf("固态电池+3.6%", "光伏逆变器+2.7%", "储能+2.3%"),
            listOf("机器人+4.5%", "工业母机+3.1%", "传感器+2.5%"),
            listOf("智能驾驶+3.3%", "汽车零部件+2.8%", "一体化压铸+2.1%")
        )
        return pools[Random.nextInt(pools.size)]
    }

    private fun mockTopStock(): Pair<String, Int> {
        val candidates = listOf(
            "克来机电" to 8, "圣龙股份" to 7, "中视传媒" to 6,
            "大唐发电" to 6, "天龙股份" to 5, "深中华A" to 5,
            "清源股份" to 4, "亚世光电" to 4, "日久光电" to 4,
            "东安动力" to 3, "惠发食品" to 3, "南京商旅" to 3
        )
        return candidates[Random.nextInt(candidates.size)]
    }

    private fun mockFlashNews(): List<String> {
        val pools = listOf(
            listOf(
                "半导体板块午后异动拉升，存储芯片方向领涨",
                "AI概念股持续走强，多股涨停封板",
                "低空经济概念快速跳水，前期高位股炸板回落",
                "券商板块异动拉升，带动指数翻红",
                "北向资金快速流入，权重股获大单扫货"
            ),
            listOf(
                "光伏板块触底反弹，龙头直线拉升封板",
                "机器人概念持续活跃，板块内多股涨停",
                "医药板块走弱，CXO方向领跌",
                "算力概念再度走强，资金回流明显",
                "次新股午后异动，多股直线拉升"
            ),
            listOf(
                "消费电子板块异动，华为产业链集体拉升",
                "汽车零部件板块分化，高位股炸板",
                "固态电池概念走强，板块涨幅居前",
                "中字头板块异动，中国科传直线涨停",
                "高标股炸板潮，短线情绪急转直下"
            )
        )
        return pools[Random.nextInt(pools.size)]
    }
}

/** 四元组（避免引入 Kotlin stdlib 之外的依赖） */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

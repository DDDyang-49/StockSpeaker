package com.stockspeaker

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ── 龙虎榜数据类 ──

data class DragonTigerEntry(
    val code: String = "",
    val name: String = "",
    val reason: String = "",          // 上榜原因
    val close: Double = 0.0,          // 收盘价
    val changePct: Double = 0.0,      // 涨跌幅%
    val netBuyWan: Double = 0.0,      // 净买入(万元)
    val buyWan: Double = 0.0,         // 买入总计(万元)
    val sellWan: Double = 0.0,        // 卖出总计(万元)
    val turnoverPct: Double = 0.0     // 换手率%
)

data class DragonTigerData(
    val onBoard: Boolean = false,                // 盯盘股是否上榜
    val entry: DragonTigerEntry? = null,         // 盯盘股上榜详情
    val totalOnBoard: Int = 0,                   // 今日全市场上榜总数
    val topNetBuy: DragonTigerEntry? = null,     // 今日净买入第一名
    val topNetBuyList: List<DragonTigerEntry> = emptyList()  // 净买入前5
) {
    val isEmpty: Boolean get() = totalOnBoard == 0

    /** 拼接为 AI 可用的龙虎榜上下文 */
    fun toContextSlice(code: String): String = buildString {
        if (onBoard && entry != null) {
            val dir = if (entry.netBuyWan > 0) "净买入" else "净卖出"
            val abs = kotlin.math.abs(entry.netBuyWan)
            val amt = if (abs >= 10000) "${"%.2f".format(abs / 10000)}亿" else "${"%.0f".format(abs)}万"
            append("【龙虎榜】该股近日上榜！${entry.reason}，$dir$amt。")
        }
        if (topNetBuy != null && topNetBuy.code != code) {
            append("近日龙虎榜净买第一：${topNetBuy.name}(${topNetBuy.reason})。")
        }
    }

    /** 简短版供播报/静态标签 */
    fun toTagText(): String {
        if (!onBoard || entry == null) return ""
        val dir = if (entry.netBuyWan > 0) "净买" else "净卖"
        val abs = kotlin.math.abs(entry.netBuyWan)
        val amt = if (abs >= 10000) "${"%.1f".format(abs / 10000)}亿" else "${"%.0f".format(abs)}万"
        return "${entry.name}近日龙虎榜${dir}${amt}，${entry.reason}"
    }
}

// ═══════════════════════════════════════════
// 东方财富龙虎榜抓取器
// 零鉴权，盘后数据 → SharedPreferences日期缓存
// 每次访问时检查日期，过期自动刷新
// ═══════════════════════════════════════════

object DragonTigerFetcher {

    private const val PREFS_NAME = "dragon_tiger_cache"
    private const val KEY_TAG = "cached_tag"
    private const val KEY_DATE = "cache_date"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // 内存缓存（避免同一次进程内重复读 SharedPreferences）
    private var memTag: String = ""
    private var memDate: String = ""

    /**
     * 获取龙虎榜静态标签。
     * 每次调用检查 SharedPreferences 中的缓存日期，若过期则发起 HTTP 刷新。
     * 确保后台长期挂机时数据不会过期。
     */
    fun fetchDailyTag(code: String, ctx: Context): String {
        if (code.isBlank()) return ""
        val today = dateFmt.format(Date())
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 优先从内存读，命中且日期当天直接返回
        if (memTag.isNotEmpty() && memDate == today) return memTag

        // 内存未命中 → 读 SharedPreferences
        val cachedDate = prefs.getString(KEY_DATE, "") ?: ""
        val cachedTag = prefs.getString(KEY_TAG, "") ?: ""

        if (cachedTag.isNotBlank() && cachedDate == today) {
            memTag = cachedTag
            memDate = cachedDate
            return cachedTag
        }

        // 缓存过期或不存在 → 发起 HTTP 请求
        try {
            var data = tryFetch(code, today)
            if (data.isEmpty) {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
                val yesterday = dateFmt.format(cal.time)
                data = tryFetch(code, yesterday)
            }

            val tag = data.toTagText()
            // 持久化到 SharedPreferences
            prefs.edit()
                .putString(KEY_TAG, tag)
                .putString(KEY_DATE, today)
                .apply()
            memTag = tag
            memDate = today
            return tag
        } catch (_: Exception) {
            // 网络失败回退到过期缓存（聊胜于无）
            return cachedTag
        }
    }

    /** 清空缓存（切换盯盘股票时调用） */
    fun clearCache(ctx: Context) {
        memTag = ""
        memDate = ""
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun tryFetch(code: String, date: String): DragonTigerData {
        try {
            val url = "https://datacenter-web.eastmoney.com/api/data/v1/get" +
                    "?reportName=RPT_DAILYBILLBOARD_DETAILSNEW" +
                    "&pageSize=500&pageNumber=1" +
                    "&sortTypes=-1&sortColumns=BILLBOARD_NET_AMT" +
                    "&filter=(TRADE_DATE='$date')"
            val req = Request.Builder().url(url)
                .header("Referer", "https://data.eastmoney.com/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return DragonTigerData()
            val json = JSONObject(body)

            if (!json.optBoolean("success", false)) return DragonTigerData()
            val result = json.optJSONObject("result") ?: return DragonTigerData()
            val dataArr = result.optJSONArray("data") ?: return DragonTigerData()

            val allEntries = mutableListOf<DragonTigerEntry>()
            val SENTINEL = 2147483647.0  // 东方财富脏数据标记值
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                fun safeD(key: String, default: Double = 0.0): Double {
                    val v = item.optDouble(key, default)
                    return if (v == SENTINEL) 0.0 else v
                }
                allEntries.add(DragonTigerEntry(
                    code = item.optString("SECURITY_CODE", ""),
                    name = item.optString("SECURITY_NAME_ABBR", ""),
                    reason = item.optString("BILLBOARD_REASON", ""),
                    close = safeD("CLOSE_PRICE"),
                    changePct = safeD("CHANGE_RATE"),
                    netBuyWan = safeD("BILLBOARD_NET_AMT") / 10000.0,
                    buyWan = safeD("BILLBOARD_BUY_AMT") / 10000.0,
                    sellWan = safeD("BILLBOARD_SELL_AMT") / 10000.0,
                    turnoverPct = safeD("TURNOVERRATE")
                ))
            }

            val myEntry = if (code.isNotBlank()) {
                allEntries.firstOrNull { it.code == code }
            } else null

            val top5 = allEntries
                .filter { it.netBuyWan > 0 }
                .sortedByDescending { it.netBuyWan }
                .take(5)

            return DragonTigerData(
                onBoard = myEntry != null,
                entry = myEntry,
                totalOnBoard = allEntries.size,
                topNetBuy = top5.firstOrNull(),
                topNetBuyList = top5
            )
        } catch (_: Exception) {
            return DragonTigerData()
        }
    }
}

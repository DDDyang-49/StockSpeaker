package com.stockspeaker

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ── 概念板块归属数据类 ──

data class ConceptBlockData(
    val industry: String = "",          // 申万一级行业，如"白酒"
    val industryPct: Double = 0.0,      // 行业实时涨跌幅%
    val concepts: List<String> = emptyList(),  // 概念板块名称列表
    val topConcept: String = "",        // 最相关概念板块
    val topConceptPct: Double = 0.0,    // 最相关概念涨跌幅%
    val region: String = ""             // 地域
) {
    /** 拼接为主力视角的板块上下文，注入 AI Prompt */
    fun toContextSlice(): String = buildString {
        if (industry.isNotBlank()) {
            append("所属行业：$industry")
            if (industryPct != 0.0) {
                val dir = if (industryPct > 0) "+" else ""
                append("($dir${"%.2f".format(industryPct)}%)")
            }
            append("。")
        }
        if (topConcept.isNotBlank()) {
            append("核心题材：$topConcept")
            if (topConceptPct != 0.0) {
                val dir = if (topConceptPct > 0) "+" else ""
                append("($dir${"%.2f".format(topConceptPct)}%)")
            }
            append("。")
        }
    }

    /** 个股 vs 板块相对强弱，供播报使用 */
    fun relativeStrength(stockPct: Double): String {
        val refPct = if (industryPct != 0.0) industryPct else topConceptPct
        if (refPct == 0.0) return ""
        val diff = stockPct - refPct
        return when {
            diff > 2.0 -> "强于板块${"%.1f".format(diff)}点"
            diff > 0.3 -> "略强于板块"
            diff < -2.0 -> "弱于板块${"%.1f".format(-diff)}点"
            diff < -0.3 -> "略弱于板块"
            else -> "与板块同步"
        }
    }

    /** Alpha差值精确公式：个股X% - 板块Y% = Z%，注入AI Prompt */
    fun alphaDiff(stockPct: Double): String {
        val refPct = if (industryPct != 0.0) industryPct else topConceptPct
        if (refPct == 0.0) return ""
        val diff = stockPct - refPct
        val stockStr = if (stockPct >= 0) "+${"%.2f".format(stockPct)}" else "${"%.2f".format(stockPct)}"
        val sectorStr = if (refPct >= 0) "+${"%.2f".format(refPct)}" else "${"%.2f".format(refPct)}"
        val diffStr = if (diff >= 0) "+${"%.2f".format(diff)}" else "${"%.2f".format(diff)}"
        return "个股${stockStr}% - 板块${sectorStr}% = ${diffStr}%"
    }

    val isEmpty: Boolean get() = industry.isBlank() && concepts.isEmpty()
}

// ═══════════════════════════════════════════
// 百度股市通 — 概念板块归属抓取器
// 零鉴权，启动时拉一次 + 每5分钟刷新
// ═══════════════════════════════════════════

object ConceptBlockFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun fetch(code: String): ConceptBlockData {
        if (code.isBlank()) return ConceptBlockData()
        try {
            val url = "https://finance.pae.baidu.com/api/getrelatedblock" +
                    "?code=$code&market=ab&typeCode=all&finClientType=pc"
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Origin", "https://gushitong.baidu.com")
                .header("Referer", "https://gushitong.baidu.com/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return ConceptBlockData()
            val json = JSONObject(body)

            // ResultCode 可能是 int 0 或 string "0"
            val rc = json.opt("ResultCode")
            val isOk = when (rc) {
                is Int -> rc == 0
                is String -> rc == "0"
                else -> false
            }
            if (!isOk) return ConceptBlockData()

            val result = json.optJSONObject("Result") ?: return ConceptBlockData()

            // 行业
            val industries = result.optJSONArray("industry")
            var industry = ""
            var industryPct = 0.0
            if (industries != null && industries.length() > 0) {
                val ind = industries.optJSONObject(0)
                if (ind != null) {
                    industry = ind.optString("name", "")
                    industryPct = ind.optDouble("change_pct", 0.0)
                }
            }

            // 概念板块
            val conceptArr = result.optJSONArray("concept")
            val concepts = mutableListOf<String>()
            var topConcept = ""
            var topConceptPct = 0.0
            if (conceptArr != null) {
                for (i in 0 until conceptArr.length()) {
                    val c = conceptArr.optJSONObject(i) ?: continue
                    val name = c.optString("name", "")
                    if (name.isNotBlank()) concepts.add(name)
                    if (i == 0) {
                        topConcept = name
                        topConceptPct = c.optDouble("change_pct", 0.0)
                    }
                }
            }

            // 地域
            val regions = result.optJSONArray("region")
            var region = ""
            if (regions != null && regions.length() > 0) {
                region = regions.optJSONObject(0)?.optString("name", "") ?: ""
            }

            return ConceptBlockData(industry, industryPct, concepts, topConcept, topConceptPct, region)
        } catch (_: Exception) {
            return ConceptBlockData()
        }
    }
}

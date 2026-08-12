package com.stockspeaker

import kotlin.math.max

/** 只提醒新出现或明显增大的盘口挂单，静止盘口不重复轰炸。 */
class OrderBookAlertTracker {
    private val previous = mutableMapOf<String, Int>()
    private val lastSpokenAt = mutableMapOf<String, Long>()

    fun evaluate(data: StockData, threshold: Int, now: Long): String? {
        val levels = buildList {
            data.bids.forEachIndexed { i, value -> add("买${levelName(i)}" to value.second) }
            data.asks.forEachIndexed { i, value -> add("卖${levelName(i)}" to value.second) }
        }
        val candidate = levels
            .filter { (level, hands) ->
                val old = previous[level] ?: 0
                val increased = hands - old >= max(threshold / 2, old / 2)
                hands >= threshold && (old < threshold || increased) && now - (lastSpokenAt[level] ?: 0L) >= 12_000L
            }
            .maxByOrNull { it.second }
        levels.forEach { previous[it.first] = it.second }
        return candidate?.let { (level, hands) ->
            lastSpokenAt[level] = now
            "盘口异动，${level}${spokenHands(hands)}大挂单"
        }
    }

    fun reset() {
        previous.clear()
        lastSpokenAt.clear()
    }

    private fun spokenHands(hands: Int): String {
        if (hands < 10_000) return "${hands}手"
        val value = (hands / 10_000.0).toString().trimEnd('0').trimEnd('.')
        return "${value}万手"
    }

    private fun levelName(index: Int) = listOf("一", "二", "三", "四", "五").getOrElse(index) { (index + 1).toString() }
}

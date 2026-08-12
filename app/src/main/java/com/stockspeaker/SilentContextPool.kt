package com.stockspeaker

/** 指数、板块和全市场情绪只在后台更新；仅共振状态改变时返回一句话。 */
class SilentContextPool {
    private enum class Resonance { NONE, UP, DOWN }

    private var previousSentiment = GlobalSentiment()
    private var breadthDelta = 0
    private var limitDelta = 0
    private var leadingSector = ""
    private var leadingRounds = 0
    private var resonance = Resonance.NONE

    @Synchronized
    fun observeSentiment(next: GlobalSentiment) {
        if (next.isEmpty || next.fetchTime == previousSentiment.fetchTime) return
        if (!previousSentiment.isEmpty) {
            breadthDelta = (next.upCount - next.downCount) -
                (previousSentiment.upCount - previousSentiment.downCount)
            limitDelta = (next.limitUpCount - next.limitDownCount) -
                (previousSentiment.limitUpCount - previousSentiment.limitDownCount)
        }
        val nextLeader = next.leadingSectors.firstOrNull().orEmpty()
        leadingRounds = if (nextLeader.isNotBlank() && nextLeader == leadingSector) leadingRounds + 1 else 1
        leadingSector = nextLeader
        previousSentiment = next
    }

    @Synchronized
    fun onFrame(stockPct: Double, sectorPct: Double, indexPct: Double): String? {
        val upParts = mutableListOf<String>()
        val downParts = mutableListOf<String>()
        if (sectorPct >= 0.5) upParts += "板块"
        if (sectorPct <= -0.5) downParts += "板块"
        if (indexPct >= 0.25) upParts += "大盘"
        if (indexPct <= -0.25) downParts += "大盘"
        if (breadthDelta >= 100 || limitDelta >= 3) upParts += "市场情绪"
        if (breadthDelta <= -100 || limitDelta <= -3) downParts += "市场情绪"
        if (leadingRounds >= 2 && leadingSector.isNotBlank() && sectorPct > 0.0) upParts += "持续主线"

        val next = when {
            stockPct >= 0.3 && upParts.distinct().size >= 2 -> Resonance.UP
            stockPct <= -0.3 && downParts.distinct().size >= 2 -> Resonance.DOWN
            else -> Resonance.NONE
        }
        if (next == resonance) return null
        val old = resonance
        resonance = next
        return when (next) {
            Resonance.UP -> "向上共振形成：${upParts.distinct().joinToString("、")}同步走强"
            Resonance.DOWN -> "下行共振形成：${downParts.distinct().joinToString("、")}同步走弱"
            Resonance.NONE -> if (old != Resonance.NONE) "原有共振已经减弱" else null
        }
    }

    @Synchronized
    fun reset() {
        previousSentiment = GlobalSentiment()
        breadthDelta = 0
        limitDelta = 0
        leadingSector = ""
        leadingRounds = 0
        resonance = Resonance.NONE
    }
}

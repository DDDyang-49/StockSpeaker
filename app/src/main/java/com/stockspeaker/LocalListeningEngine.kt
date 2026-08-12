package com.stockspeaker

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class TradingPhase {
    CALL_AUCTION, OPENING, MORNING, AFTERNOON_REOPEN, AFTERNOON, CLOSING, CLOSED;

    companion object {
        private val shanghai = ZoneId.of("Asia/Shanghai")

        fun at(wallMillis: Long): TradingPhase {
            val time = Instant.ofEpochMilli(wallMillis).atZone(shanghai)
            if (time.dayOfWeek.value > 5) return CLOSED
            val minute = time.hour * 60 + time.minute
            return when (minute) {
                in 555..569 -> CALL_AUCTION       // 09:15-09:29
                in 570..579 -> OPENING            // 09:30-09:39
                in 580..689 -> MORNING            // 09:40-11:29
                in 780..789 -> AFTERNOON_REOPEN   // 13:00-13:09
                in 790..881 -> AFTERNOON          // 13:10-14:41
                in 882..899 -> CLOSING            // 14:42-14:59
                else -> CLOSED
            }
        }
    }
}

enum class SignalStage {
    QUIET, BREAKOUT_ATTEMPT, BREAKOUT_CONFIRMED, ACCELERATING,
    EXHAUSTION_RISK, REVERSAL_CONFIRMED, EXIT_SIGNAL
}

data class HistoricalAnchors(
    val previousHigh: Double = 0.0,
    val fiveDayHigh: Double = 0.0
)

data class ListeningMetrics(
    val phase: TradingPhase,
    val frameWindowSeconds: Int,
    val speed15sPct: Double,
    val speed60sPct: Double,
    val addedVolume15s: Int,
    val recentHigh: Double,
    val recentLow: Double,
    val stage: SignalStage,
    val eventText: String?,
    val anchorText: String?
)

data class LargeOrderBurst(
    val count: Int,
    val totalHands: Int,
    val seconds: Int,
    val direction: String
) {
    val speech: String
        get() = "连续第${count}次，近${seconds.coerceAtLeast(1)}秒新增成交量${totalHands}手，$direction"
}

/**
 * 纯本地、按行情源时间工作的听盘引擎。它不读取持仓，也不调用 AI。
 */
class LocalListeningEngine {
    private data class Point(val time: Long, val price: Double, val totalVolume: Int)

    private val points = ArrayDeque<Point>()
    private var phase = TradingPhase.CLOSED
    private var tradingDate: LocalDate? = null
    private var stage = SignalStage.QUIET
    private var historical = HistoricalAnchors()
    private var burstCount = 0
    private var burstHands = 0
    private var burstStartedAt = 0L
    private var lastBurstAt = 0L
    private var burstDirection = "成交放大"

    fun setHistoricalAnchors(value: HistoricalAnchors) { historical = value }

    fun reset() {
        points.clear()
        phase = TradingPhase.CLOSED
        tradingDate = null
        stage = SignalStage.QUIET
        resetBurst()
    }

    fun onQuote(data: StockData): ListeningMetrics? {
        val now = data.sourceTimeMillis
        if (now <= 0L || data.price <= 0.0) return null
        val nextPhase = TradingPhase.at(now)
        if (nextPhase == TradingPhase.CLOSED) return null
        if (points.lastOrNull()?.time?.let { now <= it } == true) return null
        val nextDate = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        if (tradingDate != null && tradingDate != nextDate) {
            points.clear()
            stage = SignalStage.QUIET
            resetBurst()
        }
        tradingDate = nextDate

        // 午休不能成为涨速窗口的一部分；午后开盘必须重新建立短周期基线。
        if (nextPhase == TradingPhase.AFTERNOON_REOPEN && phase != TradingPhase.AFTERNOON_REOPEN) {
            points.clear()
            stage = SignalStage.QUIET
            resetBurst()
        }
        phase = nextPhase
        val previous = points.lastOrNull()
        points.addLast(Point(now, data.price, data.totalVol))
        while (points.firstOrNull()?.let { now - it.time > 240_000L } == true) points.removeFirst()

        val speed15 = speed(now, data.price, 15_000L)
        val speed60 = speed(now, data.price, 60_000L)
        val added15 = volumeDelta(now, data.totalVol, 15_000L)
        val prior60 = points.dropLast(1).filter { now - it.time <= 60_000L }
        val prior180 = points.dropLast(1).filter { now - it.time <= 180_000L }
        val breakoutHigh = (prior60.maxOfOrNull { it.price } ?: previous?.price ?: data.price)
        val recentHigh = (prior180.maxOfOrNull { it.price } ?: breakoutHigh)
        val recentLow = (prior180.minOfOrNull { it.price } ?: previous?.price ?: data.price)
        val speedThreshold = when (nextPhase) {
            TradingPhase.CALL_AUCTION -> 0.80
            TradingPhase.OPENING -> 0.45
            TradingPhase.MORNING -> 0.35
            TradingPhase.AFTERNOON_REOPEN -> 0.45
            TradingPhase.AFTERNOON -> 0.30
            TradingPhase.CLOSING -> 0.25
            TradingPhase.CLOSED -> Double.MAX_VALUE
        }
        val nearDayHigh = data.dayHigh > 0.0 && recentHigh >= data.dayHigh * 0.997
        val drawdown = if (recentHigh > 0.0) (recentHigh - data.price) / recentHigh * 100.0 else 0.0
        val volumeRising = previous != null && added15 > 0 && data.totalVol >= previous.totalVolume
        val previousStage = stage

        stage = when {
            previousStage == SignalStage.REVERSAL_CONFIRMED &&
                data.price < recentLow && drawdown >= 1.0 && volumeRising -> SignalStage.EXIT_SIGNAL
            previousStage in setOf(SignalStage.ACCELERATING, SignalStage.EXHAUSTION_RISK) &&
                drawdown >= 0.8 && speed15 <= -speedThreshold && volumeRising -> SignalStage.REVERSAL_CONFIRMED
            previousStage in setOf(SignalStage.BREAKOUT_CONFIRMED, SignalStage.ACCELERATING) &&
                drawdown >= 0.45 && speed15 < 0.0 -> SignalStage.EXHAUSTION_RISK
            previousStage in setOf(SignalStage.BREAKOUT_ATTEMPT, SignalStage.BREAKOUT_CONFIRMED) &&
                speed15 >= speedThreshold && speed60 >= 0.0 && volumeRising -> SignalStage.ACCELERATING
            breakoutHigh > 0.0 && data.price > breakoutHigh * 1.002 && speed15 > 0.0 -> SignalStage.BREAKOUT_CONFIRMED
            breakoutHigh > 0.0 && data.price >= breakoutHigh * 0.999 && speed15 > 0.0 -> SignalStage.BREAKOUT_ATTEMPT
            previousStage in setOf(SignalStage.EXHAUSTION_RISK, SignalStage.REVERSAL_CONFIRMED) &&
                data.price >= recentHigh -> SignalStage.BREAKOUT_ATTEMPT
            else -> previousStage
        }

        val event = if (stage != previousStage) when (stage) {
            SignalStage.BREAKOUT_ATTEMPT -> "突破尝试"
            SignalStage.BREAKOUT_CONFIRMED -> "突破确认"
            SignalStage.ACCELERATING -> "价格加速"
            SignalStage.EXHAUSTION_RISK -> "高位衰竭风险"
            SignalStage.REVERSAL_CONFIRMED -> "高点反转确认"
            SignalStage.EXIT_SIGNAL -> "清仓信号"
            SignalStage.QUIET -> null
        } else null

        return ListeningMetrics(
            nextPhase, ((now - (previous?.time ?: now)) / 1000L).toInt().coerceAtLeast(1),
            speed15, speed60, added15, recentHigh, recentLow, stage, event,
            nearestAnchor(data, nearDayHigh)
        )
    }

    fun recordLargeOrder(now: Long, hands: Int, direction: String, threshold: Int): LargeOrderBurst? {
        if (hands < threshold || now <= 0L) return null
        if (lastBurstAt == 0L || now - lastBurstAt > 8_000L || direction != burstDirection) {
            burstCount = 0
            burstHands = 0
            burstStartedAt = now
            burstDirection = direction
        }
        burstCount++
        burstHands += hands
        lastBurstAt = now
        return LargeOrderBurst(burstCount, burstHands, ((now - burstStartedAt) / 1000L).toInt(), direction)
    }

    private fun speed(now: Long, price: Double, window: Long): Double {
        val base = continuousWindowBase(now, window) ?: return 0.0
        return if (base.price > 0.0) (price - base.price) / base.price * 100.0 else 0.0
    }

    private fun volumeDelta(now: Long, total: Int, window: Long): Int {
        val base = continuousWindowBase(now, window) ?: return 0
        return (total - base.totalVolume).coerceAtLeast(0)
    }

    private fun continuousWindowBase(now: Long, window: Long): Point? {
        val slice = points.filter { now - it.time <= window }
        if (slice.size < 2 || now - slice.first().time < window * 4 / 5) return null
        if (slice.zipWithNext().any { (a, b) -> b.time - a.time > 7_000L }) return null
        return slice.first()
    }

    private fun nearestAnchor(data: StockData, nearDayHigh: Boolean): String? {
        val candidates = listOf(
            "昨收" to data.previousClose,
            "今开" to data.openPrice,
            "日内高点" to data.dayHigh,
            "日内低点" to data.dayLow,
            "成交均价" to data.vwap,
            "昨高" to historical.previousHigh,
            "近五日高点" to historical.fiveDayHigh
        ).filter { it.second > 0.0 }
        val nearest = candidates.minByOrNull { kotlin.math.abs(data.price - it.second) / it.second } ?: return null
        val distance = kotlin.math.abs(data.price - nearest.second) / nearest.second * 100.0
        return when {
            nearDayHigh -> "接近日内高点"
            distance <= 0.20 -> "接近${nearest.first}"
            else -> null
        }
    }

    private fun resetBurst() {
        burstCount = 0
        burstHands = 0
        burstStartedAt = 0L
        lastBurstAt = 0L
        burstDirection = "成交放大"
    }
}

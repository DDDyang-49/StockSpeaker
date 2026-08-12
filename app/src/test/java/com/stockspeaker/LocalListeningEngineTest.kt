package com.stockspeaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class LocalListeningEngineTest {
    private fun time(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun quote(time: Long, price: Double, volume: Int = 1_000, high: Double = 10.10) = StockData(
        name = "测试股", code = "000001", price = price, totalVol = volume,
        amountWan = price * volume / 100.0, previousClose = 9.80, openPrice = 9.90,
        dayHigh = high, dayLow = 9.70, sourceTimeMillis = time
    )

    @Test
    fun afternoonReopenDoesNotUseMorningAsSpeedBaseline() {
        val engine = LocalListeningEngine()
        engine.onQuote(quote(time("2026-08-12T11:29:00+08:00"), 10.00))
        val result = engine.onQuote(quote(time("2026-08-12T13:00:00+08:00"), 10.50))!!
        assertEquals(TradingPhase.AFTERNOON_REOPEN, result.phase)
        assertEquals(0.0, result.speed15sPct, 0.0001)
        assertEquals(0.0, result.speed60sPct, 0.0001)
    }

    @Test
    fun orderedStateMachineDoesNotJumpDirectlyToExit() {
        val engine = LocalListeningEngine()
        val start = time("2026-08-12T10:00:00+08:00")
        for (second in 0..15 step 3) engine.onQuote(quote(start + second * 1000L, 10.00, 1_000 + second * 10))

        assertEquals(SignalStage.BREAKOUT_CONFIRMED, engine.onQuote(quote(start + 18_000, 10.03, 1_400))!!.stage)
        assertEquals(SignalStage.ACCELERATING, engine.onQuote(quote(start + 21_000, 10.06, 1_650))!!.stage)
        assertEquals(SignalStage.EXHAUSTION_RISK, engine.onQuote(quote(start + 27_000, 9.99, 2_000))!!.stage)
        assertEquals(SignalStage.REVERSAL_CONFIRMED, engine.onQuote(quote(start + 30_000, 9.96, 2_400))!!.stage)
        assertEquals(SignalStage.EXIT_SIGNAL, engine.onQuote(quote(start + 33_000, 9.93, 2_800))!!.stage)
    }

    @Test
    fun staleOrRepeatedFrameIsRejected() {
        val engine = LocalListeningEngine()
        val now = time("2026-08-12T10:00:00+08:00")
        assertTrue(engine.onQuote(quote(now, 10.0)) != null)
        assertNull(engine.onQuote(quote(now, 10.1)))
        assertNull(engine.onQuote(quote(0L, 10.1)))
    }

    @Test
    fun largeOrdersAreMergedWithinOneBurst() {
        val engine = LocalListeningEngine()
        val now = time("2026-08-12T10:00:00+08:00")
        assertEquals(1, engine.recordLargeOrder(now, 600, "向上成交", 500)!!.count)
        val third = engine.recordLargeOrder(now + 6_000, 800, "向上成交", 500)!!
        assertEquals(2, third.count)
        assertEquals(1_400, third.totalHands)
        assertTrue(third.speech.contains("近6秒新增成交量1400手"))
        assertEquals(1, engine.recordLargeOrder(now + 15_000, 700, "向上成交", 500)!!.count)
    }

    @Test
    fun gapCannotBeUsedAsAContinuousSpeedWindow() {
        val engine = LocalListeningEngine()
        val start = time("2026-08-12T10:00:00+08:00")
        engine.onQuote(quote(start, 10.0, 1_000))
        val afterGap = engine.onQuote(quote(start + 15_000, 10.5, 2_000))!!
        assertEquals(0.0, afterGap.speed15sPct, 0.0001)
        assertEquals(0, afterGap.addedVolume15s)
    }
}

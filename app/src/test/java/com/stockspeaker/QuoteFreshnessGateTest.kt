package com.stockspeaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class QuoteFreshnessGateTest {
    private fun time(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()
    private fun quote(source: Long) = StockData(price = 10.0, sourceTimeMillis = source)

    @Test fun rejectsOldFutureRepeatedAndOffSessionFrames() {
        val gate = QuoteFreshnessGate()
        val now = time("2026-08-12T10:00:30+08:00")
        assertFalse(gate.accept(quote(now - 30_001), now))
        assertFalse(gate.accept(quote(now + 2_001), now))
        assertTrue(gate.accept(quote(now), now))
        assertFalse(gate.accept(quote(now), now + 1_000))
        assertFalse(gate.accept(quote(time("2026-08-12T12:00:00+08:00")), time("2026-08-12T12:00:01+08:00")))
    }
}

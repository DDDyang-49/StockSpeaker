package com.stockspeaker

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentContextPoolTest {
    @Test
    fun speaksOnlyWhenResonanceFormsOrDisappears() {
        val pool = SilentContextPool()
        pool.observeSentiment(GlobalSentiment(upCount = 2_000, downCount = 2_000, limitUpCount = 20, fetchTime = 1))
        pool.observeSentiment(GlobalSentiment(upCount = 2_300, downCount = 1_800, limitUpCount = 25, fetchTime = 2))
        val formed = pool.onFrame(stockPct = 1.2, sectorPct = 0.8, indexPct = 0.3)
        assertTrue(formed!!.contains("共振形成"))
        assertNull(pool.onFrame(stockPct = 1.3, sectorPct = 0.9, indexPct = 0.4))
        assertTrue(pool.onFrame(stockPct = -0.1, sectorPct = 0.0, indexPct = 0.0)!!.contains("共振已经减弱"))
    }
}

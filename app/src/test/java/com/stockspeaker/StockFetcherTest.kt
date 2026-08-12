package com.stockspeaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class StockFetcherTest {
    @Test
    fun tencentFramePreservesSourceTimeAnchorsAndRawOrderBook() {
        val fields = MutableList(50) { "0" }
        fields[1] = "测试股"; fields[2] = "000001"; fields[3] = "10.20"
        fields[4] = "10.00"; fields[5] = "9.90"; fields[6] = "12345"
        fields[9] = "10.19"; fields[10] = "300" // 小于用户阈值也必须保留到消费端
        fields[19] = "10.21"; fields[20] = "800"
        fields[30] = "20260812100000"; fields[32] = "2.00"
        fields[33] = "10.30"; fields[34] = "9.80"; fields[37] = "1234.5"
        fields[38] = "3.2"; fields[45] = "80"; fields[47] = "11.00"; fields[48] = "9.00"; fields[49] = "1.5"

        val data = StockFetcher.parse("v_sz000001=\"${fields.joinToString("~")}\";")!!
        assertEquals(10.00, data.previousClose, 0.0001)
        assertEquals(9.90, data.openPrice, 0.0001)
        assertEquals(10.30, data.dayHigh, 0.0001)
        assertEquals(9.80, data.dayLow, 0.0001)
        assertEquals(300, data.bids.first().second)
        assertEquals(10.0, data.vwap, 0.0001)
        assertEquals(OffsetDateTime.parse("2026-08-12T10:00:00+08:00").toInstant().toEpochMilli(), data.sourceTimeMillis)
        assertTrue(data.sourceTimeMillis > 0L)
    }
}

package com.stockspeaker

import org.junit.Assert.*
import org.junit.Test

class OrderBookAlertTrackerTest {
    @Test fun announcesNewOrLargerLevelWithoutRepeatingStaticBook() {
        val tracker = OrderBookAlertTracker()
        fun quote(hands: Int) = StockData(price = 10.0, bids = listOf(10.0 to 20, 9.99 to 30, 9.98 to 40, 9.97 to 50, 9.96 to hands))
        assertEquals("盘口异动，买五1万手大挂单", tracker.evaluate(quote(10_000), 800, 20_000))
        assertNull(tracker.evaluate(quote(10_000), 800, 22_000))
        assertEquals("盘口异动，买五1.6万手大挂单", tracker.evaluate(quote(16_000), 800, 33_000))
    }
}

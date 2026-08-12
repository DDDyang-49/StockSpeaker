package com.stockspeaker

import org.junit.Assert.*
import org.junit.Test

class AlertFollowUpTrackerTest {
    @Test fun onlyFollowsMeaningfulChangeAndSettlesOnce() {
        val tracker = AlertFollowUpTracker()
        tracker.start(10.00, 1_000)
        assertNull(tracker.continuing(10.01, 10_000, false))
        assertEquals("异动延续，较触发价高2分", tracker.continuing(10.02, 10_000, true))
        assertEquals("异动平复，较触发价低1分", tracker.settle(9.99))
        assertNull(tracker.settle(9.98))
    }
}

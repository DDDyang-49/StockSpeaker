package com.stockspeaker

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningSpeechFormatterTest {
    @Test fun usesLastActuallySpokenPriceAsAuditoryAnchor() {
        assertEquals("测试股，现价24.08，较上次高6毛3", ListeningSpeechFormatter.prefix("测试股", 24.08, 23.45))
        assertEquals("测试股，现价24.08，较上次低2毛", ListeningSpeechFormatter.prefix("测试股", 24.08, 24.28))
        assertEquals("测试股，现价24.08", ListeningSpeechFormatter.prefix("测试股", 24.08, 0.0))
    }

    @Test fun namesPollingVolumeHonestly() {
        assertEquals("近3秒新增成交量800手", ListeningSpeechFormatter.addedVolume(3, 800))
    }
}

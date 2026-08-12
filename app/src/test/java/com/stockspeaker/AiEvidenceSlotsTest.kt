package com.stockspeaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiEvidenceSlotsTest {
    @Test fun acceptsJsonInsideCodeFence() {
        val slots = listOf(AiEvidenceSlot("a", "p", "事实", "播报"))
        assertEquals("播报", AiEvidenceSlots.validateAndFormat("```json\n{\"usedEvidenceIds\":[\"a\"],\"phraseIds\":[\"p\"]}\n```", slots))
    }
    private val slots = listOf(
        AiEvidenceSlot("speed_up", "price_accelerating", "十五秒上行0.6%", "价格正在加速"),
        AiEvidenceSlot("flow_out", "fund_out", "资金净流出1200万", "资金增量偏弱")
    )

    @Test fun validIdsBecomeLocalSpeech() {
        val raw = """{"usedEvidenceIds":["speed_up","flow_out"],"phraseIds":["price_accelerating","fund_out"]}"""
        assertEquals("价格正在加速，资金增量偏弱", AiEvidenceSlots.validateAndFormat(raw, slots))
    }

    @Test fun freeTextOrMismatchedIdsAreRejected() {
        assertNull(AiEvidenceSlots.validateAndFormat("建议立即卖出", slots))
        assertNull(AiEvidenceSlots.validateAndFormat(
            """{"usedEvidenceIds":["speed_up"],"phraseIds":["fund_out"]}""", slots))
        assertNull(AiEvidenceSlots.validateAndFormat(
            """{"usedEvidenceIds":["made_up"],"phraseIds":["price_accelerating"]}""", slots))
    }
}

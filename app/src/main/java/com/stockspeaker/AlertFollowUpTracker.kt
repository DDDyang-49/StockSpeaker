package com.stockspeaker

import kotlin.math.abs
import kotlin.math.roundToInt

/** 异动首次播报后的短跟进：有新信息才播，平复只总结一次。 */
class AlertFollowUpTracker {
    private var triggerPrice = 0.0
    private var lastFollowPrice = 0.0
    private var lastFollowAt = 0L
    private var active = false
    val isActive: Boolean get() = active

    fun start(price: Double, now: Long) {
        if (!active) triggerPrice = price
        active = true
        lastFollowPrice = price
        lastFollowAt = now
    }

    fun continuing(price: Double, now: Long, stageChanged: Boolean): String? {
        if (!active || triggerPrice <= 0.0 || price <= 0.0 || now - lastFollowAt < 8_000L) return null
        val movedPct = abs(price - lastFollowPrice) / lastFollowPrice * 100.0
        if (!stageChanged && movedPct < 0.15) return null
        lastFollowPrice = price
        lastFollowAt = now
        return "异动延续，${relativeToTrigger(price)}"
    }

    fun settle(price: Double): String? {
        if (!active || price <= 0.0) return null
        active = false
        return "异动平复，${relativeToTrigger(price)}"
    }

    fun reset() {
        triggerPrice = 0.0
        lastFollowPrice = 0.0
        lastFollowAt = 0L
        active = false
    }

    private fun relativeToTrigger(price: Double): String {
        val cents = ((price - triggerPrice) * 100).roundToInt()
        return when {
            cents > 0 -> "较触发价高${cents}分"
            cents < 0 -> "较触发价低${-cents}分"
            else -> "仍在触发价附近"
        }
    }
}

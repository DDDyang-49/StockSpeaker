package com.stockspeaker

class QuoteFreshnessGate(
    private val maxAgeMs: Long = 30_000L,
    private val maxFutureMs: Long = 2_000L
) {
    private var lastSourceTime = 0L

    fun accept(data: StockData?, receivedAt: Long): Boolean {
        val source = data?.sourceTimeMillis ?: return false
        if (data.price <= 0.0 || source <= lastSourceTime) return false
        if (source > receivedAt + maxFutureMs || receivedAt - source > maxAgeMs) return false
        if (TradingPhase.at(source) == TradingPhase.CLOSED) return false
        lastSourceTime = source
        return true
    }

    fun reset() { lastSourceTime = 0L }
}

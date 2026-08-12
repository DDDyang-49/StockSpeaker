package com.stockspeaker

import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

object ListeningSpeechFormatter {
    fun prefix(name: String, price: Double, lastSpokenPrice: Double): String {
        val base = "$name，现价${"%.2f".format(Locale.US, price)}"
        if (lastSpokenPrice <= 0.0) return base
        val cents = round(abs(price - lastSpokenPrice) * 100.0).toInt()
        if (cents == 0) return "$base，较上次持平"
        val direction = if (price > lastSpokenPrice) "高" else "低"
        val amount = buildString {
            if (cents / 100 > 0) append("${cents / 100}元")
            if (cents % 100 / 10 > 0) append("${cents % 100 / 10}毛")
            if (cents % 10 > 0) append(cents % 10)
        }
        return "$base，较上次$direction$amount"
    }

    fun addedVolume(seconds: Int, hands: Int): String =
        "近${seconds.coerceAtLeast(1)}秒新增成交量${hands}手"
}

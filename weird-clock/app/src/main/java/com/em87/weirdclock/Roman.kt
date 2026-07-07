package com.em87.weirdclock

object Roman {

    private val NUMERALS = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
        100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
        10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
    )

    fun of(value: Int): String {
        var remainder = value
        val sb = StringBuilder()
        for ((weight, symbol) in NUMERALS) {
            while (remainder >= weight) {
                sb.append(symbol)
                remainder -= weight
            }
        }
        return sb.toString()
    }
}

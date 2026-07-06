package com.em87.weirdclock

/** Color scheme for the dial and hands. */
data class ClockTheme(
    val face: Int,
    val rim: Int,
    val tick: Int,
    val minorTick: Int,
    val numeral: Int,
    val hourHand: Int,
    val minuteHand: Int,
    val secondHand: Int,
    val decimal: Int,
    val centerDot: Int
)

object ClockThemes {

    val MIDNIGHT = ClockTheme(
        face = 0xFF1B1E28.toInt(),
        rim = 0xFF4A5163.toInt(),
        tick = 0xFFE8E8E8.toInt(),
        minorTick = 0xFF6B7284.toInt(),
        numeral = 0xFFE8E8E8.toInt(),
        hourHand = 0xFFF5F5F5.toInt(),
        minuteHand = 0xFFD8DCE8.toInt(),
        secondHand = 0xFFFF5252.toInt(),
        decimal = 0xFF00E5FF.toInt(),
        centerDot = 0xFFF5F5F5.toInt()
    )

    val IVORY = ClockTheme(
        face = 0xFFF5EFE0.toInt(),
        rim = 0xFF8A7B5C.toInt(),
        tick = 0xFF3A3226.toInt(),
        minorTick = 0xFFB0A488.toInt(),
        numeral = 0xFF3A3226.toInt(),
        hourHand = 0xFF2A2318.toInt(),
        minuteHand = 0xFF4A4030.toInt(),
        secondHand = 0xFFB03A2E.toInt(),
        decimal = 0xFF1F6FB2.toInt(),
        centerDot = 0xFF2A2318.toInt()
    )

    val NEON = ClockTheme(
        face = 0xFF0D0221.toInt(),
        rim = 0xFFFF00E5.toInt(),
        tick = 0xFF00FFC8.toInt(),
        minorTick = 0xFF4D2B7A.toInt(),
        numeral = 0xFF00FFC8.toInt(),
        hourHand = 0xFFFF00E5.toInt(),
        minuteHand = 0xFF00FFC8.toInt(),
        secondHand = 0xFFFFEB3B.toInt(),
        decimal = 0xFF00B0FF.toInt(),
        centerDot = 0xFFFFFFFF.toInt()
    )

    val TERMINAL = ClockTheme(
        face = 0xFF041204.toInt(),
        rim = 0xFF1F7A1F.toInt(),
        tick = 0xFF33FF33.toInt(),
        minorTick = 0xFF146114.toInt(),
        numeral = 0xFF33FF33.toInt(),
        hourHand = 0xFF33FF33.toInt(),
        minuteHand = 0xFF66FF66.toInt(),
        secondHand = 0xFFCCFF00.toInt(),
        decimal = 0xFF00FFAA.toInt(),
        centerDot = 0xFF33FF33.toInt()
    )

    val SUNSET = ClockTheme(
        face = 0xFF2B1B3D.toInt(),
        rim = 0xFFFF8C42.toInt(),
        tick = 0xFFFFD8A8.toInt(),
        minorTick = 0xFF6E4A7E.toInt(),
        numeral = 0xFFFFD8A8.toInt(),
        hourHand = 0xFFFFEAD0.toInt(),
        minuteHand = 0xFFFFC58F.toInt(),
        secondHand = 0xFFFF5E5B.toInt(),
        decimal = 0xFF7FDBDA.toInt(),
        centerDot = 0xFFFFEAD0.toInt()
    )

    fun byKey(key: String?): ClockTheme = when (key) {
        "ivory" -> IVORY
        "neon" -> NEON
        "terminal" -> TERMINAL
        "sunset" -> SUNSET
        else -> MIDNIGHT
    }
}

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
    val centerDot: Int,
    /**
     * Three mark colours, because there are two ways to read a mark and
     * they must not be confused for one another.
     *
     * Reading by the dial's two turns, a mark is [amMark] green on the
     * first and [pmMark] blue on the second. Reading by the sun, it is
     * [sunMark] yellow while the sun is up and [pmMark] blue once it is
     * down. Blue is deliberately shared: in both readings it means the
     * far side, the dark one. Green versus yellow is what tells you which
     * question the dial is answering — and it should be legible at a
     * glance, without opening the settings to remember.
     *
     * Defaulted so every theme gets a sane set, and overridden by the ones
     * with a pale face, where the light versions simply vanish.
     */
    val amMark: Int = 0xFF43C463.toInt(),
    val pmMark: Int = 0xFF5C8DF6.toInt(),
    val sunMark: Int = 0xFFFFC93C.toInt()
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

    /**
     * Midnight's daylight twin: a white face with dark hands and a deep
     * teal accent, so nothing washes out against a bright background. The
     * default theme swaps to this one whenever the system is in light mode.
     */
    val DAYLIGHT = ClockTheme(
        face = 0xFFFFFFFF.toInt(),
        rim = 0xFF9AA2B4.toInt(),
        tick = 0xFF1B1E28.toInt(),
        minorTick = 0xFF8A93A8.toInt(),
        numeral = 0xFF1B1E28.toInt(),
        hourHand = 0xFF10121A.toInt(),
        minuteHand = 0xFF2E3444.toInt(),
        secondHand = 0xFFD32F2F.toInt(),
        decimal = 0xFF00796B.toInt(),
        centerDot = 0xFF10121A.toInt(),
        amMark = 0xFF1E8E4A.toInt(),
        pmMark = 0xFF2E5FD0.toInt(),
        sunMark = 0xFFD09000.toInt()
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
        centerDot = 0xFF2A2318.toInt(),
        amMark = 0xFF1B7D42.toInt(),
        pmMark = 0xFF2A5EA8.toInt(),
        sunMark = 0xFFC08400.toInt()
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

    /**
     * Night mode: the same theme with every color at 30% brightness, so the
     * dial glows softly instead of lighting the bedroom.
     */
    fun dim(t: ClockTheme): ClockTheme {
        fun d(c: Int): Int {
            val a = c ushr 24
            val r = ((c shr 16 and 0xFF) * 0.30).toInt()
            val g = ((c shr 8 and 0xFF) * 0.30).toInt()
            val b = ((c and 0xFF) * 0.30).toInt()
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return ClockTheme(
            face = d(t.face), rim = d(t.rim), tick = d(t.tick),
            minorTick = d(t.minorTick), numeral = d(t.numeral),
            hourHand = d(t.hourHand), minuteHand = d(t.minuteHand),
            secondHand = d(t.secondHand), decimal = d(t.decimal),
            amMark = d(t.amMark), pmMark = d(t.pmMark), sunMark = d(t.sunMark),
            centerDot = d(t.centerDot)
        )
    }

    /**
     * Ink that shows against a face of this colour: white on a dark dial,
     * near-black on a pale one.
     *
     * Used for the ring round a calendar mark, which has to say "today only"
     * without saying it in a colour — the mark's own colour is already
     * carrying the morning-or-evening answer, and a second colour on top
     * would be a second thing to learn. Lives here because two dials draw
     * those marks, the app's and the widget's, and they have to agree.
     */
    fun contrastInk(face: Int): Int {
        val luma = 0.299 * (face shr 16 and 0xFF) +
            0.587 * (face shr 8 and 0xFF) +
            0.114 * (face and 0xFF)
        return if (luma > 140) 0xFF101010.toInt() else 0xFFFFFFFF.toInt()
    }

    fun byKey(key: String?): ClockTheme = when (key) {
        "ivory" -> IVORY
        "neon" -> NEON
        "terminal" -> TERMINAL
        "sunset" -> SUNSET
        "daylight" -> DAYLIGHT
        else -> MIDNIGHT
    }

    private fun systemInDarkMode(context: android.content.Context): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * Like [byKey] but resolving the "dynamic" key into Material You system
     * colors on Android 12+, so the clock dresses in the user's wallpaper
     * palette. Falls back to Midnight below API 31.
     */
    fun resolve(context: android.content.Context, key: String?): ClockTheme = when {
        key == "dynamic" ->
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                dynamic(context)
            } else {
                // No Material You here; follow the system's own light/dark.
                if (systemInDarkMode(context)) MIDNIGHT else DAYLIGHT
            }
        // The default theme follows the system: Midnight at night, its
        // white twin by day. Explicit picks are always honored.
        key == null || key == "midnight" ->
            if (systemInDarkMode(context)) MIDNIGHT else DAYLIGHT
        else -> byKey(key)
    }

    @androidx.annotation.RequiresApi(31)
    private fun dynamic(context: android.content.Context): ClockTheme = ClockTheme(
        face = context.getColor(android.R.color.system_neutral1_900),
        rim = context.getColor(android.R.color.system_accent2_400),
        tick = context.getColor(android.R.color.system_neutral1_50),
        minorTick = context.getColor(android.R.color.system_neutral2_500),
        numeral = context.getColor(android.R.color.system_neutral1_50),
        hourHand = context.getColor(android.R.color.system_accent1_100),
        minuteHand = context.getColor(android.R.color.system_accent1_200),
        secondHand = context.getColor(android.R.color.system_accent3_300),
        decimal = context.getColor(android.R.color.system_accent1_300),
        centerDot = context.getColor(android.R.color.system_accent1_100)
    )
}

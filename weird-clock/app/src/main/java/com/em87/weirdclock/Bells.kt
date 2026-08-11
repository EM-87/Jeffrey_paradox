package com.em87.weirdclock

/**
 * What the bells strike, and when.
 *
 * This rule was written out three times: once in the app for while it is
 * open, once in the service that rings with the app closed, and once more
 * in the settings screen so the preview matches. Three copies of one rule
 * is three chances for them to disagree, and the only way to notice would
 * be to sit through an hour with the app open and another with it closed.
 *
 * So it lives here, with no Android in it, and each of the three asks
 * rather than remembers.
 */
object Bells {

    /** What the peal is played on. */
    enum class Voice {
        /** A struck thing that rings on afterwards. */
        BELL,

        /** A piezo disc: square, flat, and over. */
        BEEP,

        /** The quick bright pair a tower clock marks its quarters with. */
        QUARTER_CHIME
    }

    /**
     * One peal: how many strikes, of what, spaced how far apart.
     *
     * [count] means strikes for a bell and beeps for a beep, but *rounds*
     * for the quarter chime, because a chime's unit is the little phrase
     * rather than the note — one round at a quarter past, two at half past,
     * three at a quarter to, which is how a tower clock tells you which
     * quarter it is rather than merely that one has gone by.
     */
    data class Peal(
        val count: Int,
        val frequency: Double,
        val ringSeconds: Double,
        val interval: Double = 1.1,
        val pairGrouping: Boolean = false,
        val voice: Voice = Voice.BELL
    )

    /** The pitch of a cheap digital watch, near the top of the piano. */
    const val CASIO_HZ = 4186.0

    /**
     * How much of the hour gets marked.
     *
     * This was a single switch for the half-hour ding, which could not
     * grow: quarters are not "half past, but more often", they are a
     * different thing to say, and a switch has no room to say which.
     */
    const val MARKS_HOUR = "hour"
    const val MARKS_HALF = "half"
    const val MARKS_QUARTERS = "quarters"

    /**
     * Which setting is in force, given the new one and the old switch.
     *
     * Phones updating from a build with the switch keep what they had: the
     * bells are the one part of this clock people arrange their day
     * around, and quietly resetting them to "on the hour only" would be
     * taking a working alarm away from somebody.
     */
    fun marksFrom(stored: String?, legacyHalfHour: Boolean): String = when (stored) {
        MARKS_HOUR, MARKS_HALF, MARKS_QUARTERS -> stored
        else -> if (legacyHalfHour) MARKS_HALF else MARKS_HOUR
    }

    /** The minutes past the hour that [marks] asks to be told about. */
    fun marked(marks: String): Set<Int> = when (marks) {
        MARKS_QUARTERS -> setOf(0, 15, 30, 45)
        MARKS_HALF -> setOf(0, 30)
        else -> setOf(0)
    }

    /**
     * What [style] strikes at [hourOfDay]:[minute], or null for silence.
     *
     * The minute is passed in whole rather than boiled down to "is it the
     * hour" on the way, which it used to be: with only the hour and the
     * half to choose between, a boolean covered it, and it stopped covering
     * it the moment there were quarters.
     */
    fun peal(style: String?, hourOfDay: Int, minute: Int, marks: String): Peal? {
        val hour = ((hourOfDay % 24) + 24) % 24
        val onTheHour = minute == 0

        // A ship's bell keeps its own calendar. Half past is half the point
        // of it — a nautical watch counted only on the hour would be
        // telling the wrong time — and quarters mean nothing at sea.
        if (style == Prefs.BELL_STYLE_SHIPS) {
            if (minute != 0 && minute != 30) return null
            val struck = (hour % 4) * 2 + if (onTheHour) 0 else 1
            return Peal(
                count = if (struck == 0) 8 else struck,
                frequency = ChimePlayer.SHIPS_HZ,
                ringSeconds = 2.0,
                pairGrouping = true
            )
        }

        if (minute !in marked(marks)) return null
        if (onTheHour) return hourPeal(style, hour)

        // A quarter is a quarter whichever way the hour is counted, so the
        // half-hour ding only survives where quarters were not asked for.
        if (marks == MARKS_QUARTERS) return quarterPeal(style, minute)
        return halfPeal(style)
    }

    private fun hourPeal(style: String?, hour: Int): Peal = when (style) {
        Prefs.BELL_STYLE_SINGLE -> Peal(1, ChimePlayer.GONG_HZ, 4.5)
        Prefs.BELL_STYLE_BEEP -> Peal(
            count = countOn(hour),
            frequency = CASIO_HZ,
            ringSeconds = 0.055,
            interval = 0.20,
            voice = Voice.BEEP
        )
        else -> Peal(
            count = countOn(hour),
            frequency = ChimePlayer.GRANDFATHER_HZ,
            ringSeconds = 3.0,
            interval = 1.3
        )
    }

    private fun halfPeal(style: String?): Peal = when (style) {
        Prefs.BELL_STYLE_BEEP -> Peal(
            // The signal every cheap watch makes: bip bip, twice and done.
            count = 2,
            frequency = CASIO_HZ,
            ringSeconds = 0.055,
            interval = 0.12,
            voice = Voice.BEEP
        )
        else -> Peal(1, ChimePlayer.HALF_HOUR_BELL_HZ, 1.5)
    }

    /**
     * One round for the first quarter, two for the second, three for the
     * third — so a quarter tells you where in the hour you are and not
     * only that a quarter has gone.
     */
    private fun quarterPeal(style: String?, minute: Int): Peal {
        val rounds = minute / 15
        return when (style) {
            Prefs.BELL_STYLE_BEEP -> Peal(
                count = rounds,
                frequency = CASIO_HZ,
                ringSeconds = 0.055,
                interval = 0.20,
                voice = Voice.BEEP
            )
            else -> Peal(
                count = rounds,
                frequency = 1046.5,
                ringSeconds = 0.55,
                interval = 0.62,
                voice = Voice.QUARTER_CHIME
            )
        }
    }

    /**
     * Twelve at noon and at midnight, not none: a clock that says nothing
     * at twelve has stopped as far as anybody listening can tell, and the
     * whole worth of the bells is not having to look.
     */
    private fun countOn(hour: Int): Int = (hour % 12).let { if (it == 0) 12 else it }

    /**
     * Whether the house is to be kept quiet at [hour].
     *
     * The bells that ring with the app closed had their own ten-at-night
     * until seven written into them, from before the hours were something
     * anybody could set. So moving the night in the settings moved it for
     * the app and left the background bells ringing on the old schedule —
     * which is the half of the feature that wakes people up.
     */
    fun quiet(nightDim: Boolean, hour: Int, from: Int, to: Int): Boolean =
        nightDim && NightWindow.isNight(hour, from, to)

    /**
     * A few seconds of [style] for the button in the settings that plays
     * one. Three strikes rather than whatever the hour happens to be —
     * the point is the timbre, not the time.
     */
    fun sample(style: String?): Peal = when (style) {
        Prefs.BELL_STYLE_SHIPS -> Peal(
            4, ChimePlayer.SHIPS_HZ, 2.0, pairGrouping = true
        )
        Prefs.BELL_STYLE_SINGLE -> Peal(1, ChimePlayer.GONG_HZ, 4.5)
        // Two, not three. Every other style previews with three strikes,
        // but two is the signal a cheap watch actually makes, and hearing
        // three of them is hearing the wrong thing.
        Prefs.BELL_STYLE_BEEP -> Peal(
            2, CASIO_HZ, 0.055, interval = 0.12, voice = Voice.BEEP
        )
        else -> Peal(3, ChimePlayer.GRANDFATHER_HZ, 3.0, interval = 1.3)
    }
}

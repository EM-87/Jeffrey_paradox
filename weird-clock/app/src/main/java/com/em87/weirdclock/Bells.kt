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

    /**
     * One peal: how many strikes, of what, spaced how far apart.
     *
     * [beeps] picks the instrument. A bell is a bell — a struck thing that
     * rings on afterwards — and a beep is a piezo disc with no body at all,
     * so the two are different sounds rather than the same sound with
     * different numbers, and everything else here means slightly different
     * things depending on which it is: [ringSeconds] is how long a bell
     * hums for, or how long a beep simply lasts.
     */
    data class Peal(
        val count: Int,
        val frequency: Double,
        val ringSeconds: Double,
        val interval: Double = 1.1,
        val pairGrouping: Boolean = false,
        val beeps: Boolean = false
    )

    /** The pitch of a cheap digital watch, near the top of the piano. */
    const val CASIO_HZ = 4186.0

    /**
     * What [style] strikes at [hourOfDay], on the hour or at half past.
     *
     * Null when the answer is silence. [halfHours] is the setting for
     * marking half past at all, which the ship's bell overrules: half past
     * is half the point of a ship's bell, and a nautical watch counted only
     * on the hour would be telling the wrong time.
     */
    fun peal(
        style: String?,
        hourOfDay: Int,
        onTheHour: Boolean,
        halfHours: Boolean
    ): Peal? {
        val hour = ((hourOfDay % 24) + 24) % 24
        if (style == Prefs.BELL_STYLE_SHIPS) {
            // One bell per half hour of the current four-hour watch, struck
            // in pairs; the watch change gets eight.
            val struck = (hour % 4) * 2 + if (onTheHour) 0 else 1
            return Peal(
                count = if (struck == 0) 8 else struck,
                frequency = ChimePlayer.SHIPS_HZ,
                ringSeconds = 2.0,
                pairGrouping = true
            )
        }
        if (!onTheHour && !halfHours) return null
        return when (style) {
            Prefs.BELL_STYLE_SINGLE ->
                if (onTheHour) Peal(1, ChimePlayer.GONG_HZ, 4.5)
                else Peal(1, ChimePlayer.HALF_HOUR_BELL_HZ, 1.5)

            Prefs.BELL_STYLE_BEEP ->
                if (onTheHour) Peal(
                    count = countOn(hour),
                    frequency = CASIO_HZ,
                    ringSeconds = 0.055,
                    interval = 0.20,
                    beeps = true
                ) else Peal(
                    // The signal every cheap watch makes: bip bip, twice and
                    // done, at no particular hour.
                    count = 2,
                    frequency = CASIO_HZ,
                    ringSeconds = 0.055,
                    interval = 0.12,
                    beeps = true
                )

            else ->
                if (onTheHour) Peal(
                    count = countOn(hour),
                    frequency = ChimePlayer.GRANDFATHER_HZ,
                    ringSeconds = 3.0,
                    interval = 1.3
                ) else Peal(1, ChimePlayer.HALF_HOUR_BELL_HZ, 1.5)
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
        Prefs.BELL_STYLE_BEEP -> Peal(
            3, CASIO_HZ, 0.055, interval = 0.20, beeps = true
        )
        else -> Peal(3, ChimePlayer.GRANDFATHER_HZ, 3.0, interval = 1.3)
    }
}

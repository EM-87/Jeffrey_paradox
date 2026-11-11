package com.em87.weirdclock

import android.content.SharedPreferences

/**
 * What the stored settings mean.
 *
 * A dozen little translations from a stored string to a typed answer,
 * which lived among three thousand lines about running an app because
 * that is where the settings were being read. They are not about running
 * an app: they are the meaning of the preferences, they need nothing but
 * the preferences, and out here they can be asked directly.
 *
 * There is a reason beyond tidiness. Twice this year the same rule turned
 * out to be written in two places — whether the sky puts a door on the
 * dial, and where a zodiac sign begins — and both times the copies were
 * enough to hide a sabotage, because breaking one left the other
 * answering correctly. A meaning with one home cannot drift from itself.
 */
object DialSettings {

    fun fastHand(prefs: SharedPreferences): ClockView.FastHandMode =
        when (prefs.getString(Prefs.FAST_HAND, Prefs.FAST_HAND_NONE)) {
            Prefs.FAST_HAND_TENTHS -> ClockView.FastHandMode.TENTHS
            Prefs.FAST_HAND_DECIMAL_MINUTE -> ClockView.FastHandMode.DECIMAL_MINUTE
            else -> ClockView.FastHandMode.NONE
        }

    fun dialShape(prefs: SharedPreferences): ClockView.DialShape =
        when (prefs.getString(Prefs.DIAL_SHAPE, Prefs.SHAPE_CIRCLE)) {
            Prefs.SHAPE_TRIANGLE -> ClockView.DialShape.TRIANGLE
            Prefs.SHAPE_SQUARE -> ClockView.DialShape.SQUARE
            Prefs.SHAPE_HEXAGON -> ClockView.DialShape.HEXAGON
            Prefs.SHAPE_OCTAGON -> ClockView.DialShape.OCTAGON
            else -> ClockView.DialShape.CIRCLE
        }

    /**
     * Whether there is a glyph on the dial to press.
     *
     * Asked in two places — when the settings are applied, and again when
     * the dial goes back to being a clock after winding a time — and so
     * written once. It was two copies of one rule, and the copies were
     * enough to hide a sabotage: taking the solar system out of one of
     * them left the other putting the door there anyway, and every test of
     * the door went on passing against a switch that had stopped working
     * on the path a person actually takes.
     *
     * The weather needs somewhere to be drawn and a door to its card, so
     * its own switch puts the token there. The moon complication puts
     * one there on its own account, for somebody who wants the moon and
     * no weather round it. It was the solar system's switch that did the
     * first of those, while the solar system was what the token opened.
     */
    fun skyTokenWanted(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(Prefs.WEATHER, false) || prefs.getBoolean(Prefs.MOON_PHASE, false)

    /**
     * And whether that glyph tracks the phase, or is a plain disc.
     *
     * The complication's own switch decides. A token that is only there
     * for the weather is a sun by day and a plain moon by night — the
     * thing the weather is drawn in front of, and no more than that.
     */
    fun moonPhaseWanted(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(Prefs.MOON_PHASE, false)

    fun numerals(prefs: SharedPreferences, key: String = Prefs.NUMERALS): ClockView.NumeralStyle =
        when (prefs.getString(key, Prefs.NUMERALS_ARABIC)) {
            Prefs.NUMERALS_NONE -> ClockView.NumeralStyle.NONE
            Prefs.NUMERALS_ROMAN -> ClockView.NumeralStyle.ROMAN
            else -> ClockView.NumeralStyle.ARABIC
        }

    fun hoursOnDial(prefs: SharedPreferences): Int {
        val preset = prefs.getString(Prefs.HOURS_PRESET, "12") ?: "12"
        return if (preset == Prefs.HOURS_CUSTOM_VALUE) {
            prefs.getInt(Prefs.HOURS_CUSTOM, 12)
        } else {
            preset.toIntOrNull() ?: 12
        }
    }
}

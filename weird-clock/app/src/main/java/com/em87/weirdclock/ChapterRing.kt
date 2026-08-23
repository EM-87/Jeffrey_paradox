package com.em87.weirdclock

import android.content.SharedPreferences

/**
 * Which hours a dial marks, and which hours it numbers.
 *
 * One rule, in one place, because there are two dials. The clock on the
 * screen and the clock on the home screen are drawn by different code —
 * the widget renders to a bitmap and ships it across a process boundary,
 * so it cannot simply be the same view — and every rule about what a face
 * looks like therefore exists twice. That is fine for a rule that never
 * changes and quietly fatal for one that does: the marks were made
 * adjustable on the dial and the widget carried on drawing twelve of them,
 * because the widget has its own copy of the loop and nobody told it.
 *
 * So the rule moved out of both of them. The two still draw their own
 * ticks, in their own coordinates, at their own sizes; what they no longer
 * each own is the answer to "which hours".
 */
object ChapterRing {

    /** How many marks the settings ask for, as a number. */
    fun marksFrom(prefs: SharedPreferences): Int =
        when (prefs.getString(Prefs.DIAL_MARKS, Prefs.MARKS_12)) {
            Prefs.MARKS_6 -> 6
            Prefs.MARKS_4 -> 4
            Prefs.MARKS_NONE -> 0
            else -> 12
        }

    /** Whether the sixty small ticks between the hours are drawn. */
    fun minuteMarksFrom(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(Prefs.MINUTE_MARKS, true)

    /**
     * Which hours carry a mark, counting from zero at the top.
     *
     * Kept as "one in how many" rather than as a count, so it works on the
     * dials that do not have twelve hours on them: a twenty-four hour face
     * asked for four marks gets one every six hours, which is the same
     * quarter of the dial the twelve-hour face gets.
     */
    fun markedHours(hoursOnDial: Int, marks: Int): List<Int> {
        if (marks <= 0) return emptyList()
        // One mark every so many hours. The counts the settings offer all
        // divide both twelve and twenty-four, so the division is exact
        // there and the rounding only decides what a face with some other
        // number of hours on it does — where the nearest whole spacing is
        // the only answer that keeps the marks evenly spread.
        val every = maxOf(1, Math.round(hoursOnDial.toFloat() / marks))
        return (0 until hoursOnDial).filter { it % every == 0 }
    }

    /**
     * Which hours carry a numeral, counting from one, with the top hour
     * written as the hour count rather than as zero.
     *
     * The numerals follow the marks. Asking for four marks and getting
     * twelve numerals is a dial that has half heard you — and asking for
     * none and getting all twelve is worse, because then the switch does
     * nothing you can see.
     *
     * With every hour marked, a crowded face still thins its numerals on
     * its own: twenty-four of them round a watch face is a smudge, so a
     * dial with more than twelve hours numbers every other one and adds
     * the top hour back, which would otherwise be missed on an odd count.
     */
    fun numeralHours(hoursOnDial: Int, marks: Int): List<Int> {
        if (marks <= 0) return emptyList()
        if (marks < hoursOnDial) {
            return markedHours(hoursOnDial, marks)
                .map { if (it == 0) hoursOnDial else it }
                .sorted()
        }
        val step = if (hoursOnDial > 12) 2 else 1
        val list = ArrayList<Int>()
        var h = step
        while (h <= hoursOnDial) {
            list.add(h)
            h += step
        }
        if (hoursOnDial % step != 0) list.add(hoursOnDial)
        return list
    }
}

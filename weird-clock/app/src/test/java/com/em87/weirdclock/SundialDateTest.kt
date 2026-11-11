package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The date cut under the plate, in all four calendars.
 *
 * A sundial is the one face in this app where the calendar is a real
 * question rather than a joke. The instrument is older than the Gregorian
 * reform: every dial cut before 1582 was made under the Julian one and
 * Britain went on reading dials by it until 1752. And the Egyptian civil
 * year — twelve months of thirty days in three seasons, five days over,
 * and no leap day ever — is the calendar Ptolemy computed in and the one
 * Copernicus was still computing in fifteen hundred years later, because a
 * year that never changes length is a year you can do arithmetic in.
 *
 * What is checked here is the writing rather than the arithmetic:
 * [EgyptianCalendarTest] holds the conversion and [JulianCalendar] holds
 * its own. This is the layer that decides what a stone says.
 */
class SundialDateTest {

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    private fun offset(ms: Long): Int = TimeZone.getDefault().getOffset(ms)

    private val seasons = listOf("Akhet", "Peret", "Shemu")

    private fun label(
        ms: Long, reckoning: Sundial.Reckoning, roman: Boolean = true
    ): String = Sundial.dateLabel(
        ms, offset(ms), reckoning, roman, seasons, "Upon the year"
    )

    /** Ours, in the numerals the rest of the plate is cut in. */
    @Test
    fun `our own date, in Rome's numerals and in ours`() {
        val ms = at(2026, 6, 21)
        assertEquals("XXI · VI", label(ms, Sundial.Reckoning.GREGORIAN))
        assertEquals("21 / 6", label(ms, Sundial.Reckoning.GREGORIAN, roman = false))
    }

    /**
     * The Julian one, thirteen days behind and the gap still growing.
     *
     * Thirteen since 1900 and until 2100, which covers every phone this
     * will ever run on — but the arithmetic is a real conversion and not a
     * subtraction of thirteen, which is what this pins.
     */
    @Test
    fun `the Julian date is the one the stone was cut in`() {
        val ms = at(2026, 6, 21)
        assertEquals("VIII · VI", label(ms, Sundial.Reckoning.JULIAN))
        assertEquals("8 / 6", label(ms, Sundial.Reckoning.JULIAN, roman = false))
    }

    /**
     * And Egypt's: the month of the season, the season, the day.
     *
     * Written the way Egyptology writes it and the way the stone does —
     * "II Akhet 15" — which is why the month of the season stays in Roman
     * numerals whichever way the numerals switch is set. That is not this
     * app's choice of alphabet; it is the notation, and has been since
     * Champollion.
     */
    @Test
    fun `the Egyptian date names its season`() {
        val ms = at(2026, 6, 21)
        val label = label(ms, Sundial.Reckoning.EGYPTIAN)
        val parts = label.split(" ")
        assertEquals("it is not three parts: $label", 3, parts.size)
        assertTrue(
            "the month of the season is not a Roman numeral: $label",
            parts[0] in listOf("I", "II", "III", "IV")
        )
        assertTrue("the season is not named: $label", parts[1] in seasons)
        // The day follows the numerals switch, and the month never does.
        val plain = label(ms, Sundial.Reckoning.EGYPTIAN, roman = false)
        assertEquals("the month of the season stopped being Roman", parts[0], plain.split(" ")[0])
        assertTrue(
            "the day is not a figure with the numerals off: $plain",
            plain.split(" ")[2].toIntOrNull() != null
        )
    }

    /**
     * It agrees with the calendar it is written from.
     *
     * Written out of [EgyptianCalendar] rather than checked against a
     * string typed here, because a label that reads beautifully and names
     * the wrong season is exactly the failure this cannot see.
     */
    @Test
    fun `the label says what the calendar says`() {
        for (year in listOf(1999, 2026, 2031)) {
            for (month in 1..12) {
                val ms = at(year, month, 15)
                val date = EgyptianCalendar.dateOf(ms, offset(ms), year)
                val label = label(ms, Sundial.Reckoning.EGYPTIAN)
                if (date.epagomenal) {
                    assertTrue(
                        "the days upon the year were numbered into a month: $label",
                        label.startsWith("Upon the year")
                    )
                } else {
                    assertEquals(
                        "wrong season for $year-$month",
                        seasons[date.season!!.ordinal], label.split(" ")[1]
                    )
                    assertEquals(
                        "wrong month of the season for $year-$month",
                        Roman.of(date.monthOfSeason), label.split(" ")[0]
                    )
                }
            }
        }
    }

    /**
     * The five days over at the end are named, not numbered into a month.
     *
     * They belonged to no month at all — the "days upon the year", on
     * which five gods were born — and a calendar that quietly called them
     * the thirty-first of something would be inventing a month Egypt
     * never had.
     */
    @Test
    fun `the days upon the year are not given a month`() {
        // Walk a year and find them rather than looking one up: which
        // days they fall on drifts by one every four years, so a date
        // typed here would be right for about as long as it took to write.
        var found = 0
        var ms = at(2026, 1, 1)
        repeat(370) {
            val date = EgyptianCalendar.dateOf(ms, offset(ms), 2026)
            if (date.epagomenal) {
                found++
                val label = label(ms, Sundial.Reckoning.EGYPTIAN)
                assertTrue("a day upon the year got a month: $label",
                    label.startsWith("Upon the year"))
                assertTrue("and no day number either: $label", label.split(" ").size == 4)
            }
            ms += 24L * 60L * 60L * 1000L
        }
        assertEquals("there were not five days over", 5, found)
    }

    /**
     * The old yes-or-no is still read, and is only read when there is
     * nothing newer.
     *
     * This was a switch called "Julian calendar" for eleven versions.
     * Every phone that turned it on has a boolean written down and no
     * string at all, and losing that would silently move somebody's dial
     * thirteen days.
     */
    @Test
    fun `the switch this replaced still means what it meant`() {
        assertEquals(
            Sundial.Reckoning.JULIAN, Sundial.Reckoning.of(null, was = true)
        )
        assertEquals(
            Sundial.Reckoning.GREGORIAN, Sundial.Reckoning.of(null, was = false)
        )
        // And a real answer wins over the old one, in both directions.
        assertEquals(
            "the old switch overruled a chosen calendar",
            Sundial.Reckoning.EGYPTIAN, Sundial.Reckoning.of("egyptian", was = true)
        )
        assertEquals(
            Sundial.Reckoning.GREGORIAN, Sundial.Reckoning.of("gregorian", was = true)
        )
        // Something unreadable falls back rather than throwing.
        assertEquals(
            Sundial.Reckoning.GREGORIAN, Sundial.Reckoning.of("mayan", was = false)
        )
        // Every value in the list is one the settings row can offer.
        for (reckoning in Sundial.Reckoning.entries) {
            assertEquals(reckoning, Sundial.Reckoning.of(reckoning.key, was = true))
        }
    }

    // ------------------------------------------------------ by the Sun

    /** An instant in universal time, whatever zone the test is run in. */
    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        AstroCalendar.msOf(year, month, day) + ((hour * 60L + minute) * 60L + second) * 1000L

    private fun bySun(ms: Long, roman: Boolean = true): String =
        Sundial.dateLabel(ms, 0, Sundial.Reckoning.ZODIAC, roman)

    /**
     * The date a dial can read for itself: the Sun's place among the
     * signs.
     *
     * Held against JPL Horizons, whose longitudes for these instants are
     * the anchors of [EphemerisTest]: 111.9860° is twenty-one whole
     * degrees into Cancer and so in its twenty-second, 197.3606° is in
     * the eighteenth of Libra, 138.3497° in the nineteenth of Leo. A
     * degree is counted the way a day of a month is — the first, not the
     * nought-th — because the Sun goes about one a day and this is being
     * read as a date.
     */
    @Test
    fun `the Sun's place among the signs is where Horizons has the Sun`() {
        assertEquals("XXII \u00b7 CANCER", bySun(utc(2026, 7, 14, 9, 43)))
        assertEquals("XVIII \u00b7 LIBRA", bySun(utc(2026, 10, 10, 15, 50)))
        assertEquals("XIX \u00b7 LEO", bySun(utc(1999, 8, 11, 11, 3, 5)))
        assertEquals("22 CANCER", bySun(utc(2026, 7, 14, 9, 43), roman = false))
    }

    /**
     * And the year turns at the equinox, to the minute Horizons gives.
     *
     * 14:46 UT on the 20th of March 2026 — the same anchor the one Sun is
     * held to. A quarter of an hour before, the Sun is in the last degree
     * of Pisces; a quarter of an hour after, in the first of Aries. It is
     * also why the longitude is the one of the date and not the one the
     * orrery draws from a line fixed in 2000, which in 2026 is a third of
     * a degree out and by the year of Thales's eclipse thirty-six: his
     * Sun, at 59.7254° of date, is in the last degree of Taurus, and
     * measured from our equinox it would be in Aries.
     */
    @Test
    fun `the signs begin at the equinox of the date`() {
        assertEquals("XXX \u00b7 PISCES", bySun(utc(2026, 3, 20, 14, 31)))
        assertEquals("I \u00b7 ARIES", bySun(utc(2026, 3, 20, 15, 1)))
        assertEquals("XXX \u00b7 TAVRVS", bySun(utc(-584, 5, 28, 14, 22, 26)))
    }

    /** Twelve of them, in Latin, with no letter Rome did not have. */
    @Test
    fun `the signs are cut the way the mottoes are`() {
        val signs = Sundial.signs()
        assertEquals(12, signs.size)
        assertEquals(12, signs.toSet().size)
        for (sign in signs) {
            assertTrue("$sign has a U in it", !sign.contains('U'))
            assertTrue("$sign has a J in it", !sign.contains('J'))
            assertEquals("$sign is not in capitals", sign.uppercase(), sign)
        }
        assertEquals("ARIES", signs.first())
        assertEquals("PISCES", signs.last())
    }
}

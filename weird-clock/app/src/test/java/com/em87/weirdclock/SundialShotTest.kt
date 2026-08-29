package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of the sundial, because the arithmetic being right is only
 * half of it.
 *
 * [SundialTest] can prove that the hour line for three o'clock in Madrid
 * is at 32.96° and cannot say whether the thing on screen looks like a
 * sundial or like a pie chart. Three kinds, three plates, two latitudes
 * and both hemispheres, plus the two states nobody thinks to draw: after
 * sunset, and standing somewhere the dial does not work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SundialShotTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** Half past two on a June afternoon, when the sun is well up. */
    private fun juneAfternoon(): Long =
        java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JUNE, 21, 14, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dial(
        kind: Sundial.Kind = Sundial.Kind.HORIZONTAL,
        plate: Sundial.Plate = Sundial.Plate.ROUND,
        lat: Double = 40.4,
        at: Long = juneAfternoon(),
        compass: Boolean = false,
        bearing: Double? = null,
        theme: ClockTheme = ClockThemes.IVORY,
        motto: Boolean = true,
        showDate: Boolean = false,
        reckoning: Sundial.Reckoning = Sundial.Reckoning.GREGORIAN,
        outside: Weather.Sky? = null,
        w: Int = 1000,
        h: Int = 1000
    ): SundialView = SundialView(context).apply {
        this.theme = theme
        this.kind = kind
        this.plate = plate
        latitude = lat
        longitude = -3.7
        this.compass = compass
        phoneBearing = bearing
        this.motto = motto
        this.showDate = showDate
        this.reckoning = reckoning
        this.outside = outside
        atMs = at
        measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, w, h)
    }

    private fun shoot(view: View, name: String): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val seen = HashSet<Int>()
        var y = 0
        while (y < view.height) {
            var x = 0
            while (x < view.width) {
                seen.add(bitmap.getPixel(x, y)); x += 4
            }
            y += 4
        }
        return seen.size
    }

    /** The three instruments, which are three different objects. */
    @Test
    fun `the three kinds of dial`() {
        for (kind in Sundial.Kind.entries) {
            assertTrue(shoot(dial(kind = kind), "sundial-${kind.key}") > 3)
        }
    }

    /** And the three plates, which are only ever cosmetic. */
    @Test
    fun `round, square and eight-sided`() {
        for (plate in Sundial.Plate.entries) {
            assertTrue(shoot(dial(plate = plate), "sundial-plate-${plate.key}") > 3)
        }
    }

    /**
     * The same dial at four latitudes, which is the whole point of the
     * face.
     *
     * A sundial made for one place reads an hour wrong in another, and
     * these four should look visibly different from each other: the fan
     * opens as you go north and closes to a stick at the equator.
     */
    @Test
    fun `the fan opens with the latitude`() {
        for (lat in listOf(4.0, 28.5, 40.4, 60.2, -33.9)) {
            val name = "sundial-lat-${lat.toString().replace('.', '_').replace('-', 's')}"
            assertTrue(shoot(dial(lat = lat), name) > 3)
        }
    }

    /**
     * And the two states nobody draws until somebody stands in them: no
     * sun, and a dial that cannot work here.
     */
    @Test
    fun `after sunset, and on the equator`() {
        val midnight = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JUNE, 21, 1, 30, 0)
        }.timeInMillis
        assertTrue(shoot(dial(at = midnight), "sundial-night") > 3)
        assertTrue(shoot(dial(lat = 0.5), "sundial-flat") > 3)
    }

    /**
     * The arrow, pointed and not pointed.
     *
     * The one part of this face that is a game rather than an instrument,
     * and the only way to tell whether it reads as "turn this way" is to
     * look at it.
     */
    @Test
    fun `the compass, wrong and right`() {
        val sun = SolarTime.position(40.4, -3.7, juneAfternoon()).azimuthDeg
        assertTrue(
            shoot(dial(compass = true, bearing = sun + 55.0), "sundial-compass-off") > 3
        )
        assertTrue(
            shoot(dial(compass = true, bearing = sun + 3.0), "sundial-compass-on") > 3
        )
    }

    /**
     * The whole card, which is what somebody actually sees.
     *
     * The plate with the row of buttons under it and the gear in the
     * corner — and three of the five buttons gone, because a shadow has
     * no alarm, no stopwatch and no countdown.
     */
    @Test
    fun `the card the sundial arrives on`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.SUNDIAL.key)
            .putBoolean(Prefs.SUNDIAL_LATITUDE_FIXED, true)
            .putInt(Prefs.SUNDIAL_LATITUDE, 40)
            .commit()
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().sundialForTest()?.atMs = juneAfternoon()
            val screen = c.get().findViewById<View>(android.R.id.content)
            assertTrue(shoot(screen, "sundial-card") > 3)
        }
    }

    /** And on a dark theme, since that is what most of this app wears. */
    @Test
    fun `the dial at night colours`() {
        assertTrue(
            shoot(dial(theme = ClockThemes.MIDNIGHT), "sundial-midnight") > 3
        )
    }

    /** A sky with everything in it, for the pedestal's two instruments. */
    private fun weather(
        hPa: Double, celsius: Double, trust: Weather.Trust = Weather.Trust.AGREED
    ) = Weather.Sky(
        temperatureC = Weather.Agreed(celsius, trust),
        pressureHpa = Weather.Agreed(hPa, trust),
        cloudPercent = Weather.Agreed(20.0, trust),
        answered = if (trust == Weather.Trust.AGREED) 3 else 1,
        atMs = juneAfternoon()
    )

    /**
     * The glass and the thermometer, in the four states worth looking at.
     *
     * A pedestal is not a number: whether these read as two brass
     * instruments or as two diagrams is a thing only a picture can say,
     * and the four here are the ones that look different — a high glass
     * on a hot day, a low one in the cold, an unconfirmed reading drawn
     * faint, and the wall dial, which hangs the other way up and keeps
     * them in the same place anyway.
     */
    @Test
    fun `the pedestal carries a glass and a thermometer`() {
        val phone = 1000 to 1900
        assertTrue(
            shoot(
                dial(outside = weather(1024.0, 27.0), w = phone.first, h = phone.second),
                "sundial-glass-fair"
            ) > 3
        )
        assertTrue(
            shoot(
                dial(outside = weather(974.0, -4.0), w = phone.first, h = phone.second),
                "sundial-glass-stormy"
            ) > 3
        )
        assertTrue(
            shoot(
                dial(
                    outside = weather(1002.0, 14.0, Weather.Trust.LONE),
                    w = phone.first, h = phone.second
                ),
                "sundial-glass-lone"
            ) > 3
        )
        assertTrue(
            shoot(
                dial(
                    kind = Sundial.Kind.VERTICAL, outside = weather(1013.0, 18.0),
                    w = phone.first, h = phone.second
                ),
                "sundial-glass-wall"
            ) > 3
        )
        // And on the dark theme, which is what most of this app wears.
        assertTrue(
            shoot(
                dial(
                    theme = ClockThemes.MIDNIGHT, outside = weather(1030.0, 31.0),
                    w = phone.first, h = phone.second
                ),
                "sundial-glass-midnight"
            ) > 3
        )
    }

    /**
     * The pedestal is drawn from the reading, is switched off with its
     * row, and is not there at all before anybody asks for the weather.
     *
     * The pictures above say it looks like two instruments. This says it
     * is two instruments — that the needle and the column are wired to
     * numbers rather than drawn at a pleasant angle, which is exactly the
     * failure a screenshot cannot see.
     */
    @Test
    fun `the pedestal follows the reading and goes with its switch`() {
        val tall = 1900
        val band = (1250 to 1800)
        val bare = pixels(dial(w = 1000, h = tall))
        val fair = pixels(dial(outside = weather(1024.0, 27.0), w = 1000, h = tall))
        assertTrue(
            "nothing was drawn under the plate for a sky that had a reading",
            differ(bare, fair, band.first, band.second) > 400
        )
        // A different sky is a different pedestal: a needle and a column
        // drawn at a fixed angle would pass everything above this line.
        val storm = pixels(dial(outside = weather(974.0, -4.0), w = 1000, h = tall))
        assertTrue(
            "the needle and the column did not move with the weather",
            differ(fair, storm, band.first, band.second) > 200
        )
        // And the row takes them away.
        val off = SundialView(context).apply {
            theme = ClockThemes.IVORY
            latitude = 40.4
            longitude = -3.7
            atMs = juneAfternoon()
            outside = weather(1024.0, 27.0)
            instruments = false
            measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, tall)
        }
        assertEquals(
            "switched off, something was still standing under the plate",
            0, differ(bare, pixels(off), band.first, band.second)
        )
    }

    /** One view, drawn. */
    private fun pixels(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }

    /**
     * How many pixels of a band two drawings disagree about.
     *
     * Counting ink instead was the first attempt and it measured nothing:
     * "not the background colour" is true of the whole plate, so a band
     * across the plate came to the same 41,130 pixels whether the date was
     * cut into it or not, and the test passed the broken code and the
     * fixed code identically. A difference between two pictures cannot go
     * wrong that way — it is nought exactly when the two are the same
     * picture, which is the thing being claimed.
     */
    private fun differ(a: Bitmap, b: Bitmap, top: Int, bottom: Int): Int {
        var n = 0
        for (y in top until bottom) {
            for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        }
        return n
    }

    /**
     * The date is cut under the plate whether or not the motto is.
     *
     * Two rows on two different screens, and for eleven versions one of
     * them quietly governed the other: the engraving bailed out on the
     * motto before it ever reached the date, so somebody who turned the
     * Latin off lost the date with it and had no way of telling why. The
     * only clue was that the row for the Julian calendar underneath went
     * on working on a date that was no longer there.
     *
     * Measured in the band where the date is cut rather than counted
     * across the whole picture: a dial without a motto is a dial with a
     * whole inscription missing, so the difference the picture makes is
     * enormous and would have swamped a date.
     */
    @Test
    fun `the date survives the motto being turned off`() {
        val top = 800
        val bottom = 880
        val without = differ(
            pixels(dial(motto = false, showDate = false)),
            pixels(dial(motto = false, showDate = true)),
            top, bottom
        )
        assertTrue(
            "the date switch changes nothing while the motto is off: $without pixels",
            without > 300
        )
        // And it is still there with the motto on, so this did not fix one
        // by breaking the other.
        val with = differ(
            pixels(dial(motto = true, showDate = false)),
            pixels(dial(motto = true, showDate = true)),
            top, bottom
        )
        assertTrue("the date went missing with the motto on: $with pixels", with > 300)
        // The same date, cut in the same place, whatever the rim says.
        assertEquals("the two dates are not the same date", without, with)
        assertTrue(shoot(dial(motto = false, showDate = true), "sundial-date-no-motto") > 3)
    }

    /**
     * The date cut in all three calendars, so somebody can look at them.
     *
     * "II Akhet 15" is longer than "XXI · VI" and is the reason to take a
     * picture rather than to trust the string: a label wide enough to run
     * into the hour numerals either side of it is a label that is wrong
     * whatever it says.
     */
    @Test
    fun `the date in all three calendars`() {
        for (reckoning in Sundial.Reckoning.entries) {
            assertTrue(
                shoot(
                    dial(showDate = true, reckoning = reckoning),
                    "sundial-date-${reckoning.key}"
                ) > 3
            )
        }
    }
}

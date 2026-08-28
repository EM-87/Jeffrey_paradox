package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
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
        theme: ClockTheme = ClockThemes.IVORY
    ): SundialView = SundialView(context).apply {
        this.theme = theme
        this.kind = kind
        this.plate = plate
        latitude = lat
        longitude = -3.7
        this.compass = compass
        phoneBearing = bearing
        atMs = at
        measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1000, 1000)
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
}

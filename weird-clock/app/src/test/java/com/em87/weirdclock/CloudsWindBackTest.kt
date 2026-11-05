package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Whether the clouds go back through the week with the world.
 *
 * Wound back, the globe was felt to keep today's clouds while the night
 * side moved. The pipeline from the week on disk to the pixels has four
 * steps that could each lose a day — the right file asked for, that file
 * decoded rather than the one already held, the globe's baked discs thrown
 * away for a new picture, and the new disc drawn — so each is measured
 * here against a week of pictures that differ in nothing but their grey,
 * which makes a day's clouds tell on the glass as a shade.
 *
 * And what happens past the week: a globe wound to a day the phone has no
 * picture of should say so and wear the nearest day it has, not quietly
 * wear the newest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudsWindBackTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private val day = DailyImageStore.DAY_MS

    /** Midsummer noon UTC, so the sun is over the Atlantic at the globe's centre. */
    private fun noon(): Long =
        java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, java.util.Calendar.JUNE, 21, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * The greys the week's pictures are painted in, newest first.
     *
     * Spread through the veil's working range — see [SatelliteClouds.DIM]
     * and [SatelliteClouds.BRIGHT], 173 to 242 — because above it every
     * grey is the same full veil, and the first run of this test took two
     * such greys for two days of clouds that had not changed.
     */
    private val greys = intArrayOf(240, 230, 220, 210, 200, 190, 180)

    @Before
    fun aWeekOfGreyClouds() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.WEATHER, true)
            .putBoolean(Prefs.HEMISPHERE_CLOUDS, true)
            .commit()
        CloudStore.forget(context)
        for (back in greys.indices) {
            val g = greys[back]
            val picture = Bitmap.createBitmap(72, 36, Bitmap.Config.ARGB_8888)
            picture.eraseColor(Color.rgb(g, g, g))
            File(context.cacheDir, "clouds-${CloudStore.dayOf(noon() - back * day)}.jpg")
                .outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        }
    }

    @After
    fun tidy() {
        CloudStore.forget(context)
    }

    private fun globe(): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        view = Hemisphere.View.GLOBE
        hourRing = false
        showSun = false
        showMoon = false
        moonlight = false
        located = false
        cloudsOn = { at -> CloudStore.cached(context, at) }
        cloudsDayOn = { at -> CloudStore.worn(context, at) }
        measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1000, 1000)
    }

    /** Draws the globe and averages the colour of the block at its centre. */
    private fun shot(v: HemisphereView, name: String?): IntArray {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        name?.let { n ->
            File(outDir, "$n.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in 470 until 530) for (x in 470 until 530) {
            val c = bitmap.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun apart(a: IntArray, b: IntArray): Int =
        kotlin.math.abs(a[0] - b[0]) + kotlin.math.abs(a[1] - b[1]) + kotlin.math.abs(a[2] - b[2])

    @Test
    fun `wound back a day at a time the globe wears each day's clouds`() {
        val globe = globe()
        var previous: Bitmap? = null
        var previousColour: IntArray? = null
        val colours = ArrayList<String>()
        for (back in greys.indices) {
            val at = noon() - back * day
            globe.atMs = at
            val bakesBefore = globe.bakesForTest()
            val colour = shot(globe, if (back == 0 || back == 6) "clouds-wound-back-$back" else null)
            // Step one and two: the right file, decoded afresh.
            val picture = CloudStore.cached(context, at)!!
            val grey = Color.red(picture.getPixel(10, 10))
            assertTrue("the wrong day's picture: grey $grey for day -$back", kotlin.math.abs(grey - greys[back]) <= 3)
            previous?.let { assertNotSame("the same bitmap handed back for a new day", it, picture) }
            previous = picture
            // Step three: the discs were thrown away and baked again.
            assertTrue("no new bake for day -$back", globe.bakesForTest() > bakesBefore)
            // Step four: it shows.
            colours += "-$back: ${colour.joinToString("/")}"
            previousColour?.let {
                assertTrue(
                    "day -$back drew the same as the day before: ${colour.joinToString("/")}",
                    apart(it, colour) >= 3
                )
            }
            previousColour = colour
        }
        println("WINDBACK centre colours by day ${colours.joinToString(", ")}")
    }

    /**
     * Past the week there is no picture, and the globe says so.
     *
     * It wears the nearest day it has, which is the oldest, with a word
     * on the face naming that day — not the newest in silence, which was
     * a globe wound back ten days wearing this morning's weather.
     */
    @Test
    fun `wound back past the week the globe wears the oldest day and says so`() {
        val globe = globe()
        globe.atMs = noon() - 6 * day
        val oldest = shot(globe, null)
        globe.atMs = noon()
        val newest = shot(globe, null)
        globe.atMs = noon() - 9 * day
        val beyond = shot(globe, "clouds-wound-back-9")
        println(
            "WINDBACK beyond the week: wears ${beyond.joinToString("/")}; oldest is ${oldest.joinToString("/")}, newest ${newest.joinToString("/")}; worn day ${globe.cloudsWornForTest()}"
        )
        assertEquals(
            "past the week it should wear the oldest day it has",
            CloudStore.dayOf(noon() - 6 * day), globe.cloudsWornForTest()
        )
        assertTrue("it wore the newest, not the oldest", apart(beyond, oldest) < 3 && apart(beyond, newest) > 30)
        // And it says so: a word on the face naming the day worn — the
        // 14th of June, which is what the oldest picture is of — where
        // a globe on a day it has says nothing.
        val said = globe.legendForTest()
        assertTrue("no word on the face for a day the phone has no picture of: $said", said != null && said.contains("14"))
        // Drawn, not only said: the pill sits low on the face, and the
        // same patch of ocean on the globe wound to a day it has is bare.
        fun patch(v: HemisphereView): IntArray {
            val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap))
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in 812 until 832) for (x in 490 until 510) {
                val c = bitmap.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
            }
            return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
        val pill = patch(globe)
        globe.atMs = noon() - 6 * day
        // Drawn first: the globe asks which day it is wearing as it draws.
        val bare = patch(globe)
        assertEquals("a word on the face on a day it has: ${globe.legendForTest()}", null, globe.legendForTest())
        assertTrue("the pill is not drawn: ${pill.joinToString("/")} against ${bare.joinToString("/")}", apart(pill, bare) >= 10)
    }
}

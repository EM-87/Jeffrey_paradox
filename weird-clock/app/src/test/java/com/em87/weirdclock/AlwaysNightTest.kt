package com.em87.weirdclock

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The turning world and everything beside it is seen at night.
 *
 * The world itself always was: space is black on that card whatever the
 * theme. Asked for from a phone in its light mode, where the solar
 * system a swipe away was a pale blue disc and the alarms a white list.
 * Every test here is run on a phone that is in its light mode, which is
 * the only one where there is anything to find.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-notnight-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlwaysNightTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun settled(face: Face, theme: String? = null) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, face.key)
            .apply { if (theme != null) putString(Prefs.THEME, theme) }
            .commit()
    }

    private fun isNight(app: MainActivity): Boolean =
        (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** How bright the whole screen is with a card on it, out of 255. */
    private fun brightness(app: MainActivity, card: Card): Double {
        app.goToForTest(card)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        val view = app.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0L
        for (i in pixels.indices step 7) {
            sum += (Color.red(pixels[i]) * 3 + Color.green(pixels[i]) * 6 + Color.blue(pixels[i])) / 10
        }
        return sum.toDouble() / ((pixels.size + 6) / 7)
    }

    /** The phone really is in its light mode here, and the other faces follow it. */
    @Test
    fun `a face with a day is light on a phone that is`() {
        for (face in listOf(Face.ANALOG, Face.DIGITAL, Face.SUNDIAL)) {
            settled(face)
            Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                val app = c.get()
                assertFalse("${face.key} is always seen at night", face.alwaysNight)
                assertFalse("the ${face.key} face is in the dark on a phone in the light", isNight(app))
                assertTrue("the ${face.key} face wears a dark theme by day", ClockThemes.isPaleFace(app.clockForTest().theme))
            }
        }
        settled(Face.ANALOG)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val alarms = brightness(c.get(), Card.ALARM)
            println("MEASURE the dial's alarms on a phone in its light mode: %.0f of 255".format(alarms))
            assertTrue("the dial's alarms are dark by day: $alarms", alarms > 170.0)
        }
    }

    /** The world's own cards are dark on the same phone: the sky, the alarms, both chronographs. */
    @Test
    fun `the turning world and every card beside it is dark on a phone in its light mode`() {
        assertTrue(Face.HEMISPHERE.alwaysNight)
        settled(Face.HEMISPHERE)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            assertTrue("the world's screen is not in night mode", isNight(app))
            val sky = app.orreryCardForTest()!!
            assertFalse("the solar system is on a pale ground", ClockThemes.isPaleFace(sky.theme))
            assertFalse("the countdown wears a pale theme", ClockThemes.isPaleFace(app.countdownForTest()!!.theme))
            val behind = app.surroundColourForTest()
            assertTrue(
                "what is behind everything is pale: ${Integer.toHexString(behind)}",
                Color.red(behind) + Color.green(behind) + Color.blue(behind) < 150
            )
            for (card in listOf(Card.CLOCK, Card.CALENDAR, Card.ALARM, Card.STOPWATCH, Card.REVERSE)) {
                val lit = brightness(app, card)
                println("MEASURE the world's $card card on a phone in its light mode: %.0f of 255".format(lit))
                assertTrue("the world's $card card is bright: $lit", lit < 60.0)
            }
        }
    }

    /** A pale theme asked for by name is worn by the faces that have a day, and not by this one. */
    @Test
    fun `a pale theme chosen by name is the night one on the turning world`() {
        for (theme in listOf("daylight", "ivory")) {
            settled(Face.HEMISPHERE, theme)
            Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                val app = c.get()
                assertFalse("the solar system wears $theme", ClockThemes.isPaleFace(app.orreryCardForTest()!!.theme))
                assertEquals("the world's own theme under $theme", ClockThemes.MIDNIGHT.numeral, app.hemisphereForTest()!!.theme.numeral)
            }
            settled(Face.ANALOG, theme)
            Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                assertTrue("the dial does not wear $theme", ClockThemes.isPaleFace(c.get().clockForTest().theme))
            }
        }
        // And a dark one chosen by name is itself, here as anywhere.
        settled(Face.HEMISPHERE, "neon")
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertEquals(ClockThemes.NEON.numeral, c.get().orreryCardForTest()!!.theme.numeral)
        }
    }

    /**
     * A widget that opens the app on the world, on a phone that keeps a
     * dial, gets the night with the world — which the screen cannot know
     * until it has been made and has read the request, so it is made
     * twice, and only twice.
     */
    @Test
    fun `a world lent by a widget is dark, on a phone whose own face is not`() {
        settled(Face.ANALOG)
        val lent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_FACE, Face.HEMISPHERE.key)
        Robolectric.buildActivity(MainActivity::class.java, lent).use { c ->
            c.create()
            val first = c.get()
            assertEquals(Face.HEMISPHERE, first.faceForTest())
            assertFalse("the first screen knew of the visit before it could have", first.attachedAtNightForTest())
            assertTrue("it did not ask to be built again: ${MainActivity.lastRecreateReason}", MainActivity.lastRecreateReason!!.startsWith("night false->true"))
            c.start().postCreate(null).resume().visible()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val app = c.get()
            assertTrue("the screen was not built again for the night", app !== first)
            assertEquals(Face.HEMISPHERE, app.faceForTest())
            assertTrue("the lent world was not made for the night", app.attachedAtNightForTest())
            assertTrue("the lent world is not in night mode", isNight(app))
            assertFalse("the lent world's solar system is pale", ClockThemes.isPaleFace(app.orreryCardForTest()!!.theme))
            val alarms = brightness(app, Card.ALARM)
            println("MEASURE a lent world's alarms on a phone in its light mode: %.0f of 255".format(alarms))
            assertTrue("the lent world's alarms are bright: $alarms", alarms < 60.0)
            // And it settles: nothing is built a third time.
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("the screen went on being rebuilt", c.get() === app)
        }
        // The night was the visit's. The next screen made for this phone,
        // by anything but that widget, is not made for the night.
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.create()
            assertEquals(Face.ANALOG, c.get().faceForTest())
            assertFalse("the dial was made for the night the world had been lent", c.get().attachedAtNightForTest())
        }
    }

    /**
     * And the other way about: a dial lent to a phone that keeps the
     * world is not made for the night, and is left to the phone.
     *
     * What it is asked for, and not what it comes out as: the test phone
     * hands a screen that is built again the mode of the screen it
     * replaces, where a real one hands it the phone's own, so the dial
     * here is still dark whatever is asked.
     */
    @Test
    fun `a dial lent by a widget to a phone that keeps the world is left to the phone`() {
        settled(Face.HEMISPHERE)
        val lent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_FACE, Face.ANALOG.key)
        Robolectric.buildActivity(MainActivity::class.java, lent).use { c ->
            c.create()
            val first = c.get()
            assertTrue("the first screen knew of the visit before it could have", first.attachedAtNightForTest())
            assertTrue("it did not ask to be built again: ${MainActivity.lastRecreateReason}", MainActivity.lastRecreateReason!!.startsWith("night true->false"))
            c.start().postCreate(null).resume().visible()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val app = c.get()
            assertTrue("the screen was not built again", app !== first)
            assertEquals(Face.ANALOG, app.faceForTest())
            assertFalse("the lent dial was made for the night", app.attachedAtNightForTest())
            assertEquals(
                "the lent dial was told which mode to be in",
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_UNSPECIFIED, app.delegate.localNightMode
            )
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("the screen went on being rebuilt", c.get() === app)
        }
    }

    /** The phone's own face is built once, whichever it is: at night from the start, or by day. */
    @Test
    fun `the phone's own face is built once`() {
        for (face in Face.entries) {
            settled(face)
            val before = MainActivity.lastRecreateReason
            Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                val first = c.get()
                assertEquals(face.alwaysNight, first.attachedAtNightForTest())
                assertEquals(face.alwaysNight, isNight(first))
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue("the ${face.key} face's own screen was built twice", c.get() === first)
                assertEquals("the ${face.key} face asked to be built again", before, MainActivity.lastRecreateReason)
            }
        }
    }
}

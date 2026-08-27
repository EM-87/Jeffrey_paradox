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
 * Pictures of the digital face, written to disk so somebody can look.
 *
 * A camera rather than a test — see [ScreenshotTest] — and here more than
 * anywhere, because this face is nothing but drawing: three idioms times
 * three alphabets is nine things that either look like a clock or look
 * like a bug, and no assertion is going to tell the two apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DigitalShotTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** A quarter past ten at night, which exercises every place at once. */
    private fun atTwentyTwoFifteen(second: Int = 47): Long =
        java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 27, 22, 15, second)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun face(
        style: DigitStyle,
        script: DigitScript,
        hour24: Boolean = true,
        seconds: Boolean = true,
        date: Boolean = true,
        at: Long = atTwentyTwoFifteen()
    ): DigitalClockView = DigitalClockView(context).apply {
        theme = ClockThemes.MIDNIGHT
        this.style = style
        this.script = script
        this.hour24 = hour24
        showSeconds = seconds
        showDate = date
        yautja = Yautja.face(context)
        atMs = at
        measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1080, 1400)
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
                seen.add(bitmap.getPixel(x, y))
                x += 5
            }
            y += 5
        }
        return seen.size
    }

    @Test
    fun `every idiom in every alphabet`() {
        for (style in DigitStyle.entries) {
            for (script in DigitScript.entries) {
                val name = "digital-${style.key}-${script.key}"
                assertTrue(name, shoot(face(style, script), name) > 3)
            }
        }
    }

    /** And the twelve-hour face, which is the one with a sun on it. */
    @Test
    fun `the twelve-hour face, morning and night`() {
        val morning = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 27, 9, 5, 0)
        }.timeInMillis
        assertTrue(
            shoot(
                face(DigitStyle.SEGMENT, DigitScript.ARABIC, hour24 = false, at = morning),
                "digital-twelve-morning"
            ) > 3
        )
        assertTrue(
            shoot(
                face(DigitStyle.SEGMENT, DigitScript.ARABIC, hour24 = false),
                "digital-twelve-night"
            ) > 3
        )
        assertTrue(
            shoot(
                face(DigitStyle.SEGMENT, DigitScript.ROMAN, hour24 = false, at = morning),
                "digital-twelve-roman"
            ) > 3
        )
    }

    /** The plainest thing this face can be: hours and minutes, nothing else. */
    @Test
    fun `hours and minutes and nothing else`() {
        assertTrue(
            shoot(
                face(DigitStyle.SEGMENT, DigitScript.ARABIC, seconds = false, date = false),
                "digital-bare"
            ) > 3
        )
    }

    /** Midnight in Rome, which is the one with two noughts in it. */
    @Test
    fun `Rome at midnight`() {
        val midnight = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 27, 0, 0, 0)
        }.timeInMillis
        assertTrue(
            shoot(
                face(DigitStyle.SEGMENT, DigitScript.ROMAN, at = midnight),
                "digital-rome-midnight"
            ) > 3
        )
    }

    /**
     * And the whole card, which is the thing anybody actually looks at:
     * the readout with the row of buttons under it and the gear on the
     * corner, and the hourglass gone from between them.
     */
    @Test
    fun `the card the digital clock arrives on`() {
        for (style in DigitStyle.entries) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().clear()
                .putBoolean(Prefs.OVERLAY_ASKED, true)
                .putBoolean(Prefs.FACE_ASKED, true)
                .putString(Prefs.FACE, Face.DIGITAL.key)
                .putString(Prefs.DIGIT_STYLE, style.key)
                .putBoolean(Prefs.SHOW_DATE, true)
                .commit()
            org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                val screen = c.get().findViewById<View>(android.R.id.content)
                assertTrue(
                    "digital-card-${style.key}",
                    shoot(screen, "digital-card-whole-${style.key}") > 3
                )
            }
        }
    }

    /**
     * Setting a time on the face with no hands, in each idiom.
     *
     * The whole card, because the question this picture answers is not
     * "do the digits look right" — it is whether somebody looking at it
     * can tell there is something here to grab.
     */
    @Test
    fun `an alarm being set by rolling the digits`() {
        for (style in DigitStyle.entries) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().clear()
                .putBoolean(Prefs.OVERLAY_ASKED, true)
                .putBoolean(Prefs.FACE_ASKED, true)
                .putString(Prefs.FACE, Face.DIGITAL.key)
                .putString(Prefs.DIGIT_STYLE, style.key)
                .commit()
            org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                c.get().windAlarmForTest(7, 30)
                val screen = c.get().findViewById<View>(android.R.id.content)
                screen.measure(
                    View.MeasureSpec.makeMeasureSpec(screen.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(screen.height, View.MeasureSpec.EXACTLY)
                )
                screen.layout(0, 0, screen.width, screen.height)
                assertTrue(
                    "digital-setting-${style.key}",
                    shoot(screen, "digital-setting-${style.key}") > 3
                )
            }
        }
    }

    /**
     * The two chronographs on the face with no hands.
     *
     * The case is the point: a digital chronograph is not a number on a
     * background, it is the same instrument with a different movement in
     * it — so the bezel, the crown and the two pushers have to still be
     * there, and the picture is the only thing that can say whether they
     * read as one object or as a screen with furniture round it.
     */
    @Test
    fun `the chronographs with a screen in them`() {
        for (card in listOf(Card.STOPWATCH, Card.REVERSE)) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().clear()
                .putBoolean(Prefs.OVERLAY_ASKED, true)
                .putBoolean(Prefs.FACE_ASKED, true)
                .putString(Prefs.FACE, Face.DIGITAL.key)
                .commit()
            org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                c.get().goToForTest(card)
                // The card that is leaving slides out rather than
                // vanishing, so a picture taken in the same frame is a
                // picture of the card we just left.
                org.robolectric.shadows.ShadowSystemClock.advanceBy(
                    java.time.Duration.ofMillis(600)
                )
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val screen = c.get().findViewById<View>(android.R.id.content)
                screen.measure(
                    View.MeasureSpec.makeMeasureSpec(screen.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(screen.height, View.MeasureSpec.EXACTLY)
                )
                screen.layout(0, 0, screen.width, screen.height)
                val name = "digital-chrono-${card.name.lowercase()}"
                assertTrue(name, shoot(screen, name) > 3)
            }
        }
    }

    /**
     * The alarm card, on both faces.
     *
     * The row that says whether alarms wear little dials is not on the
     * digital face's settings at all — a clock with no dial has no opinion
     * about little dials — so the answer has to come from what the clock
     * is, and this is the picture that says whether it did.
     */
    @Test
    fun `the alarm card on a face with no dials`() {
        for (face in Face.entries) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().clear()
                .putBoolean(Prefs.OVERLAY_ASKED, true)
                .putBoolean(Prefs.FACE_ASKED, true)
                .putString(Prefs.FACE, face.key)
                .commit()
            AlarmStore.forget()
            AlarmStore.all(context).apply {
                clear()
                add(Alarm(id = 1, hour = 7, minute = 30, enabled = true, sound = ""))
                add(Alarm(id = 2, hour = 13, minute = 5, enabled = false, sound = ""))
            }
            AlarmStore.save(context)
            org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                c.get().goToForTest(Card.ALARM)
                org.robolectric.shadows.ShadowSystemClock.advanceBy(
                    java.time.Duration.ofMillis(600)
                )
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val screen = c.get().findViewById<View>(android.R.id.content)
                screen.measure(
                    View.MeasureSpec.makeMeasureSpec(screen.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(screen.height, View.MeasureSpec.EXACTLY)
                )
                screen.layout(0, 0, screen.width, screen.height)
                val name = "alarms-${face.key}"
                assertTrue(name, shoot(screen, name) > 3)
            }
        }
    }

    /** The floating countdown, which was the last hourglass on this face. */
    @Test
    fun `the floating countdown with a screen in it`() {
        for (lcd in listOf(false, true)) {
            val view = HourglassView(context).apply {
                theme = ClockThemes.MIDNIGHT
                this.lcd = lcd
                totalMs = 600_000L
                remainingMs = 187_000L
                measure(
                    View.MeasureSpec.makeMeasureSpec(340, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(460, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 340, 460)
            }
            val name = if (lcd) "float-screen" else "float-sand"
            assertTrue(name, shoot(view, name) > 3)
        }
    }

    /**
     * The world clock on the face with no hands: six little readouts
     * floating over a big one, in the same idiom.
     */
    @Test
    fun `the cities as little readouts`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.DIGITAL.key)
            .putBoolean(Prefs.WORLD_CLOCK, true)
            .putStringSet(
                Prefs.WORLD_TZS,
                setOf("UTC", "Europe/Madrid", "America/New_York", "Asia/Tokyo")
            )
            .commit()
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val screen = c.get().findViewById<View>(android.R.id.content)
            assertTrue("digital-world", shoot(screen, "digital-world") > 3)
        }
    }

    /** And the daylight theme, which is where a pale ghost bar shows up. */
    @Test
    fun `the same face in daylight`() {
        val view = face(DigitStyle.SEGMENT, DigitScript.ARABIC).apply {
            theme = ClockThemes.DAYLIGHT
        }
        assertTrue(shoot(view, "digital-daylight") > 3)
    }
}

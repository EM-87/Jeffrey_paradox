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
        at: Long = atTwentyTwoFifteen(),
        w: Int = 1080,
        h: Int = 1400
    ): DigitalClockView = DigitalClockView(context).apply {
        theme = ClockThemes.MIDNIGHT
        // Through the same rule the app uses, so these pictures are of
        // things somebody can actually reach: a script that only exists as
        // lit bars comes out as lit bars however it was asked for, and a
        // sheet showing a Comet flip card would be a picture of a state
        // the settings cannot produce.
        this.style = DigitStyle.of(style.key, script)
        this.script = script
        this.hour24 = hour24
        showSeconds = seconds
        showDate = date
        yautja = Yautja.face(context)
        atMs = at
        measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, w, h)
    }

    /**
     * The four mechanisms with the phone on its side.
     *
     * A picture, because a landscape face is the one shape nobody looks
     * at while they are building one: the block is centred on a height
     * that has run out, and what fills a portrait card leaves a
     * letterbox with the date printed through the bottom of it.
     */
    @Test
    fun `the four mechanisms lying down`() {
        for (style in DigitStyle.entries) {
            val view = face(style, DigitScript.ARABIC, w = 2340, h = 900)
            assertTrue(shoot(view, "landscape-${style.key}") > 3)
        }
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

    /**
     * The calculator's numerals, on the face rather than on a strip.
     *
     * The drawing's own reading — `12:43` — because that is the one
     * picture in this app that can be laid straight over the file it came
     * out of, colon and all.
     */
    @Test
    fun `the panel, telling the time`() {
        val at = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 27, 12, 43, 9)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(
            shoot(
                face(DigitStyle.SEGMENT, DigitScript.ROMAN_COMET, at = at, seconds = false),
                "panel-1243"
            ) > 3
        )
        assertTrue(
            shoot(
                face(DigitStyle.SEGMENT, DigitScript.ROMAN_COMET, at = at),
                "panel-seconds"
            ) > 3
        )
        // Both lamps at once: an alarm armed and a morning, which is the
        // only reading where the panel has anything lit at either end.
        assertTrue(
            shoot(
                face(DigitStyle.SEGMENT, DigitScript.ROMAN_COMET, hour24 = false, at = at - 5 * 3_600_000L)
                    .apply { nextAlarmMs = at + 3_600_000L },
                "panel-lamps"
            ) > 3
        )
        // And on twelve hours, where the sun or the moon stands in for the
        // AM and PM the drawing's own panel has printed on it.
        assertTrue(
            shoot(
                face(DigitStyle.SEGMENT, DigitScript.ROMAN_COMET, hour24 = false, at = at),
                "panel-twelve"
            ) > 3
        )
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
                face(
                    DigitStyle.SEGMENT, DigitScript.ROMAN_COMET,
                    hour24 = false, at = morning
                ),
                "digital-twelve-panel"
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

    /**
     * The panel at midnight, which is the one with two noughts on it and a
     * rail that has just rolled over.
     */
    @Test
    fun `the panel at midnight`() {
        val midnight = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 27, 0, 0, 0)
        }.timeInMillis
        assertTrue(
            shoot(
                face(DigitStyle.SEGMENT, DigitScript.ROMAN_COMET, at = midnight),
                "panel-midnight"
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
                // On the dark theme, where the screen has to read as a
                // darker panel cut into a dark dial rather than as a hole.
                .putString(Prefs.THEME, "terminal")
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

    /**
     * The alarm editor, on both faces.
     *
     * Two places put a little face on screen for one fixed time — the
     * list and this — and they disagreed: the list showed digits on a
     * clock with no dial and the editor showed a row of little clock
     * faces. Both go through one function now, and this is the picture
     * that says so.
     */
    @Test
    fun `the alarm editor on a face with no dials`() {
        for (face in Face.entries) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().clear()
                .putBoolean(Prefs.OVERLAY_ASKED, true)
                .putBoolean(Prefs.FACE_ASKED, true)
                .putString(Prefs.FACE, face.key)
                .commit()
            org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                val alarm = Alarm(id = 3, hour = 6, minute = 45, enabled = true, sound = "")
                alarm.extraTimes.add(7 * 60 + 15)
                c.get().showAlarmSheetForTest(alarm)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val sheet = androidx.appcompat.app.AppCompatDelegate::class.java.let {
                    org.robolectric.shadows.ShadowDialog.getLatestDialog()
                }
                val content = sheet?.window?.decorView ?: return@use
                content.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.AT_MOST)
                )
                content.layout(0, 0, 1080, content.measuredHeight.coerceAtLeast(600))
                val name = "alarm-editor-${face.key}"
                assertTrue(name, shoot(content, name) > 3)
            }
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

    /**
     * The clock with the screen to itself and nothing else on it.
     *
     * The whole of what "full screen" was asked for: no seconds, no date,
     * no furniture. Shot beside the version that keeps them, because the
     * difference is the feature.
     */
    @Test
    fun `full screen, bare and not bare`() {
        for (bare in listOf(true, false)) {
            val view = DigitalClockView(context).apply {
                theme = ClockThemes.MIDNIGHT
                style = DigitStyle.SEGMENT
                script = DigitScript.ARABIC
                fullScreen = true
                bedsideSeconds = !bare
                bedsideDate = !bare
                yautja = Yautja.face(context)
                atMs = atTwentyTwoFifteen()
                measure(
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 1600, 720)
            }
            assertTrue(shoot(view, if (bare) "bedside-bare" else "bedside-full") > 3)
        }
    }

    /**
     * The day of the week and the next alarm, which every digital clock
     * ever built has and this one did not.
     *
     * Three alphabets, because the day name is the one thing on this face
     * that cannot be drawn with the bars: two of the three alphabets have
     * no letters at all. Rome gets the planet the day is named after and
     * theirs gets the number, and whether that reads as belonging to the
     * clock or as a label stuck on it is a look and not an assertion.
     */
    @Test
    fun `the day of the week, and what is armed`() {
        for (script in DigitScript.entries) {
            val view = face(DigitStyle.SEGMENT, script).apply {
                showWeekday = true
                nextAlarmMs = atTwentyTwoFifteen() + 9 * 3_600_000L + 15 * 60_000L
            }
            assertTrue(shoot(view, "digital-day-alarm-${script.key}") > 3)
        }
    }

    /**
     * The home-screen widget on the face with no hands.
     *
     * Three alphabets and three sizes, because the widget is the one part
     * of this app whose shape somebody else chooses: it is dropped at two
     * cells square and then pulled to whatever fits their home screen, and
     * the panel, the corner radius and the digits all have to survive
     * being four cells wide and one tall.
     */
    @Test
    fun `the clock widget on a face with no hands`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.DIGITAL.key)
            .putBoolean(Prefs.SHOW_DATE, true)
            .commit()
        for ((w, h) in listOf(360 to 360, 720 to 300, 320 to 480)) {
            for (script in DigitScript.entries) {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().putString(Prefs.DIGIT_SCRIPT, script.key).commit()
                val bitmap = WidgetRenderer.digitalBitmap(context, w, h)
                val name = "widget-digital-${script.key}-${w}x$h"
                File(outDir, "$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                var seen = HashSet<Int>()
                for (y in 0 until h step 4) for (x in 0 until w step 4) seen.add(bitmap.getPixel(x, y))
                assertTrue(name, seen.size > 3)
                bitmap.recycle()
            }
        }
        // And the panel with its date on, which is the one script whose
        // date is two rails of a second display rather than a line of
        // digits. A widget two cells square is the smallest place either
        // of them ever has to work.
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(Prefs.DIGIT_SCRIPT, DigitScript.ROMAN_COMET.key)
            .putBoolean(Prefs.WIDGET_DATE, true)
            .commit()
        for ((w, h) in listOf(360 to 360, 720 to 300)) {
            val bitmap = WidgetRenderer.digitalBitmap(context, w, h)
            val name = "widget-panel-dated-${w}x$h"
            File(outDir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val seen = HashSet<Int>()
            for (y in 0 until h step 4) for (x in 0 until w step 4) seen.add(bitmap.getPixel(x, y))
            assertTrue(name, seen.size > 3)
            bitmap.recycle()
        }
    }

    /**
     * The world clock, on the face that stacks it.
     *
     * The whole of what this face does with the world clock is in this
     * picture: no bubbles, no toys, a list of cities under the time in the
     * same bars. Whether it reads as one instrument with a second display
     * or as a clock with a table stuck to it is not something an assertion
     * can answer.
     */
    @Test
    fun `the other cities, stacked under the time`() {
        for (script in listOf(DigitScript.ARABIC, DigitScript.ROMAN_COMET)) {
            val view = face(DigitStyle.SEGMENT, script).apply {
                cities = listOf("Europe/Madrid", "America/New_York", "Asia/Tokyo")
                    .map { WorldClocks.City(it) }
            }
            assertTrue(shoot(view, "digital-cities-${script.key}") > 3)
        }
    }

    /**
     * The bedside clock: on its side, with the screen to itself.
     *
     * Three alphabets, because the widest of them is what decides how big
     * any of them can be — Rome writes a quarter past ten in thirteen
     * modules where we write it in six — and a landscape window that fits
     * the Roman one is a window with a lot of air round the Arabic one.
     * Whether that is right is not an assertion, it is a look.
     */
    @Test
    fun `the clock on its side, filling the screen`() {
        for (script in DigitScript.entries) {
            val view = DigitalClockView(context).apply {
                theme = ClockThemes.MIDNIGHT
                style = DigitStyle.SEGMENT
                this.script = script
                hour24 = true
                showSeconds = true
                showDate = true
                fullScreen = true
                yautja = Yautja.face(context)
                atMs = atTwentyTwoFifteen()
                measure(
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 1600, 720)
            }
            assertTrue(shoot(view, "bedside-${script.name.lowercase()}") > 3)
        }
    }
}

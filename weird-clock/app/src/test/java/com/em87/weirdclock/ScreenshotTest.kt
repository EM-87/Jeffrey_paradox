package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of screens, written to disk so somebody can look at them.
 *
 * Everything in this app is drawn by hand, and until now the only way to
 * find out what any of it actually looked like was to install it. Under
 * NATIVE graphics Robolectric rasterises for real, so a screen can be laid
 * out, drawn into a bitmap and written to a PNG — which is not the same as
 * a phone in a dark room, but it is the difference between judging a
 * layout and guessing at one.
 *
 * These assert almost nothing on purpose. They are a camera, not a test:
 * the only thing checked is that something was drawn at all, because a
 * blank picture is the one result that would quietly mean nothing was
 * being looked at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** Draws [view] into a PNG and returns how much of it is not one colour. */
    private fun shoot(view: View, name: String): Float {
        val width = view.width.takeIf { it > 0 } ?: 1080
        val height = view.height.takeIf { it > 0 } ?: 2000
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // How varied the picture is, as the crudest possible check that
        // there is a picture: an all-one-colour bitmap means the screen was
        // never laid out and the file is a photograph of nothing.
        val seen = HashSet<Int>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                seen.add(bitmap.getPixel(x, y))
                x += 7
            }
            y += 7
        }
        return seen.size.toFloat()
    }

    private fun screenOf(activity: android.app.Activity): View =
        activity.findViewById(android.R.id.content)

    @Test
    fun `the ring screen with a sum on it`() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putString(Prefs.MISSION, Mission.MATHS)
            .commit()
        val intent = android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE, 5)
            .putExtra(AlarmScheduler.EXTRA_LABEL, "Work")
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            assertTrue(shoot(screenOf(c.get()), "ring-maths") > 3f)
        }
    }

    @Test
    fun `the ring screen counting shakes`() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putString(Prefs.MISSION, Mission.SHAKE)
            .commit()
        org.robolectric.Shadows
            .shadowOf(context.getSystemService(android.hardware.SensorManager::class.java))
            .addSensor(
                org.robolectric.shadows.ShadowSensor.newInstance(
                    android.hardware.Sensor.TYPE_ACCELEROMETER
                )
            )
        Robolectric.buildActivity(AlarmRingActivity::class.java).use { c ->
            c.setup()
            assertTrue(shoot(screenOf(c.get()), "ring-shake") > 3f)
        }
    }

    /** And the ordinary one, for comparison. */
    @Test
    fun `the ring screen as it has always been`() {
        prefs.edit().clear().putBoolean(Prefs.OVERLAY_ASKED, true).commit()
        val intent = android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE, 5)
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            assertTrue(shoot(screenOf(c.get()), "ring-slider") > 3f)
        }
    }

    /** The night bar, which is the thing in here nobody has ever seen. */
    @Test
    fun `the night hours, with the bar in three states`() {
        for ((name, window) in listOf(
            "night-2207" to (22 to 7),
            "night-1418" to (14 to 18),
            "night-off" to (9 to 9)
        )) {
            val themed = androidx.appcompat.view.ContextThemeWrapper(
                context, R.style.Theme_WeirdClock
            )
            val bar = NightBar(themed).apply {
                setWindow(window.first, window.second)
                measure(
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                layout(0, 0, 1000, measuredHeight)
            }
            assertTrue(name, shoot(bar, name) > 2f)
        }
    }
}

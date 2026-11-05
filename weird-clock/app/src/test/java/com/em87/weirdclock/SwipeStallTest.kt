package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the main thread does on the way from the clock to the alarms.
 *
 * A swipe to the alarms was felt to stick, and the seconds tick to miss
 * beats while it did. The tick is on a thread of its own, so for it to
 * miss the whole process has to stall — which is what a long frame on
 * the main thread with a garbage collection behind it does. So this
 * measures the frame: how long the page change itself takes, how many
 * dials the alarm page is drawing, and how long one frame of each page
 * costs to draw. Printed, so the numbers are on the record; asserted
 * where a number is the bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwipeStallTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun <T : View> all(root: View, type: Class<T>, into: MutableList<T> = ArrayList()): List<T> {
        if (type.isInstance(root)) into.add(type.cast(root)!!)
        if (root is ViewGroup) for (i in 0 until root.childCount) all(root.getChildAt(i), type, into)
        return into
    }

    private fun ms(block: () -> Unit): Double {
        val began = System.nanoTime()
        block()
        return (System.nanoTime() - began) / 1_000_000.0
    }

    @Test
    fun `what a swipe to the alarms costs the main thread`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.ANALOG.key)
            .putBoolean(Prefs.HAND_SHADOWS, true)
            .putBoolean(Prefs.ALARM_MARKERS, true)
            .putBoolean(Prefs.TICKING, true)
            .commit()
        AlarmStore.forget()
        AlarmStore.all(context).apply {
            clear()
            add(Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_BELLS))
            add(Alarm(2, 7, 30, true, Prefs.ALARM_SOUND_BELLS))
            add(Alarm(3, 12, 15, false, Prefs.ALARM_SOUND_BELLS))
            add(Alarm(4, 22, 45, true, Prefs.ALARM_SOUND_BELLS))
        }
        AlarmStore.save(context)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val dials = all(root, ClockView::class.java)
        println("STALL dials alive on the pager: ${dials.size}")
        val before = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ClockView, Boolean>())
        before.addAll(dials)
        println("STALL constructing one dial: %.1f ms".format(ms { ClockView(activity) }))

        val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        fun frame(name: String) {
            root.draw(canvas)
            val times = (1..5).map { ms { root.draw(canvas) } }.sorted()
            println("STALL one frame of $name: median %.1f ms, worst %.1f ms".format(times[2], times[4]))
        }
        frame("the clock page")

        // Halfway through the swipe both pages are on the glass, which is
        // the frame the finger is watching.
        val pager = activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager)
        pager.beginFakeDrag()
        pager.fakeDragBy(-pager.width / 2f)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        frame("the two pages halfway through the swipe")
        pager.endFakeDrag()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        activity.showCardForTest(Card.CLOCK)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val change = ms {
            activity.showCardForTest(Card.ALARM)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        println("STALL the page change itself: %.1f ms".format(change))
        val after = all(root, ClockView::class.java)
        val born = after.count { it !in before }
        println("STALL dials alive after the change: ${after.size}, of which born by the change: $born")
        frame("the alarm page")
        // And a second visit, which is what every later swipe is.
        activity.showCardForTest(Card.CLOCK)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val second = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ClockView, Boolean>())
        second.addAll(all(root, ClockView::class.java))
        val again = ms {
            activity.showCardForTest(Card.ALARM)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        val bornAgain = all(root, ClockView::class.java).count { it !in second }
        println("STALL a second visit to the alarms: %.1f ms, dials born by it: $bornAgain".format(again))

        // And back, which is the other half of every visit.
        val back = ms {
            activity.showCardForTest(Card.CLOCK)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        println("STALL the way back: %.1f ms".format(back))
        controller.pause().stop().destroy()
    }
}

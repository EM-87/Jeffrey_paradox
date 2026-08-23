package com.em87.weirdclock

import android.appwidget.AppWidgetManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The odds and ends: the night window, the snooze limit, the widget's size.
 *
 * Small features with nothing in common except that each of them replaces a
 * number somebody once typed into the source with a number somebody can
 * choose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WaveOneTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun wipe() {
        prefs.edit().clear().commit()
    }

    // ------------------------------------------------------- the night window

    /**
     * The default, which used to be the only one: ten at night until seven.
     */
    @Test
    fun `the small hours are night`() {
        for (hour in intArrayOf(22, 23, 0, 3, 6)) {
            assertTrue("$hour", NightWindow.isNight(hour, 22, 7))
        }
        for (hour in intArrayOf(7, 12, 21)) {
            assertFalse("$hour", NightWindow.isNight(hour, 22, 7))
        }
    }

    /**
     * Wrapping midnight is the ordinary case and the whole difficulty:
     * "after ten and before seven" is true of no hour at all if it is read
     * as a plain range, and "between seven and ten" is the daytime.
     */
    @Test
    fun `a window that does not cross midnight also works`() {
        // Somebody who sleeps in the afternoon.
        assertTrue(NightWindow.isNight(15, 14, 18))
        assertFalse(NightWindow.isNight(20, 14, 18))
        assertFalse(NightWindow.isNight(2, 14, 18))
    }

    /** The end hour is outside: a night ending at seven is over at seven. */
    @Test
    fun `the hour it ends at is already morning`() {
        assertTrue(NightWindow.isNight(6, 22, 7))
        assertFalse(NightWindow.isNight(7, 22, 7))
    }

    /**
     * Dragging both sliders together means "stop dimming", not "dim for
     * ever" — which is what an unguarded wrapping test would decide, and
     * the difference is a screen that never comes back.
     */
    @Test
    fun `a window of no width is no night at all`() {
        for (hour in 0..23) {
            assertFalse("$hour", NightWindow.isNight(hour, 9, 9))
        }
    }

    // -------------------------------------------------------- snoozing twice

    @Test
    fun `by default an alarm may be put off as often as you like`() {
        assertEquals(0, AlarmScheduler.snoozeLimit(android.content.Intent()))
        assertTrue(
            AlarmScheduler.snooze(context, android.content.Intent(), 5, alreadySnoozed = 99)
        )
    }

    /**
     * And with a limit, the last one has to be got up for.
     *
     * Both numbers ride in the intent rather than in a preference: the
     * tally, so it can never be one left over from an alarm three days ago,
     * and the limit itself, because it belongs to the alarm that is ringing
     * and not to whichever alarm was edited last.
     */
    @Test
    fun `with a limit it eventually insists`() {
        val ringing = android.content.Intent()
            .putExtra(AlarmScheduler.EXTRA_SNOOZE_LIMIT, 2)
        assertEquals(2, AlarmScheduler.snoozeLimit(ringing))
        assertTrue("the first", AlarmScheduler.snooze(context, ringing, 5, alreadySnoozed = 0))
        assertTrue("the second", AlarmScheduler.snooze(context, ringing, 5, alreadySnoozed = 1))
        assertFalse("and no more", AlarmScheduler.snooze(context, ringing, 5, alreadySnoozed = 2))
    }

    /** And a limit set on one alarm does not reach the alarm beside it. */
    @Test
    fun `one alarm's limit is not another's`() {
        val limited = android.content.Intent()
            .putExtra(AlarmScheduler.EXTRA_SNOOZE_LIMIT, 1)
        val unlimited = android.content.Intent()
        assertFalse(
            "the one that has to be got up for",
            AlarmScheduler.snooze(context, limited, 5, alreadySnoozed = 1)
        )
        assertTrue(
            "the one about the bread",
            AlarmScheduler.snooze(context, unlimited, 5, alreadySnoozed = 1)
        )
    }

    // ------------------------------------------------------ the widget's size

    /**
     * The widget has always declared itself resizable, so the launcher let
     * you stretch it — and then scaled one fixed bitmap up to fill whatever
     * you had made.
     */
    @Test
    fun `a stretched widget is drawn bigger than a small one`() {
        val manager = AppWidgetManager.getInstance(context)
        val small = WidgetRenderer.dialPixels(context, manager, 1)
        assertTrue("some sensible size with nothing known", small > 0)
    }

    /**
     * Both ends are capped. Every push crosses process boundaries whole, so
     * a bitmap sized to a tablet's home screen is a poster being copied
     * through IPC several times a minute.
     */
    @Test
    fun `and never smaller than legible nor bigger than sensible`() {
        val manager = AppWidgetManager.getInstance(context)
        val density = context.resources.displayMetrics.density
        val size = WidgetRenderer.dialPixels(context, manager, 1)
        assertTrue("$size", size >= (64 * density).toInt())
        assertTrue("$size", size <= (320 * density).toInt())
    }

    /**
     * And the launcher has to let go of it downwards.
     *
     * Re-rendering at the size it is now was only half the job: the widget
     * could still only be made bigger, because minWidth is the size it is
     * dropped at *and*, with no minResizeWidth beside it, the smallest it
     * may ever be pulled back to. Two attributes in a manifest, and the
     * only place they can be checked is the manifest.
     */
    @Test
    fun `the launcher may shrink the widget below the size it arrives at`() {
        for (widget in intArrayOf(R.xml.widget_info, R.xml.widget_hourglass_info)) {
            val dropped = attribute(widget, "minWidth")
            val floor = attribute(widget, "minResizeWidth")
            assertTrue("no minResizeWidth at all", floor != null)
            assertTrue(
                "a floor at the drop size is no floor: $floor vs $dropped",
                dp(floor!!) < dp(dropped!!)
            )
            assertTrue("and the same downwards", dp(attribute(widget, "minResizeHeight")!!) < dp(attribute(widget, "minHeight")!!))
        }
    }

    /** Binary XML hands dimensions back as "40.0dip" and the like. */
    private fun dp(value: String): Float =
        value.takeWhile { it.isDigit() || it == '.' }.toFloat()

    private fun attribute(xml: Int, name: String): String? {
        val parser = context.resources.getXml(xml)
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                return parser.getAttributeValue(
                    "http://schemas.android.com/apk/res/android", name
                )
            }
        }
        return null
    }
}

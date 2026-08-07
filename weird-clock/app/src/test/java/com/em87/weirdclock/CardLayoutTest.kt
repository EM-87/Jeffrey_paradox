package com.em87.weirdclock

import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That the shape and the layout agree.
 *
 * [Cards] says which page each card lives on and the XML decides where it
 * actually is, and nothing made them talk to each other: the graph could
 * say the stopwatch sits beside the countdown while the stopwatch was
 * still inflated three pages away, and every test of the graph would pass
 * while the app did the old thing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CardLayoutTest {

    /**
     * Themed, because these pages use ?attr colours and backgrounds: a
     * bare application context inflates them straight into an
     * InflateException that says nothing about cards.
     */
    private val context: android.content.Context
        get() = androidx.appcompat.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_WeirdClock
        )

    /**
     * Written out here rather than borrowed from MainActivity on purpose:
     * this test exists to check a correspondence, and a correspondence
     * checked against one of its own sides checks nothing.
     */
    private fun containerId(card: Card): Int = when (card) {
        Card.HOURGLASS -> R.id.hourglass_container
        Card.CALENDAR -> R.id.calendar_container
        Card.CLOCK -> R.id.clock_container
        Card.ALARM -> R.id.alarms_container
        Card.STOPWATCH -> R.id.stopwatch_container
        Card.REVERSE -> R.id.countdown_container
    }

    private val pages = mapOf(
        Cards.PAGE_LEFT to R.layout.page_left,
        Cards.PAGE_HOME to R.layout.page_center,
        Cards.PAGE_RIGHT to R.layout.page_right
    )

    @Test
    fun `every card is inflated on the page its address names`() {
        for (card in Card.entries) {
            for ((page, layout) in pages) {
                val root = LayoutInflater.from(context).inflate(layout, null)
                val here = root.findViewById<View>(containerId(card)) != null
                assertEquals(
                    "$card belongs on page ${card.page}, looked on page $page",
                    card.page == page, here
                )
            }
        }
    }

    /**
     * And the swipe that started all this: the stopwatch and the countdown
     * inflate on pages next to each other, so going from one to the other
     * crosses nothing.
     */
    @Test
    fun `the stopwatch and the countdown are on adjacent pages`() {
        val centre = LayoutInflater.from(context).inflate(R.layout.page_center, null)
        val right = LayoutInflater.from(context).inflate(R.layout.page_right, null)
        assertNotNull(centre.findViewById<View>(R.id.stopwatch_container))
        assertNotNull(right.findViewById<View>(R.id.countdown_container))
        assertEquals(1, Cards.PAGE_RIGHT - Cards.PAGE_HOME)
    }

    /** And the app still opens with all six of them where they now are. */
    @Test
    fun `the app opens on the new shape`() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            // The clock is home, so its card is the one on screen and the
            // two that share its page are not.
            assertEquals(Cards.PAGE_HOME, Card.CLOCK.page)
            assertNotNull(activity.findViewById<View>(R.id.clock_container))
            assertEquals(
                "the hourglass must be put away",
                View.GONE,
                activity.findViewById<View>(R.id.hourglass_container).visibility
            )
            assertEquals(
                "and so must the stopwatch, which now shares this page",
                View.GONE,
                activity.findViewById<View>(R.id.stopwatch_container).visibility
            )
        }
    }
}

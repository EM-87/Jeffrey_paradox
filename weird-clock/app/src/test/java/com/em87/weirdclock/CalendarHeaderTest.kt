package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Seven letters over seven columns, and seven gaps between them.
 *
 * The month page asks the calendar for the *narrow* weekday name — S, M,
 * T — with a five-letter pattern, which is what a phone gives back. It is
 * not a promise: the pattern is only defined up to four letters, so an
 * implementation may hand back the whole word, and one does. Seven whole
 * words across seven narrow columns overlap into an unreadable smear.
 *
 * It had been in every photograph of the month page for as long as there
 * has been one, and nobody read it, because nobody reads the part of a
 * picture whose shape they already know.
 *
 * Counted rather than read: a row of seven initials crosses the header
 * band as seven separate marks. A smear crosses it as one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarHeaderTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the weekday header is seven separate letters`() {
        val page = CalendarPageView(context).apply {
            theme = ClockThemes.DAYLIGHT
            measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 1600)
        }
        val map = Bitmap.createBitmap(1000, 1600, Bitmap.Config.ARGB_8888)
        page.draw(Canvas(map))

        // Projected down the columns rather than read along one row.
        //
        // A single row through the letters counts the strokes of a W and
        // an M separately and reports twelve marks for seven days. What
        // makes a letter one thing is that it occupies one stretch of the
        // picture's width, whatever its shape does inside that stretch —
        // so this asks, for every column of the band, whether there is any
        // ink in it at all, and counts the stretches.
        fun groundAt(y: Int): Int {
            val t = HashMap<Int, Int>()
            for (x in 0 until 1000) {
                val p = map.getPixel(x, y)
                t[p] = (t[p] ?: 0) + 1
            }
            return t.maxByOrNull { it.value }!!.key
        }
        // Narrowed to the letters themselves: a band picked by two round
        // fractions of the height also caught the tail of the month's
        // title and reported nine marks for seven days.
        var middle = (1600 * 0.24f).toInt()
        var most = -1
        for (y in (1600 * 0.22f).toInt()..(1600 * 0.27f).toInt()) {
            val g = groundAt(y)
            var n = 0
            for (x in (1000 * 0.06f).toInt() until (1000 * 0.94f).toInt()) {
                if (map.getPixel(x, y) != g) n++
            }
            if (n > most) { most = n; middle = y }
        }
        val top = middle - 12
        val bottom = middle + 12
        val ground = (top..bottom).associateWith { groundAt(it) }
        // Only across the grid itself. The page behind the card is a
        // different colour from the card, so scanning the whole width
        // counted the card's own left and right edges as two more days.
        val from = (1000 * 0.06f).toInt()
        val to = (1000 * 0.94f).toInt()
        val lit = BooleanArray(1000)
        var ink = 0
        for (x in from until to) {
            for (y in top..bottom) {
                if (map.getPixel(x, y) != ground[y]) { lit[x] = true; break }
            }
            if (lit[x]) ink++
        }
        // Stretches, with the hairline gaps inside a letter closed up.
        //
        // A W and an M each split into two stretches when the band misses
        // the vertex where their strokes meet, which reported nine days in
        // a week. The gap inside a letter is a few columns; the gap
        // between two days is more than a hundred, because a column of
        // this grid is a seventh of the card. Anything under thirty is the
        // inside of a letter.
        var runs = 0
        var since = 1000
        for (x in from until to) {
            if (lit[x]) {
                if (since >= 30) runs++
                since = 0
            } else {
                since++
            }
        }
        assertEquals(
            "the days of the week ran into each other: $runs marks across $ink columns",
            7, runs
        )
    }
}

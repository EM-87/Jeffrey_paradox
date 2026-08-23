package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The marks on the dial, counted rather than looked at.
 *
 * A chapter ring is the one part of a clock face where "it looks about
 * right" is not good enough: a mark either stands over its hour or it does
 * not, and a screenshot of a dark dial hides a mark drawn through a
 * numeral about as well as it hides one drawn in the wrong place. So the
 * ticks are caught as they are drawn, turned back into an angle and a
 * radius, and asked where they went.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class ChapterRingTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    /** One radial mark: where it starts, where it ends, how thick it is. */
    private data class Tick(
        val inner: Float,
        val outer: Float,
        val angle: Float,
        val width: Float
    )

    /**
     * A canvas that keeps the lines and the words that went through it.
     *
     * Everything still reaches the bitmap underneath — this is a tap on the
     * wire, not a stub, so the view cannot take some other path because it
     * noticed it was being watched.
     */
    private class Recorder(bitmap: android.graphics.Bitmap) : android.graphics.Canvas(bitmap) {
        val lines = mutableListOf<FloatArray>()
        val widths = mutableListOf<Float>()
        val words = mutableListOf<String>()

        override fun drawLine(
            startX: Float,
            startY: Float,
            stopX: Float,
            stopY: Float,
            paint: android.graphics.Paint
        ) {
            lines += floatArrayOf(startX, startY, stopX, stopY)
            widths += paint.strokeWidth
            super.drawLine(startX, startY, stopX, stopY, paint)
        }

        override fun drawText(
            text: String,
            x: Float,
            y: Float,
            paint: android.graphics.Paint
        ) {
            words += text
            super.drawText(text, x, y, paint)
        }
    }

    private val size = 600
    private val centre = size / 2f

    private fun dial(marks: Int, minutes: Boolean): ClockView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return ClockView(themed).apply {
            dialMarks = marks
            minuteMarks = minutes
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, size, size)
        }
    }

    /**
     * The marks a dial drew, in dial angles: zero at twelve, clockwise.
     *
     * A tick is a line that keeps well away from the middle. The hands all
     * start at the centre and the shadows lie under them, so "how close does
     * this line come to the middle" separates the furniture from the works
     * without the test having to know how a hand is painted.
     */
    private fun ticksOf(v: ClockView): List<Tick> {
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val recorder = Recorder(bitmap)
        v.draw(recorder)
        val r = v.dialRadiusForTest()
        val ticks = recorder.lines.mapIndexedNotNull { i, l ->
            val from = hypot(l[0] - centre, l[1] - centre)
            val to = hypot(l[2] - centre, l[3] - centre)
            if (minOf(from, to) < r * 0.5f) return@mapIndexedNotNull null
            val midX = (l[0] + l[2]) / 2f - centre
            val midY = (l[1] + l[3]) / 2f - centre
            // The dial's own angle: twelve is up, and up is negative y.
            val deg = Math.toDegrees(kotlin.math.atan2(midX.toDouble(), -midY.toDouble()))
            Tick(minOf(from, to), maxOf(from, to), ((deg + 360.0) % 360.0).toFloat(), recorder.widths[i])
        }
        bitmap.recycle()
        return ticks
    }

    private fun wordsOf(v: ClockView): List<String> {
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val recorder = Recorder(bitmap)
        v.draw(recorder)
        bitmap.recycle()
        return recorder.words
    }

    /** How far apart two dial angles are, the short way round. */
    private fun gap(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return minOf(d, 360f - d)
    }

    /** The long ticks: the hour marks, whichever ring they ended up on. */
    private fun majors(ticks: List<Tick>): List<Tick> {
        if (ticks.isEmpty()) return emptyList()
        val thickest = ticks.maxOf { it.width }
        return ticks.filter { it.width > thickest * 0.9f }
    }

    // ------------------------------------------------------- how many there are

    /** Sixty ticks, twelve of them long, is what a clock face is. */
    @Test
    fun `an untouched dial carries sixty ticks and twelve hours`() {
        val ticks = ticksOf(dial(12, true))
        assertEquals("the minute ring is not sixty ticks", 60, ticks.size)
        assertEquals("the hours are not twelve of them", 12, majors(ticks).size)
    }

    /**
     * Fewer marks thins the hours out and leaves the minute ring alone.
     *
     * The two settings are separate on purpose: a dial with four hour marks
     * and all sixty minutes is a real and common face, and one that dropped
     * the minutes along with the hours would have made the second switch
     * pointless.
     */
    @Test
    fun `asking for fewer marks thins out the hours, not the minutes`() {
        for ((asked, expected) in listOf(12 to 12, 6 to 6, 4 to 4)) {
            val ticks = ticksOf(dial(asked, true))
            assertEquals("the minute ring changed at $asked marks", 60, ticks.size)
            assertEquals("$asked marks did not give $expected long ticks", expected, majors(ticks).size)
        }
    }

    /**
     * And they are spread evenly, not taken off one side.
     *
     * Four marks are the quarters. A version that filtered by "every third
     * hour" starting anywhere but midnight would still produce four marks
     * and would put them at one, four, seven and ten.
     */
    @Test
    fun `four marks are the quarters of the dial`() {
        val at = majors(ticksOf(dial(4, true))).map { Math.round(it.angle / 30f) * 30 }.sorted()
        assertEquals("four marks are not at twelve, three, six and nine", listOf(0, 90, 180, 270), at)
    }

    /** Six marks are every other hour. */
    @Test
    fun `six marks are every other hour`() {
        val at = majors(ticksOf(dial(6, true))).map { Math.round(it.angle / 30f) * 30 }.sorted()
        assertEquals(listOf(0, 60, 120, 180, 240, 300), at)
    }

    // ------------------------------------------------------ the minute switch

    /** With the minute ticks off, the hours are all that is left. */
    @Test
    fun `switching the minute ticks off leaves only the hours`() {
        assertEquals(12, ticksOf(dial(12, false)).size)
        assertEquals(4, ticksOf(dial(4, false)).size)
    }

    /**
     * And the hours stay on the chapter ring when they do.
     *
     * This is the one the drawing got wrong. The hours have a fallback ring
     * further in, for dials whose hours do not fall on fifths of the circle
     * and so cannot borrow a minute tick — and that inner ring is where the
     * numerals live. It was fine while it only ever fired on a twenty-four
     * hour face. The moment the minute ticks could be switched off on an
     * ordinary twelve-hour face it fired there too, and every numeral came
     * up with a tick struck through it.
     */
    @Test
    fun `the hour marks keep off the numerals`() {
        val v = dial(12, false)
        val ticks = ticksOf(v)
        assertTrue("no ticks to check", ticks.isNotEmpty())
        // How far out the numerals sit, and how much room they take.
        val twelve = v.numeralPositionForTest(12)
        val numeralRadius = hypot(twelve.x - centre, twelve.y - centre)
        val half = v.numeralSizeForTest() / 2f
        for (tick in ticks) {
            assertTrue(
                "an hour mark reaches in to ${tick.inner}, where the numerals sit at $numeralRadius",
                tick.inner > numeralRadius + half
            )
        }
    }

    /**
     * Off is off. With the minutes off as well the ring is empty; with them
     * on, the sixty are still there and none of them has been promoted —
     * every tick is the same thin one, because there is no hour left for a
     * long tick to stand on.
     */
    @Test
    fun `no marks at all leaves the ring bare`() {
        assertEquals("something is still drawn on a dial asked for nothing", 0, ticksOf(dial(0, false)).size)
        val minutesOnly = ticksOf(dial(0, true))
        assertEquals("the minute ring went with the hours", 60, minutesOnly.size)
        val thinnest = minutesOnly.minOf { it.width }
        assertEquals(
            "an hour tick survived on a dial with no hour marks",
            60, minutesOnly.count { it.width <= thinnest * 1.01f }
        )
    }

    // ----------------------------------------------------------- the numerals

    /**
     * The numerals follow the marks, which is what the user asked for in so
     * many words: a numeral over nothing is worse than no numeral.
     */
    @Test
    fun `the numerals follow the marks`() {
        assertEquals(
            "a twelve-mark dial is not numbered one to twelve",
            (1..12).toList(),
            wordsOf(dial(12, true)).mapNotNull { it.toIntOrNull() }.sorted()
        )
        assertEquals(
            "a four-mark dial is not numbered at the quarters",
            listOf(3, 6, 9, 12),
            wordsOf(dial(4, true)).mapNotNull { it.toIntOrNull() }.sorted()
        )
        assertEquals(
            "a six-mark dial is not numbered every other hour",
            listOf(2, 4, 6, 8, 10, 12),
            wordsOf(dial(6, true)).mapNotNull { it.toIntOrNull() }.sorted()
        )
        assertEquals(
            "a dial with no marks still has numerals on it",
            emptyList<Int>(),
            wordsOf(dial(0, true)).mapNotNull { it.toIntOrNull() }
        )
    }

    /** And each numeral stands over its own mark, not between two. */
    @Test
    fun `every numeral stands over a mark`() {
        for (marks in listOf(12, 6, 4)) {
            val v = dial(marks, true)
            val at = majors(ticksOf(v)).map { it.angle }
            for (hour in wordsOf(v).mapNotNull { it.toIntOrNull() }) {
                val angle = (hour % 12) * 30f
                assertTrue(
                    "the $hour on a $marks-mark dial has no mark under it",
                    at.any { gap(it, angle) < 1f }
                )
            }
        }
    }
}

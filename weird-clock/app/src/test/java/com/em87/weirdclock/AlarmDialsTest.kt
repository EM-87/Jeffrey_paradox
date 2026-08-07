package com.em87.weirdclock

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The little faces on an alarm card.
 *
 * An alarm can be one time or up to four, and the four are one concept —
 * pills at eight, twelve, four and eight — not four alarms. Keeping the
 * alarm's own time large and its repetitions small said the opposite, and
 * meant the four could never line up as the 2×2 they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmDialsTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun cards() = AlarmCards(
        host = context,
        prefs = PreferenceManager.getDefaultSharedPreferences(context),
        alarms = emptyList(),
        dialTheme = { ClockThemes.MIDNIGHT },
        hoursOnDial = { 12 },
        dialShape = { ClockView.DialShape.CIRCLE },
        onToggled = { _, _ -> },
        onOpen = { }
    )

    /** Every face on the card, however deeply it is nested. */
    private fun facesIn(view: View): List<ClockView> = when (view) {
        is ClockView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { facesIn(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun sizeOf(face: ClockView): Pair<Int, Int> {
        val p = face.layoutParams
        return p.width to p.height
    }

    private fun row(vararg times: Pair<Int, Int>): LinearLayout {
        val row = LinearLayout(context)
        cards().fillDials(row, times.toList())
        return row
    }

    @Test
    fun `one time is one face`() {
        val faces = facesIn(row(7 to 0))
        assertEquals(1, faces.size)
    }

    /**
     * The bug: with four times there were four faces, but one of them was
     * still the big one and the other three huddled beside it.
     */
    @Test
    fun `four times are four faces, all the same size`() {
        val faces = facesIn(row(8 to 0, 12 to 0, 16 to 0, 20 to 0))
        assertEquals(4, faces.size)
        val sizes = faces.map { sizeOf(it) }.distinct()
        assertEquals("all four must be the same size: $sizes", 1, sizes.size)
    }

    @Test
    fun `and two, and three`() {
        for (times in listOf(
            arrayOf(8 to 0, 20 to 0),
            arrayOf(8 to 0, 14 to 0, 20 to 0)
        )) {
            val faces = facesIn(row(*times))
            assertEquals(times.size, faces.size)
            assertEquals(
                "no face leads when there is more than one",
                1, faces.map { sizeOf(it) }.distinct().size
            )
        }
    }

    /**
     * However many there are, the block they sit in is the same square —
     * otherwise a card with repetitions reaches further than one without,
     * and the icon rows underneath stop lining up down the list.
     */
    @Test
    fun `the block is the same size however many faces are in it`() {
        val single = row(7 to 0).getChildAt(0).layoutParams
        for (count in 2..4) {
            val times = (0 until count).map { (6 + it * 4) to 0 }.toTypedArray()
            val block = row(*times).getChildAt(0).layoutParams
            assertEquals("width with $count", single.width, block.width)
            assertEquals("height with $count", single.height, block.height)
        }
    }

    /** And a face in the mosaic really is smaller than a lone one. */
    @Test
    fun `a mosaic face is smaller than a face on its own`() {
        val alone = sizeOf(facesIn(row(7 to 0)).single()).first
        val quartered = sizeOf(facesIn(row(8 to 0, 12 to 0, 16 to 0, 20 to 0)).first()).first
        assertTrue("$quartered should be well under $alone", quartered < alone)
    }
}

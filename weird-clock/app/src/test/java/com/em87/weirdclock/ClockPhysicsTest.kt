package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dial's falling pieces, driven through the only door they have.
 *
 * This engine cannot be lifted out of ClockView — the fallen pieces collide
 * with the hands still mounted on the dial, so the physics has to ask where
 * the minute hand is right now and how thick it is, which is the dial's
 * knowledge and nobody else's. What can be done instead is to run it: knock
 * the hands off, drive a few hundred frames, and let anything that throws,
 * hangs or leaves a number that is not a number be the failure.
 *
 * It exists because the physics is about to be worked on. A net that catches
 * the crash class is worth more before the changes than after them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClockPhysicsTest {

    private fun dial(shape: ClockView.DialShape = ClockView.DialShape.CIRCLE): ClockView {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ClockView(context).apply {
            dialShape = shape
            showDate = true
            layout(0, 0, 720, 720)
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
        }
    }

    /** Draws [frames] times, which is what drives the simulation forward. */
    private fun run(view: ClockView, frames: Int) {
        val bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        repeat(frames) { view.draw(canvas) }
        bitmap.recycle()
    }

    @Test
    fun `a dial is tidy until it is knocked`() {
        val view = dial()
        assertFalse(view.isDisarranged())
        view.knockHandsOff()
        assertTrue(view.isDisarranged())
    }

    @Test
    fun `a knocked dial survives a long fall`() {
        // Three hundred frames is five seconds of settling: long enough for
        // the heap to come to rest, and for anything that divides by a gap
        // that has closed to have done so.
        val view = dial()
        view.knockHandsOff()
        run(view, 300)
        assertTrue("the pieces vanished", view.isDisarranged())
    }

    @Test
    fun `it survives the fall inside every dial shape`() {
        // The boundary is polygonal for four of the five, and containment is
        // solved against it on every frame for every end of every piece.
        for (shape in ClockView.DialShape.values()) {
            val view = dial(shape)
            view.knockHandsOff()
            run(view, 120)
            assertTrue(shape.name, view.isDisarranged())
        }
    }

    @Test
    fun `knocking an already-knocked dial does not pile up trouble`() {
        val view = dial()
        repeat(5) {
            view.knockHandsOff()
            run(view, 20)
        }
        assertTrue(view.isDisarranged())
    }

    @Test
    fun `putting it back together leaves nothing behind`() {
        val view = dial()
        view.knockHandsOff()
        run(view, 60)
        view.reassembleAll()
        assertFalse(view.isDisarranged())
        // And it still draws, with the pieces back on their spindles.
        run(view, 30)
        assertFalse(view.isDisarranged())
    }

    @Test
    fun `the mess copies from one dial to another`() {
        // What carries the wreckage from C0 to the stopwatch when you swipe.
        val from = dial()
        val to = dial()
        from.knockHandsOff()
        run(from, 30)
        to.syncFallenFrom(from)
        assertTrue(to.isDisarranged())
        run(to, 60)
        assertTrue(to.isDisarranged())
    }

    @Test
    fun `a dial that never laid out does not fall over`() {
        // Zero width and height: every radius is zero and every gap is
        // closed, which is the arithmetic's worst day.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = ClockView(context)
        view.knockHandsOff()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        repeat(30) { view.draw(Canvas(bitmap)) }
        bitmap.recycle()
    }
}

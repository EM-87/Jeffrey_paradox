package com.em87.weirdclock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.preference.PreferenceManager
import java.util.Calendar

/**
 * The home-screen dial as the launcher actually shows it: hands and all.
 *
 * The widget is not one picture. The dial is a bitmap and each hand is
 * another, and it is the system's own `AnalogClock` that turns the three
 * of them and stacks them up — which is the only way a widget can have a
 * hand that moves without waking the app once a minute. So
 * [WidgetRenderer.dialBitmap] draws a clock face with no hands on it, and
 * that is correct.
 *
 * It is also a trap for anybody photographing it. Every picture of the
 * widget this project has ever produced was of the dial bitmap alone, and
 * every one of them showed a clock with no hands — which somebody looking
 * at a page of them noticed and reported as a bug, reasonably, because a
 * picture of a clock with no hands is a picture of a broken clock.
 *
 * This does what the launcher does. It is only ever used for pictures; the
 * tests that measure the widget go on measuring the dial bitmap, because
 * the dial bitmap is the thing the app is responsible for.
 */
object WidgetShot {

    /** The dial with its hands on, wound to [atMs]. */
    fun wholeDial(context: Context, size: Int, atMs: Long = TimeKeeper.nowMs()): Bitmap {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val theme = ClockThemes.resolve(context, prefs.getString(Prefs.THEME, "midnight"))
        val fit = WidgetRenderer.handFitFraction(context)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val blit = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(WidgetRenderer.dialBitmap(context, size), 0f, 0f, blit)

        val at = Calendar.getInstance().apply { timeInMillis = atMs }
        val second = at.get(Calendar.SECOND).toFloat()
        val minute = at.get(Calendar.MINUTE) + second / 60f
        val hour = at.get(Calendar.HOUR_OF_DAY) % 12 + minute / 60f
        // The same three bitmaps the provider hands the launcher, at the
        // same lengths, turned the way a clock turns.
        val hands = listOf(
            WidgetRenderer.handBitmap(size, theme.hourHand, 0.52f * fit, 0.10f, 0.045f)
                to hour / 12f * 360f,
            WidgetRenderer.handBitmap(size, theme.minuteHand, 0.74f * fit, 0.12f, 0.03f)
                to minute / 60f * 360f,
            WidgetRenderer.handBitmap(size, theme.secondHand, 0.82f * fit, 0.18f, 0.012f)
                to second / 60f * 360f
        )
        for ((bitmap, degrees) in hands) {
            val turn = Matrix().apply { setRotate(degrees, size / 2f, size / 2f) }
            canvas.drawBitmap(bitmap, turn, blit)
        }
        return out
    }
}

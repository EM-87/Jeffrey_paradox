package com.em87.weirdclock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.preference.PreferenceManager

/**
 * What lives inside the system bubble: the hourglass, and nothing else.
 *
 * A bubble is a window the platform owns — it floats, it collapses to a
 * circle at the edge of the screen, it survives the app being closed, and it
 * costs no draw-over-other-apps permission because the user granted it by
 * dragging the notification into a bubble. In exchange the app gives up
 * control of where it sits and how big it is, which is why this stays as an
 * alternative to the app's own floating hourglass rather than a replacement.
 */
class BubbleActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var glass: HourglassView? = null

    private val tick = object : Runnable {
        override fun run() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@BubbleActivity)
            val endsAt = prefs.getLong(Prefs.COUNTDOWN_ENDS_AT, 0L)
            val remaining = (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            glass?.remainingMs = remaining
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val theme = ClockThemes.resolve(this, prefs.getString(Prefs.THEME, "midnight"))
        val root = FrameLayout(this).apply {
            setBackgroundColor(theme.face)
        }
        glass = HourglassView(this).apply {
            this.theme = theme
            totalMs = prefs.getLong(Prefs.COUNTDOWN_TOTAL, 60_000L).coerceAtLeast(1L)
        }
        root.addView(
            glass,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        )
        // Tapping the glass opens the timer card proper.
        root.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_OPEN_TIMER, true)
            )
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }
}

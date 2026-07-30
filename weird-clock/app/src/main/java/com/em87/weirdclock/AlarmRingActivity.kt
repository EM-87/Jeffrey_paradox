package com.em87.weirdclock

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/** Full-screen ringing UI shown over the lock screen. */
class AlarmRingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm_ring)
        SystemChrome.paint(this)
        SystemChrome.padForBars(findViewById(android.R.id.content))

        // Stopped from the notification, or given up after three minutes:
        // either way this screen has nothing left to offer.
        AlarmService.onStopped = { runOnUiThread { finish() } }

        findViewById<TextView>(R.id.alarm_time_text).text =
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

        // A countdown that has run out is not an alarm going off. Same screen,
        // but it wears a stopwatch instead of a bell, and falls back to
        // "Time's up!" rather than the app's alarm line when nothing named it.
        //
        // Asked of the service rather than of our own intent, which turned
        // out to be a stale one — see AlarmService.ringingFromTimer. The
        // extra is still read as a fallback, for the case where the service
        // has already stopped by the time this screen is built.
        val fromTimer = AlarmService.ringingFromTimer ||
            intent.getBooleanExtra(AlarmScheduler.EXTRA_FROM_TIMER, false)
        val glyph = findViewById<TextView>(R.id.ring_glyph)
        glyph.text = if (fromTimer) "⏱" else "🔔"
        glyph.contentDescription =
            getString(if (fromTimer) R.string.countdown_done else R.string.alarm_ringing)

        val subtitle = findViewById<TextView>(R.id.ring_subtitle)
        if (fromTimer) subtitle.setText(R.string.countdown_done)
        intent.getStringExtra(AlarmScheduler.EXTRA_LABEL)?.takeIf { it.isNotBlank() }?.let {
            subtitle.text = it
        }
        findViewById<Button>(R.id.stop_button).setOnClickListener {
            startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
            finish()
        }
        val snoozeButton = findViewById<Button>(R.id.snooze_button)
        val snoozeMinutes = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE, 0)
        if (snoozeMinutes > 0) {
            snoozeButton.visibility = android.view.View.VISIBLE
            snoozeButton.text = getString(R.string.alarm_snooze_fmt, snoozeMinutes)
            val sound = intent.getStringExtra(AlarmScheduler.EXTRA_SOUND) ?: Prefs.ALARM_SOUND_BELLS
            val soundUri = intent.getStringExtra(AlarmScheduler.EXTRA_SOUND_URI) ?: ""
            snoozeButton.setOnClickListener {
                AlarmScheduler.snooze(this, sound, snoozeMinutes, soundUri)
                startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
                finish()
            }
        }
    }

    override fun onDestroy() {
        AlarmService.onStopped = null
        super.onDestroy()
    }
}

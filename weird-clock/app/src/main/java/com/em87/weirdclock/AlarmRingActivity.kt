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

        // Stopped from the notification, or given up after three minutes:
        // either way this screen has nothing left to offer.
        AlarmService.onStopped = { runOnUiThread { finish() } }

        findViewById<TextView>(R.id.alarm_time_text).text =
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        intent.getStringExtra(AlarmScheduler.EXTRA_LABEL)?.takeIf { it.isNotBlank() }?.let {
            findViewById<TextView>(R.id.ring_subtitle).text = it
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

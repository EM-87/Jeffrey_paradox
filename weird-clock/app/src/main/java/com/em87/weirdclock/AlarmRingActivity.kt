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

        findViewById<TextView>(R.id.alarm_time_text).text =
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        findViewById<Button>(R.id.stop_button).setOnClickListener {
            startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
            finish()
        }
        val snoozeButton = findViewById<Button>(R.id.snooze_button)
        if (intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)) {
            snoozeButton.visibility = android.view.View.VISIBLE
            val sound = intent.getStringExtra(AlarmScheduler.EXTRA_SOUND) ?: Prefs.ALARM_SOUND_BELLS
            snoozeButton.setOnClickListener {
                AlarmScheduler.snooze(this, sound)
                startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
                finish()
            }
        }
    }
}

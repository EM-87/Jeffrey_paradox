package com.em87.weirdclock

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var clockView: ClockView
    private lateinit var prefs: SharedPreferences
    private val chimePlayer = ChimePlayer()
    private val handler = Handler(Looper.getMainLooper())

    private var bellsEnabled = false
    private var bellStyle = Prefs.BELL_STYLE_COUNT
    private var halfHourEnabled = false
    private var tickingEnabled = false

    private var lastHandledMinute = -1L
    private var lastTickedSecond = -1L

    private val soundLoop = object : Runnable {
        override fun run() {
            val nowMs = System.currentTimeMillis()

            val second = nowMs / 1000L
            if (second != lastTickedSecond) {
                lastTickedSecond = second
                if (tickingEnabled) chimePlayer.playTick()
            }

            val minute = nowMs / 60000L
            if (minute != lastHandledMinute) {
                lastHandledMinute = minute
                onMinuteBoundary()
            }

            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        clockView = findViewById(R.id.clock_view)
        findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        applyPreferences()
        // Prime the boundaries so opening the app never triggers an
        // immediate chime or tick.
        lastHandledMinute = System.currentTimeMillis() / 60000L
        lastTickedSecond = System.currentTimeMillis() / 1000L
        handler.post(soundLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(soundLoop)
        super.onPause()
    }

    override fun onDestroy() {
        chimePlayer.release()
        super.onDestroy()
    }

    private fun applyPreferences() {
        clockView.use24hDial = prefs.getBoolean(Prefs.DIAL_24H, false)
        clockView.showSecondHand = prefs.getBoolean(Prefs.SECOND_HAND, true)
        clockView.smoothSeconds = prefs.getBoolean(Prefs.SMOOTH_SECONDS, false)
        clockView.showDecimalHand = prefs.getBoolean(Prefs.DECIMAL_HAND, false)
        clockView.mirrored = prefs.getBoolean(Prefs.MIRROR, false)
        clockView.numeralStyle = when (prefs.getString(Prefs.NUMERALS, Prefs.NUMERALS_ARABIC)) {
            Prefs.NUMERALS_NONE -> ClockView.NumeralStyle.NONE
            Prefs.NUMERALS_ROMAN -> ClockView.NumeralStyle.ROMAN
            else -> ClockView.NumeralStyle.ARABIC
        }

        bellsEnabled = prefs.getBoolean(Prefs.BELLS, false)
        bellStyle = prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT) ?: Prefs.BELL_STYLE_COUNT
        halfHourEnabled = prefs.getBoolean(Prefs.HALF_HOUR, false)
        tickingEnabled = prefs.getBoolean(Prefs.TICKING, false)
    }

    private fun onMinuteBoundary() {
        if (!bellsEnabled) return
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        when (now.get(Calendar.MINUTE)) {
            0 -> chimeHour(hour)
            30 -> chimeHalfHour(hour)
        }
    }

    private fun chimeHour(hourOfDay: Int) {
        when (bellStyle) {
            Prefs.BELL_STYLE_SHIPS -> {
                // Ship's bell: one bell per half hour of the current 4-hour
                // watch, struck in pairs; the watch change gets 8 bells.
                val halfHours = (hourOfDay % 4) * 2
                chimePlayer.playBellSequence(if (halfHours == 0) 8 else halfHours, pairGrouping = true)
            }
            Prefs.BELL_STYLE_SINGLE -> chimePlayer.playBellSequence(1, pairGrouping = false)
            else -> {
                val strikes = hourOfDay % 12
                chimePlayer.playBellSequence(if (strikes == 0) 12 else strikes, pairGrouping = false)
            }
        }
    }

    private fun chimeHalfHour(hourOfDay: Int) {
        when (bellStyle) {
            Prefs.BELL_STYLE_SHIPS -> {
                val halfHours = (hourOfDay % 4) * 2 + 1
                chimePlayer.playBellSequence(halfHours, pairGrouping = true)
            }
            else -> if (halfHourEnabled) {
                chimePlayer.playBellSequence(1, pairGrouping = false, frequency = ChimePlayer.HALF_HOUR_BELL_HZ)
            }
        }
    }
}

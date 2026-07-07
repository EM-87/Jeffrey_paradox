package com.em87.weirdclock

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import java.util.Calendar
import java.util.TimeZone

class MainActivity : AppCompatActivity(), ClockView.SoundListener {

    private enum class Mode { CLOCK, STOPWATCH, COUNTDOWN }

    private lateinit var clockView: ClockView
    private lateinit var worldClockView: ClockView
    private lateinit var worldClockContainer: View
    private lateinit var worldClockLabel: TextView
    private lateinit var modeButton: Button
    private lateinit var startPauseButton: Button
    private lateinit var resetButton: Button
    private lateinit var prefs: SharedPreferences
    private val chimePlayer = ChimePlayer()
    private val handler = Handler(Looper.getMainLooper())

    private var bellsEnabled = false
    private var bellStyle = Prefs.BELL_STYLE_COUNT
    private var halfHourEnabled = false
    private var tickingEnabled = false

    private var lastHandledMinute = -1L

    // Chronograph state (elapsedRealtime-based, immune to time-speed games).
    private var mode = Mode.CLOCK
    private var stopwatchAccumMs = 0L
    private var stopwatchStartedAt = 0L
    private var stopwatchRunning = false
    private var countdownRemainingMs = DEFAULT_COUNTDOWN_MS
    private var countdownEndsAt = 0L
    private var countdownRunning = false

    /** Runs on (approximately) every second boundary, so ticks stay in step. */
    private val soundLoop = object : Runnable {
        override fun run() {
            if (tickingEnabled && mode == Mode.CLOCK &&
                !clockView.isHandGrabbed() && !clockView.isSecondHandFallen()
            ) {
                chimePlayer.playTick()
            }

            if (countdownRunning && countdownRemaining() == 0L) {
                countdownRunning = false
                countdownRemainingMs = 0L
                updateChronoButtons()
                chimePlayer.playBellSequence(3, false, ChimePlayer.DAY_CHIME_HZ, 1.2, 0.3)
            }

            val minute = TimeKeeper.nowMs() / 60000L
            if (minute != lastHandledMinute) {
                lastHandledMinute = minute
                onMinuteBoundary()
            }

            handler.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        chimePlayer.prepareTick(this)
        clockView = findViewById(R.id.clock_view)
        clockView.soundListener = this
        clockView.onDialScaleChanged = { scale ->
            prefs.edit().putFloat(Prefs.DIAL_SCALE, scale).apply()
        }
        worldClockContainer = findViewById(R.id.world_clock_container)
        worldClockView = findViewById(R.id.world_clock_view)
        worldClockLabel = findViewById(R.id.world_clock_label)
        worldClockView.touchHandsEnabled = false
        worldClockView.pinchZoomEnabled = false
        worldClockView.shakeDropEnabled = false
        worldClockView.showDate = false

        modeButton = findViewById(R.id.mode_button)
        startPauseButton = findViewById(R.id.start_pause_button)
        resetButton = findViewById(R.id.reset_button)
        modeButton.setOnClickListener { cycleMode() }
        startPauseButton.setOnClickListener { toggleStartPause() }
        resetButton.setOnClickListener { resetChrono() }
        applyMode()

        findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        applyPreferences()
        // Prime the minute boundary so opening the app never chimes, and
        // start the loop on the next second boundary so ticks land in step.
        lastHandledMinute = TimeKeeper.nowMs() / 60000L
        handler.postDelayed(soundLoop, 1000L - (System.currentTimeMillis() % 1000L))
    }

    override fun onPause() {
        handler.removeCallbacks(soundLoop)
        ClockWidgetProvider.refreshAll(this)
        super.onPause()
    }

    override fun onDestroy() {
        chimePlayer.release()
        super.onDestroy()
    }

    private fun applyPreferences() {
        TimeKeeper.setSpeedPercent(prefs.getInt(Prefs.TIME_SPEED, 100))

        clockView.hoursOnDial = readHoursOnDial()
        clockView.showSecondHand = prefs.getBoolean(Prefs.SECOND_HAND, true)
        clockView.smoothSeconds = prefs.getBoolean(Prefs.SMOOTH_SECONDS, false)
        clockView.mirrored = prefs.getBoolean(Prefs.MIRROR, false)
        clockView.numeralStyle = readNumeralStyle()
        clockView.fastHand = when (prefs.getString(Prefs.FAST_HAND, Prefs.FAST_HAND_NONE)) {
            Prefs.FAST_HAND_TENTHS -> ClockView.FastHandMode.TENTHS
            Prefs.FAST_HAND_DECIMAL_MINUTE -> ClockView.FastHandMode.DECIMAL_MINUTE
            else -> ClockView.FastHandMode.NONE
        }
        clockView.theme = ClockThemes.byKey(prefs.getString(Prefs.THEME, "midnight"))
        clockView.showDate = prefs.getBoolean(Prefs.SHOW_DATE, false)
        clockView.dateFormatStyle = when (prefs.getString(Prefs.DATE_FORMAT, Prefs.DATE_FORMAT_NUMBER)) {
            Prefs.DATE_FORMAT_TEXT -> ClockView.DateFormatStyle.TEXT
            Prefs.DATE_FORMAT_ROMAN -> ClockView.DateFormatStyle.ROMAN
            else -> ClockView.DateFormatStyle.NUMBER
        }
        clockView.touchHandsEnabled = prefs.getBoolean(Prefs.TOUCH_HANDS, true)
        clockView.pinchZoomEnabled = prefs.getBoolean(Prefs.PINCH_ZOOM, true)
        clockView.shakeDropEnabled = prefs.getBoolean(Prefs.SHAKE_DROP, true)
        clockView.dialScale = prefs.getFloat(Prefs.DIAL_SCALE, 1f)

        val worldOn = prefs.getBoolean(Prefs.WORLD_CLOCK, false)
        worldClockContainer.visibility = if (worldOn) View.VISIBLE else View.GONE
        if (worldOn) {
            val tzId = prefs.getString(Prefs.WORLD_TZ, "UTC") ?: "UTC"
            worldClockView.timeZone = TimeZone.getTimeZone(tzId)
            worldClockView.theme = clockView.theme
            worldClockView.hoursOnDial = clockView.hoursOnDial
            worldClockView.numeralStyle = readNumeralStyle()
            worldClockLabel.text = tzId.substringAfterLast('/').replace('_', ' ')
        }

        bellsEnabled = prefs.getBoolean(Prefs.BELLS, false)
        bellStyle = prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT) ?: Prefs.BELL_STYLE_COUNT
        halfHourEnabled = prefs.getBoolean(Prefs.HALF_HOUR, false)
        tickingEnabled = prefs.getBoolean(Prefs.TICKING, false)
    }

    private fun readNumeralStyle(): ClockView.NumeralStyle =
        when (prefs.getString(Prefs.NUMERALS, Prefs.NUMERALS_ARABIC)) {
            Prefs.NUMERALS_NONE -> ClockView.NumeralStyle.NONE
            Prefs.NUMERALS_ROMAN -> ClockView.NumeralStyle.ROMAN
            else -> ClockView.NumeralStyle.ARABIC
        }

    private fun readHoursOnDial(): Int {
        val preset = prefs.getString(Prefs.HOURS_PRESET, "12") ?: "12"
        return if (preset == Prefs.HOURS_CUSTOM_VALUE) {
            prefs.getInt(Prefs.HOURS_CUSTOM, 12)
        } else {
            preset.toIntOrNull() ?: 12
        }
    }

    // ------------------------------------------------- chronograph modes

    private fun cycleMode() {
        mode = when (mode) {
            Mode.CLOCK -> Mode.STOPWATCH
            Mode.STOPWATCH -> Mode.COUNTDOWN
            Mode.COUNTDOWN -> Mode.CLOCK
        }
        applyMode()
        if (mode == Mode.COUNTDOWN && countdownRemainingMs == DEFAULT_COUNTDOWN_MS && !countdownRunning) {
            showCountdownPicker()
        }
    }

    private fun applyMode() {
        when (mode) {
            Mode.CLOCK -> {
                clockView.chronoProvider = null
                modeButton.setText(R.string.mode_clock)
                startPauseButton.visibility = View.GONE
                resetButton.visibility = View.GONE
            }
            Mode.STOPWATCH -> {
                clockView.chronoProvider = { stopwatchElapsed() }
                modeButton.setText(R.string.mode_stopwatch)
                startPauseButton.visibility = View.VISIBLE
                resetButton.visibility = View.VISIBLE
            }
            Mode.COUNTDOWN -> {
                clockView.chronoProvider = { countdownRemaining() }
                modeButton.setText(R.string.mode_countdown)
                startPauseButton.visibility = View.VISIBLE
                resetButton.visibility = View.VISIBLE
            }
        }
        updateChronoButtons()
    }

    private fun stopwatchElapsed(): Long =
        stopwatchAccumMs + if (stopwatchRunning) SystemClock.elapsedRealtime() - stopwatchStartedAt else 0L

    private fun countdownRemaining(): Long =
        if (countdownRunning) (countdownEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        else countdownRemainingMs

    private fun toggleStartPause() {
        when (mode) {
            Mode.STOPWATCH -> {
                if (stopwatchRunning) {
                    stopwatchAccumMs = stopwatchElapsed()
                    stopwatchRunning = false
                } else {
                    stopwatchStartedAt = SystemClock.elapsedRealtime()
                    stopwatchRunning = true
                }
            }
            Mode.COUNTDOWN -> {
                if (countdownRunning) {
                    countdownRemainingMs = countdownRemaining()
                    countdownRunning = false
                } else if (countdownRemaining() > 0L) {
                    countdownEndsAt = SystemClock.elapsedRealtime() + countdownRemainingMs
                    countdownRunning = true
                }
            }
            Mode.CLOCK -> Unit
        }
        updateChronoButtons()
    }

    private fun resetChrono() {
        when (mode) {
            Mode.STOPWATCH -> {
                stopwatchRunning = false
                stopwatchAccumMs = 0L
                updateChronoButtons()
            }
            Mode.COUNTDOWN -> showCountdownPicker()
            Mode.CLOCK -> Unit
        }
    }

    private fun updateChronoButtons() {
        val running = when (mode) {
            Mode.STOPWATCH -> stopwatchRunning
            Mode.COUNTDOWN -> countdownRunning
            Mode.CLOCK -> false
        }
        startPauseButton.setText(if (running) R.string.chrono_pause else R.string.chrono_start)
    }

    private fun showCountdownPicker() {
        val minutes = NumberPicker(this).apply {
            minValue = 0
            maxValue = 120
            value = ((countdownRemainingMs / 60000L).toInt()).coerceIn(0, 120)
        }
        val seconds = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = ((countdownRemainingMs / 1000L) % 60L).toInt()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(minutes)
            addView(seconds)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.countdown_set_title)
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                countdownRunning = false
                countdownRemainingMs = (minutes.value * 60L + seconds.value) * 1000L
                updateChronoButtons()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------- scheduled chimes

    private fun onMinuteBoundary() {
        if (!bellsEnabled) return
        val now = Calendar.getInstance()
        now.timeInMillis = TimeKeeper.nowMs()
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
                chimePlayer.playBellSequence(
                    if (halfHours == 0) 8 else halfHours,
                    pairGrouping = true,
                    frequency = ChimePlayer.SHIPS_HZ,
                    ringSeconds = 2.0
                )
            }
            Prefs.BELL_STYLE_SINGLE -> chimePlayer.playBellSequence(
                1, pairGrouping = false,
                frequency = ChimePlayer.GONG_HZ, ringSeconds = 4.5
            )
            else -> {
                val strikes = hourOfDay % 12
                chimePlayer.playBellSequence(
                    if (strikes == 0) 12 else strikes,
                    pairGrouping = false,
                    frequency = ChimePlayer.GRANDFATHER_HZ,
                    ringSeconds = 3.0,
                    interval = 1.3
                )
            }
        }
    }

    private fun chimeHalfHour(hourOfDay: Int) {
        when (bellStyle) {
            Prefs.BELL_STYLE_SHIPS -> {
                val halfHours = (hourOfDay % 4) * 2 + 1
                chimePlayer.playBellSequence(
                    halfHours, pairGrouping = true,
                    frequency = ChimePlayer.SHIPS_HZ, ringSeconds = 2.0
                )
            }
            else -> if (halfHourEnabled) {
                chimePlayer.playBellSequence(
                    1, pairGrouping = false,
                    frequency = ChimePlayer.HALF_HOUR_BELL_HZ, ringSeconds = 1.5
                )
            }
        }
    }

    // -------------------------------------- winding-interaction sounds

    override fun onTickCrossed() {
        chimePlayer.playTick()
    }

    override fun onHourCrossed() {
        chimePlayer.playBellSequence(
            1, pairGrouping = false,
            frequency = ChimePlayer.WINDING_BELL_HZ, ringSeconds = 0.9
        )
    }

    override fun onDayCrossed() {
        chimePlayer.playBellSequence(
            3, pairGrouping = false,
            frequency = ChimePlayer.DAY_CHIME_HZ, ringSeconds = 0.8, interval = 0.18
        )
    }

    override fun onHandMounted() {
        chimePlayer.playTick()
        chimePlayer.playBellSequence(
            1, pairGrouping = false,
            frequency = ChimePlayer.DAY_CHIME_HZ, ringSeconds = 0.5
        )
    }

    override fun onExploded() {
        chimePlayer.playBellSequence(
            2, pairGrouping = false,
            frequency = 150.0, ringSeconds = 2.0, interval = 0.12
        )
    }

    companion object {
        private const val DEFAULT_COUNTDOWN_MS = 5 * 60_000L
    }
}

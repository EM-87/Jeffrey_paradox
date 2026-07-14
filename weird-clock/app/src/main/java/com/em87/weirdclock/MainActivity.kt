package com.em87.weirdclock

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity(), ClockView.SoundListener {

    private enum class Mode { CLOCK, STOPWATCH, COUNTDOWN }

    private lateinit var pager: ViewPager2
    private lateinit var prefs: SharedPreferences
    private val chimePlayer = ChimePlayer()
    private val handler = Handler(Looper.getMainLooper())

    // Clock page views (bound when the pager creates the page).
    private var clockView: ClockView? = null
    private var worldClockView: ClockView? = null
    private var worldClockContainer: View? = null
    private var worldClockLabel: TextView? = null
    private var modeButton: Button? = null
    private var startPauseButton: Button? = null
    private var resetButton: Button? = null

    // Alarms page views.
    private var alarmsRecycler: RecyclerView? = null
    private var alarmsEmpty: TextView? = null
    private val alarms = mutableListOf<Alarm>()
    private val alarmsAdapter = AlarmAdapter()

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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Runs on (approximately) every second boundary, so ticks stay in step. */
    private val soundLoop = object : Runnable {
        override fun run() {
            val cv = clockView
            if (tickingEnabled && mode == Mode.CLOCK && cv != null &&
                !cv.isHandGrabbed() && !cv.isSecondHandFallen()
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
        alarms.addAll(AlarmStore.load(this))
        sortAlarms()

        pager = findViewById(R.id.pager)
        pager.offscreenPageLimit = 1
        pager.adapter = PagerAdapter()
        if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) {
            pager.post { pager.setCurrentItem(1, false) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) {
            pager.currentItem = 1
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

    // -------------------------------------------------------------- pages

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class PagerAdapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = 2

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = if (viewType == 0) {
                inflater.inflate(R.layout.page_clock, parent, false).also { bindClockPage(it) }
            } else {
                inflater.inflate(R.layout.page_alarms, parent, false).also { bindAlarmsPage(it) }
            }
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit
    }

    private fun bindClockPage(root: View) {
        clockView = root.findViewById<ClockView>(R.id.clock_view).also {
            it.soundListener = this
            it.onDialScaleChanged = { scale ->
                prefs.edit().putFloat(Prefs.DIAL_SCALE, scale).apply()
            }
        }
        worldClockContainer = root.findViewById(R.id.world_clock_container)
        worldClockView = root.findViewById<ClockView>(R.id.world_clock_view).also {
            it.touchHandsEnabled = false
            it.pinchZoomEnabled = false
            it.shakeDropEnabled = false
            it.showDate = false
        }
        worldClockLabel = root.findViewById(R.id.world_clock_label)
        modeButton = root.findViewById<Button>(R.id.mode_button).also {
            it.setOnClickListener { cycleMode() }
        }
        startPauseButton = root.findViewById<Button>(R.id.start_pause_button).also {
            it.setOnClickListener { toggleStartPause() }
        }
        resetButton = root.findViewById<Button>(R.id.reset_button).also {
            it.setOnClickListener { resetChrono() }
        }
        root.findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        applyPreferences()
        applyMode()
    }

    private fun bindAlarmsPage(root: View) {
        alarmsRecycler = root.findViewById<RecyclerView>(R.id.alarms_recycler).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = alarmsAdapter
        }
        alarmsEmpty = root.findViewById(R.id.alarms_empty)
        root.findViewById<FloatingActionButton>(R.id.add_alarm_fab).setOnClickListener {
            showAlarmTimePicker(null)
        }
        refreshAlarmsUi()
    }

    // -------------------------------------------------------------- alarms

    private inner class AlarmHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.alarm_time)
        val sound: TextView = view.findViewById(R.id.alarm_sound)
        val enabled: SwitchCompat = view.findViewById(R.id.alarm_enabled)
        val delete: ImageButton = view.findViewById(R.id.alarm_delete)
    }

    private inner class AlarmAdapter : RecyclerView.Adapter<AlarmHolder>() {

        override fun getItemCount(): Int = alarms.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmHolder =
            AlarmHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_alarm, parent, false)
            )

        override fun onBindViewHolder(holder: AlarmHolder, position: Int) {
            val alarm = alarms[position]
            holder.time.text = String.format(Locale.US, "%02d:%02d", alarm.hour, alarm.minute)
            holder.time.alpha = if (alarm.enabled) 1f else 0.4f
            holder.time.setOnClickListener { showAlarmTimePicker(alarm) }
            holder.sound.text = soundLabel(alarm.sound)
            holder.sound.setOnClickListener {
                alarm.sound = nextSound(alarm.sound)
                persistAlarms()
            }
            holder.enabled.setOnCheckedChangeListener(null)
            holder.enabled.isChecked = alarm.enabled
            holder.enabled.setOnCheckedChangeListener { _, checked ->
                alarm.enabled = checked
                if (checked) maybeRequestNotificationPermission()
                persistAlarms()
            }
            holder.delete.setOnClickListener {
                alarms.remove(alarm)
                persistAlarms()
            }
        }
    }

    private fun soundLabel(sound: String): String = getString(
        when (sound) {
            Prefs.ALARM_SOUND_DIGITAL -> R.string.alarm_sound_digital
            Prefs.ALARM_SOUND_BABY -> R.string.alarm_sound_baby
            else -> R.string.alarm_sound_bells
        }
    )

    private fun nextSound(sound: String): String = when (sound) {
        Prefs.ALARM_SOUND_BELLS -> Prefs.ALARM_SOUND_DIGITAL
        Prefs.ALARM_SOUND_DIGITAL -> Prefs.ALARM_SOUND_BABY
        else -> Prefs.ALARM_SOUND_BELLS
    }

    private fun showAlarmTimePicker(alarm: Alarm?) {
        val initHour = alarm?.hour ?: 7
        val initMinute = alarm?.minute ?: 30
        TimePickerDialog(
            this,
            { _, h, m ->
                if (alarm == null) {
                    alarms.add(Alarm(AlarmStore.nextId(alarms), h, m, true, Prefs.ALARM_SOUND_BELLS))
                    maybeRequestNotificationPermission()
                } else {
                    alarm.hour = h
                    alarm.minute = m
                }
                persistAlarms()
            },
            initHour,
            initMinute,
            true
        ).show()
    }

    private fun sortAlarms() {
        alarms.sortBy { it.hour * 60 + it.minute }
    }

    @Suppress("NotifyDataSetChanged")
    private fun persistAlarms() {
        sortAlarms()
        AlarmStore.save(this, alarms)
        AlarmScheduler.update(this)
        refreshAlarmsUi()
    }

    private fun refreshAlarmsUi() {
        alarmsAdapter.notifyDataSetChanged()
        alarmsEmpty?.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------- preferences

    private fun applyPreferences() {
        TimeKeeper.setSpeedPercent(prefs.getInt(Prefs.TIME_SPEED, 100))
        val cv = clockView ?: return

        cv.hoursOnDial = readHoursOnDial()
        cv.showSecondHand = prefs.getBoolean(Prefs.SECOND_HAND, true)
        cv.smoothSeconds = prefs.getBoolean(Prefs.SMOOTH_SECONDS, false)
        cv.mirrored = prefs.getBoolean(Prefs.MIRROR, false)
        cv.numeralStyle = readNumeralStyle()
        cv.fastHand = when (prefs.getString(Prefs.FAST_HAND, Prefs.FAST_HAND_NONE)) {
            Prefs.FAST_HAND_TENTHS -> ClockView.FastHandMode.TENTHS
            Prefs.FAST_HAND_DECIMAL_MINUTE -> ClockView.FastHandMode.DECIMAL_MINUTE
            else -> ClockView.FastHandMode.NONE
        }
        cv.theme = ClockThemes.byKey(prefs.getString(Prefs.THEME, "midnight"))
        cv.showDate = prefs.getBoolean(Prefs.SHOW_DATE, false)
        cv.dateFormatStyle = when (prefs.getString(Prefs.DATE_FORMAT, Prefs.DATE_FORMAT_NUMBER)) {
            Prefs.DATE_FORMAT_TEXT -> ClockView.DateFormatStyle.TEXT
            Prefs.DATE_FORMAT_ROMAN -> ClockView.DateFormatStyle.ROMAN
            else -> ClockView.DateFormatStyle.NUMBER
        }
        cv.touchHandsEnabled = prefs.getBoolean(Prefs.TOUCH_HANDS, true)
        cv.pinchZoomEnabled = prefs.getBoolean(Prefs.PINCH_ZOOM, true)
        cv.shakeDropEnabled = prefs.getBoolean(Prefs.SHAKE_DROP, true)
        cv.dialScale = prefs.getFloat(Prefs.DIAL_SCALE, 1f)

        val worldOn = prefs.getBoolean(Prefs.WORLD_CLOCK, false)
        worldClockContainer?.visibility = if (worldOn) View.VISIBLE else View.GONE
        if (worldOn) {
            val tzId = prefs.getString(Prefs.WORLD_TZ, "UTC") ?: "UTC"
            worldClockView?.let {
                it.timeZone = TimeZone.getTimeZone(tzId)
                it.theme = cv.theme
                it.hoursOnDial = cv.hoursOnDial
                it.numeralStyle = readNumeralStyle()
            }
            worldClockLabel?.text = tzId.substringAfterLast('/').replace('_', ' ')
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
                clockView?.chronoProvider = null
                modeButton?.setText(R.string.mode_clock)
                startPauseButton?.visibility = View.GONE
                resetButton?.visibility = View.GONE
            }
            Mode.STOPWATCH -> {
                clockView?.chronoProvider = { stopwatchElapsed() }
                modeButton?.setText(R.string.mode_stopwatch)
                startPauseButton?.visibility = View.VISIBLE
                resetButton?.visibility = View.VISIBLE
            }
            Mode.COUNTDOWN -> {
                clockView?.chronoProvider = { countdownRemaining() }
                modeButton?.setText(R.string.mode_countdown)
                startPauseButton?.visibility = View.VISIBLE
                resetButton?.visibility = View.VISIBLE
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
        startPauseButton?.setText(if (running) R.string.chrono_pause else R.string.chrono_start)
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
        const val EXTRA_OPEN_ALARMS = "extra_open_alarms"
        private const val DEFAULT_COUNTDOWN_MS = 5 * 60_000L
    }
}

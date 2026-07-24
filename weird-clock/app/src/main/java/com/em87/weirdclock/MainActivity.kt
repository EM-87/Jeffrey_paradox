package com.em87.weirdclock

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.annotation.SuppressLint
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.hypot

class MainActivity : AppCompatActivity(), ClockView.SoundListener {

    /**
     * Two modes mirroring the two pager pages: on the clock, swiping reaches
     * the alarms; on the chronograph, the same swipe reaches the countdown
     * dial. The centered bottom button toggles between them.
     */
    private enum class Mode { CLOCK, CHRONO }

    private lateinit var pager: ViewPager2
    private lateinit var prefs: SharedPreferences
    private val chimePlayer = ChimePlayer()
    private val handler = Handler(Looper.getMainLooper())

    // Clock page views (bound when the pager creates the page).
    private var clockView: ClockView? = null
    private var bubbleLayer: FrameLayout? = null
    private var modeButton: Button? = null
    private var settingsButton: ImageButton? = null
    private var alarmSetBanner: View? = null

    // Second page views (alarms in clock mode, countdown dial in chrono mode).
    private var alarmsContainer: View? = null
    private var countdownContainer: View? = null
    private var alarmsRecycler: RecyclerView? = null
    private var alarmsEmpty: TextView? = null
    private var countdownClockView: ClockView? = null

    // Third page views (calendar in clock mode, sand hourglass in chrono).
    private var calendarContainer: View? = null
    private var hourglassContainer: View? = null
    private var calendarView: CalendarPageView? = null
    private var s3Sand: SandHourglassView? = null
    private var s3DurationGroup: com.google.android.material.button.MaterialButtonToggleGroup? = null
    private var updatingDurationChecks = false
    private var sandBlocked = false
    private var lastPage = 0

    // Calendar reminders (one-shot dated alarms).
    private val reminders = mutableListOf<Reminder>()

    // Alarm-time setting on the clock dial with the wind-to-set engine.
    private var alarmSetActive = false
    private var alarmBeingSet: Alarm? = null
    private var alarmWorkingMs = 0L

    // Stable provider instances: recreating these lambdas on every
    // applyMode() made the ClockView setter think the mode changed,
    // restarting transitions and wiping any winding in progress.
    private val stopwatchProvider: () -> Long = { stopwatchElapsed() }
    private val alarmTimeProvider: () -> Long = { alarmWorkingMs }
    private val alarms = mutableListOf<Alarm>()
    private val alarmsAdapter = AlarmAdapter()

    private var bellsEnabled = false
    private var bellStyle = Prefs.BELL_STYLE_COUNT
    private var halfHourEnabled = false
    private var tickingEnabled = false
    private var countdownPersistent = true

    private var lastHandledMinute = -1L

    // Chronograph state (elapsedRealtime-based, immune to time-speed games).
    private var mode = Mode.CLOCK
    private var stopwatchAccumMs = 0L
    private var stopwatchStartedAt = 0L
    private var stopwatchRunning = false
    private var countdownRemainingMs = DEFAULT_COUNTDOWN_MS
    private var countdownEndsAt = 0L
    private var countdownRunning = false
    private var countdownTotalMs = DEFAULT_COUNTDOWN_MS

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var locationAskedThisRun = false
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) applyPreferences()
        }

    /** Alarm whose custom sound file is being picked, while SAF is open. */
    private var soundPickTarget: Alarm? = null
    private val soundPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val alarm = soundPickTarget
            soundPickTarget = null
            if (alarm != null) {
                if (uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        // Not persistable; the URI may still work until reboot.
                    }
                    alarm.sound = Prefs.ALARM_SOUND_CUSTOM
                    alarm.soundUri = uri.toString()
                } else if (alarm.soundUri.isBlank()) {
                    // Picker dismissed with nothing chosen: back to bells.
                    alarm.sound = Prefs.ALARM_SOUND_BELLS
                }
                persistAlarms()
            }
        }

    /** Whether the dial is currently wearing its dimmed night colors. */
    private var appliedNightDim = false

    /** Last seen uiMode, to recreate on system light/dark changes. */
    private var lastUiMode = 0

    // ------------------------------------------------- hourglass flip (S3)

    /**
     * Turning the phone upside down turns the hourglass over: the sand that
     * already fell becomes the sand still to fall — remaining and elapsed
     * swap, exactly like flipping a real hourglass mid-run.
     */
    private var sensorManager: SensorManager? = null
    private var deviceInverted = false
    private var flipLowPassX = 0f
    private var flipLowPassY = 9.81f
    private val flipListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            flipLowPassX = flipLowPassX * 0.8f + event.values[0] * 0.2f
            flipLowPassY = flipLowPassY * 0.8f + event.values[1] * 0.2f
            val nowInverted = when {
                flipLowPassY < -6f -> true
                flipLowPassY > 6f -> false
                else -> deviceInverted
            }
            if (nowInverted != deviceInverted) {
                deviceInverted = nowInverted
                onDeviceFlipped()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Gravity in view coordinates (unit-ish), for the bubbles' buoyancy. */
    private fun viewGravityX(): Float = -flipLowPassX / 9.81f
    private fun viewGravityY(): Float = flipLowPassY / 9.81f

    private fun onDeviceFlipped() {
        // The sand view needs no rotation: its grains obey real gravity, so
        // the pile is already physically where a flipped hourglass has it.
        // Only the clock swaps: fallen sand becomes sand still to fall.
        if (mode != Mode.CHRONO || !countdownRunning || countdownTotalMs <= 0L) return
        val newRemaining = (countdownTotalMs - countdownRemaining()).coerceAtLeast(0L)
        countdownEndsAt = SystemClock.elapsedRealtime() + newRemaining
        chimePlayer.playTick()
        updateCountdownUi()
    }

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
                updateCountdownUi()
                if (countdownPersistent) {
                    // Ring until validated: the alarm service loops the bells
                    // and the ring screen carries the stop button. The
                    // notification announces the countdown, not the time.
                    val label = getString(R.string.countdown_finished)
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, AlarmService::class.java)
                            .putExtra(AlarmScheduler.EXTRA_LABEL, label)
                    )
                    startActivity(
                        Intent(this@MainActivity, AlarmRingActivity::class.java)
                            .putExtra(AlarmScheduler.EXTRA_LABEL, label)
                    )
                } else {
                    chimePlayer.playBellSequence(3, false, ChimePlayer.DAY_CHIME_HZ, 1.2, 0.3)
                }
            }

            val minute = TimeKeeper.nowMs() / 60000L
            if (minute != lastHandledMinute) {
                lastHandledMinute = minute
                onMinuteBoundary()
            }

            // Physical time: while the sand can't reach the neck (phone flat
            // or on its side) the countdown refuses to advance.
            if (sandBlocked && countdownRunning && mode == Mode.CHRONO &&
                pager.currentItem == 2
            ) {
                countdownEndsAt += 1000L
            }

            if (countdownRunning) {
                HourglassWidgetProvider.push(this@MainActivity, countdownRemaining(), countdownTotalMs)
            }
            s3Sand?.setTime(countdownTotalMs, countdownRemaining())

            // Night falls (or lifts) while the app is open: re-dress the dial.
            if (prefs.getBoolean(Prefs.NIGHT_DIM, false) &&
                isNightNow() != appliedNightDim
            ) {
                applyPreferences()
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
        reminders.addAll(ReminderStore.load(this))

        pager = findViewById(R.id.pager)
        pager.offscreenPageLimit = 2
        pager.adapter = PagerAdapter()
        // The stopwatch and countdown dials share their chaos: fallen pieces
        // travel with the swipe instead of vanishing between cards.
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (mode == Mode.CHRONO) {
                    val main = clockView
                    val cd = countdownClockView
                    if (main != null && cd != null) {
                        if (position == 1 && lastPage == 0) cd.syncFallenFrom(main)
                        if (position == 0 && lastPage == 1) main.syncFallenFrom(cd)
                    }
                }
                lastPage = position
            }
        })
        if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) {
            pager.post { pager.setCurrentItem(1, false) }
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        maybeIntroduceFloatingHourglass()
    }

    /**
     * First run only: introduce the floating hourglass and offer the
     * draw-over-apps permission right away — buried in a settings toggle,
     * nobody would ever discover it exists.
     */
    private fun maybeIntroduceFloatingHourglass() {
        if (prefs.getBoolean(Prefs.OVERLAY_ASKED, false)) return
        prefs.edit().putBoolean(Prefs.OVERLAY_ASKED, true).apply()
        if (android.provider.Settings.canDrawOverlays(this)) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.overlay_intro_title)
            .setMessage(R.string.overlay_intro_message)
            .setPositiveButton(R.string.overlay_intro_grant) { _, _ ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton(R.string.overlay_intro_later, null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) {
            pager.currentItem = 1
        }
    }

    override fun onResume() {
        super.onResume()
        // System light/dark toggled while we were away: rebuild everything
        // so backgrounds, text colors and theme cards pick up the change.
        val uiMode = resources.configuration.uiMode
        if (lastUiMode != 0 && uiMode != lastUiMode) {
            lastUiMode = uiMode
            recreate()
            return
        }
        lastUiMode = uiMode
        // The app is visible again: the notification takes a break, and we
        // pick up anything that happened to the countdown while away.
        CountdownService.stop(this)
        when (prefs.getString(Prefs.COUNTDOWN_RESULT, null)) {
            CountdownService.RESULT_FINISHED, CountdownService.RESULT_CANCELLED -> {
                countdownRunning = false
                countdownRemainingMs = 0L
                prefs.edit().remove(Prefs.COUNTDOWN_RESULT).apply()
                updateCountdownUi()
            }
        }
        // "Put everything back" panic button from settings.
        if (prefs.getBoolean(Prefs.REASSEMBLE_PENDING, false)) {
            prefs.edit()
                .putBoolean(Prefs.REASSEMBLE_PENDING, false)
                .putFloat(Prefs.DIAL_SCALE, 1f)
                .apply()
            clockView?.reassembleAll()
            countdownClockView?.reassembleAll()
            for (b in bubbles) b.clock.reassembleAll()
            dockBubbles()
        }
        applyPreferences()
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(flipListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        handler.removeCallbacks(bubblePhysics)
        handler.post(bubblePhysics)
        // Prime the minute boundary so opening the app never chimes, and
        // start the loop on the next second boundary so ticks land in step.
        lastHandledMinute = TimeKeeper.nowMs() / 60000L
        handler.postDelayed(soundLoop, 1000L - (System.currentTimeMillis() % 1000L))
    }

    override fun onPause() {
        handler.removeCallbacks(soundLoop)
        handler.removeCallbacks(bubblePhysics)
        sensorManager?.unregisterListener(flipListener)
        ClockWidgetProvider.refreshAll(this)
        // A running countdown stays visible from outside the app as an
        // ongoing notification with live remaining time and a progress bar.
        if (countdownRunning) {
            CountdownService.start(this, countdownEndsAt, countdownTotalMs)
        }
        super.onPause()
    }

    override fun onDestroy() {
        chimePlayer.release()
        super.onDestroy()
    }

    // -------------------------------------------------------------- pages

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class PagerAdapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = 3

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = when (viewType) {
                0 -> inflater.inflate(R.layout.page_clock, parent, false).also { bindClockPage(it) }
                1 -> inflater.inflate(R.layout.page_second, parent, false).also { bindSecondPage(it) }
                else -> inflater.inflate(R.layout.page_third, parent, false).also { bindThirdPage(it) }
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
                countdownClockView?.dialScale = scale
                s3Sand?.glassScale = scale
            }
            it.onHorizontalSwipe = { fingerRight ->
                // On the stopwatch, a swipe over the dial pages to the
                // countdown, just like the clock pages to the alarms.
                if (mode == Mode.CHRONO && !alarmSetActive && !fingerRight) {
                    pager.currentItem = 1
                    true
                } else {
                    false
                }
            }
            it.onChronoStartStop = { if (!alarmSetActive) toggleStartPause() }
            it.onChronoReset = {
                if (!alarmSetActive) {
                    // Real-chronograph convention: while running, the lower
                    // pusher records a lap (a frozen ghost second hand);
                    // stopped, it resets and clears the laps.
                    if (stopwatchRunning) {
                        clockView?.recordLap()
                        chimePlayer.playTick()
                    } else {
                        resetChrono()
                        clockView?.clearLaps()
                    }
                }
            }
            it.onChronoAdjusted = { ms -> if (alarmSetActive) alarmWorkingMs = ms }
            it.onCrownTap = { chimePlayer.playCuckoo() }
            // A knock hard enough to shed hands rattles the whole scene:
            // bubbles break loose and their little hands fall off too.
            it.onKnocked = {
                freeBubbles()
                for (b in bubbles) b.clock.knockHandsOff()
            }
        }
        bubbleLayer = root.findViewById(R.id.bubble_layer)
        modeButton = root.findViewById<Button>(R.id.mode_button).also {
            it.setOnClickListener { cycleMode() }
        }
        settingsButton = root.findViewById<ImageButton>(R.id.settings_button).also { button ->
            button.setOnClickListener {
                // Let settings know whether the panic button should be offered.
                prefs.edit()
                    .putBoolean(
                        Prefs.NEEDS_REASSEMBLY,
                        clockView?.isDisarranged() == true ||
                            countdownClockView?.isDisarranged() == true
                    )
                    .apply()
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        alarmSetBanner = root.findViewById(R.id.alarm_set_banner)
        root.findViewById<Button>(R.id.alarm_set_confirm).setOnClickListener { confirmAlarmSet() }
        root.findViewById<Button>(R.id.alarm_set_cancel).setOnClickListener { exitAlarmSetMode() }
        applyPreferences()
        applyMode()
    }

    private fun bindSecondPage(root: View) {
        alarmsContainer = root.findViewById(R.id.alarms_container)
        countdownContainer = root.findViewById(R.id.countdown_container)

        alarmsRecycler = root.findViewById<RecyclerView>(R.id.alarms_recycler).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = alarmsAdapter
        }
        alarmsEmpty = root.findViewById(R.id.alarms_empty)
        root.findViewById<FloatingActionButton>(R.id.add_alarm_fab).setOnClickListener {
            enterAlarmSetMode(null)
        }
        refreshAlarmsUi()

        countdownClockView = root.findViewById<ClockView>(R.id.countdown_clock_view).also {
            it.soundListener = this
            it.shakeDropEnabled = false
            it.showDate = false
            it.chronoProvider = { countdownRemaining() }
            it.chronoButtons = true
            it.onChronoStartStop = { toggleCountdown() }
            it.onChronoReset = { resetCountdown() }
            it.onChronoAdjusted = { ms ->
                if (!countdownRunning) {
                    countdownRemainingMs = ms
                    // A freshly set countdown is all sand-up-top.
                    countdownTotalMs = ms.coerceAtLeast(1000L)
                    updateCountdownUi()
                }
            }
            // Pinching either dial resizes both: S2 stays the same size as
            // C1/S1 instead of living in its own scale.
            it.onDialScaleChanged = { scale ->
                prefs.edit().putFloat(Prefs.DIAL_SCALE, scale).apply()
                clockView?.dialScale = scale
                s3Sand?.glassScale = scale
            }
            it.onHorizontalSwipe = { fingerRight ->
                // Swiping back over the countdown dial returns to the
                // stopwatch; swiping on reaches the beat counter (S3).
                pager.currentItem = if (fingerRight) 0 else 2
                true
            }
            it.onCrownTap = { chimePlayer.playCuckoo() }
        }
        applyPreferences()
        applyMode()
    }

    private fun bindThirdPage(root: View) {
        calendarContainer = root.findViewById(R.id.calendar_container)
        hourglassContainer = root.findViewById(R.id.hourglass_container)
        calendarView = root.findViewById<CalendarPageView>(R.id.calendar_view).also {
            it.onDayTap = { day -> onCalendarDayTap(day) }
            it.onMonthChanged = { refreshCalendarMarks() }
            it.onWeekStartChanged = { monday ->
                prefs.edit().putBoolean(Prefs.WEEK_START_MONDAY, monday).apply()
            }
        }
        s3Sand = root.findViewById<SandHourglassView>(R.id.s3_sand).also {
            it.onFlowBlocked = { blocked -> sandBlocked = blocked }
            // The glass zooms with every other dial.
            it.onScaleChanged = { scale ->
                prefs.edit().putFloat(Prefs.DIAL_SCALE, scale).apply()
                clockView?.dialScale = scale
                countdownClockView?.dialScale = scale
            }
        }
        s3DurationGroup = root.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
            R.id.s3_duration_group
        ).also { group ->
            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (updatingDurationChecks || !isChecked) return@addOnButtonCheckedListener
                if (countdownRunning) {
                    // Locked while running; snap the checks back to reality.
                    syncS3DurationChecks()
                    return@addOnButtonCheckedListener
                }
                val minutes = when (checkedId) {
                    R.id.s3_d3 -> 3
                    R.id.s3_d5 -> 5
                    R.id.s3_d10 -> 10
                    else -> 15
                }
                countdownRemainingMs = minutes * 60_000L
                countdownTotalMs = minutes * 60_000L
                chimePlayer.playTick()
                updateCountdownUi()
            }
        }
        applyPreferences()
        applyMode()
    }

    /** Reflects the current countdown duration on the S3 preset buttons. */
    private fun syncS3DurationChecks() {
        val group = s3DurationGroup ?: return
        updatingDurationChecks = true
        when (if (countdownRunning) countdownTotalMs else countdownRemainingMs) {
            3 * 60_000L -> group.check(R.id.s3_d3)
            5 * 60_000L -> group.check(R.id.s3_d5)
            10 * 60_000L -> group.check(R.id.s3_d10)
            15 * 60_000L -> group.check(R.id.s3_d15)
            else -> group.clearChecked()
        }
        updatingDurationChecks = false
    }

    // -------------------------------------------------------------- alarms

    private inner class AlarmHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.alarm_time)
        val label: TextView = view.findViewById(R.id.alarm_label)
        val sound: TextView = view.findViewById(R.id.alarm_sound)
        val repeat: TextView = view.findViewById(R.id.alarm_repeat)
        val snooze: TextView = view.findViewById(R.id.alarm_snooze)
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
            holder.time.setOnClickListener { enterAlarmSetMode(alarm) }
            holder.sound.text = soundLabel(alarm.sound)
            holder.sound.setOnClickListener {
                val next = nextSound(alarm.sound)
                if (next == Prefs.ALARM_SOUND_CUSTOM) {
                    // Pick any audio file on the phone via SAF; the alarm
                    // keeps a persisted read permission on it.
                    alarm.sound = next
                    soundPickTarget = alarm
                    soundPickerLauncher.launch(arrayOf("audio/*"))
                } else {
                    alarm.sound = next
                    persistAlarms()
                }
            }
            holder.repeat.setText(
                when (alarm.repeat) {
                    Prefs.ALARM_REPEAT_WEEKDAYS -> R.string.alarm_repeat_weekdays
                    Prefs.ALARM_REPEAT_WEEKENDS -> R.string.alarm_repeat_weekends
                    else -> R.string.alarm_repeat_daily
                }
            )
            holder.repeat.setOnClickListener {
                alarm.repeat = when (alarm.repeat) {
                    Prefs.ALARM_REPEAT_DAILY -> Prefs.ALARM_REPEAT_WEEKDAYS
                    Prefs.ALARM_REPEAT_WEEKDAYS -> Prefs.ALARM_REPEAT_WEEKENDS
                    else -> Prefs.ALARM_REPEAT_DAILY
                }
                persistAlarms()
            }
            holder.snooze.text = if (alarm.snoozeMinutes > 0) {
                getString(R.string.alarm_snooze_min, alarm.snoozeMinutes)
            } else {
                getString(R.string.alarm_snooze_off)
            }
            holder.snooze.setOnClickListener {
                alarm.snoozeMinutes = when (alarm.snoozeMinutes) {
                    0 -> 5
                    5 -> 10
                    else -> 0
                }
                persistAlarms()
            }
            holder.label.text = alarm.label.ifBlank { getString(R.string.alarm_label_hint) }
            holder.label.alpha = if (alarm.label.isBlank()) 0.45f else 1f
            holder.label.setOnClickListener { showLabelDialog(alarm) }
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
            Prefs.ALARM_SOUND_CUSTOM -> R.string.alarm_sound_custom
            else -> R.string.alarm_sound_bells
        }
    )

    private fun nextSound(sound: String): String = when (sound) {
        Prefs.ALARM_SOUND_BELLS -> Prefs.ALARM_SOUND_DIGITAL
        Prefs.ALARM_SOUND_DIGITAL -> Prefs.ALARM_SOUND_BABY
        Prefs.ALARM_SOUND_BABY -> Prefs.ALARM_SOUND_CUSTOM
        else -> Prefs.ALARM_SOUND_BELLS
    }

    /**
     * Sets an alarm time the weird way: jump to the clock dial (C1) running
     * the countdown's wind-to-set engine, with a "Set alarm time" banner.
     * Winding the hands moves the proposed time; magnets and haptics apply.
     */
    private fun enterAlarmSetMode(alarm: Alarm?) {
        alarmBeingSet = alarm
        alarmWorkingMs = ((alarm?.hour ?: 7) * 3600L + (alarm?.minute ?: 30) * 60L) * 1000L
        alarmSetActive = true
        pager.currentItem = 0
        applyAlarmSetUi()
    }

    private fun confirmAlarmSet() {
        // Wrap into a day so over/under-winding still lands on a valid time.
        val dayMs = 86_400_000L
        val ms = ((alarmWorkingMs % dayMs) + dayMs) % dayMs
        val hour = (ms / 3_600_000L).toInt()
        val minute = (ms / 60_000L % 60L).toInt()
        val alarm = alarmBeingSet
        if (alarm == null) {
            alarms.add(Alarm(AlarmStore.nextId(alarms), hour, minute, true, Prefs.ALARM_SOUND_BELLS))
            maybeRequestNotificationPermission()
        } else {
            alarm.hour = hour
            alarm.minute = minute
        }
        persistAlarms()
        exitAlarmSetMode()
    }

    private fun exitAlarmSetMode() {
        alarmSetActive = false
        alarmBeingSet = null
        applyAlarmSetUi()
        pager.currentItem = 1
    }

    private fun applyAlarmSetUi() {
        alarmSetBanner?.visibility = if (alarmSetActive) View.VISIBLE else View.GONE
        pager.isUserInputEnabled = !alarmSetActive
        applyMode()
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
        updateAlarmMarkers()
    }

    private fun showLabelDialog(alarm: Alarm) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(alarm.label)
            setSelection(alarm.label.length)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.alarm_label_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                alarm.label = input.text.toString().trim()
                persistAlarms()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Sectograph-style: enabled alarms as accent wedges on the clock dial. */
    private fun updateAlarmMarkers() {
        val show = prefs.getBoolean(Prefs.ALARM_MARKERS, true)
        clockView?.alarmMarkers = if (!show) {
            emptyList()
        } else {
            val n = readHoursOnDial()
            val alarmAngles = alarms.filter { it.enabled }.map { alarm ->
                (alarm.hour + alarm.minute / 60f) % n / n * 360f
            }
            // Today's calendar reminders join the dial, Sectograph-style.
            val today = Calendar.getInstance().apply { timeInMillis = TimeKeeper.nowMs() }
            val reminderAngles = reminders.filter {
                it.year == today.get(Calendar.YEAR) &&
                    it.month == today.get(Calendar.MONTH) + 1 &&
                    it.day == today.get(Calendar.DAY_OF_MONTH)
            }.map { (it.hour + it.minute / 60f) % n / n * 360f }
            alarmAngles + reminderAngles
        }
    }

    // ---------------------------------------------------------- reminders

    private fun refreshCalendarMarks() {
        val cal = calendarView ?: return
        cal.markedDays = reminders
            .filter { it.year == cal.shownYear && it.month == cal.shownMonth1 }
            .map { it.day }
            .toSet()
    }

    private fun persistReminders() {
        ReminderStore.save(this, reminders)
        AlarmScheduler.update(this)
        refreshCalendarMarks()
        updateAlarmMarkers()
    }

    private fun onCalendarDayTap(day: Int) {
        val cal = calendarView ?: return
        val year = cal.shownYear
        val month = cal.shownMonth1
        val dayReminders = reminders.filter {
            it.year == year && it.month == month && it.day == day
        }
        if (dayReminders.isEmpty()) {
            showAddReminderDialog(year, month, day)
            return
        }
        val items = dayReminders.map {
            String.format(
                Locale.US, "%02d:%02d  %s",
                it.hour, it.minute,
                it.label.ifBlank { getString(R.string.reminder_untitled) }
            )
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(String.format(Locale.US, "%02d/%02d/%04d", day, month, year))
            .setItems(items) { _, which ->
                // Tapping a reminder removes it.
                reminders.remove(dayReminders[which])
                persistReminders()
                Toast.makeText(this, R.string.reminder_deleted, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.reminder_add) { _, _ ->
                showAddReminderDialog(year, month, day)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddReminderDialog(year: Int, month: Int, day: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.reminder_hint)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.reminder_add)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val label = input.text.toString().trim()
                android.app.TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        reminders.add(
                            Reminder(
                                ReminderStore.nextId(reminders),
                                year, month, day, hour, minute, label
                            )
                        )
                        persistReminders()
                        maybeRequestNotificationPermission()
                    },
                    9, 0, true
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------- world-clock bubbles

    /**
     * World clocks as bubbles: mini dials floating over the main clock.
     * Newly added ones dock in an orderly column and stay put — until you
     * drag one and give it momentum, or a moving bubble crashes into it.
     * Then they bounce off the screen edges, off each other and off the
     * main dial (shrink the dial and the bubbles get more room).
     */
    private inner class Bubble(val tzId: String, val view: View, val clock: ClockView) {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var moving = false
        var sizePx = 0f

        fun centerX() = x + sizePx / 2f
        fun centerY() = y + sizePx / 2f
        fun place() {
            view.translationX = x
            view.translationY = y
        }
    }

    private val bubbles = ArrayList<Bubble>()
    private var bubbleTzsApplied: List<String> = emptyList()

    private val bubblePhysics = object : Runnable {
        override fun run() {
            stepBubbles()
            handler.postDelayed(this, 16L)
        }
    }

    private fun selectedWorldTzs(): List<String> {
        if (!prefs.getBoolean(Prefs.WORLD_CLOCK, false)) return emptyList()
        val set = prefs.getStringSet(Prefs.WORLD_TZS, null)
        if (set != null) return set.toList().sorted()
        // Migration from the old single-city preference.
        return listOf(prefs.getString(Prefs.WORLD_TZ, "UTC") ?: "UTC")
    }

    private fun rebuildBubbles() {
        val layer = bubbleLayer ?: return
        // At full zoom about six bubbles fit; the picker enforces the cap.
        val tzs = selectedWorldTzs().take(6)
        if (tzs == bubbleTzsApplied) return
        bubbleTzsApplied = tzs
        layer.removeAllViews()
        bubbles.clear()
        val density = resources.displayMetrics.density
        val size = (108 * density).toInt()
        for (tz in tzs) {
            val clock = ClockView(this).apply {
                touchHandsEnabled = false
                pinchZoomEnabled = false
                shakeDropEnabled = false
                showDate = false
            }
            clock.timeZone = TimeZone.getTimeZone(tz)
            val label = TextView(this).apply {
                text = tz.substringAfterLast('/').replace('_', ' ')
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(clock, LinearLayout.LayoutParams(size, size))
                addView(
                    label,
                    LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
            layer.addView(
                box,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val bubble = Bubble(tz, box, clock)
            bubble.sizePx = size.toFloat()
            attachBubbleTouch(bubble)
            bubbles.add(bubble)
        }
        dockBubbles()
    }

    /** Orderly 3×2 matrix along the top ("put everything back" too). */
    private fun dockBubbles() {
        val layer = bubbleLayer ?: return
        layer.post {
            val density = resources.displayMetrics.density
            val gap = 8 * density
            val size = bubbles.firstOrNull()?.sizePx ?: return@post
            val cols = 3
            val gridW = cols * size + (cols - 1) * gap
            val startX = ((layer.width - gridW) / 2f).coerceAtLeast(4 * density)
            val startY = 8 * density
            for ((i, b) in bubbles.withIndex()) {
                b.moving = false
                b.vx = 0f
                b.vy = 0f
                b.x = startX + (i % cols) * (size + gap)
                b.y = startY + (i / cols) * (size + 30 * density)
                b.place()
            }
        }
    }

    /** A knock on the main dial shakes every bubble loose too. */
    private fun freeBubbles() {
        for (b in bubbles) {
            b.moving = true
            b.vx = (Math.random().toFloat() - 0.5f) * 400f
            b.vy = -Math.random().toFloat() * 250f
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBubbleTouch(b: Bubble) {
        var lastX = 0f
        var lastY = 0f
        var lastT = 0L
        b.view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    b.moving = false
                    b.vx = 0f
                    b.vy = 0f
                    lastX = e.rawX
                    lastY = e.rawY
                    lastT = SystemClock.uptimeMillis()
                    bubbleLayer?.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastT).coerceAtLeast(1L) / 1000f
                    val dx = e.rawX - lastX
                    val dy = e.rawY - lastY
                    b.x += dx
                    b.y += dy
                    b.vx = b.vx * 0.6f + (dx / dt) * 0.4f
                    b.vy = b.vy * 0.6f + (dy / dt) * 0.4f
                    lastX = e.rawX
                    lastY = e.rawY
                    lastT = now
                    b.place()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // A real fling turns it into a bubble; a gentle drop
                    // leaves it parked where you put it.
                    if (hypot(b.vx, b.vy) > 260f) {
                        b.moving = true
                    } else {
                        b.vx = 0f
                        b.vy = 0f
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun stepBubbles() {
        if (bubbles.isEmpty() || bubbles.none { it.moving }) return
        val layer = bubbleLayer ?: return
        val w = layer.width.toFloat()
        val h = layer.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dt = 0.016f
        val dialR = clockView?.currentDialRadius() ?: 0f
        val dialCx = w / 2f
        val dialCy = h / 2f

        for (b in bubbles) {
            if (!b.moving) continue
            // Bubbles are buoyant: free ones drift against gravity, so they
            // bob toward whatever edge is currently "up" as you tilt.
            b.vx += -viewGravityX() * BUBBLE_BUOYANCY * dt
            b.vy += -viewGravityY() * BUBBLE_BUOYANCY * dt
            b.x += b.vx * dt
            b.y += b.vy * dt
            b.vx *= 0.985f
            b.vy *= 0.985f
            val r = b.sizePx / 2f
            // Screen edges.
            if (b.x < 0f) { b.x = 0f; b.vx = -b.vx * 0.9f }
            if (b.y < 0f) { b.y = 0f; b.vy = -b.vy * 0.9f }
            if (b.x + b.sizePx > w) { b.x = w - b.sizePx; b.vx = -b.vx * 0.9f }
            if (b.y + b.sizePx > h) { b.y = h - b.sizePx; b.vy = -b.vy * 0.9f }
            // The main dial is a fixed obstacle.
            if (dialR > 0f && mode == Mode.CLOCK) {
                val dx = b.centerX() - dialCx
                val dy = b.centerY() - dialCy
                val d = hypot(dx, dy)
                val minD = dialR + r
                if (d < minD && d > 0.001f) {
                    val nx = dx / d
                    val ny = dy / d
                    b.x += nx * (minD - d)
                    b.y += ny * (minD - d)
                    val vn = b.vx * nx + b.vy * ny
                    if (vn < 0f) {
                        b.vx -= 1.85f * vn * nx
                        b.vy -= 1.85f * vn * ny
                    }
                }
            }
            // Free bubbles never park themselves: buoyancy keeps them
            // bobbing until "put everything back" pins them again.
        }

        // Bubble-bubble collisions; a resting bubble that gets hit wakes up.
        for (i in 0 until bubbles.size - 1) {
            for (j in i + 1 until bubbles.size) {
                val a = bubbles[i]
                val c = bubbles[j]
                if (!a.moving && !c.moving) continue
                val dx = c.centerX() - a.centerX()
                val dy = c.centerY() - a.centerY()
                val d = hypot(dx, dy)
                val minD = (a.sizePx + c.sizePx) / 2f
                if (d < minD && d > 0.001f) {
                    val nx = dx / d
                    val ny = dy / d
                    val push = (minD - d) / 2f
                    a.x -= nx * push
                    a.y -= ny * push
                    c.x += nx * push
                    c.y += ny * push
                    val relVn = (a.vx - c.vx) * nx + (a.vy - c.vy) * ny
                    if (relVn > 0f) {
                        val impulse = relVn * 0.92f
                        a.vx -= impulse * nx
                        a.vy -= impulse * ny
                        c.vx += impulse * nx
                        c.vy += impulse * ny
                        if (!a.moving && hypot(a.vx, a.vy) > 30f) a.moving = true
                        if (!c.moving && hypot(c.vx, c.vy) > 30f) c.moving = true
                    }
                }
            }
        }

        for (b in bubbles) if (b.moving) b.place()
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
        applySolarTime()
        val cv = clockView ?: return

        cv.hoursOnDial = readHoursOnDial()
        cv.dialShape = readDialShape()
        cv.showSecondHand = prefs.getBoolean(Prefs.SECOND_HAND, true)
        cv.smoothSeconds = prefs.getBoolean(Prefs.SMOOTH_SECONDS, false)
        cv.mirrored = prefs.getBoolean(Prefs.MIRROR, false)
        cv.numeralStyle = readNumeralStyle()
        cv.fastHand = when (prefs.getString(Prefs.FAST_HAND, Prefs.FAST_HAND_NONE)) {
            Prefs.FAST_HAND_TENTHS -> ClockView.FastHandMode.TENTHS
            Prefs.FAST_HAND_DECIMAL_MINUTE -> ClockView.FastHandMode.DECIMAL_MINUTE
            else -> ClockView.FastHandMode.NONE
        }
        // Night mode: after 22:00 (and before 07:00) the whole outfit dims
        // to 30% so the bedroom stays dark.
        appliedNightDim = prefs.getBoolean(Prefs.NIGHT_DIM, false) && isNightNow()
        val resolvedTheme = ClockThemes.resolve(this, prefs.getString(Prefs.THEME, "midnight"))
            .let { if (appliedNightDim) ClockThemes.dim(it) else it }
        cv.theme = resolvedTheme
        cv.showDate = prefs.getBoolean(Prefs.SHOW_DATE, false)
        cv.dateFormatStyle = when (prefs.getString(Prefs.DATE_FORMAT, Prefs.DATE_FORMAT_NUMBER)) {
            Prefs.DATE_FORMAT_TEXT -> ClockView.DateFormatStyle.TEXT
            Prefs.DATE_FORMAT_ROMAN -> ClockView.DateFormatStyle.ROMAN
            else -> ClockView.DateFormatStyle.NUMBER
        }
        cv.showMoonPhase = prefs.getBoolean(Prefs.MOON_PHASE, false)
        updateAlarmMarkers()
        cv.touchHandsEnabled = prefs.getBoolean(Prefs.TOUCH_HANDS, true)
        cv.pinchZoomEnabled = prefs.getBoolean(Prefs.PINCH_ZOOM, true)
        cv.shakeDropEnabled = prefs.getBoolean(Prefs.SHAKE_DROP, true)
        cv.dialScale = prefs.getFloat(Prefs.DIAL_SCALE, 1f)

        rebuildBubbles()
        for (b in bubbles) {
            b.clock.theme = cv.theme
            b.clock.hoursOnDial = cv.hoursOnDial
            b.clock.dialShape = cv.dialShape
            b.clock.numeralStyle = cv.numeralStyle
        }

        // The countdown dial mirrors the main dial's styling — including its
        // shape and pinch scale, so S2 is the same size as C1/S1. It stays
        // touchable regardless of the grab-hands preference: winding is its
        // only setting mechanism.
        countdownClockView?.let {
            it.hoursOnDial = cv.hoursOnDial
            it.dialShape = cv.dialShape
            it.showSecondHand = cv.showSecondHand
            it.smoothSeconds = cv.smoothSeconds
            it.mirrored = cv.mirrored
            it.numeralStyle = cv.numeralStyle
            it.theme = cv.theme
            it.touchHandsEnabled = true
            it.pinchZoomEnabled = cv.pinchZoomEnabled
            it.dialScale = cv.dialScale
        }

        calendarView?.let {
            it.theme = cv.theme
            it.numeralStyle = cv.numeralStyle
            it.weekStartsMonday = prefs.getBoolean(
                Prefs.WEEK_START_MONDAY,
                Calendar.getInstance().firstDayOfWeek == Calendar.MONDAY
            )
        }
        refreshCalendarMarks()
        s3Sand?.let {
            it.theme = cv.theme
            it.glassScale = cv.dialScale
            it.setTime(countdownTotalMs, countdownRemaining())
        }
        syncS3DurationChecks()

        // Night dims the alarms card too — it was the only bright one left.
        alarmsContainer?.alpha = if (appliedNightDim) 0.45f else 1f

        bellsEnabled = prefs.getBoolean(Prefs.BELLS, false)
        bellStyle = prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT) ?: Prefs.BELL_STYLE_COUNT
        halfHourEnabled = prefs.getBoolean(Prefs.HALF_HOUR, false)
        tickingEnabled = prefs.getBoolean(Prefs.TICKING, false)
        countdownPersistent = prefs.getBoolean(Prefs.COUNTDOWN_PERSISTENT, true)
    }

    /**
     * Sundial mode: shifts the whole app's display time to local apparent
     * solar time using the last known longitude (one coarse fix, cached, no
     * network). Alarms keep ringing on civil time.
     */
    private fun applySolarTime() {
        if (!prefs.getBoolean(Prefs.SOLAR_TIME, false)) {
            TimeKeeper.solarOffsetMs = 0L
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            TimeKeeper.solarOffsetMs = 0L
            if (!locationAskedThisRun) {
                locationAskedThisRun = true
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            return
        }
        val longitude = readLongitude()
        TimeKeeper.solarOffsetMs = if (longitude != null) {
            SolarTime.offsetMs(longitude, System.currentTimeMillis())
        } else {
            0L
        }
    }

    private fun readLongitude(): Double? {
        val lm = getSystemService(LocationManager::class.java)
        var best: android.location.Location? = null
        if (lm != null) {
            for (provider in lm.allProviders) {
                try {
                    val location = lm.getLastKnownLocation(provider) ?: continue
                    if (best == null || location.time > best!!.time) best = location
                } catch (e: SecurityException) {
                    // Provider needs a finer permission; skip it.
                }
            }
        }
        best?.let {
            prefs.edit().putFloat(Prefs.LAST_LONGITUDE, it.longitude.toFloat()).apply()
            return it.longitude
        }
        return if (prefs.contains(Prefs.LAST_LONGITUDE)) {
            prefs.getFloat(Prefs.LAST_LONGITUDE, 0f).toDouble()
        } else {
            null
        }
    }

    private fun readDialShape(): ClockView.DialShape =
        when (prefs.getString(Prefs.DIAL_SHAPE, Prefs.SHAPE_CIRCLE)) {
            Prefs.SHAPE_TRIANGLE -> ClockView.DialShape.TRIANGLE
            Prefs.SHAPE_SQUARE -> ClockView.DialShape.SQUARE
            Prefs.SHAPE_HEXAGON -> ClockView.DialShape.HEXAGON
            Prefs.SHAPE_OCTAGON -> ClockView.DialShape.OCTAGON
            else -> ClockView.DialShape.CIRCLE
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

    /** The centered bottom button toggles clock ⏳ ↔ chronograph 🕐. */
    private fun cycleMode() {
        mode = if (mode == Mode.CLOCK) Mode.CHRONO else Mode.CLOCK
        applyMode()
    }

    private fun applyMode() {
        val chrono = mode == Mode.CHRONO
        clockView?.let {
            when {
                alarmSetActive -> {
                    // C1 borrows the countdown's wind-to-set engine to pick
                    // an alarm time, with the flat 5-minute magnet grid.
                    it.chronoProvider = alarmTimeProvider
                    it.chronoSettable = true
                    it.chronoButtons = false
                    it.magnetProfile = ClockView.MagnetProfile.ALARM
                }
                chrono -> {
                    it.chronoProvider = stopwatchProvider
                    it.chronoSettable = false
                    it.chronoButtons = true
                    it.chronoRunning = stopwatchRunning
                    it.magnetProfile = ClockView.MagnetProfile.COUNTDOWN
                }
                else -> {
                    it.chronoProvider = null
                    it.chronoSettable = false
                    it.chronoButtons = false
                    it.magnetProfile = ClockView.MagnetProfile.COUNTDOWN
                }
            }
        }
        modeButton?.setText(if (chrono) R.string.mode_stopwatch else R.string.mode_clock)
        modeButton?.visibility = if (alarmSetActive) View.GONE else View.VISIBLE
        // Bubbles fade with the mode change, like the crown and pushers.
        val showBubbles = !chrono && !alarmSetActive
        bubbleLayer?.let { layer ->
            layer.animate().cancel()
            if (showBubbles) {
                layer.visibility = View.VISIBLE
                layer.animate().alpha(1f).setDuration(500L).start()
            } else {
                layer.animate().alpha(0f).setDuration(500L)
                    .withEndAction { layer.visibility = View.GONE }
                    .start()
            }
        }
        settingsButton?.visibility = if (chrono || alarmSetActive) View.GONE else View.VISIBLE
        // Pages two and three follow the mode: alarms and calendar beside
        // the clock, the countdown dial and the hourglass beside the stopwatch.
        alarmsContainer?.visibility = if (chrono) View.GONE else View.VISIBLE
        countdownContainer?.visibility = if (chrono) View.VISIBLE else View.GONE
        calendarContainer?.visibility = if (chrono) View.GONE else View.VISIBLE
        hourglassContainer?.visibility = if (chrono) View.VISIBLE else View.GONE
        updateCountdownUi()
    }

    private fun updateCountdownUi() {
        countdownClockView?.chronoSettable = !countdownRunning
        countdownClockView?.chronoRunning = countdownRunning
        s3Sand?.setTime(countdownTotalMs, countdownRemaining())
        syncS3DurationChecks()
    }

    private fun isNightNow(): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = TimeKeeper.nowMs()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 7
    }

    private fun stopwatchElapsed(): Long =
        stopwatchAccumMs + if (stopwatchRunning) SystemClock.elapsedRealtime() - stopwatchStartedAt else 0L

    private fun countdownRemaining(): Long =
        if (countdownRunning) (countdownEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        else countdownRemainingMs

    /** Start/Pause pusher on the stopwatch (main dial in chrono mode). */
    private fun toggleStartPause() {
        if (mode != Mode.CHRONO) return
        if (stopwatchRunning) {
            stopwatchAccumMs = stopwatchElapsed()
            stopwatchRunning = false
        } else {
            stopwatchStartedAt = SystemClock.elapsedRealtime()
            stopwatchRunning = true
        }
        clockView?.chronoRunning = stopwatchRunning
    }

    private fun resetChrono() {
        stopwatchRunning = false
        stopwatchAccumMs = 0L
        clockView?.chronoRunning = false
    }

    /** Start/Pause on the countdown dial (second page in chrono mode). */
    private fun toggleCountdown() {
        if (countdownRunning) {
            countdownRemainingMs = countdownRemaining()
            countdownRunning = false
        } else if (countdownRemaining() > 0L) {
            countdownEndsAt = SystemClock.elapsedRealtime() + countdownRemainingMs
            countdownTotalMs = countdownRemainingMs
            countdownRunning = true
        }
        updateCountdownUi()
    }

    private fun resetCountdown() {
        // Back to zero; the user winds the hands to set a new time.
        countdownRunning = false
        countdownRemainingMs = 0L
        updateCountdownUi()
        HourglassWidgetProvider.pushIdle(this)
    }

    // ------------------------------------------------- scheduled chimes

    private fun onMinuteBoundary() {
        // Night mode keeps the house quiet: no bells while the dial is dim.
        if (!bellsEnabled || appliedNightDim) return
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

    override fun onCheater() {
        // Sad trombone-ish womp-womp for the stopwatch cheater.
        chimePlayer.playBellSequence(
            2, pairGrouping = false,
            frequency = 110.0, ringSeconds = 0.8, interval = 0.28
        )
    }

    companion object {
        const val EXTRA_OPEN_ALARMS = "extra_open_alarms"
        private const val DEFAULT_COUNTDOWN_MS = 5 * 60_000L
        private const val BUBBLE_BUOYANCY = 300f
    }
}

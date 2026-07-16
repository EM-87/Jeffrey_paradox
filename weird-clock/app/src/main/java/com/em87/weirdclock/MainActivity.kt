package com.em87.weirdclock

import android.Manifest
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
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

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
    private var worldClockView: ClockView? = null
    private var worldClockContainer: View? = null
    private var worldClockLabel: TextView? = null
    private var modeButton: Button? = null
    private var settingsButton: ImageButton? = null
    private var alarmSetBanner: View? = null

    // Second page views (alarms in clock mode, countdown dial in chrono mode).
    private var alarmsContainer: View? = null
    private var countdownContainer: View? = null
    private var alarmsRecycler: RecyclerView? = null
    private var alarmsEmpty: TextView? = null
    private var countdownClockView: ClockView? = null

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
                    // and the ring screen carries the stop button.
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, AlarmService::class.java)
                    )
                    startActivity(Intent(this@MainActivity, AlarmRingActivity::class.java))
                } else {
                    chimePlayer.playBellSequence(3, false, ChimePlayer.DAY_CHIME_HZ, 1.2, 0.3)
                }
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
        }
        applyPreferences()
        // Prime the minute boundary so opening the app never chimes, and
        // start the loop on the next second boundary so ticks land in step.
        lastHandledMinute = TimeKeeper.nowMs() / 60000L
        handler.postDelayed(soundLoop, 1000L - (System.currentTimeMillis() % 1000L))
    }

    override fun onPause() {
        handler.removeCallbacks(soundLoop)
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

        override fun getItemCount(): Int = 2

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = if (viewType == 0) {
                inflater.inflate(R.layout.page_clock, parent, false).also { bindClockPage(it) }
            } else {
                inflater.inflate(R.layout.page_second, parent, false).also { bindSecondPage(it) }
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
            it.onChronoReset = { if (!alarmSetActive) resetChrono() }
            it.onChronoAdjusted = { ms -> if (alarmSetActive) alarmWorkingMs = ms }
            it.onCrownTap = { chimePlayer.playCuckoo() }
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
        settingsButton = root.findViewById<ImageButton>(R.id.settings_button).also { button ->
            button.setOnClickListener {
                // Let settings know whether the panic button should be offered.
                prefs.edit()
                    .putBoolean(Prefs.NEEDS_REASSEMBLY, clockView?.isDisarranged() == true)
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
            it.pinchZoomEnabled = false
            it.shakeDropEnabled = false
            it.showDate = false
            it.chronoProvider = { countdownRemaining() }
            it.chronoButtons = true
            it.onChronoStartStop = { toggleCountdown() }
            it.onChronoReset = { resetCountdown() }
            it.onChronoAdjusted = { ms ->
                if (!countdownRunning) {
                    countdownRemainingMs = ms
                    updateCountdownUi()
                }
            }
            it.onHorizontalSwipe = { fingerRight ->
                // Swiping back over the countdown dial returns to the
                // stopwatch page.
                if (fingerRight) {
                    pager.currentItem = 0
                    true
                } else {
                    false
                }
            }
            it.onCrownTap = { chimePlayer.playCuckoo() }
        }
        root.findViewById<Button>(R.id.countdown_back_button).setOnClickListener {
            // Straight home from S2. The mode flips only once the scroll
            // lands on page 0 — flipping it earlier swaps page two back to
            // the alarms while it's still on screen (a brief C2 flash).
            val callback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    if (state == ViewPager2.SCROLL_STATE_IDLE) {
                        pager.unregisterOnPageChangeCallback(this)
                        mode = Mode.CLOCK
                        applyMode()
                    }
                }
            }
            pager.registerOnPageChangeCallback(callback)
            pager.currentItem = 0
        }
        applyPreferences()
        applyMode()
    }

    // -------------------------------------------------------------- alarms

    private inner class AlarmHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.alarm_time)
        val sound: TextView = view.findViewById(R.id.alarm_sound)
        val repeat: TextView = view.findViewById(R.id.alarm_repeat)
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
                alarm.sound = nextSound(alarm.sound)
                persistAlarms()
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

        // The countdown dial mirrors the main dial's styling. It stays
        // touchable regardless of the grab-hands preference: winding is its
        // only setting mechanism.
        countdownClockView?.let {
            it.hoursOnDial = cv.hoursOnDial
            it.showSecondHand = cv.showSecondHand
            it.smoothSeconds = cv.smoothSeconds
            it.mirrored = cv.mirrored
            it.numeralStyle = cv.numeralStyle
            it.theme = cv.theme
            it.touchHandsEnabled = true
        }

        bellsEnabled = prefs.getBoolean(Prefs.BELLS, false)
        bellStyle = prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT) ?: Prefs.BELL_STYLE_COUNT
        halfHourEnabled = prefs.getBoolean(Prefs.HALF_HOUR, false)
        tickingEnabled = prefs.getBoolean(Prefs.TICKING, false)
        countdownPersistent = prefs.getBoolean(Prefs.COUNTDOWN_PERSISTENT, true)
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
                    // an alarm time.
                    it.chronoProvider = alarmTimeProvider
                    it.chronoSettable = true
                    it.chronoButtons = false
                }
                chrono -> {
                    it.chronoProvider = stopwatchProvider
                    it.chronoSettable = false
                    it.chronoButtons = true
                    it.chronoRunning = stopwatchRunning
                }
                else -> {
                    it.chronoProvider = null
                    it.chronoSettable = false
                    it.chronoButtons = false
                }
            }
        }
        modeButton?.setText(if (chrono) R.string.mode_stopwatch else R.string.mode_clock)
        modeButton?.visibility = if (alarmSetActive) View.GONE else View.VISIBLE
        settingsButton?.visibility = if (chrono || alarmSetActive) View.GONE else View.VISIBLE
        // Page two follows the mode: alarms beside the clock, the countdown
        // dial beside the stopwatch.
        alarmsContainer?.visibility = if (chrono) View.GONE else View.VISIBLE
        countdownContainer?.visibility = if (chrono) View.VISIBLE else View.GONE
        updateCountdownUi()
    }

    private fun updateCountdownUi() {
        countdownClockView?.chronoSettable = !countdownRunning
        countdownClockView?.chronoRunning = countdownRunning
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
    }
}

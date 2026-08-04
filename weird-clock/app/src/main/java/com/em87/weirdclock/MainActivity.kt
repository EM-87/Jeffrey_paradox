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
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
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

    // Center page (C0 clock / S0 hourglass).
    private var clockView: ClockView? = null
    private var clockContainer: View? = null
    private var bubbleLayer: FrameLayout? = null
    private lateinit var worldBubbles: WorldBubbles
    private var modeButton: ImageButton? = null
    private var homeButtonRow: View? = null
    private var settingsButton: ImageButton? = null
    private var alarmSetBanner: View? = null
    private var alarmSetLabel: TextView? = null
    private var sandStartStop: Button? = null
    private var sandFreeze: Button? = null

    // Left page (C-1 calendar / S-1 stopwatch).
    private var stopwatchContainer: View? = null
    private var stopwatchClockView: ClockView? = null

    // Right page (C1 alarms / S1 countdown).
    private var alarmsContainer: View? = null
    private var countdownContainer: View? = null
    private var alarmsRecycler: RecyclerView? = null
    private var alarmsEmpty: TextView? = null
    private var countdownClockView: ClockView? = null

    private var calendarContainer: View? = null
    private var hourglassContainer: View? = null
    private var calendarView: CalendarPageView? = null
    private var s3Sand: SandHourglassView? = null
    private var s3DurationGroup: com.google.android.material.button.MaterialButtonToggleGroup? = null
    private var updatingDurationChecks = false
    private var sandBlocked = false
    private var lastPage = 0

    // Calendar reminders (one-shot dated alarms).
    /**
     * The one shared list, not a copy of it. See ReminderStore.
     *
     * Fetched on every read rather than held in a field, so that no order of
     * initialisation can be the wrong one. Held in a lateinit field it was:
     * the sheets are built near the top of onCreate and the field was filled
     * a hundred lines below, which crashed the app on launch and got past a
     * clean compile, 52 green tests and lint — none of which look at when a
     * field is written. The store hands back the same instance every time.
     */
    private val reminders: MutableList<Reminder> get() = ReminderStore.all(this)

    /** Date + label of the reminder whose time is being wound on the dial. */
    /**
     * What C0's dial is being used to set right now, if anything.
     *
     * This was eleven fields: two "being set" drafts, two parked ones, two
     * flags, a provisional id, an index and an active bit — and the last two
     * bugs both lived in the gaps between them. One of them cleared a flag
     * before the code that read it ran; the other simply had no field saying
     * "come back to the alarm sheet", so nothing did.
     *
     * They were describing one thing badly. There is only ever one job, and
     * "parked" was never a different state from "in flight" — it is the same
     * job, still waiting to be finished. Written as a closed set of four, the
     * compiler will not let a fifth kind be added without answering, at every
     * place that asks, what it should do.
     */
    private sealed interface DialJob {
        /**
         * Whether what is being wound is a length rather than a time of day.
         * Lengths take the countdown's magnets, times take the alarm's.
         */
        val isLength: Boolean

        /** One of an alarm's times of day. */
        data class AlarmTime(
            val target: Alarm,
            val draft: Alarm,
            /** Born only so the dial had a target; cancelling takes it back. */
            val isNew: Boolean,
            /** Which of the alarm's up-to-four times is being wound. */
            val timeIndex: Int
        ) : DialJob {
            override val isLength = false
        }

        /** How long the alarm's thing lasts. */
        data class AlarmLength(
            val target: Alarm,
            val draft: Alarm,
            val isNew: Boolean
        ) : DialJob {
            override val isLength = true
        }

        /** A reminder's time of day. */
        data class ReminderTime(val draft: ReminderDraft) : DialJob {
            override val isLength = false
        }

        /** How long a reminder's thing lasts. */
        data class ReminderLength(val draft: ReminderDraft) : DialJob {
            override val isLength = true
        }
    }

    private var dialJob: DialJob? = null

    /** The value the hands are showing while a job is in progress. */
    private var alarmWorkingMs = 0L

    /**
     * The hour a length is wound from, so the magnets count from there.
     * Zero for a time of day, which counts from midnight like the dial does.
     */
    private var dialMagnetOrigin = 0L

    // Stable provider instances: recreating these lambdas on every
    // applyMode() made the ClockView setter think the mode changed,
    // restarting transitions and wiping any winding in progress.
    private val stopwatchProvider: () -> Long = { stopwatchElapsed() }
    private val alarmTimeProvider: () -> Long = { alarmWorkingMs }
    /** The one shared list, not a copy of it. See [reminders] and AlarmStore. */
    private val alarms: MutableList<Alarm> get() = AlarmStore.all(this)
    private lateinit var alarmCards: AlarmCards
    private lateinit var reminderSheet: ReminderSheet
    private lateinit var alarmSheet: AlarmSheet

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

    /** True while a one-shot location request is still out. */
    private var locationFixPending = false
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) applyPreferences()
        }

    /** Alarm whose custom sound file is being picked, while SAF is open. */
    private var soundPickTarget: Alarm? = null

    /** Refreshes the open edit sheet once the picker returns. */
    private var soundPickCallback: (() -> Unit)? = null
    private val soundPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val alarm = soundPickTarget
            val done = soundPickCallback
            soundPickTarget = null
            soundPickCallback = null
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
                if (done != null) done() else persistAlarms()
            }
        }

    /** Whether the dial is currently wearing its dimmed night colors. */
    private var appliedNightDim = false

    /** Last seen uiMode, to recreate on system light/dark changes. */
    private var lastUiMode = 0

    /** True while stepping into our own settings screen. */
    private var openingSettings = false

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
    private var lastBubbleJoltAt = 0L
    private val flipListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val devX = event.values[0] - flipLowPassX
            val devY = event.values[1] - flipLowPassY
            flipLowPassX = flipLowPassX * 0.8f + event.values[0] * 0.2f
            flipLowPassY = flipLowPassY * 0.8f + event.values[1] * 0.2f
            // Free bubbles take any knock, not just the hand-shedding ones:
            // they get shoved in the direction the phone was struck.
            val jolt = hypot(devX, devY)
            val now = SystemClock.uptimeMillis()
            if (jolt > 3.5f && now - lastBubbleJoltAt > 120L) {
                lastBubbleJoltAt = now
                worldBubbles.jolt(devX, devY)
            }
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
                !cv.isSecondHandGrabbed() && !cv.isSecondHandFallen()
            ) {
                chimePlayer.playTick()
            }

            if (countdownRunning && countdownRemaining() == 0L) {
                countdownRunning = false
                countdownRemainingMs = 0L
                CountdownService.clearPublished(this@MainActivity)
                updateCountdownUi()
                if (countdownPersistent) {
                    // Ring until validated. Handed over exactly as
                    // CountdownService does it when the app is closed — this
                    // used to be a second, slightly different copy of that
                    // code, and the difference was the whole bug: it never
                    // said the ring came from a timer, so the ring screen
                    // wore a bell, and it opened that screen itself on top of
                    // the one the service's own notification was already
                    // asking for. One handover, one screen.
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, AlarmService::class.java)
                            .putExtra(
                                AlarmScheduler.EXTRA_LABEL,
                                getString(R.string.countdown_finished)
                            )
                            .putExtra(AlarmScheduler.EXTRA_FROM_TIMER, true)
                    )
                } else {
                    // Quick, bright quarter-chimes: clearly a timer, not an hour.
                    chimePlayer.playQuarters()
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

            // "in 7 h 20 min" is only true for a minute at a time.
            val minuteNow = System.currentTimeMillis() / 60_000L
            if (minuteNow != lastCountdownMinute) {
                lastCountdownMinute = minuteNow
                updateAlarmCountdowns()
            }

            handler.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L))
        }
    }

    private var lastCountdownMinute = 0L

    /**
     * Retimes the cards in place. Rebinding the whole list every minute would
     * be a lot of work to change eight characters, and would set every face
     * winding again.
     */
    private fun updateAlarmCountdowns() {
        alarmCards.retimeVisible(alarmsRecycler ?: return)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The app's background runs behind the status and navigation bars,
        // so the clock looks like it goes on past the edges of the screen.
        SystemChrome.paint(this)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // The floating hourglass used to be a yes/no switch; it is now a
        // choice of two. Carry the old answer over once.
        if (!prefs.contains(Prefs.COUNTDOWN_FLOAT)) {
            prefs.edit().putString(
                Prefs.COUNTDOWN_FLOAT,
                if (prefs.getBoolean(Prefs.COUNTDOWN_BUBBLE, true)) Prefs.FLOAT_OVERLAY
                else Prefs.FLOAT_NONE
            ).apply()
        } else if (prefs.getString(Prefs.COUNTDOWN_FLOAT, null) == "bubble") {
            // System bubbles were offered for three versions and never worked:
            // see CountdownService. Anyone left holding that answer gets the
            // floating hourglass instead of a setting that points nowhere.
            prefs.edit().putString(Prefs.COUNTDOWN_FLOAT, Prefs.FLOAT_OVERLAY).apply()
        }
        // An alarm with no days set now means "once", and retires itself
        // after ringing. Before this it meant "every day" — so any alarm
        // already stored that way is written out explicitly, once, rather
        // than quietly becoming single-use under someone who set it to wake
        // them every morning.
        if (!prefs.getBoolean(Prefs.ONCE_MIGRATED, false)) {
            val stored = AlarmStore.all(this)
            val stale = stored.filter { it.daysMask == 0 }
            if (stale.isNotEmpty()) {
                for (a in stale) a.daysMask = Alarm.ALL_DAYS
                AlarmStore.save(this)
            }
            prefs.edit().putBoolean(Prefs.ONCE_MIGRATED, true).apply()
        }
        alarmCards = AlarmCards(
            host = this,
            prefs = prefs,
            alarms = alarms,
            dialTheme = { clockView?.theme ?: ClockThemes.MIDNIGHT },
            hoursOnDial = ::readHoursOnDial,
            dialShape = ::readDialShape,
            onToggled = { alarm, checked ->
                alarm.enabled = checked
                if (checked) maybeRequestNotificationPermission()
                persistAlarms()
            },
            onOpen = { alarm -> showAlarmSheet(alarm) }
        )
        alarmSheet = AlarmSheet(this, alarmCards, alarms, object : AlarmSheet.Callbacks {
            override fun animateSheet(
                sheet: com.google.android.material.bottomsheet.BottomSheetDialog,
                content: View
            ) = this@MainActivity.animateSheet(sheet, content)

            override fun commitDraft(target: Alarm, draft: Alarm, isNew: Boolean) =
                this@MainActivity.commitDraft(target, draft, isNew)

            override fun deleteAlarm(alarm: Alarm) {
                alarms.remove(alarm)
                persistAlarms()
            }

            override fun persistAlarms() = this@MainActivity.persistAlarms()

            override fun notificationPermissionIfNeeded() =
                maybeRequestNotificationPermission()

            override fun windTime(target: Alarm, draft: Alarm, isNew: Boolean, timeIndex: Int) {
                val (h, m) = draft.timeAt(timeIndex)
                startDial(
                    DialJob.AlarmTime(target, draft, isNew, timeIndex),
                    (h * 3600L + m * 60L) * 1000L
                )
            }

            override fun windDuration(parked: AlarmDurationDraft) {
                // Wound from the alarm's own hour to where the thing ends,
                // rather than from midnight. A length starting at zero told
                // you nothing about when it lands, and left the sun and moon
                // with nothing to say; now the token shows whether you get
                // out in daylight.
                val (h, m) = parked.draft.timeAt(0)
                val startsAt = (h * 3_600_000L) + (m * 60_000L)
                startDial(
                    DialJob.AlarmLength(parked.target, parked.draft, parked.isNew),
                    startsAt + parked.draft.durationMinutes.coerceAtLeast(15) * 60_000L,
                    magnetOrigin = startsAt
                )
            }

            override fun pickAudioFile(target: Alarm, onPicked: () -> Unit) {
                soundPickTarget = target
                soundPickCallback = onPicked
                soundPickerLauncher.launch(arrayOf("audio/*"))
            }
        })
        reminderSheet = ReminderSheet(this, alarmCards, object : ReminderSheet.Callbacks {
            override fun animateSheet(
                sheet: com.google.android.material.bottomsheet.BottomSheetDialog,
                content: View
            ) = this@MainActivity.animateSheet(sheet, content)

            override fun commitReminder(existing: Reminder?, draft: ReminderDraft) =
                this@MainActivity.commitReminder(existing, draft)

            override fun deleteReminder(reminder: Reminder) {
                reminders.remove(reminder)
                persistReminders()
            }

            override fun windTime(draft: ReminderDraft) {
                startDial(
                    DialJob.ReminderTime(draft),
                    (draft.hour * 3_600_000L) + (draft.minute * 60_000L)
                )
            }

            override fun windDuration(draft: ReminderDraft) {
                // Same as the alarm's: from its own hour to where it ends.
                val startsAt = (draft.hour * 3_600_000L) + (draft.minute * 60_000L)
                startDial(
                    DialJob.ReminderLength(draft),
                    startsAt + draft.duration.coerceAtLeast(15) * 60_000L,
                    magnetOrigin = startsAt
                )
            }

            override fun isPastDay(year: Int, month: Int, day: Int): Boolean =
                this@MainActivity.isPastDay(year, month, day)
        })
        worldBubbles = WorldBubbles(
            host = this,
            prefs = prefs,
            chimePlayer = chimePlayer,
            mainDial = { clockView },
            dialIsObstacle = { mode == Mode.CLOCK },
            gravityX = ::viewGravityX,
            gravityY = ::viewGravityY
        )
        chimePlayer.prepareTick(this)
        sortAlarms()

        pager = findViewById(R.id.pager)
        // The pages keep clear of the bars; the pager's own background does
        // not, which is what makes the whole thing read as one surface.
        SystemChrome.padForBars(pager)
        pager.offscreenPageLimit = 2
        pager.adapter = PagerAdapter()
        // The app opens on the clock, with calendar and alarms one swipe
        // away on either side.
        pager.setCurrentItem(PAGE_HOME, false)
        lastPage = PAGE_HOME
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                lastPage = position
                carryFallenHands()
                // Landing on the alarms: whatever happened while away — a
                // time wound on the dial, an alarm that rang — shows now.
                if (position == PAGE_RIGHT) refreshAlarmsUi()
            }
        })
        if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) {
            pager.post { pager.setCurrentItem(PAGE_RIGHT, false) }
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_TIMER, false)) {
            // From the countdown notification or the hourglass widget:
            // straight to whichever timer face is in use.
            pager.post {
                mode = Mode.CHRONO
                applyMode()
                pager.setCurrentItem(
                    if (prefs.getBoolean(Prefs.TIMER_ON_DIAL, false)) PAGE_RIGHT else PAGE_HOME,
                    false
                )
            }
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_CALENDAR, false)) {
            // Clock mode too: the left page is the calendar there and the
            // stopwatch in chronograph mode, so a shortcut asking for the
            // calendar landed on the stopwatch if that is where the app was
            // last left.
            pager.post {
                mode = Mode.CLOCK
                applyMode()
                pager.setCurrentItem(PAGE_LEFT, false)
            }
        }

        // Winding a time on the dial locks the pager, and the only way out
        // was the little cross. Back left the app instead — and with it any
        // half-made alarm that was only there for the dial to write into.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = exitAlarmSetMode()
        }.also { backOutOfSetMode = it })

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
            mode = Mode.CLOCK
            applyMode()
            pager.currentItem = PAGE_RIGHT
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_TIMER, false)) {
            mode = Mode.CHRONO
            applyMode()
            pager.currentItem =
                if (prefs.getBoolean(Prefs.TIMER_ON_DIAL, false)) PAGE_RIGHT else PAGE_HOME
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_CALENDAR, false)) {
            mode = Mode.CLOCK
            applyMode()
            pager.currentItem = PAGE_LEFT
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
            null -> {
                // A countdown the app never started — the assistant asked for
                // one while it was closed, or a shortcut did — is adopted
                // rather than ignored, so opening the app shows the real one.
                val endsAt = prefs.getLong(Prefs.COUNTDOWN_ENDS_AT, 0L)
                if (!countdownRunning && endsAt > SystemClock.elapsedRealtime()) {
                    countdownEndsAt = endsAt
                    countdownTotalMs = prefs.getLong(Prefs.COUNTDOWN_TOTAL, 60_000L)
                    countdownRunning = true
                    updateCountdownUi()
                }
            }
            CountdownService.RESULT_EXTENDED -> {
                // A minute was bought from the shade while we were away.
                countdownEndsAt = prefs.getLong(Prefs.COUNTDOWN_ENDS_AT, countdownEndsAt)
                countdownTotalMs = prefs.getLong(Prefs.COUNTDOWN_TOTAL, countdownTotalMs)
                countdownRunning = true
                prefs.edit().remove(Prefs.COUNTDOWN_RESULT).apply()
                updateCountdownUi()
            }
        }
        // "Put everything back" panic button from settings.
        if (prefs.getBoolean(Prefs.REASSEMBLE_PENDING, false)) {
            prefs.edit().putBoolean(Prefs.REASSEMBLE_PENDING, false).apply()
            reassembleEverything()
        }
        // The store is not ours alone. The assistant adds alarms through
        // ClockIntentActivity, and a one-shot switches itself off from the
        // receiver when it rings — both while this activity sits in the
        // background holding a list from whenever it was created. Coming
        // back with that stale list meant a spoken alarm was invisible, and
        // the next save here wrote it back out of existence.
        refreshFromStores()
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
        // Leaving the app is as good a moment as any to make sure the bells
        // are still armed: their chain lives on alarms, and anything that
        // breaks it (a force stop, an update) leaves them silent otherwise.
        BellScheduler.update(this)
        // The sand widget wears the theme too, and nothing was repainting
        // it: it kept the old colours until a countdown happened to run. A
        // running one is already being pushed live, so leave it alone.
        if (!countdownRunning) HourglassWidgetProvider.pushIdle(this)
        // A running countdown stays visible from outside the app as an
        // ongoing notification with live remaining time and a progress bar.
        if (countdownRunning && !openingSettings) {
            CountdownService.start(this, countdownEndsAt, countdownTotalMs)
        }
        openingSettings = false
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
                PAGE_LEFT -> inflater.inflate(R.layout.page_left, parent, false)
                    .also { bindLeftPage(it) }
                PAGE_HOME -> inflater.inflate(R.layout.page_center, parent, false)
                    .also { bindCenterPage(it) }
                else -> inflater.inflate(R.layout.page_right, parent, false)
                    .also { bindRightPage(it) }
            }
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit
    }

    /** C-1 calendar / S-1 stopwatch. */
    private fun bindLeftPage(root: View) {
        calendarContainer = root.findViewById(R.id.calendar_container)
        stopwatchContainer = root.findViewById(R.id.stopwatch_container)
        calendarView = root.findViewById<CalendarPageView>(R.id.calendar_view).also {
            it.onDayTap = { day -> onCalendarDayTap(day) }
            it.onMonthChanged = { refreshCalendarMarks() }
            it.onWeekStartChanged = { monday ->
                prefs.edit().putBoolean(Prefs.WEEK_START_MONDAY, monday).apply()
            }
        }
        stopwatchClockView = root.findViewById<ClockView>(R.id.stopwatch_clock_view).also {
            it.soundListener = this
            it.shakeDropEnabled = false
            it.showDate = false
            it.chronoProvider = stopwatchProvider
            it.chronoButtons = true
            it.onChronoStartStop = { toggleStartPause() }
            it.onChronoReset = {
                // Real-chronograph convention: running, the lower pusher
                // records a lap; stopped, it resets and clears them.
                if (stopwatchRunning) {
                    stopwatchClockView?.recordLap()
                    chimePlayer.playTick()
                } else {
                    resetChrono()
                    stopwatchClockView?.clearLaps()
                }
            }
            it.onDialScaleChanged = { scale -> shareDialScale(scale, it) }
            it.onHorizontalSwipe = { fingerRight ->
                // Home is one swipe away from anywhere — and home from a
                // chronograph is the clock, which is up and across: the
                // same diagonal the button took to get here, run backwards.
                if (!fingerRight) {
                    goDiagonal(PAGE_HOME, Mode.CLOCK)
                    true
                } else {
                    false
                }
            }
            it.onVerticalSwipe = { up -> swipeRows(up) }
            it.onCrownTap = {
                // The winding crown tidies the whole scene, bubbles included.
                chimePlayer.playCuckoo()
                healBubbleClocks()
                worldBubbles.dock()
            }
        }
        root.findViewById<ImageButton>(R.id.stopwatch_back_button).setOnClickListener {
            goHomeToClock()
        }
        applyPreferences()
        applyMode()
    }

    /** C1 alarms / S1 countdown. */
    private fun bindRightPage(root: View) {
        alarmsContainer = root.findViewById(R.id.alarms_container)
        countdownContainer = root.findViewById(R.id.countdown_container)

        alarmsRecycler = root.findViewById<RecyclerView>(R.id.alarms_recycler).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = alarmCards.adapter
        }
        alarmsEmpty = root.findViewById(R.id.alarms_empty)
        root.findViewById<FloatingActionButton>(R.id.add_alarm_fab).setOnClickListener {
            // A new alarm opens straight in the editor, at a civilised hour.
            showAlarmSheet(
                Alarm(AlarmStore.nextId(alarms), 7, 30, true, Prefs.ALARM_SOUND_BELLS)
            )
        }
        // The dial markers toggle belongs with the alarms, not buried in
        // settings three layers down.
        root.findViewById<SwitchCompat>(R.id.alarm_markers_switch).also { markers ->
            markers.isChecked = prefs.getBoolean(Prefs.ALARM_MARKERS, true)
            markers.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Prefs.ALARM_MARKERS, checked).apply()
                updateAlarmMarkers()
                ClockWidgetProvider.refreshAll(this)
            }
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
            it.onDialScaleChanged = { scale -> shareDialScale(scale, it) }
            it.onHorizontalSwipe = { fingerRight ->
                if (fingerRight) {
                    goDiagonal(PAGE_HOME, Mode.CLOCK)
                    true
                } else {
                    false
                }
            }
            it.onVerticalSwipe = { up -> swipeRows(up) }
            it.onCrownTap = {
                // The winding crown tidies the whole scene, bubbles included.
                chimePlayer.playCuckoo()
                healBubbleClocks()
                worldBubbles.dock()
            }
        }
        // The countdown goes straight home to the clock, skipping S0.
        root.findViewById<ImageButton>(R.id.countdown_back_button).setOnClickListener {
            goHomeToClock()
        }
        applyPreferences()
        applyMode()
    }

    /** C0 clock / S0 sand hourglass — the card the app opens on. */
    private fun bindCenterPage(root: View) {
        clockContainer = root.findViewById(R.id.clock_container)
        hourglassContainer = root.findViewById(R.id.hourglass_container)
        clockView = root.findViewById<ClockView>(R.id.clock_view).also {
            it.soundListener = this
            it.onDialScaleChanged = { scale -> shareDialScale(scale, it) }
            it.onChronoAdjusted = { ms -> if (dialJob != null) alarmWorkingMs = ms }
            // Carry the hands round to tomorrow and the dial shows what
            // tomorrow holds, which is the one thing a twelve-hour face can
            // do that a list cannot.
            it.onShownDayChanged = { updateAlarmMarkers() }
            it.onVerticalSwipe = { up -> swipeRows(up) }
            it.onCrownTap = {
                // The winding crown tidies the whole scene, bubbles included.
                chimePlayer.playCuckoo()
                healBubbleClocks()
                worldBubbles.dock()
            }
            // A knock hard enough to shed hands rattles the whole scene.
            it.onKnocked = { onDialKnocked() }
        }
        bubbleLayer = root.findViewById(R.id.bubble_layer)
        worldBubbles.layer = bubbleLayer
        modeButton = root.findViewById<ImageButton>(R.id.mode_button).also {
            it.setOnClickListener { cycleMode() }
        }
        homeButtonRow = root.findViewById(R.id.home_button_row)
        root.findViewById<ImageButton>(R.id.to_stopwatch_button)
            .setOnClickListener { goToChrono(PAGE_LEFT) }
        root.findViewById<ImageButton>(R.id.to_countdown_button)
            .setOnClickListener { goToChrono(PAGE_RIGHT) }
        settingsButton = root.findViewById<ImageButton>(R.id.settings_button).also { button ->
            button.setOnClickListener {
                // Let settings know whether the panic button should be offered.
                prefs.edit()
                    .putBoolean(Prefs.NEEDS_REASSEMBLY, sceneIsDisarranged())
                    .apply()
                // Opening our own settings is not "leaving the app": don't
                // fire up the countdown notification and floating bubble.
                openingSettings = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        alarmSetBanner = root.findViewById(R.id.alarm_set_banner)
        alarmSetLabel = root.findViewById(R.id.alarm_set_label)
        root.findViewById<Button>(R.id.alarm_set_confirm).setOnClickListener { confirmAlarmSet() }
        root.findViewById<Button>(R.id.alarm_set_cancel).setOnClickListener { exitAlarmSetMode() }

        s3Sand = root.findViewById<SandHourglassView>(R.id.s3_sand).also {
            it.onFlowBlocked = { blocked -> sandBlocked = blocked }
            it.onScaleChanged = { scale -> shareDialScale(scale, null) }
            it.onVerticalSwipe = { up -> swipeRows(up) }
        }
        sandStartStop = root.findViewById<Button>(R.id.sand_start_stop).also {
            it.setOnClickListener { toggleCountdown() }
        }
        root.findViewById<ImageButton>(R.id.sand_back_button)
            .setOnClickListener { goHomeToClock() }
        sandFreeze = root.findViewById<Button>(R.id.sand_freeze).also { button ->
            button.setOnClickListener {
                val sand = s3Sand ?: return@setOnClickListener
                sand.frozen = !sand.frozen
                button.setText(if (sand.frozen) R.string.sand_thaw else R.string.sand_freeze)
                chimePlayer.playTick()
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

    /**
     * Real movement, both ways. The sheet's own behavior does the sliding —
     * driving the content view by hand fought the dialog's internal
     * container and lost — and dismissWithAnimation makes tapping outside
     * or pressing back slide it back down instead of blinking it away.
     */
    private fun animateSheet(
        sheet: com.google.android.material.bottomsheet.BottomSheetDialog,
        content: View
    ) {
        sheet.dismissWithAnimation = true
        sheet.behavior.apply {
            skipCollapsed = true
            isFitToContents = true
        }
        // The dialog's own window animation slides the sheet in over about a
        // fifth of a second, and it was winning the race against ours — hence
        // the sheet still looking like it just appeared. Silencing it leaves
        // the climb below as the only entrance. Dismissal is untouched: it
        // rides the behaviour, not the window.
        sheet.window?.setWindowAnimations(0)
        // A sheet rises from the very bottom of the screen, gesture bar
        // included, so its last row needs the height of that bar under it.
        SystemChrome.padForBars(content, top = false)
        // Pushed down on the frame before the first one is drawn, not on the
        // show callback: that callback can land after a frame has already
        // gone out with the sheet sitting at its final place, and that stray
        // frame is the flicker — the sheet appearing, vanishing downwards and
        // climbing back.
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    val container = sheet.findViewById<View>(
                        com.google.android.material.R.id.design_bottom_sheet
                    ) ?: content
                    // Laid out by now, but a zero-length climb is exactly the
                    // abruptness we are chasing away, so fall back to the
                    // screen if it somehow is not.
                    // The sheet's own rounded background is the one that
                    // shows; the dialog's would square off the corners
                    // behind it.
                    container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    val travel = (
                        if (container.height > 0) container.height
                        else resources.displayMetrics.heightPixels
                        ).toFloat()
                    container.translationY = travel
                    climb(container, travel)
                    return true
                }
            }
        )
    }

    /**
     * Walks a view up from [travel] pixels below its place, frame by frame.
     *
     * Deliberately not ViewPropertyAnimator: everything built on ValueAnimator
     * is multiplied by the system's animator duration scale, and on a phone
     * where that scale is off every duration collapses to zero — which is
     * exactly how this sheet behaved, appearing at once no matter how long the
     * animation was asked to last, while the calendar's slides and the sheet's
     * own descent (both hand-clocked or scroller-driven) kept animating fine.
     * Clocking it ourselves off uptime makes the climb immune to the setting.
     */
    private fun climb(view: View, travel: Float) {
        val start = SystemClock.uptimeMillis()
        // Matched to the descent, which the behaviour settles in about this
        // long: a sheet that takes longer to arrive than to leave feels slow.
        val duration = 400f
        val ease = android.view.animation.DecelerateInterpolator()
        val choreographer = android.view.Choreographer.getInstance()
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val t = ((SystemClock.uptimeMillis() - start) / duration).coerceIn(0f, 1f)
                view.translationY = travel * (1f - ease.getInterpolation(t))
                if (t < 1f && view.isAttachedToWindow) {
                    choreographer.postFrameCallback(this)
                }
            }
        })
    }

    /**
     * Fades a card in or out, clocked by hand.
     *
     * Not ViewPropertyAnimator: every duration it is given is multiplied by
     * the system's animator scale, and with that scale off — which is how
     * this phone is set up — a fade becomes a snap. The same trap that made
     * the bottom sheets appear out of nowhere had the cards changing mode
     * with a jump cut, and the world-clock bubbles popping instead of
     * arriving.
     */
    /** True while the move in flight crosses to another page as well. */
    private var cardMoveIsDiagonal = false

    /**
     * Slides a card in or out vertically, hand-clocked.
     *
     * [direction] is where the card comes from and goes to: +1 below, -1
     * above, and 0 for no movement at all, which is what a diagonal gets —
     * the page underneath has already changed, so there is nothing to slide
     * away from and the hands carry the eye instead.
     *
     * Not a ValueAnimator, like everything else that moves here: the system
     * animator scale is off on the author's phone and anything on one
     * teleports.
     */
    private fun slideCard(view: View?, show: Boolean, direction: Float) {
        val target = view ?: return
        val token = (fadeTokens[target] ?: 0) + 1
        fadeTokens[target] = token
        if (!show && target.visibility == View.GONE) return
        val travel = (target.height.takeIf { it > 0 } ?: pager.height).toFloat()
        if (direction == 0f || travel <= 0f) {
            target.translationY = 0f
            target.alpha = shownAlpha(target)
            target.visibility = if (show) View.VISIBLE else View.GONE
            if (show) target.bringToFront()
            return
        }
        val from = if (show) travel * direction else 0f
        val to = if (show) 0f else -travel * direction
        target.translationY = from
        target.alpha = shownAlpha(target)
        if (show) {
            target.visibility = View.VISIBLE
            target.bringToFront()
        }
        val start = SystemClock.uptimeMillis()
        val ease = android.view.animation.AccelerateDecelerateInterpolator()
        val choreographer = android.view.Choreographer.getInstance()
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (fadeTokens[target] != token) return
                val t = ((SystemClock.uptimeMillis() - start) / CARD_SLIDE_MS).coerceIn(0f, 1f)
                target.translationY = from + (to - from) * ease.getInterpolation(t)
                if (t < 1f && target.isAttachedToWindow) {
                    choreographer.postFrameCallback(this)
                } else {
                    target.translationY = 0f
                    if (!show) target.visibility = View.GONE
                }
            }
        })
    }

    /**
     * The dial that was on screen when a move began.
     *
     * Noted before anything changes, not looked up afterwards. Changing the
     * page fires onPageSelected, which calls carryFallenHands, which moves
     * lastVisibleDial on to the *arriving* dial — so by the time the cards
     * were swapped the outgoing face was already forgotten and every dial
     * was handed its own angles, with nowhere to travel. Which is exactly
     * what "the stopwatch appears out of nowhere" looks like.
     */
    private var handOverSource: ClockView? = null

    /** Hands the outgoing dial's angles to the incoming one. */
    private fun handOverHands() {
        val from = handOverSource ?: lastVisibleDial ?: return
        handOverSource = null
        val to = dialFor(pager.currentItem, mode)
        if (to != null && to !== from) to.handOverFrom(from)
    }

    private fun fadeCard(
        view: View?,
        show: Boolean,
        durationMs: Float = CARD_FADE_MS,
        raise: Boolean = true
    ) {
        val target = view ?: return
        val full = shownAlpha(target)
        if (show && target.visibility == View.VISIBLE && target.alpha == full) return
        if (!show && target.visibility == View.GONE) return
        if (show && target.visibility != View.VISIBLE) {
            target.alpha = 0f
            target.visibility = View.VISIBLE
            // Both cards are on screen while they dissolve, stacked in the
            // same frame. The arriving one goes on top so that a tap during
            // those few hundred milliseconds reaches the card being asked
            // for, not the ghost of the one leaving. Not for the bubble layer,
            // which lives inside a card and must stay under its buttons.
            if (raise) target.bringToFront()
        }
        val from = target.alpha
        val to = if (show) full else 0f
        val start = SystemClock.uptimeMillis()
        val ease = android.view.animation.AccelerateDecelerateInterpolator()
        val choreographer = android.view.Choreographer.getInstance()
        // One token per card, not one for all of them: six cards cross-fade at
        // the same time on every mode change, and a single shared counter meant
        // each new call killed the five before it — only the last card moved.
        val token = (fadeTokens[target] ?: 0) + 1
        fadeTokens[target] = token
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                // A newer fade on this card cancels the older one, so a fast
                // flick between modes cannot leave a card half-faded.
                if (fadeTokens[target] != token) return
                val t = ((SystemClock.uptimeMillis() - start) / durationMs).coerceIn(0f, 1f)
                target.alpha = from + (to - from) * ease.getInterpolation(t)
                if (t < 1f && target.isAttachedToWindow) {
                    choreographer.postFrameCallback(this)
                    return
                }
                if (!show) target.visibility = View.GONE
                // Done: drop the bookkeeping so the map does not hold on to
                // page views the pager has since thrown away.
                fadeTokens.remove(target)
            }
        })
    }

    /**
     * How opaque a card is when fully shown. Normally solid — but the alarms
     * card is dimmed at night, and the fade has to land on that value instead
     * of undoing it.
     */
    private fun shownAlpha(view: View): Float =
        if (view === alarmsContainer && appliedNightDim) NIGHT_DIM_ALPHA else 1f

    private val fadeTokens = HashMap<View, Int>()

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

    /**
     * The cards themselves live in AlarmCards. What stays here is the data
     * and the editor; these delegates keep the rest of the file reading as
     * it did, and are the whole of the seam.
     */
    private fun miniDial(hour: Int, minute: Int): ClockView = alarmCards.miniDial(hour, minute)

    private fun fillAlarmDials(row: LinearLayout, times: List<Pair<Int, Int>>) =
        alarmCards.fillDials(row, times)

    private fun weekdayOrder(): List<Int> = alarmCards.weekdayOrder()

    private fun weekdayLetters(): List<String> = alarmCards.weekdayLetters()

    private fun soundLabel(sound: String): String = alarmCards.soundLabel(sound)

    private fun leadLabel(minutes: Int): String = alarmCards.leadLabel(minutes)

    private val leadChoicesList: List<Int> get() = alarmCards.leadChoicesList

    private fun pickFromList(
        titleRes: Int,
        labels: List<String>,
        checked: Int,
        onPicked: (Int) -> Unit
    ) = alarmCards.pickFromList(titleRes, labels, checked, onPicked)

    private fun pickSound(current: String, allowCustom: Boolean, onPicked: (String) -> Unit) =
        alarmCards.pickSound(current, allowCustom, onPicked)

    /** C-1's editor, which lives in its own file; this is the way in. */
    private fun showReminderSheet(
        existing: Reminder?,
        year: Int,
        month: Int,
        day: Int,
        seed: ReminderDraft? = null
    ) = reminderSheet.show(existing, year, month, day, seed)

    /**
     * The alarm editor, Google-Clock style: a bottom sheet with the time, the
     * weekday strip, the options as plain rows, and delete/save in opposite
     * corners. Deleting asks first.
     */
    /** C1's editor, which lives in its own file; this is the way in. */
    private fun showAlarmSheet(alarm: Alarm, seed: Alarm? = null) =
        alarmSheet.show(alarm, seed)

    /** Copies a sheet draft back onto the real alarm (adding it if new). */
    private fun commitDraft(target: Alarm, draft: Alarm, isNew: Boolean) {
        target.hour = draft.hour
        target.minute = draft.minute
        // A new alarm arrives switched on; an existing one keeps whatever its
        // switch says. Renaming a sleeping alarm must not wake it.
        if (isNew) target.enabled = true
        target.sound = draft.sound
        target.soundUri = draft.soundUri
        target.daysMask = draft.daysMask
        target.snoozeMinutes = draft.snoozeMinutes
        target.label = draft.label
        target.notes = draft.notes
        target.vibrate = draft.vibrate
        target.flash = draft.flash
        target.durationMinutes = draft.durationMinutes
        target.extraTimes = draft.extraTimes.toMutableList()
        if (isNew && !alarms.contains(target)) alarms.add(target)
        persistAlarms()
    }

    /**
     * Off to C0 to wind the hands, whatever it is we are winding.
     *
     * One departure for all four jobs. It used to be four copies of the same
     * four lines with a different set of fields poked before each — which is
     * how one of them came to clear a flag the exit still needed.
     */
    /** Every dial tidy again, and the bubbles docked with them. */
    private fun reassembleEverything() {
        prefs.edit().putFloat(Prefs.DIAL_SCALE, 1f).apply()
        clockView?.reassembleAll()
        countdownClockView?.reassembleAll()
        stopwatchClockView?.reassembleAll()
        worldBubbles.reassembleAll()
        healBubbleClocks()
        worldBubbles.dock()
    }

    private fun startDial(job: DialJob, startMs: Long, magnetOrigin: Long = 0L) {
        // You cannot wind a time onto hands that are lying at the bottom of
        // the dial, and from this screen there is no way to pick them up:
        // the crown and the panic button are both gone while setting. The
        // only route was out, tidy up, and come back — so the dial tidies
        // itself on the way in. Knocking the hands off is a game; being
        // unable to set an alarm is not.
        if (sceneIsDisarranged()) reassembleEverything()
        dialJob = job
        dialMagnetOrigin = magnetOrigin
        alarmWorkingMs = startMs
        mode = Mode.CLOCK
        pager.currentItem = PAGE_HOME
        applyAlarmSetUi()
    }

    /**
     * The hands are where you want them. Each job knows what to do with the
     * value, and the `when` is exhaustive: a fifth kind of job cannot be
     * added without answering here.
     */
    private fun confirmAlarmSet() {
        val job = dialJob ?: return
        // Wrap into a day so over/under-winding still lands on a valid time.
        val dayMs = 86_400_000L
        val ms = ((alarmWorkingMs % dayMs) + dayMs) % dayMs
        val hour = (ms / 3_600_000L).toInt()
        val minute = (ms / 60_000L % 60L).toInt()
        dialJob = when (job) {
            is DialJob.AlarmTime -> {
                job.target.setTime(job.timeIndex, hour, minute)
                // The sheet edits a copy and is about to come back: the copy
                // has to learn what was wound, or it would reopen showing the
                // old time and write it back on save.
                job.draft.setTime(job.timeIndex, hour, minute)
                persistAlarms()
                // Confirmed, so it is an alarm like any other now — no longer
                // the provisional one the exit would take away.
                job.copy(isNew = false)
            }
            is DialJob.AlarmLength -> {
                val (h, m) = job.draft.timeAt(0)
                job.draft.durationMinutes = spanFrom(h * 60 + m)
                job
            }
            is DialJob.ReminderTime -> job.copy(draft = job.draft.copy(hour = hour, minute = minute))
            is DialJob.ReminderLength ->
                job.copy(
                    draft = job.draft.copy(
                        duration = spanFrom(job.draft.hour * 60 + job.draft.minute)
                    )
                )
        }
        exitAlarmSetMode()
    }

    /**
     * How long the hands have been carried past [startMinutes], in minutes.
     *
     * The length dial starts at the thing's own hour, so what is wound is
     * the moment it ends and the length is the distance between them. Wound
     * backwards past its start it wraps forward a whole day rather than
     * going negative, because an event cannot last minus twenty minutes.
     */
    private fun spanFrom(startMinutes: Int): Int {
        val wound = (alarmWorkingMs / 60_000L).toInt()
        return (((wound - startMinutes) % 1440) + 1440) % 1440
    }

    /**
     * Done with the dial, confirmed or not. Every job goes back to the sheet
     * it left, on the card that sheet belongs to — which is the whole of what
     * the last two bug reports were about.
     */
    private fun exitAlarmSetMode() {
        val job = dialJob
        dialJob = null
        // An alarm that only existed so the dial had a target, and never got
        // a time confirmed, was never really created. Confirming clears the
        // flag above, so only a cancelled one is still marked new here.
        if (job is DialJob.AlarmTime && job.isNew) {
            if (alarms.removeAll { it.id == job.draft.id }) persistAlarms()
        }
        applyAlarmSetUi()
        pager.currentItem = when (job) {
            is DialJob.ReminderTime, is DialJob.ReminderLength -> PAGE_LEFT
            else -> PAGE_RIGHT
        }
        when (job) {
            is DialJob.AlarmTime -> showAlarmSheet(job.target, job.draft)
            is DialJob.AlarmLength -> showAlarmSheet(job.target, job.draft)
            is DialJob.ReminderTime, is DialJob.ReminderLength -> {
                val d = if (job is DialJob.ReminderTime) job.draft
                else (job as DialJob.ReminderLength).draft
                showReminderSheet(d.existing, d.year, d.month, d.day, d)
            }
            null -> Unit
        }
    }

    private var backOutOfSetMode: androidx.activity.OnBackPressedCallback? = null

    private fun applyAlarmSetUi() {
        val active = dialJob != null
        backOutOfSetMode?.isEnabled = active
        alarmSetBanner?.visibility = if (active) View.VISIBLE else View.GONE
        alarmSetLabel?.setText(
            when {
                dialJob?.isLength == true -> R.string.set_duration
                dialJob is DialJob.ReminderTime -> R.string.set_reminder_time
                else -> R.string.set_alarm_time
            }
        )
        pager.isUserInputEnabled = !active
        applyMode()
    }

    /**
     * Chronological, by the earliest time each alarm rings at — which is the
     * time its card leads with. Alarms that share it keep a fixed order
     * rather than shuffling on every save.
     */
    private fun sortAlarms() {
        alarms.sortWith(
            compareBy(
                { it.allTimes().first().let { (h, m) -> h * 60 + m } },
                { it.id }
            )
        )
    }

    /**
     * Catches the screen up with whatever changed the lists while this
     * activity was away — the assistant adding an alarm, a one-shot retiring
     * itself when it rang.
     *
     * There is nothing to re-read any more: it is the same list they changed.
     * What is left is to put it back in order and repaint, which is all this
     * ever really needed to do. It used to reload from disk into a private
     * copy, which was the patch for the bug that a single owner removes.
     */
    private fun refreshFromStores() {
        sortAlarms()
        refreshAlarmsUi()
        refreshCalendarMarks()
    }

    @Suppress("NotifyDataSetChanged")
    private fun persistAlarms() {
        sortAlarms()
        AlarmStore.save(this)
        AlarmScheduler.update(this)
        refreshAlarmsUi()
    }

    private fun refreshAlarmsUi() {
        val recycler = alarmsRecycler
        if (recycler != null && recycler.isComputingLayout) {
            recycler.post { refreshAlarmsUi() }
            return
        }
        // One notify does it. The second pass that used to live here was
        // chasing a layout request I thought the nested lists were losing;
        // the cards were really stale because the sheet shared the alarm's
        // own list of times, so there was nothing new to show. Fixed at the
        // source, this is just work.
        alarmCards.adapter.notifyDataSetChanged()
        alarmsEmpty?.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
        updateAlarmMarkers()
    }

    /** The day the marks currently on the dial were built for. */
    private var markedDayMs = 0L

    /**
     * Sectograph-style: enabled alarms as accent wedges on the clock dial.
     *
     * Built for the day the dial is *showing*, not for today — carry the
     * hour hand round past midnight and this runs again for tomorrow, so
     * Saturday's weekday alarms go away and Saturday's appointments arrive.
     * Naming them is the dial's own job: run the hour hand over a mark and
     * it says what it is.
     */
    private fun updateAlarmMarkers() {
        val show = prefs.getBoolean(Prefs.ALARM_MARKERS, true)
        markedDayMs = clockView?.shownWallMs() ?: TimeKeeper.nowMs()
        clockView?.alarmMarkers = if (!show) {
            emptyList()
        } else {
            val n = readHoursOnDial()
            // Each dot carries the half of the day it belongs to, which is
            // the one thing its position on a twelve-hour face cannot say.
            val shownDow = Calendar.getInstance().apply { timeInMillis = markedDayMs }
                .get(Calendar.DAY_OF_WEEK)
            val alarmDots = alarms
                .filter {
                    it.enabled && it.durationMinutes <= 0 && it.ringsOn(shownDow)
                }
                .flatMap { alarm -> alarm.allTimes().map { alarm to it } }
                .map { (alarm, time) ->
                    val (h, m) = time
                    DialMark(
                        (h + m / 60f) % n / n * 360f,
                        DayNight.isDarkAt(h, m),
                        fromCalendar = false,
                        label = alarm.label.ifBlank { getString(R.string.alarm_untitled) },
                        notes = alarm.notes
                    )
                }
            // Instant reminders join the alarm dots; ones with a duration
            // become wedges instead. They carry the ring that says "today
            // only", which is the whole difference between them.
            val reminderDots = remindersOn(markedDayMs)
                .filter { it.durationMinutes <= 0 }
                .map {
                    DialMark(
                        (it.hour + it.minute / 60f) % n / n * 360f,
                        DayNight.isDarkAt(it.hour, it.minute),
                        fromCalendar = true,
                        label = it.label.ifBlank { getString(R.string.reminder_untitled) },
                        notes = it.notes
                    )
                }
            alarmDots + reminderDots
        }
        clockView?.eventArcs = if (!show) {
            emptyList()
        } else {
            val n = readHoursOnDial()
            // An alarm with a duration is an event too, and reads as a wedge.
            val shownDow = Calendar.getInstance().apply { timeInMillis = markedDayMs }
                .get(Calendar.DAY_OF_WEEK)
            val alarmArcs = alarms
                .filter { it.enabled && it.durationMinutes > 0 && it.ringsOn(shownDow) }
                .flatMap { alarm ->
                    alarm.allTimes().map { (h, m) ->
                        DialArc(
                            (h + m / 60f) % n / n * 360f,
                            alarm.durationMinutes / 60f / n * 360f,
                            DayNight.isDarkAt(h, m),
                            fromCalendar = false,
                            label = alarm.label.ifBlank { getString(R.string.alarm_untitled) },
                            notes = alarm.notes,
                            startMinute = h * 60 + m,
                            endMinute = h * 60 + m + alarm.durationMinutes
                        )
                    }
                }
            val reminderArcs = remindersOn(markedDayMs)
                .filter { it.durationMinutes > 0 }
                .map { reminder ->
                    DialArc(
                        (reminder.hour + reminder.minute / 60f) % n / n * 360f,
                        reminder.durationMinutes / 60f / n * 360f,
                        DayNight.isDarkAt(reminder.hour, reminder.minute),
                        fromCalendar = true,
                        label = reminder.label.ifBlank { getString(R.string.reminder_untitled) },
                        notes = reminder.notes,
                        startMinute = reminder.hour * 60 + reminder.minute,
                        endMinute = reminder.hour * 60 + reminder.minute + reminder.durationMinutes
                    )
                }
            alarmArcs + reminderArcs
        }
    }

    private fun isPastDay(year: Int, month: Int, day: Int): Boolean {
        val now = Calendar.getInstance().apply { timeInMillis = TimeKeeper.nowMs() }
        return when {
            year != now.get(Calendar.YEAR) -> year < now.get(Calendar.YEAR)
            month != now.get(Calendar.MONTH) + 1 -> month < now.get(Calendar.MONTH) + 1
            else -> day < now.get(Calendar.DAY_OF_MONTH)
        }
    }

    /**
     * What is on today, repeats included.
     *
     * This compared the three numbers directly, which is the same as asking
     * "was it first set for today" — so a weekly, monthly or yearly reminder
     * marked the dial on its original date and never again, while the
     * calendar beside it marked every occurrence. occursOn is the one place
     * that knows the rule, and now this asks it.
     */
    private fun remindersOn(whenMs: Long): List<Reminder> {
        val today = Calendar.getInstance().apply { timeInMillis = whenMs }
        val y = today.get(Calendar.YEAR)
        val m = today.get(Calendar.MONTH) + 1
        val d = today.get(Calendar.DAY_OF_MONTH)
        return reminders.filter { it.occursOn(y, m, d) }
    }

    // ---------------------------------------------------------- reminders

    private fun refreshCalendarMarks() {
        val cal = calendarView ?: return
        // A repeating reminder is on the calendar every time it comes round,
        // so the marks are asked date by date rather than read off the one
        // date it was created on.
        val daysInMonth = Calendar.getInstance().apply {
            set(cal.shownYear, cal.shownMonth1 - 1, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
        // Split by the half of the day each one falls in, so the calendar
        // says the same thing the dial does.
        cal.morningDays = (1..daysInMonth).filter { day ->
            reminders.any {
                it.occursOn(cal.shownYear, cal.shownMonth1, day) &&
                    !DayNight.isDarkAt(it.hour, it.minute)
            }
        }.toSet()
        cal.eveningDays = (1..daysInMonth).filter { day ->
            reminders.any {
                it.occursOn(cal.shownYear, cal.shownMonth1, day) &&
                    DayNight.isDarkAt(it.hour, it.minute)
            }
        }.toSet()
        // Year view dots every busy day of the whole year at once.
        val marks = mutableSetOf<Int>()
        val probe = Calendar.getInstance()
        for (m in 1..12) {
            probe.set(cal.shownYear, m - 1, 1)
            for (d in 1..probe.getActualMaximum(Calendar.DAY_OF_MONTH)) {
                if (reminders.any { it.occursOn(cal.shownYear, m, d) }) {
                    marks.add((m - 1) * 100 + d)
                }
            }
        }
        cal.yearMarks = marks
    }

    /**
     * Writes a reminder down, keeping its identity when it is an edit rather
     * than minting a new one — an edited reminder used to come back with a
     * fresh id, which is a different reminder wearing the same name.
     */
    private fun commitReminder(existing: Reminder?, d: ReminderDraft) {
        existing?.let { reminders.remove(it) }
        reminders.add(
            Reminder(
                existing?.id ?: ReminderStore.nextId(reminders),
                d.year, d.month, d.day, d.hour, d.minute, d.label,
                d.duration, d.rings, d.sound, d.lead, d.repeat, d.notes
            )
        )
        persistReminders()
        if (d.rings) maybeRequestNotificationPermission()
    }

    private fun persistReminders() {
        ReminderStore.save(this)
        AlarmScheduler.update(this)
        refreshCalendarMarks()
        updateAlarmMarkers()
    }

    private fun onCalendarDayTap(day: Int) {
        val cal = calendarView ?: return
        val year = cal.shownYear
        val month = cal.shownMonth1
        val dayReminders = reminders.filter { it.occursOn(year, month, day) }
        if (dayReminders.isEmpty()) {
            showReminderSheet(null, year, month, day)
            return
        }
        val items = (
            dayReminders.map {
                String.format(
                    Locale.US, "%02d:%02d  %s",
                    it.hour, it.minute,
                    it.label.ifBlank { getString(R.string.reminder_untitled) }
                )
            } + getString(R.string.reminder_add)
            ).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(String.format(Locale.US, "%02d/%02d/%04d", day, month, year))
            .setItems(items) { _, which ->
                if (which < dayReminders.size) {
                    showReminderSheet(dayReminders[which], year, month, day)
                } else {
                    showReminderSheet(null, year, month, day)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------- world-clock bubbles

    /** The world clocks live in their own file now; this is the handle. */
    private val bubblePhysics = object : Runnable {
        override fun run() {
            worldBubbles.step()
            handler.postDelayed(this, 16L)
        }
    }

    /**
     * Escalating damage, knock by knock: hands first (bubbles break loose
     * and a third of their movements seize up or run backwards), then the
     * numerals a third at a time, and finally the moon and the date.
     */
    private var knockCount = 0

    private fun onDialKnocked() {
        knockCount++
        worldBubbles.free()
        when (knockCount) {
            1 -> worldBubbles.seize(1f / 3f)
            2 -> {
                worldBubbles.seize(1f)
                worldBubbles.knockSomeHandsOff()
            }
            else -> worldBubbles.knockSomeHandsOff()
        }
    }

    private fun healBubbleClocks() {
        worldBubbles.heal()
        knockCount = 0
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
        // One coarse fix, asked for once, and the sunrise equation carries
        // the rest of the year from it. It is asked on the first run rather
        // than behind an option, because the thing it unlocks — a sun on the
        // face when the sun is actually up — is the default behaviour of the
        // sky complication, not an advanced setting. Declined, everything
        // still works; the moon simply never gives way to the sun.
        if (DayNight.wantsLocation(this)) askForLocationOnce()
        DayNight.configure(this)
        val cv = clockView ?: return

        cv.hoursOnDial = readHoursOnDial()
        cv.dialShape = readDialShape()
        // Off while a time is being wound, whatever the setting says — see
        // applyMode(). Anything that reapplies preferences mid-wind would
        // otherwise put it back under the user's finger.
        cv.showSecondHand = dialJob == null && prefs.getBoolean(Prefs.SECOND_HAND, true)
        cv.smoothSeconds = prefs.getBoolean(Prefs.SMOOTH_SECONDS, false)
        cv.mirrored = prefs.getBoolean(Prefs.MIRROR, false)
        cv.numeralStyle = readNumeralStyle()
        // Off while a time is being wound, like the second hand it belongs
        // to — see applyMode().
        cv.fastHand = if (dialJob != null) ClockView.FastHandMode.NONE else readFastHand()
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
        // While a time is being wound the sky stays on whatever the setting
        // says, because there it is feedback rather than decoration — see
        // applyMode(). Without this, anything that reapplies preferences
        // mid-wind would take it away under the user's finger.
        cv.showMoonPhase = dialJob != null || prefs.getBoolean(Prefs.MOON_PHASE, false)
        updateAlarmMarkers()
        cv.touchHandsEnabled = prefs.getBoolean(Prefs.TOUCH_HANDS, true)
        cv.pinchZoomEnabled = prefs.getBoolean(Prefs.PINCH_ZOOM, true)
        cv.shakeDropEnabled = prefs.getBoolean(Prefs.SHAKE_DROP, true)
        cv.dialScale = prefs.getFloat(Prefs.DIAL_SCALE, 1f)
        cv.setSelectedHours(
            prefs.getStringSet(Prefs.SELECTED_HOURS, emptySet())
                .orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
        )
        cv.onSelectedHoursChanged = { hours ->
            prefs.edit()
                .putStringSet(Prefs.SELECTED_HOURS, hours.map { it.toString() }.toSet())
                .apply()
        }

        worldBubbles.rebuild()
        worldBubbles.applyStyle(cv)

        // The chrono dials mirror the clock's styling — shape, scale and all
        // — so every face is the same size. They stay touchable regardless
        // of the grab-hands preference: winding is how you set them.
        for (dial in listOfNotNull(countdownClockView, stopwatchClockView)) {
            dial.hoursOnDial = cv.hoursOnDial
            dial.dialShape = cv.dialShape
            // Read from the settings rather than copied off C0: while a
            // time is being wound C0 puts its own second and tenths hands
            // away, and mirroring it took them off the stopwatch and the
            // countdown too — where they are the whole point — until the
            // next time preferences happened to be applied.
            dial.showSecondHand = prefs.getBoolean(Prefs.SECOND_HAND, true)
            dial.smoothSeconds = cv.smoothSeconds
            dial.mirrored = cv.mirrored
            dial.numeralStyle = cv.numeralStyle
            dial.fastHand = readFastHand()
            dial.theme = cv.theme
            dial.touchHandsEnabled = true
            dial.pinchZoomEnabled = cv.pinchZoomEnabled
            dial.dialScale = cv.dialScale
        }

        calendarView?.let {
            it.theme = cv.theme
            it.numeralStyle = cv.numeralStyle
            it.pastStyle = when (prefs.getString(Prefs.PAST_DAYS, Prefs.PAST_NONE)) {
                Prefs.PAST_DIM -> CalendarPageView.PastStyle.DIM
                Prefs.PAST_CROSS -> CalendarPageView.PastStyle.CROSS
                Prefs.PAST_RING -> CalendarPageView.PastStyle.RING
                else -> CalendarPageView.PastStyle.NONE
            }
            it.weekStartsMonday = prefs.getBoolean(
                Prefs.WEEK_START_MONDAY,
                Calendar.getInstance().firstDayOfWeek == Calendar.MONDAY
            )
            it.birthday = prefs.getInt(Prefs.BIRTHDAY, 0)
        }
        refreshCalendarMarks()
        s3Sand?.let {
            it.theme = cv.theme
            it.maxGrains = prefs.getInt(Prefs.SAND_GRAINS, 600)
            it.glassScale = cv.dialScale
            it.setTime(countdownTotalMs, countdownRemaining())
        }
        syncS3DurationChecks()

        // Night dims the alarms card too — it was the only bright one left.
        // Routed through the same fade rather than assigned: a raw alpha here
        // would fight whatever cross-fade the card is in the middle of, and
        // this way the dim itself arrives gently when 22:00 comes round.
        if (mode != Mode.CHRONO) fadeCard(alarmsContainer, true)
        // Hands or digits, and the shape and hour count the faces wear, are
        // all settings: the cards are rebuilt so they follow.
        alarmsRecycler?.let { if (!it.isComputingLayout) refreshAlarmsUi() }

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

    /**
     * One prompt, shared by sundial mode and the sky complication.
     *
     * Once per run, and — once the user has been asked and said no — never
     * again on its own. Being asked for your location every time you open a
     * clock is the behaviour of an app that does not take no for an answer.
     * The settings entry still asks, because that is the user asking.
     */
    private fun askForLocationOnce() {
        // Permission first, then the once-per-run guard. The other way round
        // — as it was — swallowed the one call that matters: granting the
        // permission calls back into here, and the guard was already set by
        // the request that produced the grant, so the fix was never read
        // until the next launch.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            if (readLongitude() == null) requestOneFix()
            return
        }
        if (locationAskedThisRun) return
        if (prefs.getBoolean(Prefs.LOCATION_ASKED, false)) return
        locationAskedThisRun = true
        prefs.edit().putBoolean(Prefs.LOCATION_ASKED, true).apply()
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /**
     * Asks for one fix when the system has no last-known location to give.
     *
     * A phone that has not been asked where it is by anything else has an
     * empty cache, and then "one measurement serves the whole year" never
     * gets its one measurement. This listens for a single update and takes
     * itself off again — a clock has no business holding a location
     * subscription open.
     */
    private fun requestOneFix() {
        if (locationFixPending) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> return
        }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                try {
                    lm.removeUpdates(this)
                } catch (e: SecurityException) {
                    // Permission withdrawn mid-flight; nothing left to stop.
                }
                locationFixPending = false
                prefs.edit()
                    .putFloat(Prefs.LAST_LONGITUDE, location.longitude.toFloat())
                    .putFloat(Prefs.LAST_LATITUDE, location.latitude.toFloat())
                    .apply()
                DayNight.configure(this@MainActivity)
                clockView?.invalidate()
            }

            // Required on API levels below 30, where the default methods of
            // LocationListener do not exist yet.
            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        try {
            locationFixPending = true
            lm.requestLocationUpdates(provider, 0L, 0f, listener, mainLooper)
        } catch (e: SecurityException) {
            locationFixPending = false
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
            // Both halves of the fix: the sunrise equation needs the
            // latitude, and one measurement then serves the whole year.
            prefs.edit()
                .putFloat(Prefs.LAST_LONGITUDE, it.longitude.toFloat())
                .putFloat(Prefs.LAST_LATITUDE, it.latitude.toFloat())
                .apply()
            return it.longitude
        }
        return if (prefs.contains(Prefs.LAST_LONGITUDE)) {
            prefs.getFloat(Prefs.LAST_LONGITUDE, 0f).toDouble()
        } else {
            null
        }
    }

    private fun readFastHand(): ClockView.FastHandMode =
        when (prefs.getString(Prefs.FAST_HAND, Prefs.FAST_HAND_NONE)) {
            Prefs.FAST_HAND_TENTHS -> ClockView.FastHandMode.TENTHS
            Prefs.FAST_HAND_DECIMAL_MINUTE -> ClockView.FastHandMode.DECIMAL_MINUTE
            else -> ClockView.FastHandMode.NONE
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
        handOverSource = visibleDial()
        mode = if (mode == Mode.CLOCK) Mode.CHRONO else Mode.CLOCK
        applyMode()
    }

    /**
     * A flick down goes to the chronograph row, a flick up comes back.
     *
     * The two rows are above and below one another, so the gesture that
     * moves between them is the one that points that way. It replaces
     * nothing — the button in the middle still does it — but it is the move
     * the layout was always describing and there was no way to make.
     */
    private fun swipeRows(up: Boolean): Boolean {
        if (dialJob != null) return false
        val wanted = if (up) Mode.CLOCK else Mode.CHRONO
        if (mode == wanted) return false
        handOverSource = visibleDial()
        mode = wanted
        applyMode()
        return true
    }

    /**
     * The diagonals: from the clock straight to the stopwatch or the
     * countdown, without going down to the hourglass and across.
     *
     * The page changes without scrolling. A slide would be the pager saying
     * "you have moved sideways", and you have not — you have gone from a
     * clock to a chronograph, which is the same instrument in a different
     * job, and the hands travelling say so far better than a card sliding
     * past. That is the whole point of the two buttons.
     */
    private fun goToChrono(page: Int) = goDiagonal(page, Mode.CHRONO)

    /**
     * A diagonal move: another row *and* another page, in one gesture.
     *
     * The bubbles go first, on their own fade — they belong to the clock
     * and there is no room for them where we are going. Then the page
     * changes without scrolling and the row with it, both in the same
     * frame, and the arriving dial starts with the hands the leaving one
     * had and walks them across. Two dials the same size in the same place
     * with their hands in the same position: the cut between them is the
     * one frame nobody sees, and what is left is a watch changing its job.
     */
    private fun goDiagonal(page: Int, wanted: Mode) {
        if (dialJob != null) return
        val from = visibleDial()
        val hop = {
            handOverSource = from
            mode = wanted
            cardMoveIsDiagonal = pager.currentItem != page
            if (pager.currentItem != page) pager.setCurrentItem(page, false)
            applyMode()
        }
        // Only worth waiting for if there is something to watch leave.
        if (bubbleLayer?.visibility == View.VISIBLE && (bubbleLayer?.alpha ?: 0f) > 0f) {
            fadeCard(bubbleLayer, false, raise = false)
            handler.postDelayed(hop, CARD_FADE_MS.toLong())
        } else {
            hop()
        }
    }

    /**
     * Back to C0 from anywhere, in one move. The mode flips *first* so the
     * clock is already dressed as a clock while the pager glides home —
     * waiting for the scroll to settle made the hourglass flash past and
     * the card blink as it swapped underneath.
     */
    private fun goHomeToClock() = goDiagonal(PAGE_HOME, Mode.CLOCK)

    /** Every dial shares one pinch scale; [source] is the one being pinched. */
    private fun shareDialScale(scale: Float, source: ClockView?) {
        prefs.edit().putFloat(Prefs.DIAL_SCALE, scale).apply()
        for (dial in listOfNotNull(clockView, stopwatchClockView, countdownClockView)) {
            if (dial !== source) dial.dialScale = scale
        }
        s3Sand?.glassScale = scale
        // Growing the main dial shoves any bubble it swallows out of the way.
        if (source === clockView) worldBubbles.kickFromDial()
    }

    /**
     * The fallen pieces follow you from card to card.
     *
     * ClockView has had syncFallenFrom since the beginning and lost its only
     * caller when the cards were reordered, so knocking the hands off the
     * clock and swiping to the stopwatch quietly tidied the workshop. Only
     * C0 can shed hands — the chrono faces refuse to, being wound by hand —
     * so the mess hops from the last dial you looked at to the next one,
     * and a piece dragged home on either face stays home on both.
     */
    private var lastVisibleDial: ClockView? = null

    private fun visibleDial(): ClockView? = dialFor(pager.currentItem, mode)

    /** Which dial a given card of a given row shows, if that card has one. */
    private fun dialFor(page: Int, forMode: Mode): ClockView? = when (page) {
        PAGE_LEFT -> if (forMode == Mode.CHRONO) stopwatchClockView else null
        PAGE_HOME -> if (forMode == Mode.CHRONO) null else clockView
        PAGE_RIGHT -> if (forMode == Mode.CHRONO) countdownClockView else null
        else -> null
    }

    private fun carryFallenHands() {
        val now = visibleDial() ?: return
        val before = lastVisibleDial
        if (before != null && before !== now) now.syncFallenFrom(before)
        lastVisibleDial = now
    }

    private fun sceneIsDisarranged(): Boolean =
        clockView?.isDisarranged() == true ||
            stopwatchClockView?.isDisarranged() == true ||
            countdownClockView?.isDisarranged() == true ||
            worldBubbles.anyMoving()

    private fun applyMode() {
        val chrono = mode == Mode.CHRONO
        val setting = dialJob != null
        clockView?.let {
            if (setting) {
                // C0 borrows the wind-to-set engine to pick an alarm time, a
                // reminder time, or how long an activity lasts.
                it.chronoProvider = alarmTimeProvider
                it.chronoSettable = true
                // On regardless of the setting while a time is being wound:
                // this is the one moment the sky has to be on the face,
                // because it is the feedback that says which seven you have
                // landed on. A length gets it too — it says whether the
                // thing ends in the light.
                it.showMoonPhase = true
                // And no second hand. Nobody sets an alarm for twenty past
                // seven and eleven seconds: the hand is one more thing to
                // catch by accident on a face where two others have to be
                // placed exactly, and it says nothing either of them does
                // not. The same goes for a length.
                it.showSecondHand = false
                // And the tenths hand with it: it is the second hand's own
                // decoration, and leaving it spinning on a face with no
                // second hand on it is the strangest of both worlds.
                it.fastHand = ClockView.FastHandMode.NONE
                // A length is a length, whoever it belongs to. Only the
                // reminder's used the countdown magnets before, so winding
                // "how long does this last" on an alarm snapped to the grid
                // meant for times of day.
                it.magnetProfile = if (dialJob?.isLength == true) {
                    ClockView.MagnetProfile.COUNTDOWN
                } else {
                    ClockView.MagnetProfile.ALARM
                }
                // The detents count from the hour the thing starts at, so
                // "and it lasts twenty minutes" has a magnet to land on.
                it.magnetOrigin = dialMagnetOrigin
                // Both jobs wind a time of day, and a day is twenty-four
                // hours long: past that the dial reads 00:00.
                it.chronoWrapsDay = true
            } else {
                it.chronoProvider = null
                it.chronoSettable = false
                // Back to a clock telling the time, so the sky goes back to
                // being the user's choice. Wind the hands forward with it on
                // and the sun sets under your finger, which is both the
                // proof the sunrise arithmetic works and the only way to
                // watch a whole day go past without waiting for one.
                it.showMoonPhase = prefs.getBoolean(Prefs.MOON_PHASE, false)
                it.showSecondHand = prefs.getBoolean(Prefs.SECOND_HAND, true)
                it.fastHand = readFastHand()
                it.magnetOrigin = 0L
                it.chronoWrapsDay = false
                it.magnetProfile = ClockView.MagnetProfile.COUNTDOWN
            }
            it.chronoButtons = false
        }
        stopwatchClockView?.chronoRunning = stopwatchRunning
        homeButtonRow?.visibility = if (setting) View.GONE else View.VISIBLE
        // Bubbles fade with the mode change, like the crown and pushers.
        fadeCard(bubbleLayer, !chrono && !setting, raise = false)
        settingsButton?.visibility = if (setting) View.GONE else View.VISIBLE
        // Every card follows the mode: clock / calendar / alarms against
        // hourglass / stopwatch / countdown.
        // Each pair dissolves into the other rather than cutting: the
        // stopwatch gives way to the calendar, the hourglass to the clock,
        // the countdown to the alarms — and the crown and pushers are
        // already fading on their own clock inside the dial.
        // The crown and the pushers belong to the chronograph row, and they
        // arrive with it: setting the flag is what starts their fade, so it
        // is set on every mode change rather than once when the page was
        // built — which is why they were simply always there.
        stopwatchClockView?.chronoButtons = chrono
        countdownClockView?.chronoButtons = chrono
        // The dial that is about to appear starts its hands where the one
        // leaving has them, and covers the distance itself. Seeded before
        // the swap, so the first frame it draws is already on its way.
        handOverHands()
        // The two rows are above and below one another, so the card that
        // arrives comes from that direction and the one that leaves goes
        // the opposite way. A cross-fade said nothing about where either of
        // them was; a slide says it without a word. Except on a diagonal,
        // where the page has already changed underneath and there is
        // nothing on screen to slide away from — there the hands do it.
        val slide = if (cardMoveIsDiagonal) 0f else if (chrono) 1f else -1f
        cardMoveIsDiagonal = false
        slideCard(clockContainer, !chrono, -slide)
        slideCard(hourglassContainer, chrono, slide)
        slideCard(calendarContainer, !chrono, -slide)
        slideCard(stopwatchContainer, chrono, slide)
        slideCard(alarmsContainer, !chrono, -slide)
        slideCard(countdownContainer, chrono, slide)
        carryFallenHands()
        updateCountdownUi()
    }

    private fun updateCountdownUi() {
        countdownClockView?.chronoSettable = !countdownRunning
        countdownClockView?.chronoRunning = countdownRunning
        s3Sand?.setTime(countdownTotalMs, countdownRemaining())
        sandStartStop?.setText(if (countdownRunning) R.string.chrono_pause else R.string.chrono_start)
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

    /** Start/Pause pusher on the stopwatch dial (S-1). */
    private fun toggleStartPause() {
        if (stopwatchRunning) {
            stopwatchAccumMs = stopwatchElapsed()
            stopwatchRunning = false
        } else {
            stopwatchStartedAt = SystemClock.elapsedRealtime()
            stopwatchRunning = true
        }
        stopwatchClockView?.chronoRunning = stopwatchRunning
    }

    private fun resetChrono() {
        stopwatchRunning = false
        stopwatchAccumMs = 0L
        stopwatchClockView?.chronoRunning = false
    }

    /** Start/Pause on the countdown dial (second page in chrono mode). */
    private fun toggleCountdown() {
        if (countdownRunning) {
            countdownRemainingMs = countdownRemaining()
            countdownRunning = false
            CountdownService.clearPublished(this)
        } else if (countdownRemaining() > 0L) {
            countdownEndsAt = SystemClock.elapsedRealtime() + countdownRemainingMs
            countdownTotalMs = countdownRemainingMs
            countdownRunning = true
            // Published straight away: the tile in the shade knows about a
            // countdown started in the app without the app telling it.
            CountdownService.publish(this, countdownEndsAt, countdownTotalMs)
        }
        updateCountdownUi()
    }

    private fun resetCountdown() {
        // Back to zero; the user winds the hands to set a new time.
        countdownRunning = false
        countdownRemainingMs = 0L
        CountdownService.clearPublished(this)
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
        const val EXTRA_OPEN_TIMER = "extra_open_timer"
        const val EXTRA_OPEN_CALENDAR = "extra_open_calendar"

        /** C-1 calendar / S-1 stopwatch. */
        const val PAGE_LEFT = 0

        /** C0 clock / S0 hourglass — where the app opens. */
        const val PAGE_HOME = 1

        /** C1 alarms / S1 countdown. */
        const val PAGE_RIGHT = 2

        private const val DEFAULT_COUNTDOWN_MS = 5 * 60_000L

        /**
         * How long a card takes to dissolve into the one behind it. Matched
         * to the 500 ms the crown and pushers already take to fade inside the
         * dial, so the whole mode change moves as one gesture.
         */
        private const val CARD_FADE_MS = 500f

        /**
         * How long a card takes to slide between the two rows. Shorter
         * than the fade it replaces: a movement reads as soon as it starts,
         * where a dissolve has to finish before you know what you are
         * looking at.
         */
        private const val CARD_SLIDE_MS = 320f

        /** The alarms card at night. */
        private const val NIGHT_DIM_ALPHA = 0.45f
    }
}

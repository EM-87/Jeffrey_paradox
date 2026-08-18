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

    /**
     * The way to put the hands back, opposite the way to the settings.
     *
     * It used to be a row three screens into the menu, which is the one
     * place you cannot look while looking at the mess it fixes — and it had
     * to be hunted for at the exact moment the app was least willing to be
     * navigated, because a dial with its hands on the floor is a dial you
     * cannot wind. On the glass, and only when there is something to put
     * back, it is a button that answers the question it raises.
     */
    private var reassembleButton: ImageButton? = null
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
    private var bellMarks = Bells.MARKS_HOUR
    private var tickingEnabled = false
    private var countdownPersistent = true

    private var lastHandledMinute = -1L

    // Chronograph state (elapsedRealtime-based, immune to time-speed games).
    /**
     * Which row of cards is showing. Was a two-valued Mode — clock or
     * chronograph — which is a fair description of two rows of three and
     * no description at all of a centre with cards above and below it.
     */
    private var row = Cards.HOME.row

    /** The card on screen: the row says which of the page's cards it is. */
    private fun current(): Card? = Cards.on(pager.currentItem, row)
    /**
     * The two chronographs, as arithmetic rather than as seven loose
     * fields and a line of it repeated wherever a number was wanted.
     *
     * The properties below are the same names the rest of this screen has
     * always used, pointing at the objects instead of at fields of their
     * own — so the seventy-odd places that read them did not have to be
     * rewritten to move the logic out. What moved is the part worth
     * testing: what a running total is, what stopping banks, and what a
     * countdown with nothing left does when you press start.
     */
    private val stopwatch = Chronograph { SystemClock.elapsedRealtime() }
    private val countdown = Countdown({ SystemClock.elapsedRealtime() }, DEFAULT_COUNTDOWN_MS)

    private var stopwatchAccumMs by stopwatch::accumMs
    private var stopwatchStartedAt by stopwatch::startedAt
    private val stopwatchRunning get() = stopwatch.running
    private var countdownRemainingMs by countdown::remainingMs
    private var countdownEndsAt by countdown::endsAt
    private val countdownRunning get() = countdown.running
    private var countdownTotalMs by countdown::totalMs

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
        if (row == Row.MIDDLE || !countdownRunning || countdownTotalMs <= 0L) return
        val newRemaining = (countdownTotalMs - countdownRemaining()).coerceAtLeast(0L)
        countdownEndsAt = SystemClock.elapsedRealtime() + newRemaining
        chimePlayer.playTick()
        updateCountdownUi()
    }

    /** Runs on (approximately) every second boundary, so ticks stay in step. */
    private val soundLoop = object : Runnable {
        override fun run() {
            val cv = clockView
            if (tickingEnabled && row == Row.MIDDLE && cv != null &&
                !cv.isSecondHandGrabbed() && !cv.isSecondHandFallen()
            ) {
                chimePlayer.playTick()
            }

            if (countdownRunning && countdownRemaining() == 0L) {
                countdown.reset()
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
            if (sandBlocked && countdownRunning && current() == Card.REVERSE) {
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

            showReassembleIfNeeded()

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
            dialIsObstacle = { row == Row.MIDDLE },
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
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                carryFallenHands()
                // Landing on the alarms: whatever happened while away — a
                // time wound on the dial, an alarm that rang — shows now.
                if (position == PAGE_RIGHT) refreshAlarmsUi()
            }
        })
        if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) {
            pager.post { goTo(Card.ALARM, scroll = false) }
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_TIMER, false)) {
            // From the countdown notification or the hourglass widget:
            // straight to whichever timer face is in use.
            pager.post { goTo(timerCard(), scroll = false) }
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_CALENDAR, false)) {
            // The card, not the page: asking for the calendar used to
            // land on whatever else happened to share its page.
            pager.post { goTo(Card.CALENDAR, scroll = false) }
        }

        // Back goes back. It never did: the app has one activity, so the
        // button between the other two fell straight through to the system
        // and closed it — from the alarms, from a running stopwatch, from
        // anywhere. Registered first of the two, so that winding a time
        // still gets first refusal on the gesture.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val target = Cards.back(current() ?: Cards.HOME)
                if (target != null) {
                    goTo(target)
                    return
                }
                // Standing on the clock there is nowhere further back, and
                // leaving is the right answer: step aside for one press and
                // let whatever the system does — finish, predictive back —
                // happen, then take the job back.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

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
     * The way out of an alarm that keeps coming back.
     *
     * A mission nobody passed books itself another go, and this is the
     * escape the whole thing was designed around: it takes finding the
     * app, reading a question and answering it — awake work, which is the
     * point, and not one tap from under the covers, which is what a button
     * on the ringing screen or in the notification shade would be.
     *
     * Asked every time the app comes back while one is booked, because the
     * one thing worse than an alarm that keeps returning is an alarm that
     * keeps returning and never says so.
     */
    private fun offerToCallOffTheNag() {
        if (!Nag.pending(prefs)) return
        if (nagDialog?.isShowing == true) return
        val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            .format(java.util.Date(Nag.bookedAt(prefs)))
        nagDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.nag_title)
            .setMessage(getString(R.string.nag_message, at, Nag.rounds(prefs)))
            .setPositiveButton(R.string.nag_call_off) { _, _ -> Nag.callOff(this) }
            .setNegativeButton(R.string.nag_leave_it, null)
            .show()
    }

    /** The dialog offering the way out, while it is on screen. */
    internal var nagDialog: androidx.appcompat.app.AlertDialog? = null
        private set

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
        if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) goTo(Card.ALARM)
        if (intent.getBooleanExtra(EXTRA_OPEN_TIMER, false)) goTo(timerCard())
        if (intent.getBooleanExtra(EXTRA_OPEN_CALENDAR, false)) goTo(Card.CALENDAR)
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
                countdown.reset()
                prefs.edit().remove(Prefs.COUNTDOWN_RESULT).apply()
                updateCountdownUi()
            }
            null -> {
                // A countdown the app never started — the assistant asked for
                // one while it was closed, or a shortcut did — is adopted
                // rather than ignored, so opening the app shows the real one.
                val endsAt = prefs.getLong(Prefs.COUNTDOWN_ENDS_AT, 0L)
                if (!countdownRunning && endsAt > SystemClock.elapsedRealtime()) {
                    countdown.adopt(endsAt, prefs.getLong(Prefs.COUNTDOWN_TOTAL, 60_000L))
                    updateCountdownUi()
                }
            }
            CountdownService.RESULT_EXTENDED -> {
                // A minute was bought from the shade while we were away.
                countdown.adopt(
                    prefs.getLong(Prefs.COUNTDOWN_ENDS_AT, countdownEndsAt),
                    prefs.getLong(Prefs.COUNTDOWN_TOTAL, countdownTotalMs)
                )
                prefs.edit().remove(Prefs.COUNTDOWN_RESULT).apply()
                updateCountdownUi()
            }
        }
        offerToCallOffTheNag()
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

    /**
     * Picks the stopwatch up where it was left, laps and all.
     *
     * Called when the card is built rather than in onCreate: the dial it
     * hands the laps to does not exist until then.
     */
    private fun restoreStopwatch() {
        val run = StopwatchStore.load(prefs) ?: return
        stopwatch.restore(run.accumMs, run.startedAt, run.running)
        stopwatchClockView?.importLaps(run.laps)
        stopwatchClockView?.chronoRunning = run.running
    }

    private fun saveStopwatch() {
        StopwatchStore.save(
            prefs,
            stopwatchAccumMs,
            stopwatchStartedAt,
            stopwatchRunning,
            stopwatchClockView?.exportLaps().orEmpty()
        )
    }

    /**
     * The volume keys as the chronograph's pushers.
     *
     * Only while a chronograph is the card on screen — and that turned out
     * to be the whole of the answer, so the setting that guarded it is
     * gone. It was there in case taking the volume keys away was a poor
     * trade, but there is nothing to trade: neither of these two cards
     * makes a sound, and nobody sits on one of them with something else
     * playing they want turned down. A preference nobody can have a reason
     * to change is a row of the menu spent on nothing.
     *
     * Up is start and stop, down is lap and reset, which is the order the
     * two pushers sit in on the case.
     *
     * Foreground only, and that is not a limitation to be fixed: hearing
     * these keys from the pocket needs an accessibility service, which is
     * a wildly disproportionate thing for a clock to ask for.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (takesVolumeKeys()) {
            val card = current()
            if (card == Card.STOPWATCH) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                        toggleStartPause(); return true
                    }
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        if (stopwatchRunning) {
                            lapChrono()
                        } else {
                            resetChrono()
                            stopwatchClockView?.clearLaps()
                        }
                        return true
                    }
                }
            } else if (card == Card.REVERSE) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                        toggleCountdown(); return true
                    }
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        resetCountdown(); return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * And the release of the same press.
     *
     * Consuming only the press is not enough: the window handles the
     * volume keys on the way up as well, and the half we left through was
     * the half that puts the volume slider on the screen. So the pusher
     * worked and a volume panel slid over the dial at the same time.
     */
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (takesVolumeKeys() &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Whether the card on screen is one whose pushers the keys work. */
    private fun takesVolumeKeys(): Boolean =
        dialJob == null && current().let { it == Card.STOPWATCH || it == Card.REVERSE }

    override fun onPause() {
        // A dialog outlives the window it was shown in unless it is told
        // otherwise, and the system says so out loud in the log.
        nagDialog?.dismiss()
        nagDialog = null
        // Written down every time the app goes to the background, because
        // that is the last moment it is certain to be alive: the system may
        // reclaim it at any point afterwards without another word.
        saveStopwatch()
        handler.removeCallbacks(soundLoop)
        handler.removeCallbacks(bubblePhysics)
        sensorManager?.unregisterListener(flipListener)
        ClockWidgetProvider.refreshAll(this)
        // And book the next wake-up, in case the settings just changed
        // where the sun is or whether it is drawn at all.
        ClockWidgetProvider.scheduleSkyTick(this)
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
        calendarView = root.findViewById<CalendarPageView>(R.id.calendar_view).also {
            it.onDayTap = { day -> onCalendarDayTap(day) }
            it.onMonthChanged = { refreshCalendarMarks() }
            it.onWeekStartChanged = { monday ->
                prefs.edit().putBoolean(Prefs.WEEK_START_MONDAY, monday).apply()
            }
        }
        applyPreferences()
        applyRow(row)
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
                    countdown.setTo(ms)
                    updateCountdownUi()
                }
            }
            it.onDialScaleChanged = { scale -> shareDialScale(scale, it) }
            it.onHorizontalSwipe = { fingerRight -> swipeSideways(Card.REVERSE, fingerRight) }
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
        applyRow(row)
    }

    /** C0 clock / S0 sand hourglass — the card the app opens on. */
    private fun bindCenterPage(root: View) {
        clockContainer = root.findViewById(R.id.clock_container)
        hourglassContainer = root.findViewById(R.id.hourglass_container)
        stopwatchContainer = root.findViewById(R.id.stopwatch_container)
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
                    lapChrono()
                } else {
                    resetChrono()
                    stopwatchClockView?.clearLaps()
                }
            }
            it.onDialScaleChanged = { scale -> shareDialScale(scale, it) }
            it.onHorizontalSwipe = { fingerRight -> swipeSideways(Card.STOPWATCH, fingerRight) }
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
        restoreStopwatch()
        clockView = root.findViewById<ClockView>(R.id.clock_view).also {
            it.soundListener = this
            it.onDialScaleChanged = { scale -> shareDialScale(scale, it) }
            it.onChronoAdjusted = { ms -> if (dialJob != null) alarmWorkingMs = ms }
            // Carry the hands round to tomorrow and the dial shows what
            // tomorrow holds, which is the one thing a twelve-hour face can
            // do that a list cannot.
            it.onShownDayChanged = { updateAlarmMarkers() }
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
        // The four ways out of the clock, in the order the cards are laid
        // out: calendar left, alarms right, stopwatch below and the
        // countdown below and across. Every card the app has bar the
        // hourglass — which is what the middle button does — named on the
        // first screen, because the swipes were there all along and nothing
        // on screen said so.
        root.findViewById<ImageButton>(R.id.to_calendar_button)
            .setOnClickListener { goTo(Card.CALENDAR) }
        root.findViewById<ImageButton>(R.id.to_alarms_button)
            .setOnClickListener { goTo(Card.ALARM) }
        root.findViewById<ImageButton>(R.id.to_stopwatch_button)
            .setOnClickListener { goTo(Card.STOPWATCH) }
        root.findViewById<ImageButton>(R.id.to_countdown_button)
            .setOnClickListener { goTo(Card.REVERSE) }
        settingsButton = root.findViewById<ImageButton>(R.id.settings_button).also { button ->
            button.setOnClickListener {
                // Opening our own settings is not "leaving the app": don't
                // fire up the countdown notification and floating bubble.
                openingSettings = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        reassembleButton = root.findViewById<ImageButton>(R.id.reassemble_button).also { button ->
            button.setOnClickListener {
                reassembleEverything()
                showReassembleIfNeeded()
            }
        }
        alarmSetBanner = root.findViewById(R.id.alarm_set_banner)
        alarmSetLabel = root.findViewById(R.id.alarm_set_label)
        root.findViewById<Button>(R.id.alarm_set_confirm).setOnClickListener { confirmAlarmSet() }
        root.findViewById<Button>(R.id.alarm_set_cancel).setOnClickListener { exitAlarmSetMode() }

        s3Sand = root.findViewById<SandHourglassView>(R.id.s3_sand).also {
            it.onFlowBlocked = { blocked -> sandBlocked = blocked }
            it.onScaleChanged = { scale -> shareDialScale(scale, null) }
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
                countdown.setTo(minutes * 60_000L)
                chimePlayer.playTick()
                updateCountdownUi()
            }
        }
        applyPreferences()
        applyRow(row)
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
        val to = visibleDial()
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

    /** C1's editor, which lives in its own file; this is the way in. */
    private fun showAlarmSheet(alarm: Alarm, seed: Alarm? = null) =
        alarmSheet.show(alarm, seed)

    /** For the tests: the card builder, which decides the marks on a row. */
    internal fun alarmCardsForTest(): AlarmCards = alarmCards

    /**
     * For the tests: wind [alarm]'s time to [hour]:[minute] the way the dial
     * does, and hand back the draft the sheet would reopen with.
     *
     * The whole point being what it does *not* touch. Winding used to write
     * straight onto the stored alarm, which is why pulling the sheet down
     * afterwards asked nothing and kept nothing.
     */
    internal fun windAndConfirmForTest(alarm: Alarm, hour: Int, minute: Int): Alarm {
        val draft = alarm.copy(extraTimes = alarm.extraTimes.toMutableList())
        dialJob = DialJob.AlarmTime(alarm, draft, isNew = false, timeIndex = 0)
        alarmWorkingMs = (hour * 60L + minute) * 60_000L
        confirmAlarmSet()
        return draft
    }

    /** For the tests: what the calendar has been told about the cycle. */
    internal fun calendarCyclePhasesForTest(): Map<Int, Cycle.Phase> =
        calendarView?.cyclePhases ?: emptyMap()

    /** For the tests: whether the toolbox is being offered. */
    internal fun reassembleShowing(): Boolean =
        reassembleButton?.visibility == View.VISIBLE

    /** The same, for the tests: saving is the step that lost two settings. */
    internal fun commitDraftForTest(target: Alarm, draft: Alarm, isNew: Boolean) =
        commitDraft(target, draft, isNew)

    /**
     * Copies a sheet draft back onto the real alarm (adding it if new).
     *
     * Every field at once, rather than a list of assignments. The list was
     * a field per line and it was wrong the moment an alarm grew a new
     * one: the mission and the gradual sunrise were added, nobody added
     * them here, and the result was two settings that could be chosen and
     * not saved — you picked "straight on", pressed save, and the row came
     * back saying thirty seconds, because the draft had been thrown away.
     *
     * The same mistake, in the same week, as the two counts dropped on the
     * way through the alarm chain. So this one stops being a list.
     */
    private fun commitDraft(target: Alarm, draft: Alarm, isNew: Boolean) {
        // The id belongs to the alarm and never to the draft; the switch is
        // the alarm's too, since renaming a sleeping alarm must not wake it,
        // and a new one arrives switched on.
        val merged = draft.copy(
            id = target.id,
            enabled = if (isNew) true else target.enabled,
            // copy() is shallow, and this one is a list the draft still holds.
            extraTimes = draft.extraTimes.toMutableList()
        )
        val at = alarms.indexOfFirst { it.id == target.id }
        if (at >= 0) alarms[at] = merged else alarms.add(merged)
        persistAlarms()
    }

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

    /**
     * Off to C0 to wind the hands, whatever it is we are winding.
     *
     * One departure for all four jobs. It used to be four copies of the same
     * four lines with a different set of fields poked before each — which is
     * how one of them came to clear a flag the exit still needed.
     */
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
        row = Row.MIDDLE
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
                // Into the draft and *only* into the draft. The sheet edits a
                // copy and nothing is committed until Save — and the time
                // used to be the one field that broke that rule, writing
                // itself straight onto the real alarm on the way back.
                //
                // Which is why winding a time and then pulling the sheet
                // down asked nothing and kept nothing: the draft and the
                // stored alarm already agreed, so there was no unsaved
                // change to warn about, and the one place the new time
                // lived was a draft nobody was going to commit.
                job.draft.setTime(job.timeIndex, hour, minute)
                job
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
        applyRow(row)
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

        // And the cycle, day by day across the month on screen. Worked out
        // here because [Cycle] answers in days counted from 1970 and the
        // calendar thinks in days of a month, and this is the one place
        // that knows which month is being looked at.
        val record = CycleStore.all(this)
        cal.cyclePhases = if (record.isEmpty()) {
            emptyMap()
        } else {
            val now = Cycle.today(
                TimeKeeper.nowMs(),
                java.util.TimeZone.getDefault().getOffset(TimeKeeper.nowMs())
            )
            (1..daysInMonth).associateWith { day ->
                Cycle.phase(record, Cycle.epochDay(cal.shownYear, cal.shownMonth1, day), now)
            }.filterValues { it != Cycle.Phase.NONE }
        }
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
        cv.dateDayFirst = DateShape.dayFirst(
            DateShape.order(prefs.getString(Prefs.DATE_ORDER, DateShape.AUTO)),
            phoneWritesDayFirst()
        )
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
            // A fixed number now. It was a slider, and a slider for "how
            // many grains" is a question nobody can answer without watching
            // the sand fall — which is the thing the setting was in the way
            // of. The hourglass is being rebuilt; the count comes back as
            // part of that or not at all.
            it.maxGrains = 600
            it.glassScale = cv.dialScale
            it.setTime(countdownTotalMs, countdownRemaining())
        }
        syncS3DurationChecks()

        // Night dims the alarms card too — it was the only bright one left.
        // Routed through the same fade rather than assigned: a raw alpha here
        // would fight whatever cross-fade the card is in the middle of, and
        // this way the dim itself arrives gently when 22:00 comes round.
        if (row == Row.MIDDLE) fadeCard(alarmsContainer, true)
        // Hands or digits, and the shape and hour count the faces wear, are
        // all settings: the cards are rebuilt so they follow.
        alarmsRecycler?.let { if (!it.isComputingLayout) refreshAlarmsUi() }

        bellsEnabled = prefs.getBoolean(Prefs.BELLS, false)
        bellStyle = prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT) ?: Prefs.BELL_STYLE_COUNT
        bellMarks = Bells.marksFrom(
            prefs.getString(Prefs.BELL_MARKS, null),
            prefs.getBoolean(Prefs.HALF_HOUR, false)
        )
        // Written back the first time, or the settings screen would show
        // "the hour only" to somebody whose clock is dinging at half past:
        // the old switch is honoured by everything that rings, and by
        // nothing that draws.
        if (!prefs.contains(Prefs.BELL_MARKS)) {
            prefs.edit().putString(Prefs.BELL_MARKS, bellMarks).apply()
        }
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

    /** The centred bottom button goes up to the hourglass ⏳ and back 🕐. */
    private fun cycleMode() {
        goTo(if (current() == Card.HOURGLASS) Card.CLOCK else Card.HOURGLASS)
    }

    /**
     * Whichever timer face is in use, for the ways in from outside.
     *
     * Nothing ever wrote this down, so the answer was always "the
     * hourglass": the notification of a running countdown, and the sand
     * widget, both landed on the sand even for somebody who only ever uses
     * the dial. It is written now, in [applyRow], as the rows are used.
     */
    private fun timerCard(): Card =
        if (prefs.getBoolean(Prefs.TIMER_ON_DIAL, false)) Card.REVERSE else Card.HOURGLASS

    /**
     * Go to [card], however far away it is.
     *
     * One function where there were four — a diagonal, a row swipe, a jump
     * to a chronograph and a way home — which were four spellings of "set
     * the row, set the page, and let the cards sort themselves out". A move
     * across a row scrolls, because that is what a row is for; a move that
     * changes rows cuts the page and lets the hands carry the story, because
     * a card sliding sideways while the row changes underneath says two
     * things at once and neither of them clearly.
     */
    private fun goTo(card: Card, scroll: Boolean = true) {
        if (dialJob != null) return
        handOverSource = visibleDial()
        val wasRow = row
        val wasPage = pager.currentItem
        // Which card is on screen *now*, asked before anything moves. Asked
        // afterwards it was "which card of the row I am leaving lives on
        // the page I have just arrived at", which on a diagonal names a
        // card that was never on screen at all — going to the countdown
        // brought the alarms up for one frame and then faded them away.
        val leaving = Cards.on(wasPage, wasRow)
        row = card.row
        val turning = card.row != wasRow
        if (pager.currentItem != card.page) {
            pager.setCurrentItem(card.page, scroll && !turning)
        }
        // And only the page we stayed on can dissolve anything: on a
        // diagonal the pager takes the old page away in the same frame, so
        // there is nothing left there to fade.
        applyRow(wasRow, leaving.takeIf { wasPage == card.page })
    }

    /**
     * Takes a card away by dissolving everything on it except its dial.
     *
     * The dial goes at once, because its story is already being told by the
     * hands travelling on the card that replaced it — two dials fading
     * through each other would tell it twice. Everything else the card was
     * carrying has no such stand-in: the world-clock bubbles and the row of
     * buttons simply blinked out of existence.
     *
     * Only possible at all because the clock and the stopwatch share a
     * page now. When they were a diagonal apart the pager cut the whole
     * page in the same frame and nothing drawn on it afterwards was seen by
     * anybody — which is what made me say this could not be done.
     */
    private fun dissolveChrome(card: Card) {
        val container = containerOf(card) ?: return
        if (container.visibility != View.VISIBLE) return
        dialOf(card)?.visibility = View.INVISIBLE
        fadeCard(container, false, raise = false)
    }

    /**
     * A sideways swipe on a row the pager is not allowed to drag through.
     *
     * The bottom row is two cards wide and the pager is three, so dragging
     * it freely lands you on a page with nothing on it. Dragging is off
     * there — see [applyRow] — and the dials carry the gesture themselves:
     * to the card the shape says is that way, or nowhere at all.
     *
     * Always consumed, either way. Handing back a swipe the pager cannot
     * act on leaves the finger doing nothing visible, which reads as the
     * app having missed it.
     */
    private fun swipeSideways(card: Card, fingerRight: Boolean): Boolean {
        val there = Cards.neighbour(card, if (fingerRight) Direction.LEFT else Direction.RIGHT)
        if (there != null) goTo(there)
        return true
    }

    private fun goHomeToClock() = goTo(Card.CLOCK)

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

    private fun visibleDial(): ClockView? = current()?.let { dialOf(it) }

    /** Which dial a card shows, if it has one. */
    private fun dialOf(card: Card): ClockView? = when (card) {
        Card.CLOCK -> clockView
        Card.STOPWATCH -> stopwatchClockView
        Card.REVERSE -> countdownClockView
        Card.HOURGLASS, Card.CALENDAR, Card.ALARM -> null
    }

    /** And which view is the whole card. */
    private fun containerOf(card: Card): View? = when (card) {
        Card.HOURGLASS -> hourglassContainer
        Card.CALENDAR -> calendarContainer
        Card.CLOCK -> clockContainer
        Card.ALARM -> alarmsContainer
        Card.STOPWATCH -> stopwatchContainer
        Card.REVERSE -> countdownContainer
    }

    private fun carryFallenHands() {
        val now = visibleDial() ?: return
        val before = lastVisibleDial
        if (before != null && before !== now) now.syncFallenFrom(before)
        lastVisibleDial = now
    }

    /** The toolbox appears with the mess and goes with it. */
    private fun showReassembleIfNeeded() {
        val wanted = dialJob == null && row == Row.MIDDLE && sceneIsDisarranged()
        reassembleButton?.visibility = if (wanted) View.VISIBLE else View.GONE
    }

    private fun sceneIsDisarranged(): Boolean =
        clockView?.isDisarranged() == true ||
            stopwatchClockView?.isDisarranged() == true ||
            countdownClockView?.isDisarranged() == true ||
            worldBubbles.anyMoving()

    private fun applyRow(from: Row = row, leaving: Card? = null) {
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
        // Bubbles belong to the clock, and fade with it.
        fadeCard(bubbleLayer, row == Row.MIDDLE && !setting, raise = false)
        settingsButton?.visibility = if (setting) View.GONE else View.VISIBLE
        showReassembleIfNeeded()
        // The dial that is about to appear starts its hands where the one
        // leaving has them, and covers the distance itself. Seeded before
        // the swap, so the first frame it draws is already on its way — and
        // *before* the crowns below are switched off, because the arriving
        // dial inherits the outgoing one's crown by asking whether it had
        // one. Asked after, the answer was already no, so the inheritance
        // never once fired in the app however well it worked on its own.
        //
        // Only between rows: a move along a row scrolls the pager with both
        // cards on screen at once, and hands that jump across to start
        // travelling while you can see where they came from are not a
        // hand-over, they are a glitch.
        val handsCarryIt = from != row && handOverSource != null && visibleDial() != null
        if (from != row) handOverHands() else handOverSource = null
        // The crown and the pushers belong to the cards that have them, and
        // they arrive with them: setting the flag is what starts their
        // fade, so it is set on every move rather than once when the page
        // was built — which is why they used to be simply always there.
        // Which timer face was last looked at, so the notification and the
        // sand widget come back to the one actually in use.
        Cards.on(pager.currentItem, row)?.let { here ->
            if (here == Card.REVERSE || here == Card.HOURGLASS) {
                prefs.edit().putBoolean(Prefs.TIMER_ON_DIAL, here == Card.REVERSE).apply()
            }
        }
        stopwatchClockView?.chronoButtons = row == Card.STOPWATCH.row
        countdownClockView?.chronoButtons = row == Card.REVERSE.row
        // A card arrives from the direction it lives in and the one it
        // replaces leaves the opposite way: the hourglass drops in from
        // above, the clock rises from below. A cross-fade said nothing
        // about where either of them was; a slide says it without a word.
        //
        // Unless there are hands to do the talking. Between two dials the
        // cards must not move at all: the whole point is one instrument
        // changing its job, and a card sliding underneath while the hands
        // travel tells the same story twice, badly. That used to fall out
        // of "is this a diagonal", which was true of clock-to-stopwatch
        // only for as long as they were on different pages — put them on
        // the same page and the slide came back.
        val slide = if (handsCarryIt) 0f else Cards.slideFrom(from, row).toFloat()
        // The card being left, when the hands are carrying the move and it
        // is not the pager that takes it away.
        val dissolving = leaving?.takeIf { handsCarryIt && it.row != row }
        for (card in Card.entries) {
            if (card == dissolving) continue
            // A card that is about to show gets its dial back: the last
            // time it was left, the dial was hidden out from under it.
            if (card.row == row) dialOf(card)?.visibility = View.VISIBLE
            // Each card slides from its own side, so the one going up and
            // the one coming down pass each other rather than both drifting
            // the same way.
            val own = if (card.row.ordinal >= row.ordinal) slide else -slide
            slideCard(containerOf(card), card.row == row, own)
        }
        dissolving?.let { dissolveChrome(it) }
        // The bottom row is two cards wide, so there is a page under it
        // with nothing on it, and the pager will happily drag you onto it:
        // swallowing the gesture in the dial does not stop it, because by
        // the time a swipe has been recognised the pager has been scrolling
        // for a while already. Only the middle row is safe to drag through.
        pager.isUserInputEnabled = row == Row.MIDDLE
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

    /**
     * Which way round this phone writes dates.
     *
     * Asked of the system rather than guessed from the language: the two
     * are not the same question, and somebody living somewhere that writes
     * dates the other way round has already answered this once, in the
     * place every other app on the phone reads it from.
     */
    private fun phoneWritesDayFirst(): Boolean =
        android.text.format.DateFormat.getDateFormatOrder(this).firstOrNull() != 'M'

    private fun isNightNow(): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = TimeKeeper.nowMs()
        return NightWindow.isNight(
            cal.get(Calendar.HOUR_OF_DAY),
            prefs.getInt(Prefs.NIGHT_FROM, NightWindow.DEFAULT_FROM),
            prefs.getInt(Prefs.NIGHT_TO, NightWindow.DEFAULT_TO)
        )
    }

    private fun stopwatchElapsed(): Long = stopwatch.elapsed()

    private fun countdownRemaining(): Long = countdown.remaining()

    /**
     * The last thing a pusher was felt to do. Only the tests read it: a
     * press that reaches [pushed] is a press that reached the vibrator, so
     * a call site that forgets to say what it did shows up here.
     */
    internal var lastPusherFeel: Pusher.Feel? = null
        private set

    /**
     * A pusher pressed, felt rather than seen.
     *
     * This used to ask the dial for haptic feedback — LONG_PRESS to start
     * and VIRTUAL_KEY for everything else — and on a real phone the second
     * of those cannot be felt at all through a case, so starting buzzed and
     * stopping appeared to do nothing. Stopping is the press that most
     * needs confirming, since it is the one that has to land on the
     * instant.
     */
    private fun pushed(feel: Pusher.Feel) {
        lastPusherFeel = feel
        Pusher.play(this, feel)
    }

    /** Start/Pause pusher on the stopwatch dial (S-1). */
    private fun toggleStartPause() {
        val nowRunning = stopwatch.startOrStop()
        stopwatchClockView?.chronoRunning = nowRunning
        pushed(if (nowRunning) Pusher.Feel.START else Pusher.Feel.STOP)
    }

    private fun resetChrono() {
        stopwatch.reset()
        stopwatchClockView?.chronoRunning = false
        pushed(Pusher.Feel.RESET)
    }

    /** The lower pusher on a running stopwatch: this lap goes on the list. */
    private fun lapChrono() {
        stopwatchClockView?.recordLap()
        chimePlayer.playTick()
        pushed(Pusher.Feel.LAP)
    }

    /** Start/Pause on the countdown dial (second page in chrono mode). */
    private fun toggleCountdown() {
        val was = countdown.running
        val nowRunning = countdown.startOrStop()
        if (nowRunning) {
            // Published straight away: the tile in the shade knows about a
            // countdown started in the app without the app telling it.
            CountdownService.publish(this, countdownEndsAt, countdownTotalMs)
            pushed(Pusher.Feel.START)
        } else if (was) {
            CountdownService.clearPublished(this)
            pushed(Pusher.Feel.STOP)
        }
        updateCountdownUi()
    }

    private fun resetCountdown() {
        // Back to zero; the user winds the hands to set a new time.
        countdown.reset()
        pushed(Pusher.Feel.RESET)
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
        chimeAt(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
    }

    /**
     * The last peal struck and how many have been struck at all, for the
     * tests to check the wiring by.
     *
     * The count is not redundant. Two consecutive hours of the same style
     * sound identical, so the last peal alone cannot tell a bell that rang
     * twice from one that did not ring at all — and "rang when it should
     * have kept quiet" is precisely the failure worth catching.
     */
    internal var lastPeal: Bells.Peal? = null
        private set

    internal var pealsStruck = 0
        private set

    /**
     * The hour, or half past it, in whatever the bells are set to.
     *
     * Which strikes to play is [Bells]' decision and not this screen's: the
     * service that rings with the app closed and the preview button in the
     * settings both used to carry their own copy of the same rule.
     */
    internal fun chimeAt(hourOfDay: Int, minute: Int) {
        // Which minutes count is [Bells]' business too. This used to keep
        // its own list of them, which was fine while the list was "the hour
        // and half past" and wrong the moment there were quarters.
        val peal = Bells.peal(bellStyle, hourOfDay, minute, bellMarks) ?: return
        lastPeal = peal
        pealsStruck++
        chimePlayer.play(peal)
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

        // The pager's three columns. Which card of each is showing is the
        // row's business, and both live in Cards, where the shape is.
        const val PAGE_LEFT = Cards.PAGE_LEFT
        const val PAGE_HOME = Cards.PAGE_HOME
        const val PAGE_RIGHT = Cards.PAGE_RIGHT

        private const val DEFAULT_COUNTDOWN_MS = 5 * 60_000L

        /**
         * How long a card takes to dissolve into the one behind it, and how
         * long everything else that fades on a mode change takes with it.
         *
         * One number, because the complaint that keeps coming back is not
         * that a fade is missing but that they do not agree: the crown used
         * to take 500 ms, the face 700, the bubbles 500, so whichever
         * finished first looked like the one thing that had been left out.
         * Matched to the dial's own transition, which is the motion the
         * whole gesture is built around.
         */
        private const val CARD_FADE_MS = 700f

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

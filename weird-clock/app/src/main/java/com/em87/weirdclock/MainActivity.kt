package com.em87.weirdclock

import android.graphics.Rect
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

    /** The "by time" button, shown only on a list arranged by hand. */
    private var alarmsByTime: View? = null
    private var alarmsEmpty: TextView? = null
    private var countdownClockView: ClockView? = null

    private var calendarContainer: View? = null
    private var hourglassContainer: View? = null

    /** The clock with no hands, on the faces that have one. */
    private var digitalView: DigitalClockView? = null
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

    /**
     * Which kind of clock this is, read once when the screen is built.
     *
     * Held rather than looked up because it decides how the screen is put
     * together — which cards exist, what the middle one draws — and a
     * question asked while the answer is changing underneath gets two
     * different replies in one frame. Changing it in the settings rebuilds
     * the screen; see [onResume].
     */
    private var face = Face.ANALOG

    /** The card on screen: the row says which of the page's cards it is. */
    private fun current(): Card? = Cards.on(pager.currentItem, row, face)
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

    /**
     * The length the countdown last had on it, for the crown to give back.
     *
     * The exact counterpart of [lastRunMs] on the stopwatch, and for the
     * same reason: a dial reading zero has been either never used or
     * cleared, and only one of those is worth offering to undo.
     *
     * It was a small class holding *two* lengths, which the reset pusher
     * swapped between — three minutes for the tea, five for the eggs. That
     * only made sense while reset meant "again"; with reset meaning
     * "clear", there is one length worth remembering and it is the one that
     * was just cleared.
     */
    private var lastCountdownMs = 0L

    /**
     * Whether the crown has been asked for the time elapsed.
     *
     * Off to begin with: the countdown's own question is how long is left,
     * and a second number under it is a second question that has to be
     * asked for.
     */
    private var countdownElapsedShown = false

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
    /**
     * The phone's own sounds, chosen from the system's own list.
     *
     * Not through the document picker: these are not files on a disk
     * anybody can browse to, they are whatever this phone shipped with,
     * and the system is the only thing that can enumerate them. It also
     * previews each one as you move down the list, which is the whole
     * reason to use it rather than build a list of our own.
     */
    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val alarm = soundPickTarget
            val done = soundPickCallback
            soundPickTarget = null
            soundPickCallback = null
            if (alarm != null) {
                val uri = result.data?.getParcelableExtra<android.net.Uri>(
                    android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI
                )
                if (uri != null) {
                    alarm.sound = Prefs.ALARM_SOUND_SYSTEM
                    alarm.soundUri = uri.toString()
                } else if (alarm.soundUri.isBlank()) {
                    // Backed out with nothing chosen and nothing to fall
                    // back on: the bells, rather than an alarm that has a
                    // voice named and no sound behind it.
                    alarm.sound = Prefs.ALARM_SOUND_BELLS
                }
                if (done != null) done() else persistAlarms()
            }
        }

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

    /** When the phone last came to be upside down, or zero if it is not. */
    private var invertedSince = 0L

    /** Whether this stretch of being upside down has already turned it. */
    private var glassTurned = false
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
                invertedSince = if (nowInverted) now else 0L
                if (!nowInverted) glassTurned = false
            }
            // Turned only once it has been held there — see [Hourglass].
            // Acting the instant the phone passed upside down is what let a
            // pocket finish a three-minute countdown.
            if (deviceInverted && !glassTurned &&
                Hourglass.turns(current(), countdownRunning, now - invertedSince)
            ) {
                glassTurned = true
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
        if (countdownTotalMs <= 0L) return
        val newRemaining = Hourglass.turned(countdownTotalMs, countdownRemaining())
        countdownEndsAt = SystemClock.elapsedRealtime() + newRemaining
        chimePlayer.playTick()
        updateCountdownUi()
    }

    // ------------------------------------------------------------ ticking

    /**
     * The second hand's tick, on a thread of its own.
     *
     * It used to ride the same handler as everything else that happens
     * once a second, which is the same thread that draws the dial — and the
     * dial is not cheap: eight planets, a physics step for whatever is
     * lying in the case, a layer of transparency or two. A frame that ran
     * long pushed the tick along with it, so ticks came late and sometimes
     * two seconds apart. On a clock that is the one fault nobody can be
     * talked out of hearing.
     *
     * So the timing lives here, where nothing draws. The thread reads one
     * volatile flag and plays one sound; every decision about *whether* to
     * tick is still made on the main thread, once a second, and left in
     * [ticksWanted] for it to find.
     */
    private var tickThread: android.os.HandlerThread? = null
    private var tickHandler: android.os.Handler? = null

    @Volatile
    private var ticksWanted = false

    /** The beat's fixed point, and how many have gone since. */
    private var tickAnchorUptime = 0L
    private var tickBeats = 0L

    /** What the beat has been doing — see [Ticker.Record]. */
    internal val tickRecord = Ticker.Record()

    /**
     * How loud this dial's tick should be at this moment.
     *
     * Only the seconds tick asks: the pushers and the crown are sounds you
     * caused, and a click you asked for and cannot hear is a broken button.
     * The tick is the one the room hears whether or not anybody touched
     * anything, and the room at two in the morning is a different room.
     */
    private fun tickLevel(): Float = Ticker.tickVolume(appliedNightDim)

    private val tickBeat = object : Runnable {
        override fun run() {
            val due = Ticker.beatAt(tickAnchorUptime, tickBeats)
            val lag = SystemClock.uptimeMillis() - due
            // On the clock the second hand keeps, not the wall clock. They
            // are the same number until solar time is switched on, and then
            // they are minutes apart — so the tick would sound at one
            // instant and the hand step at another.
            val now = TimeKeeper.nowMs()
            if (ticksWanted) {
                if (!Ticker.onTime(now)) {
                    tickRecord.missedIt()
                    // Turned down for the night with everything else on
                    // this dial. Only the seconds tick: the pushers and the
                    // crown are things you did, and a click you asked for
                    // that you cannot hear is a broken button.
                } else if (chimePlayer.playTick(tickLevel())) {
                    tickRecord.sounded(lag)
                } else {
                    tickRecord.refusedIt()
                }
            }
            tickBeats++
            // Laid out in advance and posted at an absolute time, so a
            // callback that arrives late does not push the next one late as
            // well. Only a real parting of the ways between uptime and the
            // wall clock re-lays the beat.
            if (Ticker.needsResync(now)) {
                layTheBeat()
            } else {
                tickHandler?.postAtTime(this, Ticker.beatAt(tickAnchorUptime, tickBeats))
            }
        }
    }

    /** Puts the anchor on the next whole second and counts from there. */
    private fun layTheBeat() {
        lastTickRecord = tickRecord
        val handler = tickHandler ?: return
        handler.removeCallbacks(tickBeat)
        tickAnchorUptime = SystemClock.uptimeMillis() + Ticker.delayToNext(TimeKeeper.nowMs())
        tickBeats = 0
        handler.postAtTime(tickBeat, Ticker.beatAt(tickAnchorUptime, 0))
    }

    private fun startTicking() {
        if (tickThread != null) return
        val thread = android.os.HandlerThread("ticks", android.os.Process.THREAD_PRIORITY_AUDIO)
        thread.start()
        tickThread = thread
        tickHandler = android.os.Handler(thread.looper)
        layTheBeat()
    }

    private fun stopTicking() {
        tickHandler?.removeCallbacks(tickBeat)
        tickHandler = null
        tickThread?.quitSafely()
        tickThread = null
    }

    /** For the tests: whether the tick would sound on this second. */
    internal fun ticksWantedForTest(): Boolean = ticksWanted



    /** Runs on (approximately) every second boundary, so ticks stay in step. */
    private val soundLoop = object : Runnable {
        override fun run() {
            val cv = clockView
            ticksWanted = tickingEnabled && row == Row.MIDDLE && cv != null &&
                !cv.isSecondHandGrabbed() && !cv.isSecondHandFallen() &&
                // A dial showing the planets is not showing a second hand,
                // so there is nothing for a tick to be the sound of.
                !cv.orreryShowing()
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

    /** The page's own colour, before the night has anything to say about it. */
    private var surroundColour = 0

    /**
     * Turns the page the dial sits on down with the dial.
     *
     * Night mode dropped the clock to thirty per cent and left everything
     * around it alone. In dark mode nobody noticed, because the page was
     * already dark; in light mode it was a dimmed clock in the middle of a
     * lit sheet of paper, and the sheet is most of the screen.
     */
    private fun paintSurround() {
        if (surroundColour == 0) return
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                if (appliedNightDim) ClockThemes.dimColour(surroundColour) else surroundColour
            )
        )
    }

    /** For the tests: the colour behind everything. */
    internal fun surroundColourForTest(): Int =
        (window.decorView.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0

    /**
     * Retimes the cards in place. Rebinding the whole list every minute would
     * be a lot of work to change eight characters, and would set every face
     * winding again.
     */
    private fun updateAlarmCountdowns() {
        alarmCards.retimeVisible(alarmsRecycler ?: return)
    }

    /**
     * Holds the screen on where it earns it, and lets it sleep everywhere
     * else.
     *
     * The clock is a bedside clock: a face that goes black after thirty
     * seconds is not one. A running stopwatch or countdown has the same
     * claim — you are watching it. The calendar, the alarm list and an idle
     * chronograph have none, and the flag was set once in onCreate and
     * never cleared, so the whole app kept the screen burning whatever was
     * on it.
     */
    private fun keepScreenAwake() {
        if (!this::pager.isInitialized) return
        val card = current()
        // Walking away from the clock puts the sky away with it — but not
        // until the clock has finished walking away. The cards dissolve into
        // one another and this one is in the picture the whole time, so
        // closing the sky at the top of the move put the hands back on a
        // dial still being looked at: a solar system, a flash of clock, and
        // then the chronograph. The sky leaves with the card now.
        if (card != Card.CLOCK) clockView?.leaveOrrery(CARD_FADE_MS.toLong())
        val awake = card == Card.CLOCK ||
            (card == Card.STOPWATCH && stopwatchRunning) ||
            ((card == Card.REVERSE || card == Card.HOURGLASS) && countdownRunning)
        if (awake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        surroundColour = android.util.TypedValue().let { value ->
            theme.resolveAttribute(android.R.attr.colorBackground, value, true)
            value.data
        }
        // The app's background runs behind the status and navigation bars,
        // so the clock looks like it goes on past the edges of the screen.
        SystemChrome.paint(this)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Somebody who has been using this app for weeks is not asked
        // which clock they want. They did not choose the dial — it was the
        // only one there was — but they have been living with it, and a
        // modal question on the morning after an update, from the app that
        // is now their alarm clock, is not a welcome. The row is the first
        // one in the settings; that is discoverable enough.
        if (!prefs.getBoolean(Prefs.FACE_ASKED, false) && prefs.contains(Prefs.OVERLAY_ASKED)) {
            prefs.edit().putBoolean(Prefs.FACE_ASKED, true).apply()
        }
        face = Face.of(prefs)
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
            onToggled = { alarm, checked -> toggleAlarm(alarm, checked) },
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

            override fun pickSystemSound(target: Alarm, onPicked: () -> Unit) {
                soundPickTarget = target
                soundPickCallback = onPicked
                ringtonePickerLauncher.launch(
                    Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                        // Alarms first, but not alarms only: a great many
                        // phones ship two alarm tones and forty ringtones,
                        // and the one somebody wants to wake up to is often
                        // among the forty.
                        .putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                            android.media.RingtoneManager.TYPE_ALARM or
                                android.media.RingtoneManager.TYPE_RINGTONE or
                                android.media.RingtoneManager.TYPE_NOTIFICATION
                        )
                        .putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_TITLE,
                            getString(R.string.alarm_sound_system)
                        )
                        // No "None": a silent alarm has its own entry in
                        // the list this picker was opened from, and one
                        // reached by accident here would be an alarm that
                        // looks set and says nothing.
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            target.soundUri.takeIf { it.isNotBlank() }
                                ?.let { android.net.Uri.parse(it) }
                        )
                )
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
                // A sideways swipe changes which card is showing without
                // touching the row, so the screen-on flag is reconsidered
                // here as well as wherever the row moves.
                keepScreenAwake()
                carryFallenHands()
                closeSheetLeftBehind()
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
        if (intent.getBooleanExtra(EXTRA_OPEN_SKY, false)) {
            pager.post { goTo(Card.CLOCK, scroll = false); openSky() }
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
        // The one question the app asks before anything else, and only ever
        // once. Everything below it is an introduction to a feature of a
        // clock we have not decided on yet, so they wait their turn.
        if (!askWhichFaceOnce()) settleInAfterTheFaceIsChosen()
    }

    /**
     * First run only: which kind of clock this is going to be.
     *
     * Asked rather than defaulted, and asked first, because it is not a
     * preference — it decides what the app is. Somebody who wants digits
     * and is handed a dial has to find the settings, get past four screens
     * of hands and marks, and know that the thing they want is in there at
     * all; asking costs one tap and answers it.
     *
     * Not cancellable, and not written down until it is answered: a choice
     * the app quietly made on your behalf because the screen was tapped
     * through is exactly the outcome this exists to avoid. Killed before
     * answering, it asks again.
     *
     * Returns true when the question is on screen, so the introductions
     * that follow can wait for it.
     */
    private fun askWhichFaceOnce(): Boolean {
        if (prefs.getBoolean(Prefs.FACE_ASKED, false)) return false
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.face_ask_title)
            .setMessage(R.string.face_ask_message)
            .setCancelable(false)
            .setPositiveButton(R.string.face_analog) { _, _ -> chooseFace(Face.ANALOG) }
            .setNegativeButton(R.string.face_digital) { _, _ -> chooseFace(Face.DIGITAL) }
            .show()
            .also { faceDialog = it }
        return true
    }

    /** The first-run question, while it is on screen. */
    internal var faceDialog: androidx.appcompat.app.AlertDialog? = null
        private set

    /**
     * Writes down the answer to [askWhichFaceOnce] and acts on it.
     *
     * Rebuilding for the digital answer rather than switching the screen
     * over in place: the cards are already built by then, and one of them
     * is an hourglass that this face does not have.
     */
    internal fun chooseFace(chosen: Face) {
        prefs.edit()
            .putString(Prefs.FACE, chosen.key)
            .putBoolean(Prefs.FACE_ASKED, true)
            .apply()
        if (chosen != face) {
            recreate()
            return
        }
        settleInAfterTheFaceIsChosen()
    }

    /** The introductions that only make sense once we know what we are. */
    private fun settleInAfterTheFaceIsChosen() {
        maybeIntroduceFloatingHourglass()
        maybeWarnAboutExactAlarms()
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
        // Nothing to introduce on a face that has no hourglass, and asking
        // for a permission on behalf of something that is not there is how
        // an app teaches people to say no to it.
        if (Card.HOURGLASS !in face.cards) return
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

    /**
     * Says so when the phone will not let the app set an exact alarm.
     *
     * The failure is invisible by design of the platform: the alarm still
     * rings, roughly, and the only outward sign is that the little clock
     * that should appear in the status bar does not. An alarm that is
     * quietly approximate is not something to find out about on a Monday.
     */
    private fun maybeWarnAboutExactAlarms() {
        if (!prefs.getBoolean(Prefs.EXACT_DENIED, false)) return
        if (prefs.getBoolean(Prefs.EXACT_WARNED, false)) return
        prefs.edit().putBoolean(Prefs.EXACT_WARNED, true).apply()
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.exact_alarms_title)
            .setMessage(R.string.exact_alarms_message)
            .setNegativeButton(R.string.overlay_intro_later, null)
        // The screen that grants it only exists from Android 12 on; before
        // that the permission was not something a phone could withhold.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            builder.setPositiveButton(R.string.exact_alarms_grant) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }
        builder.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) goTo(Card.ALARM)
        if (intent.getBooleanExtra(EXTRA_OPEN_TIMER, false)) goTo(timerCard())
        if (intent.getBooleanExtra(EXTRA_OPEN_CALENDAR, false)) goTo(Card.CALENDAR)
        if (intent.getBooleanExtra(EXTRA_OPEN_SKY, false)) {
            goTo(Card.CLOCK)
            openSky()
        }
    }

    /**
     * Opens the solar system on the main dial, if it is switched on.
     *
     * Silently nothing if it is not: arriving from the widget is not a
     * reason to turn on a setting somebody left off, and a card that
     * opened something the settings say is off would be the app arguing
     * with its own switches.
     */
    private fun openSky() {
        val cv = clockView ?: return
        if (!cv.orreryEnabled) return
        if (!cv.orreryShowing()) cv.toggleOrrery()
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
        // The face was changed in the settings while we were away. Rebuild
        // for the same reason as above and a stronger one: this is not a
        // repaint, it is a different instrument, with different cards and a
        // different thing in the middle of the screen.
        if (Face.of(prefs) != face) {
            recreate()
            return
        }
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
                    // The reset pusher goes back to the length it can see,
                    // which now is this one and not whatever was on the dial
                    // before the app went away.
                    lastCountdownMs = countdownTotalMs
                    updateCountdownUi()
                }
            }
            CountdownService.RESULT_EXTENDED -> {
                // A minute was bought from the shade while we were away.
                countdown.adopt(
                    prefs.getLong(Prefs.COUNTDOWN_ENDS_AT, countdownEndsAt),
                    prefs.getLong(Prefs.COUNTDOWN_TOTAL, countdownTotalMs)
                )
                lastCountdownMs = countdownTotalMs
                prefs.edit().remove(Prefs.COUNTDOWN_RESULT).apply()
                updateCountdownUi()
            }
        }
        keepScreenAwake()
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
        handler.postDelayed(soundLoop, Ticker.delayToNext(System.currentTimeMillis()))
        startTicking()
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
        stopTicking()
        // The sky is a thing you open, look at and leave. Coming back to
        // one still standing on a date wound to last Tuesday reads as a
        // clock that has stopped — and the view is never detached when the
        // app goes to the background, so onDetachedFromWindow never ran.
        clockView?.leaveOrrery()
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
            attachAlarmDrag(it)
        }
        alarmsEmpty = root.findViewById(R.id.alarms_empty)
        alarmsByTime = root.findViewById<View>(R.id.alarms_by_time).also { back ->
            back.setOnClickListener {
                AlarmOrder.clear(alarms)
                sortAlarms()
                persistAlarms()
            }
        }
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
                    // Only what was chosen by hand goes into the memory, and
                    // this is the one place a hand chooses: the hands are
                    // committed on release, not while they are being
                    // dragged, so the length before this one is a length
                    // somebody meant rather than wherever a finger passed.
                    if (countdown.remainingMs > 0L) lastCountdownMs = countdown.remainingMs
                    updateCountdownUi()
                }
            }
            it.onDialScaleChanged = { scale -> shareDialScale(scale, it) }
            it.onHorizontalSwipe = { fingerRight -> swipeSideways(Card.REVERSE, fingerRight) }
            it.secondReadout = {
                // Only while it is running, and only when asked for. A
                // stopped countdown has not been running for any length of
                // time, and a row of digits saying so would be a row of
                // zeroes.
                if (countdownRunning && countdownElapsedShown) {
                    (countdownTotalMs - countdownRemaining()).coerceAtLeast(0L)
                } else {
                    null
                }
            }
            it.onCrownTap = { tidied ->
                // The winding crown tidies the whole scene, bubbles included.
                crownSound(tidied)
                healBubbleClocks()
                worldBubbles.dock()
                // And on a countdown sitting at zero it puts the last
                // length back, exactly as the stopwatch's crown puts the
                // last race back — the crown is where this watch keeps its
                // second thoughts, on both cards.
                //
                // While it is running there is nothing to restore, so the
                // crown says something instead: how long the thing has been
                // going, in a smaller row under the digits. The hands can
                // only show what is left. Press again and it goes; again
                // and it is back.
                if (!tidied) {
                    if (countdownRunning) {
                        countdownElapsedShown = !countdownElapsedShown
                        countdownClockView?.invalidate()
                    } else {
                        restoreLastCountdown()
                    }
                }
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
            it.onCrownTap = { tidied ->
                // The winding crown tidies the whole scene, bubbles included.
                crownSound(tidied)
                healBubbleClocks()
                worldBubbles.dock()
                // And on a stopwatch sitting at zero it puts the last run
                // back, so a race stopped and cleared by accident is not
                // gone: the crown is where a mechanical watch keeps its
                // second thoughts.
                if (!tidied) restoreLastRun()
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
            it.onCrownTap = { tidied ->
                // The winding crown tidies the whole scene, bubbles included.
                crownSound(tidied)
                healBubbleClocks()
                worldBubbles.dock()
            }
            // A knock hard enough to shed hands rattles the whole scene.
            it.onKnocked = { onDialKnocked() }
        }
        digitalView = root.findViewById(R.id.digital_view)
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
        takeAwayWhatThisFaceHasNot(root)
        applyPreferences()
        applyRow(row)
    }

    /**
     * Removes the cards this face does not have, and the ways to them.
     *
     * The page is inflated whole and then pruned, rather than laid out in
     * two versions: one layout that always holds every card is one place to
     * look when something is where it should not be, and the alternative is
     * two files that drift apart. What has to be right is that a card the
     * face has not got is gone *and* unreachable — a hidden card with a
     * live button to it is worse than either.
     */
    private fun takeAwayWhatThisFaceHasNot(root: View) {
        for (card in Card.entries - face.cards) containerOf(card)?.visibility = View.GONE
        // One of the two clocks comes out of the card altogether — taken
        // out rather than hidden, because a dial that is merely invisible
        // still asks for a frame a second and still holds the
        // accelerometer open waiting to be shaken. The object stays: the
        // stopwatch, the calendar and the sand all take their styling off
        // it, and a detached view answers those questions perfectly well.
        if (face.hands) {
            digitalView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            digitalView = null
        } else {
            digitalView?.visibility = View.VISIBLE
            clockView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
        // And the ways in. The hourglass is the only card with no second
        // door — the row of buttons on the clock names the other four — so
        // its button goes with it, rather than stranding a finger on a card
        // that is not there.
        if (Card.HOURGLASS !in face.cards) modeButton?.visibility = View.GONE
    }

    /**
     * The sheet or dialog on screen, and the card it belongs to.
     *
     * A sheet is part of a card: the reminder editor belongs to the
     * calendar and the alarm editor to the alarm list, and neither has any
     * business standing over the clock. It could, because a tap opens one
     * at once and a swipe a moment later carries the card out from under
     * it — press a date, flick sideways, and the reminder editor rises over
     * the clock face like a window with no house.
     */
    private var openSheet: android.app.Dialog? = null
    private var openSheetCard: Card? = null

    /**
     * Shuts a sheet whose card has gone.
     *
     * Only when the card has actually changed: a sheet that opens a second
     * sheet — picking a sound, picking a repeat — is still on its own card
     * and must survive.
     */
    private fun closeSheetLeftBehind() {
        val sheet = openSheet ?: return
        if (openSheetCard == showingCard()) return
        openSheet = null
        openSheetCard = null
        if (sheet.isShowing) sheet.dismiss()
    }

    /** Which card is on the glass right now, if the pager is on one. */
    private fun showingCard(): Card? = Cards.on(pager.currentItem, row, face)

    /**
     * Remembers a dialog and the card it was opened from, so it can be
     * taken away with the card.
     */
    internal fun ownSheet(dialog: android.app.Dialog) {
        openSheet = dialog
        openSheetCard = showingCard()
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
        ownSheet(sheet)
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

    /** For the tests: the reset pusher on the countdown. */
    internal fun resetCountdownForTest() = resetCountdown()

    /** For the tests: how long the countdown has left. */
    internal fun countdownRemainingForTest(): Long = countdownRemaining()

    /**
     * For the tests: start a countdown of [ms] on whichever card is showing.
     *
     * The pusher would do it too, but through three views and a page
     * change — and what is on trial is the accelerometer, not the buttons.
     */
    internal fun startCountdownForTest(ms: Long) {
        // Through setTo, the way winding the hands does it. Writing the
        // total and the end time by hand left the remaining time at
        // whatever it had been, and startOrStop then started *that*.
        countdown.setTo(ms)
        if (countdown.remainingMs > 0L) lastCountdownMs = countdown.remainingMs
        countdown.startOrStop()
    }

    /**
     * For the tests: wind the countdown to [ms] and let go of the hand.
     *
     * Through the very callback the dial invokes on release, so that what
     * is on trial is the wiring and not a copy of it kept alongside.
     */
    internal fun windCountdownForTest(ms: Long) {
        countdownClockView?.onChronoAdjusted?.invoke(ms)
    }

    /** For the tests: the length the crown would give back. */
    internal fun lastCountdownForTest(): Long = lastCountdownMs

    /** For the tests: the dial on the countdown card. */
    internal fun countdownForTest(): ClockView? = countdownClockView

    /**
     * For the tests: taps a day of the month shown on the calendar,
     * returning the day it stands for.
     */
    internal fun markCycleForTest(day: Int): Int {
        val cal = calendarView ?: return 0
        markCycleOn(cal.shownYear, cal.shownMonth1, day)
        return Cycle.epochDay(cal.shownYear, cal.shownMonth1, day)
    }

    /**
     * For the tests: picks an alarm card up, the way a long press does.
     *
     * Through the drag helper rather than by calling the callback, so what
     * is measured is the card as the helper leaves it.
     */
    internal fun dragAlarmCardForTest(
        list: RecyclerView,
        card: android.view.View
    ) {
        alarmDragHelper?.startDrag(list.getChildViewHolder(card))
    }

    /** For the tests: the alarms this activity is holding. */
    internal fun alarmsForTest(): List<Alarm> = alarms

    /** For the tests: how loud the seconds tick would be right now. */
    internal fun tickLevelForTest(): Float = tickLevel()

    /** For the tests: repaint the alarms card from the list as it stands. */
    internal fun refreshAlarmsForTest() = refreshAlarmsUi()

    /** For the tests: the switch on an alarm card, pressed. */
    internal fun toggleAlarmForTest(alarm: Alarm, checked: Boolean) = toggleAlarm(alarm, checked)

    /** For the tests: put the app on a given card. */
    internal fun showCardForTest(card: Card) {
        row = card.row
        pager.setCurrentItem(card.page, false)
        // The pager only swaps pages once it has been laid out again, and a
        // picture taken before that is a picture of the page it was on.
        pager.measure(
            View.MeasureSpec.makeMeasureSpec(pager.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(pager.height, View.MeasureSpec.EXACTLY)
        )
        pager.layout(pager.left, pager.top, pager.right, pager.bottom)
        keepScreenAwake()
    }

    /** For the tests: the main dial, so a picture can be taken of it. */
    internal fun clockForTest(): ClockView = clockView!!

    /** For the tests: which face this screen was built for. */
    internal fun faceForTest(): Face = face

    /** For the tests: whether a card the face has not got is on the glass. */
    internal fun cardShowingForTest(card: Card): Boolean =
        containerOf(card)?.visibility == View.VISIBLE

    /** For the tests: the button that goes up to the hourglass. */
    internal fun modeButtonForTest(): View? = modeButton

    /** For the tests: ask to go somewhere, and see whether the app agrees. */
    internal fun goToForTest(card: Card) = goTo(card)

    /** For the tests: which card the pager and the row between them name. */
    internal fun showingCardForTest(): Card? = showingCard()

    /** For the tests: the clock with no hands, when this face has one. */
    internal fun digitalForTest(): DigitalClockView? = digitalView

    /** For the tests: whether the dial is still in the layout at all. */
    internal fun dialIsInTheCardForTest(): Boolean = clockView?.parent != null

    /** For the tests: the little world clocks floating over it. */
    internal fun worldClocksForTest(): List<ClockView> = worldBubbles.clocksForTest()

    /** For the tests: the bubbles themselves, to be told something directly. */
    internal fun worldBubblesForTest(): WorldBubbles = worldBubbles

    /** For the tests: the numerals the month page is actually writing in. */
    internal fun calendarNumeralsForTest(): ClockView.NumeralStyle? = calendarView?.numeralStyle

    /** For the tests: the theme the calendar is actually drawing with. */
    internal fun calendarThemeForTest(): ClockTheme? = calendarView?.theme

    /** For the tests: what the calendar has been told about the sky. */
    internal fun calendarSkyForTest(): Map<Int, SkyEvents.Kind> =
        calendarView?.skyDays ?: emptyMap()

    /** For the tests: which month the calendar is showing. */
    internal fun calendarYearForTest(): Int = calendarView?.shownYear ?: 0
    internal fun calendarMonthForTest(): Int = calendarView?.shownMonth1 ?: 0

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
        digitalView?.reassembleAll()
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
        keepScreenAwake()
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
     * Puts the cards in order — by the clock, or by hand.
     *
     * Which of the two, and what each means, lives in [AlarmOrder]. It used
     * to be chronological and nothing else, which is right until somebody
     * drags a card: a list that re-sorts itself after every drag makes
     * dragging a thing you do and then watch being undone.
     */
    private fun sortAlarms() = AlarmOrder.sort(alarms)

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
    /**
     * Takes hold of an alarm card and carries it up or down the list.
     *
     * Long press to pick one up, which is the gesture everything else on a
     * phone uses for it, and which leaves an ordinary press and an ordinary
     * scroll alone. The card lifts while it is held, because a card being
     * carried and a card sitting still have to look different or the
     * gesture reads as the list having gone wrong.
     *
     * The list stops sorting itself the moment anything is dropped
     * somewhere new — see [AlarmOrder].
     */
    private fun attachAlarmDrag(list: RecyclerView) {
        alarmDragHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                    androidx.recyclerview.widget.ItemTouchHelper.DOWN,
                // Nothing sideways. A card swiped off the list would be an
                // alarm deleted by a gesture that is one slip from a page
                // change, and this app is somebody's morning.
                0
            ) {
                override fun onMove(
                    recycler: RecyclerView,
                    holder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = holder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                        return false
                    }
                    AlarmOrder.moved(alarms, from, to)
                    alarmCards.adapter.notifyItemMoved(from, to)
                    return true
                }

                override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun onSelectedChanged(
                    holder: RecyclerView.ViewHolder?,
                    actionState: Int
                ) {
                    super.onSelectedChanged(holder, actionState)
                    // Lifted, not enlarged. Scaling a card up made it wider
                    // than the row it lives in, and a list clips its
                    // children — so the card being carried had its own
                    // edges sliced off, which is the one card you are
                    // looking at. The lift is the helper's own shadow,
                    // which cannot be clipped because it is not size.
                    if (actionState ==
                        androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG
                    ) {
                        performHapticFeedbackOnList(list)
                    }
                }

                override fun clearView(recycler: RecyclerView, holder: RecyclerView.ViewHolder) {
                    super.clearView(recycler, holder)
                    // Written down when it is put down, not on every step of
                    // the way: a drag across six cards is one decision.
                    persistAlarms()
                    refreshAlarmsUi()
                }
            }
        )
        alarmDragHelper?.attachToRecyclerView(list)
    }

    /** The thing that carries an alarm card up and down the list. */
    private var alarmDragHelper: androidx.recyclerview.widget.ItemTouchHelper? = null

    private fun performHapticFeedbackOnList(list: RecyclerView) {
        list.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * The switch on an alarm card.
     *
     * Turning a repeating alarm off is nearly always about one morning —
     * a day off, a late night — and it is also the commonest way to miss
     * the morning after, because nobody remembers to put it back. So a
     * repeating alarm asks which you meant, the way Samsung's does, and
     * "just tomorrow" leaves it armed with one occurrence let off.
     *
     * A one-shot is not asked: skipping its only occurrence and turning it
     * off are the same thing.
     */
    private fun toggleAlarm(alarm: Alarm, checked: Boolean) {
        if (checked) {
            alarm.enabled = true
            // Switching one back on is a decision about now, so it cancels
            // any morning it was standing down for.
            alarm.skippedOccurrence = 0L
            maybeRequestNotificationPermission()
            persistAlarms()
            return
        }
        val next = AlarmScheduler.nextOccurrence(alarm)
        // Where the card is, *before* the list is told anything. Switching
        // an alarm off sorts the list and rebinds it, and after that the
        // view holder for this alarm may be a different view or none at
        // all — which is why the offer was arriving in the bottom corner
        // of the screen instead of over the card it is about: the lookup
        // found nothing and fell back to hanging the bubble off the list.
        val over = if (alarm.once) null else cardBounds(alarm)
        alarm.enabled = false
        alarm.skippedOccurrence = 0L
        persistAlarms()
        // A one-shot has nothing to offer: skipping its only occurrence and
        // turning it off are the same thing.
        if (over != null) offerToKeepTheDaysAfter(alarm, next, over)
    }

    /** Where an alarm's card is on screen, or null if it is not. */
    private fun cardBounds(alarm: Alarm): Rect? {
        val list = alarmsRecycler ?: return null
        val at = alarms.indexOfFirst { it.id == alarm.id }
        if (at < 0) return null
        val row = list.findViewHolderForAdapterPosition(at)?.itemView ?: return null
        val where = IntArray(2)
        row.getLocationInWindow(where)
        return Rect(where[0], where[1], where[0] + row.width, where[1] + row.height)
    }

    /**
     * The bubble over a card whose alarm has just been switched off.
     *
     * This was a dialog, asked *before* the alarm went off: "just that one,
     * or off for good?" It is the right question and it was in the wrong
     * place. Switching an alarm off is one flick of a switch, and the
     * dialog made it a flick and a tap — a toll paid every single time by
     * everybody, including the people who meant exactly what the switch
     * said.
     *
     * So the switch does what a switch does, at once, and the other reading
     * is offered afterwards and costs nothing to decline. Tapped, the alarm
     * comes back on with the one occurrence let off, which is what "just
     * tomorrow" always meant. Ignored, it fades and the alarm stays off —
     * so the cheap path is the common one, and the expensive path is the
     * one fewer people want.
     */
    private fun offerToKeepTheDaysAfter(alarm: Alarm, next: Long, over: Rect) {
        // A window needs a live one to hang off. Switching an alarm off is
        // one of the last things that can happen on the way out of this
        // activity — the switch is on screen, the finger is on it — and a
        // popup shown against a token that has already gone throws rather
        // than doing nothing.
        if (isFinishing || isDestroyed) return
        val list = alarmsRecycler ?: return
        val bubble = layoutInflater.inflate(R.layout.bubble_alarm_skip, null)
        bubble.findViewById<android.widget.TextView>(R.id.bubble_text).text =
            getString(R.string.alarm_skip_bubble, alarmCards.whenIs(next))
        val popup = android.widget.PopupWindow(
            bubble,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 8 * resources.displayMetrics.density
        bubble.setOnClickListener {
            alarm.enabled = true
            alarm.skippedOccurrence = next
            persistAlarms()
            popup.dismiss()
        }
        skipBubble?.dismiss()
        skipBubble = popup
        popup.setOnDismissListener { if (skipBubble === popup) skipBubble = null }
        // Centred over the card, in window coordinates taken before the
        // list moved. Over it rather than under: it is about that alarm and
        // nothing else, and a bubble that sits on the thing it is about
        // needs no words saying which one it means.
        bubble.measure(
            android.view.View.MeasureSpec.UNSPECIFIED,
            android.view.View.MeasureSpec.UNSPECIFIED
        )
        popup.showAtLocation(
            list,
            android.view.Gravity.NO_GRAVITY,
            over.centerX() - bubble.measuredWidth / 2,
            over.centerY() - bubble.measuredHeight / 2
        )
        // Long enough to read and notice, short enough that it is not a
        // thing to be dismissed. Ignoring it is a way of answering.
        list.postDelayed({ if (popup.isShowing) popup.dismiss() }, SKIP_BUBBLE_MS)
    }

    /** The bubble on screen, if any, so a second flick replaces the first. */
    private var skipBubble: android.widget.PopupWindow? = null

    /** For the tests: whether the offer is on screen. */
    internal fun skipBubbleShowingForTest(): Boolean = skipBubble?.isShowing == true

    /** For the tests: taking the offer, as a tap on the bubble would. */
    internal fun takeSkipOfferForTest() {
        skipBubble?.contentView?.performClick()
    }

    private fun persistAlarms() {
        sortAlarms()
        AlarmStore.save(this)
        AlarmScheduler.update(this)
        refreshAlarmsUi()
        keepARestorePoint()
    }

    /**
     * Today's restore point, if there is a folder for it and today has not
     * had one yet.
     *
     * Hung off the moments something worth keeping changes rather than off
     * a schedule: a clock nobody has touched since yesterday has nothing
     * new to save, and a phone switched off at midnight would miss a timed
     * one anyway. [Backup.autoSave] decides whether there is anything to
     * do — this is only the list of moments worth asking at.
     *
     * Off the main thread, because it writes a file to a folder that may
     * be on a memory card or behind a cloud provider, and nothing about a
     * backup is worth a dropped frame.
     */
    private fun keepARestorePoint() {
        val app = applicationContext
        kotlin.concurrent.thread(name = "restore-point") { Backup.autoSave(app) }
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
        // The way back out of a hand-arranged list, and only while there is
        // one to get out of.
        alarmsByTime?.visibility =
            if (AlarmOrder.isManual(alarms)) View.VISIBLE else View.GONE
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

    /**
     * Every day of this year with something on it, and what.
     *
     * For the solar system zoomed out to the Earth's orbit, where the dial
     * becomes a calendar of the year: a dot on each busy day, grey behind
     * and bright ahead, so how full the year has been and how full it is
     * about to get can be read round the rim at a glance.
     *
     * Only when the marks are wanted on the dial at all — it is the same
     * question in a different shape, and somebody who has turned the marks
     * off has said they do not want their diary on the clock face.
     */
    private fun busyDaysOfTheYear(): Map<Int, String> {
        if (!prefs.getBoolean(Prefs.ALARM_MARKERS, true)) return emptyMap()
        if (reminders.isEmpty()) return emptyMap()
        val year = Calendar.getInstance().apply {
            timeInMillis = TimeKeeper.nowMs()
        }.get(Calendar.YEAR)
        val busy = HashMap<Int, String>()
        val probe = Calendar.getInstance()
        for (month in 1..12) {
            probe.set(year, month - 1, 1)
            for (day in 1..probe.getActualMaximum(Calendar.DAY_OF_MONTH)) {
                val on = reminders.filter { it.occursOn(year, month, day) }
                if (on.isEmpty()) continue
                busy[CivilDays.epochDay(year, month, day)] =
                    on.joinToString(", ") { it.label.ifBlank { getString(R.string.reminder_untitled) } }
            }
        }
        return busy
    }

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

        // And the sky, the same way. The rarest thing on a day wins the
        // one corner there is room for — an eclipse on the night of a
        // shower is an eclipse, and that is the order [SkyEvents] answers
        // in already.
        cal.skyDays = (1..daysInMonth).mapNotNull { day ->
            SkyEvents.headline(
                CivilDays.epochDay(cal.shownYear, cal.shownMonth1, day)
            )?.let { day to it.kind }
        }.toMap()
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
        keepARestorePoint()
    }

    private fun onCalendarDayTap(day: Int) {
        val cal = calendarView ?: return
        val year = cal.shownYear
        val month = cal.shownMonth1
        val dayReminders = reminders.filter { it.occursOn(year, month, day) }
        val cycleOn = prefs.getBoolean(Prefs.CYCLE, false)
        // Nothing on the day and nothing else to offer: straight to the
        // sheet, which is what a tap on an empty day has always meant.
        if (dayReminders.isEmpty() && !cycleOn) {
            showReminderSheet(null, year, month, day)
            return
        }

        val marking = if (cycleOn) cycleMarkLabel(year, month, day) else null
        val items = (
            dayReminders.map {
                String.format(
                    Locale.US, "%02d:%02d  %s",
                    it.hour, it.minute,
                    it.label.ifBlank { getString(R.string.reminder_untitled) }
                )
            } + getString(R.string.reminder_add) + listOfNotNull(marking)
            ).toTypedArray()
        // Owned by the calendar, so a swipe away from the calendar takes it
        // with it rather than leaving it standing over the clock.
        ownSheet(androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(dayTitle(year, month, day))
            .setItems(items) { _, which ->
                when {
                    which < dayReminders.size ->
                        showReminderSheet(dayReminders[which], year, month, day)
                    which == dayReminders.size ->
                        showReminderSheet(null, year, month, day)
                    else -> markCycleOn(year, month, day)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show())
    }

    /**
     * A tapped day's own name: the date, and what the sky is doing on it.
     *
     * The mark in the corner of the cell says *that* something happens;
     * this is where it says what. Without it the calendar has a symbol
     * nobody can look up.
     */
    private fun dayTitle(year: Int, month: Int, day: Int): String {
        val date = String.format(Locale.US, "%02d/%02d/%04d", day, month, year)
        val sky = SkyEvents.headline(CivilDays.epochDay(year, month, day))
            ?: return date
        return date + "  ·  " + OrreryDial.nameOf(resources, sky)
    }

    /**
     * What the cycle entry on a tapped day says it will do.
     *
     * Named for the outcome rather than for the feature — "period started
     * here", not "cycle" — because a menu entry that names a subject
     * rather than an action leaves you pressing it to find out.
     */
    private fun cycleMarkLabel(year: Int, month: Int, day: Int): String {
        val on = Cycle.marked(
            CycleStore.all(this), Cycle.epochDay(year, month, day)
        )
        return getString(if (on) R.string.cycle_unmark_day else R.string.cycle_mark_day)
    }

    /**
     * Marks or unmarks a day, from the calendar, in one tap.
     *
     * The sheet is still where a period's length is set and where the
     * history is edited. This is the two-second job that the sheet is too
     * much ceremony for — and the one that was asked for by name.
     */
    private fun markCycleOn(year: Int, month: Int, day: Int) {
        val at = Cycle.epochDay(year, month, day)
        CycleStore.replace(this, Cycle.tapped(CycleStore.all(this), at))
        refreshCalendarMarks()
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

    /**
     * The five questions the digits ask, and the two they share.
     *
     * Which numerals and how they are made are its own; whether the
     * seconds are shown and whether it ticks are the dial's questions
     * under different names — see [FaceOptions.titleFor] — so they are
     * read from the same stored answers here.
     */
    private fun applyDigitalPreferences(theme: ClockTheme) {
        val face = digitalView ?: return
        face.theme = theme
        face.style = DigitStyle.of(prefs.getString(Prefs.DIGIT_STYLE, null))
        face.script = DigitScript.of(prefs.getString(Prefs.DIGIT_SCRIPT, null))
        face.hour24 = prefs.getBoolean(Prefs.HOUR_24, true)
        face.leadingZero = prefs.getBoolean(Prefs.LEADING_ZERO, true)
        face.blinkColon = prefs.getBoolean(Prefs.BLINK_COLON, false)
        face.weight = when (prefs.getString(Prefs.SEGMENT_WEIGHT, Prefs.WEIGHT_NORMAL)) {
            Prefs.WEIGHT_HAIRLINE -> 0.038f
            Prefs.WEIGHT_HEAVY -> 0.080f
            else -> 0.055f
        }
        face.ghosts = prefs.getBoolean(Prefs.SEGMENT_GHOSTS, true)
        face.pokeable = prefs.getBoolean(Prefs.POKE_SEGMENTS, false)
        face.onPoked = {
            chimePlayer.playTick()
            showReassembleIfNeeded()
        }
        face.showSeconds = prefs.getBoolean(Prefs.SECOND_HAND, true)
        face.showDate = prefs.getBoolean(Prefs.SHOW_DATE, false)
        face.dateDayFirst = DateShape.dayFirst(
            DateShape.order(prefs.getString(Prefs.DATE_ORDER, DateShape.AUTO)),
            phoneWritesDayFirst()
        )
        face.yautja = Yautja.face(this)
    }

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
        cv.dialMarks = when (prefs.getString(Prefs.DIAL_MARKS, Prefs.MARKS_12)) {
            Prefs.MARKS_6 -> 6
            Prefs.MARKS_4 -> 4
            Prefs.MARKS_NONE -> 0
            else -> 12
        }
        cv.minuteMarks = prefs.getBoolean(Prefs.MINUTE_MARKS, true)
        cv.dialShape = readDialShape()
        // Off while a time is being wound, whatever the setting says — see
        // applyMode(). Anything that reapplies preferences mid-wind would
        // otherwise put it back under the user's finger.
        cv.showSecondHand = dialJob == null && prefs.getBoolean(Prefs.SECOND_HAND, true)
        // The minute hand, unlike the second one, stays on while a time is
        // being wound: it is one of the two hands you are placing.
        cv.showMinuteHand = prefs.getBoolean(Prefs.MINUTE_HAND, true)
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
        paintSurround()
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
        // The sky's one switch puts its own door on the dial. It used to
        // hang off the moon complication, which meant the switch a person
        // actually wanted did nothing until they had found and turned on a
        // different one — see [Prefs.ORRERY].
        val sky = prefs.getBoolean(Prefs.ORRERY, false)
        cv.showMoonPhase = dialJob != null || skyTokenWanted()
        cv.moonPhaseShown = moonPhaseWanted()
        cv.orreryEnabled = dialJob == null && sky
        cv.cometsEnabled = prefs.getBoolean(Prefs.COMETS, false)
        cv.zodiacShown = prefs.getBoolean(Prefs.ZODIAC, false)
        // The shadows want the sun where it actually is, so they want a
        // place. DayNight has already read the stored fix; without one it
        // falls back to a middle latitude and the zone's nominal longitude,
        // because a switch that draws nothing when you turn it on is worse
        // than one that is honest about guessing — see [HandShadow].
        cv.handShadows = prefs.getBoolean(Prefs.HAND_SHADOWS, false)
        cv.shadowSurface = if (
            prefs.getString(Prefs.SHADOW_SURFACE, Prefs.SHADOW_GROUND) == Prefs.SHADOW_WALL
        ) {
            HandShadow.Surface.WALL
        } else {
            HandShadow.Surface.GROUND
        }
        DayNight.configure(this)
        cv.shadowLatitude =
            if (DayNight.hasFix()) DayNight.latitudeNow() else HandShadow.NO_FIX_LATITUDE
        cv.shadowLongitude =
            if (DayNight.hasFix()) DayNight.longitudeNow()
            else HandShadow.longitudeFromZone(
                java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
            )
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
        applyDigitalPreferences(cv.theme)

        worldBubbles.rebuild()
        worldBubbles.secondHands = prefs.getBoolean(Prefs.WORLD_SECONDS, true)
        worldBubbles.applyStyle(cv)

        // The chrono dials mirror the clock's styling — shape, scale and all
        // — so every face is the same size. They stay touchable regardless
        // of the grab-hands preference: winding is how you set them.
        for (dial in listOfNotNull(countdownClockView, stopwatchClockView)) {
            dial.hoursOnDial = cv.hoursOnDial
            dial.dialMarks = cv.dialMarks
            dial.minuteMarks = cv.minuteMarks
            dial.dialShape = cv.dialShape
            // Read from the settings rather than copied off C0: while a
            // time is being wound C0 puts its own second and tenths hands
            // away, and mirroring it took them off the stopwatch and the
            // countdown too — where they are the whole point — until the
            // next time preferences happened to be applied.
            dial.showSecondHand = prefs.getBoolean(Prefs.SECOND_HAND, true)
            dial.showMinuteHand = prefs.getBoolean(Prefs.MINUTE_HAND, true)
            dial.smoothSeconds = cv.smoothSeconds
            dial.mirrored = cv.mirrored
            dial.numeralStyle = cv.numeralStyle
            dial.fastHand = readFastHand()
            dial.theme = cv.theme
            dial.touchHandsEnabled = true
            dial.pinchZoomEnabled = cv.pinchZoomEnabled
            dial.dialScale = cv.dialScale
            // And the light in the room, which they were not getting: a
            // stopwatch is the same object lying on the same table as the
            // clock, and swapping between the two put the sun out. What a
            // chrono dial shows is an elapsed duration rather than a time
            // of day, so its light is the light of now — see
            // [ClockView.depictedMs].
            dial.handShadows = cv.handShadows
            dial.shadowLatitude = cv.shadowLatitude
            dial.shadowLongitude = cv.shadowLongitude
            dial.shadowSurface = cv.shadowSurface
        }

        // The world clock's bubbles float over the dial, and over the
        // planets too until told otherwise. They leave with the hands.
        cv.onSkyFade = { fade -> worldBubbles.layer?.alpha = 1f - fade }
        cv.orreryBusyDays = busyDaysOfTheYear()

        calendarView?.let {
            it.theme = cv.theme
            // Its own answer, not the dial's. Roman numerals are a fine
            // thing to have on a clock face and a grid of thirty-one of
            // them is a puzzle, so the two questions were separated.
            it.numeralStyle = readNumeralStyle(Prefs.CALENDAR_NUMERALS)
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

    // What the stored settings mean, in one place — see [DialSettings].
    // They lived here as a dozen little `when` blocks among three thousand
    // lines about running an app, and twice this year the same rule turned
    // out to be written in two of them.

    private fun readFastHand(): ClockView.FastHandMode = DialSettings.fastHand(prefs)

    private fun readDialShape(): ClockView.DialShape = DialSettings.dialShape(prefs)

    private fun skyTokenWanted(): Boolean = DialSettings.skyTokenWanted(prefs)

    private fun moonPhaseWanted(): Boolean = DialSettings.moonPhaseWanted(prefs)

    private fun readNumeralStyle(key: String = Prefs.NUMERALS): ClockView.NumeralStyle =
        DialSettings.numerals(prefs, key)

    private fun readHoursOnDial(): Int = DialSettings.hoursOnDial(prefs)

    // ------------------------------------------------- chronograph modes

    /** The centred bottom button goes up to the hourglass ⏳ and back 🕐. */
    private fun cycleMode() {
        if (Card.HOURGLASS !in face.cards) return
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
    private fun timerCard(): Card = when {
        // A face with no hourglass has one timer, and that is where every
        // way in from outside lands, whatever was written down last.
        Card.HOURGLASS !in face.cards -> Card.REVERSE
        prefs.getBoolean(Prefs.TIMER_ON_DIAL, false) -> Card.REVERSE
        else -> Card.HOURGLASS
    }

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
        // A widget, a notification or a shortcut can name a card this face
        // has not got. There is nothing to show and nothing to animate, and
        // going anyway left the pager on a page with a hidden card on it.
        if (card !in face.cards) return
        closeSheetLeftBehind()
        handOverSource = visibleDial()
        val wasRow = row
        val wasPage = pager.currentItem
        // Which card is on screen *now*, asked before anything moves. Asked
        // afterwards it was "which card of the row I am leaving lives on
        // the page I have just arrived at", which on a diagonal names a
        // card that was never on screen at all — going to the countdown
        // brought the alarms up for one frame and then faded them away.
        val leaving = Cards.on(wasPage, wasRow, face)
        row = card.row
        keepScreenAwake()
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
        val there = Cards.neighbour(card, if (fingerRight) Direction.LEFT else Direction.RIGHT, face)
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
        // Null on a face with no dial, and it matters: the hand-over that
        // carries one card's hands onto the next asks this, and a dial
        // that is not on the screen has no hands to lend.
        Card.CLOCK -> clockView.takeIf { face.hands }
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
        digitalView?.isDisarranged() == true ||
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
                // The minute hand comes back whatever the setting says.
                // Choosing an alarm for twenty past seven with the hour
                // hand alone is choosing it to the nearest half hour, and
                // this is the one screen where the hand is not decoration.
                it.showMinuteHand = true
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
                it.showMoonPhase = skyTokenWanted()
                it.moonPhaseShown = moonPhaseWanted()
                it.orreryEnabled = prefs.getBoolean(Prefs.ORRERY, false)
                it.cometsEnabled = prefs.getBoolean(Prefs.COMETS, false)
                it.zodiacShown = prefs.getBoolean(Prefs.ZODIAC, false)
                it.handShadows = prefs.getBoolean(Prefs.HAND_SHADOWS, false)
                it.shadowLatitude = clockView?.shadowLatitude ?: HandShadow.NO_FIX_LATITUDE
                it.shadowLongitude = clockView?.shadowLongitude ?: 0.0
                it.showSecondHand = prefs.getBoolean(Prefs.SECOND_HAND, true)
                it.showMinuteHand = prefs.getBoolean(Prefs.MINUTE_HAND, true)
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
        Cards.on(pager.currentItem, row, face)?.let { here ->
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
        for (card in face.cards) {
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
        // A running stopwatch is something you are watching, so it keeps
        // the screen; a stopped one has no such claim.
        keepScreenAwake()
        pushed(if (nowRunning) Pusher.Feel.START else Pusher.Feel.STOP)
    }

    private fun resetChrono() {
        // Remembered only when there is something to remember, so pressing
        // reset twice does not replace the last race with nothing.
        val was = stopwatch.elapsed()
        if (was > 0L) lastRunMs = was
        stopwatch.reset()
        stopwatchClockView?.chronoRunning = false
        stopwatchClockView?.glideChronoTo(was, 0L)
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
        // A countdown that has just been started, or stopped, changes
        // whether this card has any claim on the screen staying lit.
        keepScreenAwake()
    }

    private fun resetCountdown() {
        // To nothing, like the stopwatch's reset beside it. It used to mean
        // "again" — back to the length it was last set to — which is what a
        // kitchen timer's reset means and is a perfectly good button. It is
        // also the opposite of what the pusher one card away does, and the
        // two dials are the same dial with different hands: a reset that
        // clears on one page and refills on the other is a button whose
        // meaning depends on which way you swiped to get there.
        //
        // Nothing is lost by it. The length goes to the crown, which is
        // where this watch already keeps its second thoughts — see
        // [restoreLastCountdown].
        //
        // The hands travel there rather than arriving: everything else on
        // this dial travels, and a jump reads as a glitch.
        val was = countdownRemaining()
        // What the crown remembers is the length somebody *set*, not what
        // was left of it. This wrote the remaining time here, so a ten
        // minute countdown stopped with three minutes to go and reset came
        // back as three minutes — the crown taking you to the end of the
        // count instead of to the beginning of it, which is the one thing
        // it exists not to do. The length is remembered where a length is
        // chosen: on release of the hands, and on adopting one from
        // outside.
        countdown.reset()
        countdownClockView?.glideChronoTo(was, 0L)
        pushed(Pusher.Feel.RESET)
        CountdownService.clearPublished(this)
        updateCountdownUi()
        HourglassWidgetProvider.pushIdle(this)
    }

    /**
     * The crown on the countdown: the last length it was set to, put back.
     *
     * The stopwatch's crown does exactly this with the last race, and for
     * exactly the same reason — a dial reading zero has been either never
     * used or cleared, and only one of those is worth offering to undo. A
     * three-minute timer cleared by accident is three minutes to wind back
     * by hand, and the crown is where a mechanical watch keeps the way out
     * of that.
     *
     * Only from zero. A countdown with something on it is not asking to be
     * replaced, and the crown's other job — tidying the scene — still
     * happens either way.
     */
    private fun restoreLastCountdown() {
        if (countdownRunning) return
        val back = lastCountdownMs
        if (back <= 0L) return
        countdown.setTo(back)
        countdownClockView?.glideChronoTo(0L, back)
        updateCountdownUi()
        HourglassWidgetProvider.pushIdle(this)
    }

    /**
     * The click, or the bird.
     *
     * The cuckoo belongs to the crown having *done* something — put the
     * hands back, or torn up the cheater's stamp. Winding a tidy dial set a
     * whole bird off for nothing, several times a minute, which is how a
     * good joke becomes a bad one. A tidy dial gets a click.
     */
    private fun crownSound(tidied: Boolean) {
        if (tidied) chimePlayer.playCuckoo() else chimePlayer.playTick()
    }

    /**
     * The last race, put back on a stopwatch that has been cleared.
     *
     * A stopwatch reads zero for two different reasons — it has never been
     * run, or it has been run and cleared — and only one of them is worth
     * offering to undo. So the reading is remembered when it is cleared and
     * not when it is already zero, which is what keeps a second and third
     * press of reset from wiping the memory of the first.
     */
    private fun restoreLastRun() {
        val back = lastRunMs
        if (back <= 0L || stopwatchRunning || stopwatch.elapsed() != 0L) return
        stopwatch.restore(back, 0L, false)
        stopwatchClockView?.glideChronoTo(0L, back)
        stopwatchClockView?.invalidate()
    }

    /** The reading the stopwatch had when it was last cleared. */
    private var lastRunMs = 0L

    // ------------------------------------------------- scheduled chimes

    private fun onMinuteBoundary() {
        // Night mode keeps the house quiet: no bells while the dial is dim.
        if (!bellsEnabled || appliedNightDim) return
        // So does an alarm going off, unless the bells have been given the
        // right of way. The same rule the background bells follow, asked of
        // the same place — this used to be the half that could disagree.
        if (!Bells.mayStrike(
                prefs.getString(Prefs.BELL_PRIORITY, Bells.PRIORITY_ALARM),
                AlarmService.ringing
            )
        ) {
            return
        }
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
        /**
         * How long the "just tomorrow" offer stays on screen.
         *
         * Long enough to read and notice; short enough that it is not a
         * thing to be dismissed. Ignoring it is a way of answering, and an
         * offer that has to be got rid of is the dialog again wearing a
         * different shape.
         */
        const val SKIP_BUBBLE_MS = 6_000L

        /**
         * What the running clock's tick has been doing, for whoever asks in
         * the settings.
         *
         * Here rather than on the instance because the settings are a
         * different activity, and the numbers worth reading are the ones
         * from the clock that has been ticking all night.
         */
        @Volatile
        var lastTickRecord: Ticker.Record? = null

        const val EXTRA_OPEN_ALARMS = "extra_open_alarms"
        const val EXTRA_OPEN_TIMER = "extra_open_timer"
        const val EXTRA_OPEN_CALENDAR = "extra_open_calendar"

        /**
         * From the solar-system widget: open the clock with the sky
         * already up.
         *
         * Somebody who taps a picture of the planets is asking for the
         * planets, not for the front page with the planets one gesture
         * away — and the gesture is a tap on the sun, which is not
         * something a first-time tapper of the widget necessarily knows.
         */
        const val EXTRA_OPEN_SKY = "extra_open_sky"

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

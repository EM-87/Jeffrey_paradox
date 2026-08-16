package com.em87.weirdclock

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

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
        // Before anything is laid out, let alone drawn. Set after the
        // window was up, the screen came on at whatever brightness the
        // phone was on and *then* dropped to the glow — a blink, which is
        // the opposite of a gentle wake.
        startGentleWake()
        setContentView(R.layout.activity_alarm_ring)
        SystemChrome.paint(this)
        SystemChrome.padForBars(findViewById(android.R.id.content))

        // Stopped from the notification, or given up after three minutes:
        // either way this screen has nothing left to offer.
        AlarmService.onStopped = { runOnUiThread { finish() } }

        // Time since it went off, ticking up. Based on when the service
        // started ringing rather than on when this screen opened, so the
        // count survives the screen being rebuilt — and so it is still
        // honest if the screen arrives a moment late.
        findViewById<android.widget.Chronometer>(R.id.alarm_time_text).apply {
            val since = AlarmService.ringingSince
            base = if (since > 0L) since else android.os.SystemClock.elapsedRealtime()
            start()
        }

        // A countdown that has run out is not an alarm going off. Same screen,
        // but it wears a stopwatch instead of a bell, and falls back to
        // "Time's up!" rather than the app's alarm line when nothing named it.
        //
        // Asked of the service first and of the intent second. Whoever rang
        // knows; an intent only knows what the caller remembered to put on
        // it, and for two versions one caller did not.
        val fromTimer = AlarmService.ringingFromTimer ||
            intent.getBooleanExtra(AlarmScheduler.EXTRA_FROM_TIMER, false)
        val glyph = findViewById<TextView>(R.id.ring_glyph)
        glyph.text = if (fromTimer) "⏱" else "🔔"
        glyph.contentDescription =
            getString(if (fromTimer) R.string.countdown_done else R.string.alarm_ringing)

        val subtitle = findViewById<TextView>(R.id.ring_subtitle)
        if (fromTimer) subtitle.setText(R.string.countdown_done)
        intent.getStringExtra(AlarmScheduler.EXTRA_LABEL)?.takeIf { it.isNotBlank() }?.let {
            subtitle.text = it
        }
        findViewById<SlideToStopView>(R.id.stop_slider).onSlid = { stopRinging() }
        setUpMission()
        val snoozeButton = findViewById<Button>(R.id.snooze_button)
        val snoozeMinutes = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE, 0)
        val already = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
        val limit = AlarmScheduler.snoozeLimit(this)
        // Offered only while it would do something. A Snooze button that
        // silently does nothing is worse than no button: the whole reason
        // for a limit is that the last one has to be got up for, and that
        // has to be visible before you press it, not after.
        val spent = limit in 1..already
        if (snoozeMinutes > 0 && !spent) {
            snoozeButton.visibility = android.view.View.VISIBLE
            snoozeButton.text = getString(R.string.alarm_snooze_fmt, snoozeMinutes)
            val sound = intent.getStringExtra(AlarmScheduler.EXTRA_SOUND) ?: Prefs.ALARM_SOUND_BELLS
            val soundUri = intent.getStringExtra(AlarmScheduler.EXTRA_SOUND_URI) ?: ""
            snoozeButton.setOnClickListener {
                // The snooze takes over from here, so anything already
                // booked would land in the middle of it.
                Nag.callOff(this)
                AlarmScheduler.snooze(this, sound, snoozeMinutes, soundUri, already)
                startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
                finish()
            }
        }
    }

    private fun stopRinging() {
        // Dealt with properly, so nothing is coming back. Called on every
        // stop and not only after a mission: a nag left armed from an
        // earlier round would otherwise go off after a morning that had
        // already been got up for.
        Nag.callOff(this)
        startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
        finish()
    }

    // -------------------------------------------------------- gentle wake

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** How long the screen takes to come up, in ms; 0 for straight on. */
    internal var gentleRampMs = 0L
        private set

    /** What the window is set to now, for the tests to read. */
    internal val screenBrightness: Float
        get() = window.attributes.screenBrightness

    private val brighten = object : Runnable {
        override fun run() {
            val elapsed = ringingFor()
            setBrightness(GentleWake.brightness(elapsed, gentleRampMs))
            // Re-posted only while there is ramp left, so a screen that has
            // arrived at full brightness costs nothing to sit on.
            if (GentleWake.ramping(elapsed, gentleRampMs)) handler.postDelayed(this, 100L)
        }
    }

    /**
     * How long this alarm has been ringing — measured from when the
     * service started, not from when this screen was built.
     *
     * A screen rebuilt half way through (a rotation, the system rebuilding
     * it over the lock screen) would otherwise start the ramp again from
     * the dark, which is the one moment it must not: by then somebody is
     * looking at it.
     */
    private fun ringingFor(): Long = GentleWake.elapsed(
        AlarmService.ringingSince, android.os.SystemClock.elapsedRealtime()
    )

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun startGentleWake() {
        gentleRampMs = GentleWake.clamp(
            intent.getIntExtra(AlarmScheduler.EXTRA_GENTLE, 0)
        ) * 1000L
        // Nothing asked for means the window is left exactly as it was —
        // taking the screen brightness over at all is a thing to do only
        // when somebody has said so.
        if (gentleRampMs <= 0L) return
        // Set here and now, not on the next pass of the looper: a posted
        // message runs after this window has had its first frame, which is
        // the frame that was arriving at full brightness.
        setBrightness(GentleWake.brightness(ringingFor(), gentleRampMs))
        handler.post(brighten)
    }

    // ----------------------------------------------------------- missions

    private var problem: Mission.Problem? = null

    /** For the tests: the answer wanted right now, whatever the rung. */
    internal val wantedAnswer: Int?
        get() = problem?.answer
    private var shakes: Mission.Shakes? = null
    private var sensors: android.hardware.SensorManager? = null

    /** Which mission this screen is showing, once it has been asked. */
    internal var missionKind = Mission.NONE
        private set

    /** And on which rung of the ladder, when it is a sum. */
    internal var missionLevel = Mission.DEFAULT_LEVEL
        private set

    private val shakeListener = object : android.hardware.SensorEventListener {
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            val counter = shakes ?: return
            val done = counter.feed(
                Mission.magnitude(event.values[0], event.values[1], event.values[2])
            )
            showShakeProgress()
            if (done) stopRinging()
        }
    }

    /**
     * The mission takes the slider's place, or the slider stays.
     *
     * Never both: a mission beside a working slide-to-stop is decoration,
     * and the one thing this feature is for is that there is no easier way
     * out than the one that needs you awake.
     */
    private fun setUpMission() {
        // The alarm's own, carried on the intent. It used to be a setting
        // of the app, which meant one answer for the alarm that wakes you
        // and the reminder that says the bread is done.
        missionKind = Mission.required(intent.getStringExtra(AlarmScheduler.EXTRA_MISSION))
        missionLevel = Mission.level(
            intent.getIntExtra(AlarmScheduler.EXTRA_MISSION_LEVEL, Mission.DEFAULT_LEVEL)
        )
        // A finished countdown is not a wake-up. Making somebody do sums to
        // silence the pasta timer would be a joke that stops being funny
        // the first time it happens.
        val fromTimer = AlarmService.ringingFromTimer ||
            intent.getBooleanExtra(AlarmScheduler.EXTRA_FROM_TIMER, false)
        if (fromTimer) missionKind = Mission.NONE
        if (missionKind == Mission.NONE) return

        findViewById<android.view.View>(R.id.stop_slider).visibility = android.view.View.GONE
        val block = findViewById<android.view.View>(R.id.mission_block)
        block.visibility = android.view.View.VISIBLE

        val prompt = findViewById<TextView>(R.id.mission_prompt)
        val answer = findViewById<android.widget.EditText>(R.id.mission_answer)
        val button = findViewById<Button>(R.id.mission_button)

        if (missionKind == Mission.SHAKE) {
            sensors = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager
            val accelerometer =
                sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            if (accelerometer == null) {
                // No accelerometer, no shaking — and a shake mission on a
                // phone that cannot feel one is an alarm with no way to
                // turn it off at all. The slider comes back rather than
                // leaving somebody with a screen that asks for something
                // impossible.
                missionKind = Mission.NONE
                block.visibility = android.view.View.GONE
                findViewById<android.view.View>(R.id.stop_slider).visibility =
                    android.view.View.VISIBLE
                return
            }
            answer.visibility = android.view.View.GONE
            button.visibility = android.view.View.GONE
            shakes = Mission.Shakes()
            showShakeProgress()
            sensors?.registerListener(
                shakeListener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_GAME
            )
            return
        }

        // The numeric keypad takes the bottom half of the screen, and what
        // it was covering was the question, the box and the button — all
        // three of the things the mission is made of. The bell and the
        // running count are decoration next to that, so they stand down.
        findViewById<android.view.View>(R.id.ring_glyph).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.alarm_time_text).visibility =
            android.view.View.GONE

        askAnother(prompt, answer)
        button.setOnClickListener {
            val typed = answer.text.toString()
            if (Mission.solved(problem ?: return@setOnClickListener, typed)) {
                stopRinging()
            } else {
                // A fresh one, so a wrong answer cannot be got past by
                // pressing the button again until it happens to be right.
                Pusher.play(this, Pusher.Feel.STOP)
                askAnother(prompt, answer)
            }
        }
    }

    private fun askAnother(prompt: TextView, answer: android.widget.EditText) {
        val next = Mission.problem(missionLevel)
        problem = next
        prompt.text = next.text
        answer.text = null
    }

    private fun showShakeProgress() {
        val counter = shakes ?: return
        findViewById<TextView>(R.id.mission_prompt).text =
            getString(R.string.mission_shake_fmt, counter.count, counter.needed)
    }

    override fun onDestroy() {
        AlarmService.onStopped = null
        sensors?.unregisterListener(shakeListener)
        handler.removeCallbacks(brighten)
        super.onDestroy()
    }
}

package com.em87.weirdclock

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * The hidden metronome. There is no way into this screen from the normal
 * UI: it opens only after tapping the version row in settings seven times,
 * in the finest Android tradition. Tap the beat, read your BPM, let the
 * pendulum keep it.
 */
class BpmActivity : AppCompatActivity() {

    private val chimePlayer = ChimePlayer()
    private var bpmView: BpmView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bpm)
        SystemChrome.paint(this)
        SystemChrome.padForBars(findViewById(android.R.id.content))
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        bpmView = findViewById<BpmView>(R.id.bpm_view).also {
            it.theme = ClockThemes.resolve(this, prefs.getString(Prefs.THEME, "midnight"))
            it.onTap = { chimePlayer.playTick() }
            it.onBeat = { chimePlayer.playTick() }
        }
        chimePlayer.prepareTick(this)
    }

    override fun onPause() {
        bpmView?.stopMetronome()
        super.onPause()
    }

    override fun onDestroy() {
        chimePlayer.release()
        super.onDestroy()
    }
}

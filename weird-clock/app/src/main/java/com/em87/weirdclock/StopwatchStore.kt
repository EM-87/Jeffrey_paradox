package com.em87.weirdclock

import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * A stopwatch run, written down so it survives the app being closed.
 *
 * Nothing about the stopwatch was kept: not the laps, not the accumulated
 * time, not even the fact that it was running. Time something for an hour,
 * have Android reclaim the app while you are in another one, and the run is
 * simply gone — which is the one thing a stopwatch must never do.
 */
object StopwatchStore {

    private const val KEY_ACCUM = "stopwatch_accum_ms"
    private const val KEY_STARTED = "stopwatch_started_at"
    private const val KEY_RUNNING = "stopwatch_running"
    private const val KEY_BOOT = "stopwatch_boot_at"
    private const val KEY_LAPS = "stopwatch_laps"

    class Run(
        val accumMs: Long,
        val startedAt: Long,
        val running: Boolean,
        val laps: List<ClockView.LapRecord>
    )

    /**
     * Roughly when the device last started, on the wall clock.
     *
     * The stopwatch runs on [SystemClock.elapsedRealtime], which counts
     * from boot and so begins again at zero after one — a stored start time
     * from before a reboot would read as an enormous elapsed time. Storing
     * when that clock started lets a reboot be recognised rather than
     * measured.
     */
    private fun bootAt(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    fun save(
        prefs: SharedPreferences,
        accumMs: Long,
        startedAt: Long,
        running: Boolean,
        laps: List<ClockView.LapRecord>
    ) {
        val array = JSONArray()
        for (lap in laps) {
            array.put(
                JSONObject()
                    .put("ms", lap.ms)
                    .put("fake", lap.fake)
                    .put("h", lap.hour.toDouble())
                    .put("m", lap.minute.toDouble())
                    .put("s", lap.second.toDouble())
            )
        }
        prefs.edit()
            .putLong(KEY_ACCUM, accumMs)
            .putLong(KEY_STARTED, startedAt)
            .putBoolean(KEY_RUNNING, running)
            .putLong(KEY_BOOT, bootAt())
            .putString(KEY_LAPS, array.toString())
            .apply()
    }

    /**
     * What was going on last time, or null if nothing was.
     *
     * Across a reboot the elapsed clock has restarted, so a run that was in
     * progress cannot be continued: what it had already banked is kept and
     * the watch comes back stopped. Guessing the missing stretch from the
     * wall clock would be worse than admitting to it — the wall clock is
     * the one thing on the device a user can change by hand.
     */
    fun load(prefs: SharedPreferences): Run? {
        if (!prefs.contains(KEY_ACCUM)) return null
        val accum = prefs.getLong(KEY_ACCUM, 0L)
        val startedAt = prefs.getLong(KEY_STARTED, 0L)
        var running = prefs.getBoolean(KEY_RUNNING, false)
        // A couple of seconds of slack: the boot instant is computed from
        // two clocks that tick independently, so it drifts a little even
        // when the device has not been near a reboot.
        val rebooted = kotlin.math.abs(prefs.getLong(KEY_BOOT, 0L) - bootAt()) > 5_000L
        var banked = accum
        if (running && rebooted) {
            running = false
        } else if (running) {
            banked = accum
        }

        val laps = mutableListOf<ClockView.LapRecord>()
        val raw = prefs.getString(KEY_LAPS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    laps.add(
                        ClockView.LapRecord(
                            o.optLong("ms"),
                            o.optBoolean("fake"),
                            o.optDouble("h").toFloat(),
                            o.optDouble("m").toFloat(),
                            o.optDouble("s").toFloat()
                        )
                    )
                }
            } catch (e: org.json.JSONException) {
                // A run that cannot be read is a run that is gone; it must
                // not also be an app that will not open.
            }
        }
        if (banked == 0L && !running && laps.isEmpty()) return null
        return Run(banked, startedAt, running, laps)
    }
}

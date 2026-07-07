package com.em87.weirdclock

/**
 * The app's notion of "now", with an adjustable speed factor. Display time
 * flows at [speedPercent]% of real time from an anchor point. Setting the
 * speed back to 100% snaps back to real time — no time travel allowed.
 */
object TimeKeeper {

    var speedPercent: Int = 100
        private set

    private var anchorRealMs: Long = System.currentTimeMillis()
    private var anchorDisplayMs: Long = anchorRealMs

    fun setSpeedPercent(percent: Int) {
        val clamped = percent.coerceIn(25, 400)
        if (clamped == speedPercent) return
        val realNow = System.currentTimeMillis()
        anchorDisplayMs = if (clamped == 100) realNow else nowMs()
        anchorRealMs = realNow
        speedPercent = clamped
    }

    fun nowMs(): Long {
        if (speedPercent == 100 && anchorDisplayMs == anchorRealMs) {
            return System.currentTimeMillis()
        }
        val realNow = System.currentTimeMillis()
        return anchorDisplayMs + ((realNow - anchorRealMs) * speedPercent / 100.0).toLong()
    }
}

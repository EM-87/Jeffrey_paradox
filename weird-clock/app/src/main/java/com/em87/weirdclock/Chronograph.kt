package com.em87.weirdclock

/**
 * A stopwatch, as arithmetic.
 *
 * It lived in the activity as three loose fields and a line of arithmetic
 * repeated wherever the number was wanted, which is how a running total
 * ends up being computed one way in the readout and another in the
 * persistence. There is not much to it — that is rather the point. What
 * there is can now be checked without a phone.
 *
 * The clock is passed in rather than read from [android.os.SystemClock],
 * so a test can move time by hand. It has to be the *elapsed-realtime*
 * clock and not the wall clock: a stopwatch that was started before
 * midnight and read after it must not lose a day, and one running while
 * somebody corrects the phone's time must not jump.
 */
class Chronograph(private val now: () -> Long) {

    /** Time banked from earlier runs, before the current one started. */
    var accumMs = 0L

    /** When the current run began, on the same clock as [now]. */
    var startedAt = 0L

    var running = false
        private set

    /** How long it reads, running or stopped. */
    fun elapsed(): Long = accumMs + if (running) now() - startedAt else 0L

    /**
     * Starts it if stopped and stops it if running; returns what it is now.
     *
     * Stopping banks what the current run was worth rather than reading the
     * clock again later — the value is fixed at the moment the pusher goes
     * down, which is the whole point of a chronograph.
     */
    fun startOrStop(): Boolean {
        if (running) {
            accumMs = elapsed()
            running = false
        } else {
            startedAt = now()
            running = true
        }
        return running
    }

    /** Back to nothing, and stopped. */
    fun reset() {
        running = false
        accumMs = 0L
        startedAt = 0L
    }

    /** Puts it back where it was found — after a reboot, say. */
    fun restore(accum: Long, started: Long, wasRunning: Boolean) {
        accumMs = accum
        startedAt = started
        running = wasRunning
    }
}

/**
 * And the same thing run backwards.
 *
 * Not a [Chronograph] with a minus sign: a countdown has a length it was
 * set to and a floor it stops at, and those are two facts a stopwatch does
 * not have. Sharing the class would mean a stopwatch carrying a total it
 * never uses and a countdown carrying a banked total that means something
 * different.
 */
class Countdown(private val now: () -> Long, startingAt: Long) {

    /** What it was set to, which is what the sand and the ring draw against. */
    var totalMs = startingAt

    /** What is left while it is stopped. Meaningless while it runs. */
    var remainingMs = startingAt

    /** When it will reach zero, on the same clock as [now]. */
    var endsAt = 0L

    var running = false
        private set

    /** How long is left, running or stopped, and never less than nothing. */
    fun remaining(): Long =
        if (running) (endsAt - now()).coerceAtLeast(0L) else remainingMs

    /**
     * Starts or stops it. Returns what it is now.
     *
     * Starting a countdown with nothing left to count does nothing at all:
     * it would run out in the same instant, which is a timer that goes off
     * the moment you press it.
     */
    fun startOrStop(): Boolean {
        if (running) {
            remainingMs = remaining()
            running = false
        } else if (remaining() > 0L) {
            endsAt = now() + remainingMs
            totalMs = remainingMs
            running = true
        }
        return running
    }

    /** Back to nothing, waiting to be wound to a new length. */
    fun reset() {
        running = false
        remainingMs = 0L
    }

    /**
     * Wound to a new length by hand, which only makes sense while stopped.
     *
     * Never more than a day. Past that the hands have gone round the whole
     * dial twice and the number underneath means nothing anybody can read
     * off the face — and a thing you want to happen the day after tomorrow
     * is an alarm, which this app already has and which survives the phone
     * being switched off.
     */
    fun setTo(ms: Long) {
        remainingMs = ms.coerceAtMost(A_DAY_MS)
        // A freshly set countdown is all sand up top, so the total it draws
        // against is the length just chosen — and never zero, or the sand
        // would be dividing by it.
        totalMs = remainingMs.coerceAtLeast(1000L)
    }

    private companion object {
        /** The longest a countdown can be wound to. */
        const val A_DAY_MS = 24L * 60 * 60 * 1000
    }

    /** Adopted from elsewhere: the tile in the shade, or a spoken request. */
    fun adopt(endingAt: Long, total: Long) {
        endsAt = endingAt
        totalMs = total
        running = true
    }
}

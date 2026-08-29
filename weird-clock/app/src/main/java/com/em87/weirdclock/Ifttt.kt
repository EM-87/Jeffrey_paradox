package com.em87.weirdclock

import org.json.JSONObject

/**
 * The clock, telling the rest of the house what it is doing.
 *
 * One webhook key and five moments. Its owner's example is the one that
 * makes the case: an alarm set for seven, and a sunrise scene in the
 * bedroom lights that starts ramping at half past six — which needs the
 * clock to say something *before* it rings, because a lamp that begins its
 * sunrise at the same instant as the bell has not simulated anything.
 *
 * Deliberately small, and this file is where that is enforced. A clock
 * that can fire arbitrary requests at arbitrary services is not a feature,
 * it is a footgun with a settings page; so there is one destination, five
 * fixed event names, and nothing anywhere that lets a URL be typed in.
 * What the owner chooses is a key and whether it is on.
 *
 * Everything here is strings and rules. The sending is [IftttStore].
 */
object Ifttt {

    /**
     * The five things a clock knows that a house might want.
     *
     * Fixed names rather than five text boxes. An applet is named on
     * IFTTT's side anyway, so a name here buys nothing but five more rows
     * to get wrong — and a mistyped event name fails silently, which is
     * the worst way for a thing like this to fail.
     */
    enum class Event(val event: String) {

        /**
         * The alarm is coming, in so many minutes.
         *
         * The one that earns this whole feature. Everything else here is a
         * thing that has already happened.
         */
        SOON("weird_clock_soon"),

        /** It is ringing now. */
        ALARM("weird_clock_alarm"),

        /** Put off, for so many minutes. */
        SNOOZE("weird_clock_snooze"),

        /** Turned off by somebody who is now awake. */
        DISMISS("weird_clock_dismiss"),

        /** And a countdown that has run out, which is not an alarm. */
        TIMER("weird_clock_timer")
    }

    /** Where IFTTT listens. */
    const val HOST = "https://maker.ifttt.com"

    /**
     * The shortest gap between two of the same event, in milliseconds.
     *
     * A guard rather than a feature. Anything that fires a request from
     * inside a service that can be restarted is one loop away from
     * hammering somebody's house, and the failure mode of that is lights
     * flashing at four in the morning rather than an exception in a log.
     */
    const val QUIET_MS = 20_000L

    /**
     * Whether a key looks like a key.
     *
     * IFTTT's are a couple of dozen letters, digits, dashes and
     * underscores. This is not authentication — it is the check that stops
     * a pasted URL, a stray newline or half a sentence from being put in
     * the path of a request, which is how a webhook key becomes an open
     * redirect.
     */
    fun usable(key: String?): Boolean {
        val text = key?.trim() ?: return false
        if (text.length !in 8..120) return false
        return text.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /**
     * Where one event goes.
     *
     * The event name is this file's own constant and the key has been
     * through [usable], so nothing in here came from a text box unchecked.
     */
    fun url(key: String, event: Event): String =
        "$HOST/trigger/${event.event}/with/key/${key.trim()}"

    /**
     * What goes with it: three values, which is all IFTTT carries.
     *
     * Built with a JSON writer rather than by pasting strings together,
     * because the first value is an alarm's label and an alarm's label is
     * whatever somebody typed — including a quotation mark, which would
     * otherwise end the field and hand a malformed body to somebody else's
     * server.
     */
    fun body(value1: String? = null, value2: String? = null, value3: String? = null): String {
        val json = JSONObject()
        value1?.let { json.put("value1", it) }
        value2?.let { json.put("value2", it) }
        value3?.let { json.put("value3", it) }
        return json.toString()
    }

    /**
     * The time of day an event is about, as a house would read it.
     *
     * Always twenty-four hour and always two digits, whatever the clock
     * face is set to: this is going to a machine, and a machine that has
     * to parse "7:05 PM" is a machine somebody will curse at.
     */
    fun clockOf(hour: Int, minute: Int): String =
        String.format(java.util.Locale.US, "%02d:%02d", hour, minute)

    /**
     * Whether this event may be sent, given when the same one last was.
     *
     * Separated out because it is the only rule in here that can be got
     * wrong in a way that matters to somebody's house rather than to their
     * clock — see [QUIET_MS].
     */
    fun mayFire(lastMs: Long, nowMs: Long): Boolean =
        lastMs <= 0L || nowMs - lastMs >= QUIET_MS || nowMs < lastMs
}

package com.em87.weirdclock

import android.content.Context
import androidx.preference.PreferenceManager
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sending the five events, and refusing to send them the rest of the time.
 *
 * The second thing in this app that opens a socket, and the first that
 * *pushes* rather than asks. That is a different kind of risk and it is
 * worth naming: a clock that reads the weather badly shows the wrong
 * weather, and a clock that fires webhooks badly turns somebody's lights
 * on at four in the morning. So almost everything in here is a reason not
 * to send.
 *
 * Four gates, and all four have to be open: the switch is on, the key
 * looks like a key, this event has not just been sent, and the request
 * itself runs on a thread that nothing is waiting for. A failure is
 * silence — there is nothing useful a clock can do about a webhook that
 * did not land, and nothing worse it could do than retry in a loop.
 */
object IftttStore {

    /** How long any one request gets before it is abandoned. */
    const val TIMEOUT_MS = 8000

    /** Where the last time each event went is kept. */
    private const val LAST = "pref_ifttt_last_"

    /**
     * One request, so the tests can watch without a network.
     *
     * The same seam [WeatherStore] has and for a stronger reason: nothing
     * about this feature can be checked by looking at the clock. Whether a
     * request went, where it went, and whether it went twice are facts
     * that live entirely on the other side of a socket.
     */
    fun interface Post {
        fun send(url: String, body: String): Boolean
    }

    /** The real one, replaced in tests. */
    var post: Post = Post { url, body ->
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", WeatherSources.AGENT)
            }
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode in 200..299
        } catch (e: Exception) {
            // Every way a request can fail, and they are all the same
            // failure here: the house did not hear. There is nothing a
            // clock can usefully do about that and one obvious wrong thing
            // — try again, and again.
            false
        } finally {
            connection?.disconnect()
        }
    }

    /** Whether any of this has been switched on. */
    fun wanted(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(Prefs.IFTTT, false) &&
            Ifttt.usable(prefs.getString(Prefs.IFTTT_KEY, null))
    }

    /** How many minutes before an alarm the house is told it is coming. */
    fun lead(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(Prefs.IFTTT_LEAD, 30).coerceIn(0, 120)

    /**
     * Send one event, on a thread of its own, if all four gates are open.
     *
     * Returns whether it was *started*, not whether it arrived — the
     * caller is a service that has an alarm to ring and cannot wait for a
     * webhook.
     */
    fun fire(
        context: Context,
        event: Ifttt.Event,
        value1: String? = null,
        value2: String? = null,
        value3: String? = null
    ): Boolean {
        if (!wanted(context)) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = prefs.getString(Prefs.IFTTT_KEY, null) ?: return false
        val now = TimeKeeper.nowMs()
        val last = prefs.getLong(LAST + event.event, 0L)
        if (!Ifttt.mayFire(last, now)) return false
        // Written down before the request goes out, not after. A crash
        // halfway through a send must not leave the door open for an
        // immediate second one.
        prefs.edit().putLong(LAST + event.event, now).apply()
        val url = Ifttt.url(key, event)
        val body = Ifttt.body(value1, value2, value3)
        Thread {
            post.send(url, body)
        }.apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * The same thing without the thread, for the tests and for a caller
     * that is already off the main one.
     */
    fun fireNow(
        context: Context,
        event: Ifttt.Event,
        value1: String? = null,
        value2: String? = null,
        value3: String? = null
    ): Boolean {
        if (!wanted(context)) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = prefs.getString(Prefs.IFTTT_KEY, null) ?: return false
        val now = TimeKeeper.nowMs()
        if (!Ifttt.mayFire(prefs.getLong(LAST + event.event, 0L), now)) return false
        prefs.edit().putLong(LAST + event.event, now).apply()
        return post.send(Ifttt.url(key, event), Ifttt.body(value1, value2, value3))
    }

    /** Forgets when everything last went, for a key being changed. */
    fun forget(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
        for (event in Ifttt.Event.entries) editor.remove(LAST + event.event)
        editor.apply()
    }
}

package com.em87.weirdclock

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceDataStore
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
     * The preferences file the webhook key lives in, on its own.
     *
     * Everything else this app remembers is in the one shared preferences
     * file, and Android's own backup — to the Google account, and across to
     * a new phone while it is being set up — takes that file whole. Its
     * rules can leave out a file but not one line of one, so the key has a
     * file of its own and both sets of rules name it:
     * `res/xml/backup_rules.xml` for Android 11 and earlier,
     * `res/xml/data_extraction_rules.xml` for 12 and later. Rename this and
     * those two have to follow, which BackupRulesTest checks.
     */
    const val FILE = "phone_only"

    /** The key's own file, with any key still in the shared one moved in first. */
    private fun kept(context: Context): SharedPreferences {
        moveKeyOut(context)
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** The webhook key as typed, or null if there is none. */
    fun storedKey(context: Context): String? =
        kept(context).getString(Prefs.IFTTT_KEY, null)

    /** A key typed in, or null to forget the one there is. */
    fun storeKey(context: Context, key: String?) {
        kept(context).edit().putString(Prefs.IFTTT_KEY, key).apply()
    }

    /**
     * Takes the key out of the shared file, if it is there.
     *
     * Every key typed in before it had a file of its own is there, and so
     * is one brought back onto a new phone by an Android backup taken before
     * then. This runs on every read, when a new build is installed, and when
     * the app is opened — not on reads alone, because a key the app never
     * reads (switch off, key still saved) would otherwise sit in the
     * backed-up file for good.
     *
     * The copy is finished before the original goes, so a crash between the
     * two leaves the key in both files and the next run moves it again,
     * rather than leaving it in neither. A key found in the shared file
     * replaces one already moved: it can only have got there afterwards.
     */
    fun moveKeyOut(context: Context) {
        val shared = PreferenceManager.getDefaultSharedPreferences(context)
        if (!shared.contains(Prefs.IFTTT_KEY)) return
        val copied = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(Prefs.IFTTT_KEY, shared.getString(Prefs.IFTTT_KEY, null))
            .commit()
        if (copied) shared.edit().remove(Prefs.IFTTT_KEY).apply()
    }

    /**
     * The settings row's way to the key.
     *
     * Left to itself an EditTextPreference reads and writes the shared
     * file, which is the one place the key must not be.
     */
    class Row(context: Context) : PreferenceDataStore() {
        private val context = context.applicationContext

        override fun getString(key: String?, defValue: String?): String? =
            IftttStore.storedKey(context) ?: defValue

        override fun putString(key: String?, value: String?) =
            IftttStore.storeKey(context, value)
    }

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
        return prefs.getBoolean(Prefs.IFTTT, false) && Ifttt.usable(storedKey(context))
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
        val key = storedKey(context) ?: return false
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
        val key = storedKey(context) ?: return false
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

package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The four gates, each one shut on its own.
 *
 * This is the first thing in the app that *pushes* rather than asks, and
 * the difference is where a mistake lands: a clock that reads the weather
 * badly shows the wrong weather, and a clock that fires webhooks badly
 * turns somebody's lights on at four in the morning. So nearly every test
 * here is about a request that must not go.
 *
 * None of it can be seen by looking at the clock, either. Whether a
 * request went, where it went, and whether it went twice are facts that
 * live entirely on the other side of a socket — so the socket is a seam
 * and these tests hold the other end of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IftttStoreTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val sent = ArrayList<Pair<String, String>>()
    private val real = IftttStore.post
    private val key = "bX9kQ27fLmNp4rS8tV1w"

    @Before
    fun watchTheWire() {
        sent.clear()
        IftttStore.post = IftttStore.Post { url, body ->
            sent += url to body
            true
        }
        switchedOn(true)
    }

    @After
    fun putTheSocketBack() {
        IftttStore.post = real
    }

    private fun switchedOn(on: Boolean, withKey: String? = key) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.IFTTT, on)
            .apply { withKey?.let { putString(Prefs.IFTTT_KEY, it) } }
            .commit()
        IftttStore.forget(context)
    }

    /** Off means nothing leaves the phone. Not sent and discarded — not sent. */
    @Test
    fun `switched off, the house hears nothing`() {
        switchedOn(false)
        for (event in Ifttt.Event.entries) {
            assertFalse(IftttStore.fireNow(context, event, "wake up", "07:00"))
        }
        assertTrue("it went out on the wire with the switch off", sent.isEmpty())
        assertFalse(IftttStore.wanted(context))
    }

    /**
     * And on with no key is also off.
     *
     * Two settings, both needed. Somebody who flicks the switch before
     * pasting the key has an app that is on and cannot send, which must be
     * silence rather than a request to `.../with/key/null`.
     */
    @Test
    fun `switched on with no key is still off`() {
        switchedOn(true, withKey = null)
        assertFalse(IftttStore.wanted(context))
        assertFalse(IftttStore.fireNow(context, Ifttt.Event.ALARM))
        assertTrue(sent.isEmpty())
        // And on with something that is not a key.
        switchedOn(true, withKey = "https://maker.ifttt.com/use/abcdefgh")
        assertFalse(IftttStore.wanted(context))
        assertFalse(IftttStore.fireNow(context, Ifttt.Event.ALARM))
        assertTrue("a pasted URL went out as a key", sent.isEmpty())
    }

    /** On, with a key, one event goes once and goes to IFTTT. */
    @Test
    fun `on, an event goes once and goes where it should`() {
        assertTrue(IftttStore.fireNow(context, Ifttt.Event.ALARM, "wake up", "07:00"))
        assertEquals(1, sent.size)
        val (url, body) = sent.single()
        assertEquals(Ifttt.url(key, Ifttt.Event.ALARM), url)
        val json = JSONObject(body)
        assertEquals("wake up", json.getString("value1"))
        assertEquals("07:00", json.getString("value2"))
    }

    /**
     * The same event cannot go twice in a breath, and the other four still
     * can.
     *
     * The guard is per event, not global: an alarm ringing and the house
     * being told it was dismissed a moment later are two different things
     * and both have to arrive.
     */
    @Test
    fun `the quiet gap is per event and not a gag on all of them`() {
        assertTrue(IftttStore.fireNow(context, Ifttt.Event.ALARM))
        assertFalse("it fired the same event twice", IftttStore.fireNow(context, Ifttt.Event.ALARM))
        assertEquals(1, sent.size)
        // A different event is a different thing.
        assertTrue(IftttStore.fireNow(context, Ifttt.Event.DISMISS))
        assertEquals(2, sent.size)
    }

    /**
     * The gap is written down before the request goes, not after.
     *
     * A send that throws must not leave the door open for an immediate
     * second one — which is exactly the shape a loop takes when the thing
     * doing the sending is a service that gets restarted.
     */
    @Test
    fun `a send that blows up does not leave the door open`() {
        IftttStore.post = IftttStore.Post { _, _ -> throw RuntimeException("the wire broke") }
        try {
            IftttStore.fireNow(context, Ifttt.Event.ALARM)
        } catch (e: RuntimeException) {
            // The real sender swallows this; the fake one is being rude on
            // purpose. What matters is what happened to the record.
        }
        IftttStore.post = IftttStore.Post { url, body ->
            sent += url to body
            true
        }
        assertFalse(
            "a failed send let the next one straight through",
            IftttStore.fireNow(context, Ifttt.Event.ALARM)
        )
        assertTrue(sent.isEmpty())
    }

    /** A key being changed clears the record, so the first event is not eaten. */
    @Test
    fun `changing the key does not swallow the next event`() {
        assertTrue(IftttStore.fireNow(context, Ifttt.Event.ALARM))
        IftttStore.forget(context)
        assertTrue("the first event after a new key was eaten",
            IftttStore.fireNow(context, Ifttt.Event.ALARM))
        assertEquals(2, sent.size)
    }

    /**
     * The lead time is minutes, and it is clamped.
     *
     * Nought is meaningful — tell the house at the moment it rings and
     * nothing before — and two hours is as far as a sunrise scene is worth
     * ramping. Anything outside that came from somewhere that was not the
     * slider.
     */
    @Test
    fun `the lead time is minutes, and within reason`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals("the default is not half an hour", 30, IftttStore.lead(context))
        prefs.edit().putInt(Prefs.IFTTT_LEAD, 45).commit()
        assertEquals(45, IftttStore.lead(context))
        prefs.edit().putInt(Prefs.IFTTT_LEAD, -10).commit()
        assertEquals(0, IftttStore.lead(context))
        prefs.edit().putInt(Prefs.IFTTT_LEAD, 9000).commit()
        assertEquals(120, IftttStore.lead(context))
    }

    /**
     * And the webhook key is never written into a backup.
     *
     * A backup is a plain file in a folder somebody chose. A key in it is
     * a key anybody who can read that file can fire the house with — and
     * unlike every other setting in there, it is a credential rather than
     * a preference.
     */
    @Test
    fun `the key is not in the backup`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Prefs.IFTTT_KEY, key)
            .putBoolean(Prefs.IFTTT, true)
            .putBoolean(Prefs.SHOW_DATE, true)
            .commit()
        val file = Backup.export(context)
        assertFalse("the webhook key is in the backup file", file.contains(key))
        val entries = JSONObject(file).getJSONObject("entries")
        assertFalse(entries.has(Prefs.IFTTT_KEY))
        // The switch itself does survive, so a restored phone says it is
        // on and has no key — which the settings row is written to explain
        // rather than leave as a silent gap.
        assertTrue(entries.has(Prefs.IFTTT))
        assertTrue("an ordinary setting stopped being backed up", entries.has(Prefs.SHOW_DATE))
    }
}

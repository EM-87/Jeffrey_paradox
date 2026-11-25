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
            .commit()
        IftttStore.storeKey(context, withKey)
        IftttStore.forget(context)
    }

    private val shared: android.content.SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    /** The key's own file, read directly rather than through the store. */
    private val ownFile: android.content.SharedPreferences
        get() = context.getSharedPreferences(IftttStore.FILE, android.content.Context.MODE_PRIVATE)

    /** A key where every build before this one kept it. */
    private fun keyInTheSharedFile() {
        IftttStore.storeKey(context, null)
        shared.edit().putString(Prefs.IFTTT_KEY, key).commit()
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
        IftttStore.storeKey(context, key)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
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

    /** Nor is one still in the shared file, not yet moved out of it. */
    @Test
    fun `a key not yet moved is not in the backup either`() {
        keyInTheSharedFile()
        assertFalse("the webhook key is in the backup file", Backup.export(context).contains(key))
    }

    // ------------------------------------------- the key's own file

    /**
     * A key typed in before it had a file of its own is moved into it.
     *
     * The shared file is the one Android's backup takes whole, so a key
     * left in it is a key in the Google account and on the next phone —
     * see BackupRulesTest for the file the rules leave out.
     */
    @Test
    fun `a key from before is moved out of the shared file`() {
        keyInTheSharedFile()
        IftttStore.moveKeyOut(context)
        assertFalse("the key is still in the backed-up file", shared.contains(Prefs.IFTTT_KEY))
        assertEquals(key, ownFile.getString(Prefs.IFTTT_KEY, null))
        assertTrue("a moved key stopped working", IftttStore.fireNow(context, Ifttt.Event.ALARM))
        assertTrue(sent.single().first.contains(key))
    }

    /** Reading it moves it, so no path that uses the key can use it from there. */
    @Test
    fun `reading the key moves it`() {
        keyInTheSharedFile()
        assertTrue(IftttStore.wanted(context))
        assertFalse(shared.contains(Prefs.IFTTT_KEY))
    }

    /**
     * And so does the new build arriving, which is what catches the key the
     * app never reads: switch off, key still saved, nothing ever asking for
     * it — and the shared file backed up every night with it inside.
     */
    @Test
    fun `installing the update moves a key nothing reads`() {
        switchedOn(false, withKey = null)
        keyInTheSharedFile()
        assertFalse(IftttStore.wanted(context))
        assertTrue("the switch being off stopped reading it, which is the point",
            shared.contains(Prefs.IFTTT_KEY))

        RearmReceiver().onReceive(
            context, android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED)
        )
        assertFalse("the update left the key in the backed-up file",
            shared.contains(Prefs.IFTTT_KEY))
        assertEquals(key, IftttStore.storedKey(context))
    }

    /** Moved once is moved: a second pass finds nothing and changes nothing. */
    @Test
    fun `moving it twice is the same as once`() {
        keyInTheSharedFile()
        IftttStore.moveKeyOut(context)
        IftttStore.moveKeyOut(context)
        assertEquals(key, IftttStore.storedKey(context))
        assertFalse(shared.contains(Prefs.IFTTT_KEY))
    }

    /**
     * A restore on the phone the key was typed into keeps it.
     *
     * The restore empties the shared file and fills it from the backup —
     * which used to take the key with it, because that is where it was. It
     * is not there now, so the one phone that already had it still does.
     */
    @Test
    fun `restoring the app's backup on the same phone keeps the key`() {
        val file = Backup.export(context)
        org.junit.Assert.assertNotNull(Backup.import(context, file))
        assertEquals(key, IftttStore.storedKey(context))
        assertFalse(shared.contains(Prefs.IFTTT_KEY))
    }

    /** The settings row, built for real, as the screen builds it. */
    private fun keyRow(): androidx.preference.EditTextPreference {
        val controller = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val fragment = SettingsActivity.VeryAdvancedSettingsFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment).commitNow()
        return fragment.findPreference(Prefs.IFTTT_KEY)!!
    }

    /**
     * The row shows the key from its own file, and one typed in goes there.
     *
     * Left alone the row reads and writes the shared file like every other
     * row on the screen — which would quietly put the key straight back
     * where the backup takes it.
     */
    @Test
    fun `the settings row reads and writes the key's own file`() {
        val row = keyRow()
        assertEquals(key, row.text)
        assertTrue(row.summary.toString().startsWith(
            context.getString(R.string.pref_ifttt_key_set, "").trim()))

        val typed = "Zq7wPm2LxN8vR4tY6sK3"
        // What the row's dialog does with the text when OK is pressed.
        if (row.callChangeListener(typed)) row.text = typed
        assertEquals(typed, ownFile.getString(Prefs.IFTTT_KEY, null))
        assertFalse("the row wrote the key into the backed-up file",
            shared.contains(Prefs.IFTTT_KEY))

        if (row.callChangeListener("")) row.text = ""
        assertTrue("the row did not say the key had gone",
            row.summary.toString().startsWith(
                context.getString(R.string.pref_ifttt_key_none, "").trim()))
    }

    /** Opening the screen on a key nobody has moved yet shows it, and moves it. */
    @Test
    fun `the settings row finds a key from before`() {
        keyInTheSharedFile()
        assertEquals(key, keyRow().text)
        assertFalse(shared.contains(Prefs.IFTTT_KEY))
        assertEquals(key, ownFile.getString(Prefs.IFTTT_KEY, null))
    }
}

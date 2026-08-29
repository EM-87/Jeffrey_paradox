package com.em87.weirdclock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules that keep a clock from doing something silly to a house.
 *
 * This is the first thing in the app whose failures happen somewhere the
 * owner cannot see them: a mistyped key is a request that quietly does
 * nothing, and a loop is somebody's bedroom lights going on and off at
 * four in the morning. So the tests here are almost all about refusing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IftttTest {

    /**
     * A key is checked before it is ever put in a URL.
     *
     * Not authentication — that is IFTTT's job. This is the check that
     * stops a pasted URL, a stray newline or half a sentence from being
     * pushed into the *path* of a request, which is how a webhook key
     * turns into an open redirect to somewhere nobody chose.
     */
    @Test
    fun `only something shaped like a key is ever sent`() {
        assertTrue(Ifttt.usable("bX9-kQ2_7fLmNp4rS8tV1w"))
        assertTrue("a key with padding round it", Ifttt.usable("  bX9kQ27fLmNp4  "))
        assertFalse("nothing", Ifttt.usable(null))
        assertFalse("nothing", Ifttt.usable(""))
        assertFalse("too short to be one", Ifttt.usable("abc"))
        // The ones that matter: anything that could change where the
        // request goes, or split it in two.
        assertFalse("a whole URL", Ifttt.usable("https://maker.ifttt.com/use/abcdefgh"))
        assertFalse("a path escape", Ifttt.usable("abcdefgh/../../somewhere"))
        assertFalse("a query", Ifttt.usable("abcdefgh?to=elsewhere"))
        assertFalse("a newline", Ifttt.usable("abcdefgh\nHost: elsewhere"))
        assertFalse("a space", Ifttt.usable("abcdefgh ijklmnop"))
        assertFalse("a sentence", Ifttt.usable("my key is abcdefgh"))
    }

    /** And the address it goes to is this file's own, start to finish. */
    @Test
    fun `every event goes to IFTTT and nowhere else`() {
        val key = "bX9kQ27fLmNp4rS8tV1w"
        for (event in Ifttt.Event.entries) {
            val url = Ifttt.url(key, event)
            assertTrue(url, url.startsWith("https://maker.ifttt.com/trigger/"))
            assertTrue(url, url.endsWith("/with/key/$key"))
            assertTrue("the event name is not in it", url.contains(event.event))
        }
        // Five events, five different names, all of them plain.
        val names = Ifttt.Event.entries.map { it.event }
        assertEquals(5, names.toSet().size)
        for (name in names) {
            assertTrue(name, name.all { it.isLetterOrDigit() || it == '_' })
        }
        // A key arrives trimmed, so a copied-and-pasted one with a newline
        // on the end does not produce a request with a newline in its path.
        assertEquals(Ifttt.url(key, Ifttt.Event.ALARM), Ifttt.url("  $key\n", Ifttt.Event.ALARM))
    }

    /**
     * An alarm's label is whatever somebody typed, and it goes out in a
     * body that survives it.
     *
     * A quotation mark in a label, pasted into a hand-built JSON string,
     * ends the field and hands somebody else's server a malformed body.
     * Written with a JSON writer, it is a quotation mark.
     */
    @Test
    fun `a label with a quote in it does not break the message`() {
        val awkward = "\"wake up\", he said\\ — {ok}\n"
        val body = Ifttt.body(awkward, "07:00", "30")
        val back = JSONObject(body)
        assertEquals(awkward, back.getString("value1"))
        assertEquals("07:00", back.getString("value2"))
        assertEquals("30", back.getString("value3"))
        // And a message with nothing to say carries nothing rather than
        // three empty strings.
        assertEquals("{}", Ifttt.body())
        assertFalse(JSONObject(Ifttt.body(value2 = "x")).has("value1"))
    }

    /**
     * The time goes out as a machine reads it, never as the face shows it.
     *
     * Whatever the clock is wearing — twelve hours, Roman numerals, a
     * calculator's nine — the house gets 07:05.
     */
    @Test
    fun `the time is sent for a machine to read`() {
        assertEquals("07:05", Ifttt.clockOf(7, 5))
        assertEquals("19:05", Ifttt.clockOf(19, 5))
        assertEquals("00:00", Ifttt.clockOf(0, 0))
        assertEquals("23:59", Ifttt.clockOf(23, 59))
    }

    /**
     * The same event cannot go twice in a breath.
     *
     * The guard, and the reason it exists: everything that fires one of
     * these lives inside a service, services get restarted, and a restart
     * that re-fires is a loop. The failure is not an exception in a log,
     * it is a bedroom flashing at four in the morning.
     */
    @Test
    fun `the same event cannot be sent twice in a breath`() {
        val now = 1_700_000_000_000L
        assertTrue("the first one was refused", Ifttt.mayFire(0L, now))
        assertFalse("it fired again immediately", Ifttt.mayFire(now, now))
        assertFalse(Ifttt.mayFire(now, now + Ifttt.QUIET_MS - 1))
        assertTrue(Ifttt.mayFire(now, now + Ifttt.QUIET_MS))
        // And a clock wound backwards does not lock the house out for
        // however long it was wound back by.
        assertTrue("a clock set back jammed it", Ifttt.mayFire(now, now - 60_000L))
    }
}

package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * A week of backups nobody had to remember to make.
 *
 * There was already a way to write the whole preference store to a file and
 * read it back, and it is the right file — but it only exists on the days
 * somebody thinks about backups, which is the day after they needed one.
 * So the app keeps its own: one a day into a folder handed over once, seven
 * days deep.
 *
 * What is worth pinning is the housekeeping. A backup that writes fifty
 * copies of a Tuesday pushes out the week you wanted; one that tidies up
 * around itself deletes somebody's photographs; and one that restores a
 * file it only half understands loses the very alarms it was opened for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RestorePointTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AlarmStore.forget()
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ------------------------------------------------- when one is written

    /** Never having made one is always a reason to make one. */
    @Test
    fun `the first restore point is due at once`() {
        assertTrue(Backup.dueFor(lastMs = 0L, nowMs = at(2026, 8, 19)))
    }

    /** One a day, and not one an hour. */
    @Test
    fun `a second one on the same day is not due`() {
        assertFalse(
            "a day of fiddling with alarms would fill the folder",
            Backup.dueFor(at(2026, 8, 19, hour = 9), at(2026, 8, 19, hour = 23))
        )
        assertTrue(
            "and the next day is",
            Backup.dueFor(at(2026, 8, 19, hour = 23), at(2026, 8, 20, hour = 1))
        )
    }

    /**
     * A clock corrected backwards does not stop the backups.
     *
     * "Later than the last one plus a day" would leave a phone whose clock
     * had jumped forward and back with no backup until it caught up again,
     * and the whole point is that it happens without anybody watching.
     */
    @Test
    fun `a different day either way is a new day`() {
        assertTrue(
            Backup.dueFor(at(2026, 8, 19), at(2026, 8, 18))
        )
    }

    // ------------------------------------------------- what they are called

    /** The name carries the day, so a folder can be read without opening it. */
    @Test
    fun `a restore point is named for the day it was written`() {
        val name = Backup.nameFor(at(2026, 8, 19))
        assertTrue("'$name' says nothing about when it is from", "2026-08-19" in name)
        assertTrue("and is not obviously ours", name.startsWith(Backup.FILE_STEM))
        assertEquals(
            "the day cannot be read back out of the name",
            CivilDays.epochDay(2026, 8, 19), Backup.savedOn(name)
        )
    }

    /** And anything else in the folder is not ours. */
    @Test
    fun `somebody else's file is not mistaken for a restore point`() {
        for (name in listOf(
            "holiday.jpg",
            "weird-clock-backup.json",
            "weird-clock-2026-8-19.json",
            "weird-clock-2026-08-19.json.bak",
            "notes-2026-08-19.json"
        )) {
            assertNull("'$name' was taken for one of ours", Backup.savedOn(name))
        }
    }

    // ------------------------------------------------- what gets thrown away

    /** A week is kept and the oldest go. */
    @Test
    fun `only the oldest are pruned, and only down to a week`() {
        val names = (1..10).map { Backup.nameFor(at(2026, 8, it)) }
        val gone = Backup.prune(names, keep = 7)
        assertEquals(3, gone.size)
        assertEquals(
            "the wrong three went",
            listOf(1, 2, 3).map { Backup.nameFor(at(2026, 8, it)) }.toSet(),
            gone.toSet()
        )
    }

    /** With a week or less there is nothing to throw away. */
    @Test
    fun `a folder inside its allowance loses nothing`() {
        val names = (1..7).map { Backup.nameFor(at(2026, 8, it)) }
        assertEquals(emptyList<String>(), Backup.prune(names, keep = 7))
    }

    /**
     * And nothing that is not ours is ever in the answer.
     *
     * This is the one that matters. The folder is the user's — Documents,
     * most likely — and a backup feature that counts every file in it and
     * deletes the surplus is a backup feature that deletes a wedding
     * photograph.
     */
    @Test
    fun `nothing that is not ours is ever deleted`() {
        val theirs = listOf("holiday.jpg", "taxes.pdf", "notes.txt", "a.json", "b.json")
        val ours = (1..10).map { Backup.nameFor(at(2026, 8, it)) }
        val gone = Backup.prune(theirs + ours, keep = 7)
        for (name in theirs) {
            assertFalse("'$name' was going to be deleted", name in gone)
        }
        assertEquals("and the pruning stopped working", 3, gone.size)
    }

    // ------------------------------------------------- a file from the future

    /** What this build writes, it can read. */
    @Test
    fun `a file this version wrote is not from the future`() {
        assertFalse(Backup.tooNew(Backup.export(context)))
    }

    /**
     * A file from a later version is refused rather than half-read.
     *
     * Half a restore is worse than none: the parts this build does not
     * recognise would be dropped silently, and what somebody opened the
     * file for is exactly the part a later version added.
     */
    @Test
    fun `a file from a later version is refused`() {
        val fromTheFuture = org.json.JSONObject(Backup.export(context))
            .put("version", Backup.VERSION + 1)
            .toString()
        assertTrue(Backup.tooNew(fromTheFuture))
        assertNull(
            "a file this build cannot promise to restore was restored anyway",
            Backup.import(context, fromTheFuture)
        )
    }

    /** A file from before the version was written down still restores. */
    @Test
    fun `a file from the first version still comes back`() {
        val old = org.json.JSONObject(Backup.export(context))
            .put("version", 1)
            .toString()
        assertFalse(Backup.tooNew(old))
        assertTrue(
            "an old backup stopped being readable",
            Backup.import(context, old) != null
        )
    }

    /**
     * Where the backups go is not itself backed up.
     *
     * A folder handed over on one phone is a folder another phone has no
     * permission for, so restoring it would leave the second phone
     * believing it had somewhere to write and quietly failing every day.
     */
    @Test
    fun `the folder and the last backup time are not in the file`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Prefs.BACKUP_FOLDER, "content://tree/primary%3ADocuments")
            .putLong(Prefs.BACKUP_AT, 1_700_000_000_000L)
            .commit()
        val json = Backup.export(context)
        assertFalse("the folder travelled with the backup", Prefs.BACKUP_FOLDER in json)
        assertFalse("and so did the day it was written", Prefs.BACKUP_AT in json)
    }

    /** With no folder chosen, nothing is written and nothing complains. */
    @Test
    fun `no folder means no restore point and no fuss`() {
        assertFalse(Backup.autoSave(context))
    }
}

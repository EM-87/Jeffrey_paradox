package com.em87.weirdclock

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Android's own backup leaves out the webhook key, and nothing else.
 *
 * Both halves fail silently. The rules go by file name and the file name
 * lives in code: rename [IftttStore.FILE] without the two rule files and
 * the key is back in every backup, with nothing compiling differently and
 * nothing on the phone looking different. And a single include rule,
 * added one day for some other reason, turns either file into a list of
 * the only things backed up — which loses every alarm on a new phone just
 * as quietly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRulesTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** The key's file as the rules have to spell it. */
    private val file = IftttStore.FILE + ".xml"

    /** Every rule in a rules file, as "section rule domain path". */
    private fun rules(xml: Int): List<String> {
        val parser = context.resources.getXml(xml)
        val found = mutableListOf<String>()
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "include", "exclude" -> found += listOf(
                    section,
                    parser.name,
                    parser.getAttributeValue(null, "domain"),
                    parser.getAttributeValue(null, "path")
                ).joinToString(" ")
                else -> section = parser.name
            }
        }
        return found
    }

    /** Up to Android 11: the key's file, and only that, is left out. */
    @Test
    fun `the older rules leave out the key's file and nothing else`() {
        assertEquals(
            listOf("full-backup-content exclude sharedpref $file"),
            rules(R.xml.backup_rules)
        )
    }

    /**
     * From Android 12: the same, in both sections.
     *
     * They are separate, and a missing one means everything goes. The
     * transfer to a new phone is the one that matters most here, because
     * on some phones it happens even with backup switched off.
     */
    @Test
    fun `the newer rules leave it out of the Google backup and the new phone`() {
        assertEquals(
            listOf(
                "cloud-backup exclude sharedpref $file",
                "device-transfer exclude sharedpref $file"
            ),
            rules(R.xml.data_extraction_rules)
        )
    }

    /**
     * And the manifest points at both, with backup still on.
     *
     * Read off disk, as NotesTest reads the sources: Gradle runs unit tests
     * in the module directory. Backup off would be the other way to keep
     * the key out, and would take every alarm with it.
     */
    @Test
    fun `the manifest uses both sets of rules`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"")
        )
    }

    /** The key really is written to the file the rules name. */
    @Test
    fun `the key is written where the rules look`() {
        val key = "bX9kQ27fLmNp4rS8tV1w"
        IftttStore.storeKey(context, key)
        assertEquals(
            key,
            context.getSharedPreferences(IftttStore.FILE, Context.MODE_PRIVATE)
                .getString(Prefs.IFTTT_KEY, null)
        )
        assertFalse(
            PreferenceManager.getDefaultSharedPreferences(context).contains(Prefs.IFTTT_KEY)
        )
    }
}

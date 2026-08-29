package com.em87.weirdclock

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every settings row's title, measured to see whether it fits.
 *
 * A title that runs off the end of its row is replaced by Android with an
 * ellipsis, silently, at layout time. Nothing throws, nothing logs, and
 * the row still works — it just stops saying what it is. "Tell the house
 * (IFT…" is a setting nobody can look up, and the only reason the last two
 * were ever found is that the screenshot harness started working and
 * somebody looked at the picture.
 *
 * Looking at pictures does not scale to four faces times three screens, so
 * this asks the layout instead. [android.text.Layout.getEllipsisCount] is
 * the exact number Android itself computed when it decided to cut the
 * text, which makes this a measurement rather than an opinion about how
 * long a title ought to be.
 *
 * Titles only. Summaries wrap to as many lines as they need and are meant
 * to — the paragraph under a switch is prose, and prose that fits on one
 * line is a different thing from a name that does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RowFitTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    /** Every TextView under [root], in the order they were laid out. */
    private fun texts(root: View, into: MutableList<TextView> = ArrayList()): List<TextView> {
        if (root is TextView) into += root
        if (root is ViewGroup) for (i in 0 until root.childCount) texts(root.getChildAt(i), into)
        return into
    }

    /**
     * The whole screen, laid out at the width of a phone and tall enough
     * for all of it.
     *
     * The same measure-then-draw [ScreenshotTest] does, and for the same
     * reason: a RecyclerView only ever lays out what fits, so a screen
     * measured at screen height hides the bottom of itself from this as
     * well as from the camera.
     */
    private fun laidOut(fragment: androidx.preference.PreferenceFragmentCompat): View {
        val list = fragment.listView
        fun count(group: androidx.preference.PreferenceGroup): Int {
            var n = group.preferenceCount
            for (i in 0 until group.preferenceCount) {
                val row = group.getPreference(i)
                if (row is androidx.preference.PreferenceGroup) n += count(row)
            }
            return n
        }
        val tall = 260 * count(fragment.preferenceScreen) + 400
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 1080, tall)
        return list
    }

    /** The titles this screen cut short, each with how many characters went. */
    private fun cutShort(list: View): List<String> {
        val bad = ArrayList<String>()
        for (view in texts(list)) {
            if (view.id != android.R.id.title) continue
            val layout = view.layout ?: continue
            var lost = 0
            for (line in 0 until layout.lineCount) lost += layout.getEllipsisCount(line)
            if (lost > 0) bad += "\"${view.text}\" loses $lost characters"
        }
        return bad
    }

    /**
     * The summaries this screen ran out of room for, and where they stop.
     *
     * A different failure from a cut title, and a quieter one. A summary
     * past its row's line limit is not ellipsised — the text is laid out
     * in full and the *view* is simply too short for it, so the last line
     * is sliced through horizontally with a scroll bar beside it that a
     * settings list will not let anybody drag.
     *
     * Which is why this compares the lines against the limit and not, as
     * the first version of it did, the last line's end offset against the
     * length of the text. Those two are equal even when the row is cut:
     * the layout is complete, the box around it is not. That version
     * passed on a screen whose picture plainly showed a sentence stopping
     * in the middle of itself.
     */
    private fun cutOff(list: View): List<String> {
        val bad = ArrayList<String>()
        for (view in texts(list)) {
            if (view.id != android.R.id.summary) continue
            val layout = view.layout ?: continue
            val limit = view.maxLines
            if (limit <= 0 || layout.lineCount <= limit) continue
            bad += "\"${view.text.take(30)}…\" needs ${layout.lineCount} lines and has $limit"
        }
        return bad
    }

    /** Both faults on the screen that opens first. */
    private fun openSettings(): List<String> =
        Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
            c.setup()
            val list = laidOut(
                c.get().supportFragmentManager.fragments.first()
                    as androidx.preference.PreferenceFragmentCompat
            )
            cutShort(list) + cutOff(list)
        }

    /** And on either of the two behind it. */
    private fun open(fragment: androidx.preference.PreferenceFragmentCompat): List<String> =
        Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
            c.setup()
            c.get().supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, fragment).commitNow()
            val list = laidOut(fragment)
            cutShort(list) + cutOff(list)
        }

    /**
     * All three screens, on all four faces, with every title whole.
     *
     * One test rather than twelve because the failure is the same failure
     * everywhere and the useful output is the whole list of it — twelve
     * tests would report the first face and stop.
     */
    @Test
    fun `no settings row is cut short on any face`() {
        val bad = ArrayList<String>()
        for (face in Face.entries) {
            prefs.edit().clear()
                .putBoolean(Prefs.OVERLAY_ASKED, true)
                .putBoolean(Prefs.FACE_ASKED, true)
                .putString(Prefs.FACE, face.key)
                .putBoolean(Prefs.BELLS, true)
                .putBoolean(Prefs.NIGHT_DIM, true)
                .commit()
            for (line in openSettings()) bad += "${face.key} / root: $line"
            for (line in open(SettingsActivity.AdvancedSettingsFragment())) {
                bad += "${face.key} / advanced: $line"
            }
            for (line in open(SettingsActivity.VeryAdvancedSettingsFragment())) {
                bad += "${face.key} / very advanced: $line"
            }
        }
        assertTrue(
            "settings rows the screen did not have room for:\n  " + bad.joinToString("\n  "),
            bad.isEmpty()
        )
    }
}

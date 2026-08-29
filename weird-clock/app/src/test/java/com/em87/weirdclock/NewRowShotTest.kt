package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * One picture of each new settings row, in each state it has.
 *
 * A whole settings page at once says whether a page reads. It does not
 * say whether *this row* does — and a row is the unit somebody actually
 * meets: a title, a paragraph under it, and a switch that is either doing
 * something or waiting on something two screens away. Those two states
 * look different on purpose and nobody had ever looked at either of them
 * on its own.
 *
 * Cut out of the laid-out list rather than mocked up, so what is in the
 * picture is the row Android drew.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewRowShotTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** Lays a screen out tall enough for all of it and hands back the list. */
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
        val tall = 300 * count(fragment.preferenceScreen) + 600
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 1080, tall)
        return list
    }

    /** The row whose title reads [title], as its own picture. */
    private fun shootRow(list: View, title: String, name: String): Boolean {
        fun find(view: View): View? {
            if (view is android.view.ViewGroup) {
                val label = view.findViewById<android.widget.TextView>(android.R.id.title)
                if (label?.text?.toString() == title) return view
                for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        val row = find(list) ?: return false
        if (row.width <= 0 || row.height <= 0) return false
        val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFFFFFFF.toInt())
        // Through a layer at the row's own alpha, and this is not a
        // detail. View.draw does not apply a view's alpha — a parent does,
        // when it composites the child — so drawing a row straight into a
        // bitmap produces a picture of a row that is *never* faded. The
        // first run of this test showed the waiting rows and the working
        // ones as pixel-identical and I nearly reported the fading as
        // broken; it was the camera.
        val layer = canvas.saveLayerAlpha(
            0f, 0f, row.width.toFloat(), row.height.toFloat(),
            (row.alpha * 255).toInt()
        )
        row.draw(canvas)
        canvas.restoreToCount(layer)
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return true
    }

    private fun onRoot(name: String, title: String): Boolean =
        Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
            c.setup()
            val fragment = c.get().supportFragmentManager.fragments.first()
                as androidx.preference.PreferenceFragmentCompat
            shootRow(laidOut(fragment), title, name)
        }

    private fun onAdvanced(name: String, title: String): Boolean =
        Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
            c.setup()
            val fragment = SettingsActivity.AdvancedSettingsFragment()
            c.get().supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, fragment).commitNow()
            shootRow(laidOut(fragment), title, name)
        }

    private fun face(which: Face) {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, which.key)
            .commit()
    }

    /** The diary row, in the three states it can be in. */
    @Test
    fun `the calendar row, off, on and refused`() {
        val title = context.getString(R.string.pref_agenda_title)
        face(Face.ANALOG)
        assertTrue(onRoot("row-agenda-off", title))
        prefs.edit().putBoolean(Prefs.AGENDA, true).commit()
        // Without the permission, which is the state somebody lands in the
        // moment they flick the switch and then say no.
        assertTrue(onRoot("row-agenda-denied", title))
        (context.applicationContext as android.app.Application).let {
            org.robolectric.Shadows.shadowOf(it)
                .grantPermissions(android.Manifest.permission.READ_CALENDAR)
        }
        assertTrue(onRoot("row-agenda-on", title))
    }

    /** The satellite clouds, waiting on the weather and then not. */
    @Test
    fun `the cloud row, faded and lit`() {
        val title = context.getString(R.string.pref_hemisphere_clouds_title)
        face(Face.HEMISPHERE)
        assertTrue(onAdvanced("row-clouds-waiting", title))
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        assertTrue(onAdvanced("row-clouds-lit", title))
    }

    /** The pedestal's two instruments, the same way. */
    @Test
    fun `the glass row, faded and lit`() {
        val title = context.getString(R.string.pref_sundial_glass_title)
        face(Face.SUNDIAL)
        assertTrue(onAdvanced("row-glass-waiting", title))
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        assertTrue(onAdvanced("row-glass-lit", title))
    }

    /** And the calendar under the plate, in all three answers. */
    @Test
    fun `the plate's calendar row, in each of its answers`() {
        val title = context.getString(R.string.pref_sundial_calendar_title)
        face(Face.SUNDIAL)
        assertTrue(onAdvanced("row-plate-calendar-waiting", title))
        for (reckoning in Sundial.Reckoning.entries) {
            prefs.edit()
                .putBoolean(Prefs.SHOW_DATE, true)
                .putString(Prefs.SUNDIAL_CALENDAR, reckoning.key)
                .commit()
            assertTrue(onAdvanced("row-plate-calendar-${reckoning.key}", title))
        }
    }

    /**
     * And the heading each face puts over the rows that outlive the dial.
     *
     * A heading is a row too, and the one nobody photographs.
     */
    @Test
    fun `the heading over the borrowed rows, on every face`() {
        for (which in Face.entries) {
            face(which)
            Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
                c.setup()
                val fragment = SettingsActivity.AdvancedSettingsFragment()
                c.get().supportFragmentManager.beginTransaction()
                    .replace(R.id.settings_container, fragment).commitNow()
                val list = laidOut(fragment)
                // The category's own view is the group, which contains
                // every row under it — so what gets photographed by title
                // is a whole screenful. The heading is a string; print it.
                val digits = fragment.findPreference<androidx.preference.Preference>(
                    FaceOptions.CAT_DIGITS
                )?.title?.toString()
                val dial = fragment.findPreference<androidx.preference.Preference>(
                    FaceOptions.CAT_DIAL
                )?.title?.toString()
                println("HEADINGS ${which.key}: cat_digits=[$digits] cat_dial=[$dial]")
                for (key in listOf(FaceOptions.CAT_DIGITS, FaceOptions.CAT_DIAL)) {
                    val group = fragment.findPreference<androidx.preference.PreferenceGroup>(key)
                        ?: continue
                    val rows = (0 until group.preferenceCount)
                        .map { group.getPreference(it).title }
                    println("  ${which.key} / ${group.title}: $rows")
                }
                assertTrue(list.height > 0)
            }
        }
    }

    /**
     * The dial with somebody's diary on it, and the same dial without.
     *
     * The wedges are the half of this feature that can only be judged by
     * looking: whether an appointment reads as an appointment or as a
     * smear over three hours of a clock face.
     */
    @Test
    fun `the dial wearing the phone's diary`() {
        val real = AgendaStore.reader
        try {
            // Counted forward from now rather than pinned to nine
            // o'clock, and the first version of this picture is why. The
            // dial only draws what is still to come — the same rule the
            // alarms and reminders have always followed — so a diary set
            // at fixed hours showed two wedges out of four in the
            // afternoon and none of them by teatime, which is correct
            // behaviour and a useless photograph.
            fun soon(minutes: Int): Long = TimeKeeper.nowMs() + minutes * 60_000L
            val diary = listOf(
                Agenda.Event(1L, "the dentist", soon(45), soon(105), false),
                Agenda.Event(2L, "stand-up", soon(150), soon(155), false),
                Agenda.Event(3L, "lunch", soon(210), soon(300), false),
                Agenda.Event(4L, "the flight", soon(360), soon(510), false)
            )
            for ((name, on) in listOf("dial-diary-on" to true, "dial-diary-off" to false)) {
                prefs.edit().clear()
                    .putBoolean(Prefs.OVERLAY_ASKED, true)
                    .putBoolean(Prefs.FACE_ASKED, true)
                    .putString(Prefs.FACE, Face.ANALOG.key)
                    .putBoolean(Prefs.ALARM_MARKERS, true)
                    .putBoolean(Prefs.AGENDA, on)
                    .commit()
                (context.applicationContext as android.app.Application).let {
                    org.robolectric.Shadows.shadowOf(it)
                        .grantPermissions(android.Manifest.permission.READ_CALENDAR)
                }
                AgendaStore.reader = AgendaStore.Reader { _, from, to ->
                    Agenda.between(diary, from, to)
                }
                Robolectric.buildActivity(MainActivity::class.java).use { c ->
                    c.setup()
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    val screen = c.get().findViewById<View>(android.R.id.content)
                    val bitmap = Bitmap.createBitmap(
                        screen.width, screen.height, Bitmap.Config.ARGB_8888
                    )
                    screen.draw(Canvas(bitmap))
                    File(outDir, "$name.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    assertTrue(screen.width > 0)
                }
            }
        } finally {
            AgendaStore.reader = real
        }
    }
}

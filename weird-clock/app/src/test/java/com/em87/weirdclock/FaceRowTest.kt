package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The face row: one tap changes the clock.
 *
 * Bound the way the settings screen binds it and tapped the way a finger
 * would, through the cell's click. What is checked is what the list row
 * used to do in two taps — the preference written under the same key,
 * the change listener told first, the chosen cell filled — and a picture
 * of the row is kept to look at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaceRowTest {

    private val context: android.content.Context
        get() = androidx.appcompat.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_WeirdClock
        )

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** The row, on a screen, bound to a freshly inflated view. */
    private fun bound(): Triple<FaceRowPreference, PreferenceViewHolder, View> {
        val manager = PreferenceManager(context)
        val screen = manager.createPreferenceScreen(context)
        val pref = FaceRowPreference(context).apply {
            key = Prefs.FACE
            title = context.getString(R.string.pref_face_title)
            summary = context.getString(R.string.pref_face_summary)
        }
        screen.addPreference(pref)
        val view = LayoutInflater.from(context).inflate(R.layout.preference_face_row, null)
        val holder = PreferenceViewHolder.createInstanceForTests(view)
        pref.onBindViewHolder(holder)
        return Triple(pref, holder, view)
    }

    private fun shoot(view: View, name: String) {
        val w = (411 * context.resources.displayMetrics.density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, w, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(w, view.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF121318.toInt())
        view.draw(canvas)
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `one tap on a face is the clock changed`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putString(Prefs.FACE, Face.ANALOG.key).commit()
        val (pref, holder, view) = bound()
        var told: Any? = null
        pref.setOnPreferenceChangeListener { _, value -> told = value; true }
        val analog = pref.cellForTest(holder, Face.ANALOG)
        val digital = pref.cellForTest(holder, Face.DIGITAL)
        assertNotNull("no cell for the dial", analog)
        assertNotNull("no cell for the digits", digital)
        assertTrue("the dial is not shown as worn", analog!!.isSelected)
        assertFalse("the digits are shown as worn", digital!!.isSelected)
        shoot(view, "settings-face-row-analog")

        digital.performClick()
        assertEquals("the preference was not written", Face.DIGITAL.key,
            PreferenceManager.getDefaultSharedPreferences(context).getString(Prefs.FACE, null))
        assertEquals("the change listener was not told", Face.DIGITAL.key, told)
        // Bound again, as the screen does when a preference changes.
        val again = LayoutInflater.from(context).inflate(R.layout.preference_face_row, null)
        val holder2 = PreferenceViewHolder.createInstanceForTests(again)
        pref.onBindViewHolder(holder2)
        assertTrue("the digits are not shown as worn", pref.cellForTest(holder2, Face.DIGITAL)!!.isSelected)
        assertFalse("the dial is still shown as worn", pref.cellForTest(holder2, Face.ANALOG)!!.isSelected)
        shoot(again, "settings-face-row-digital")
    }

    /** A listener that says no keeps the clock as it was. */
    @Test
    fun `a refused change is not written`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putString(Prefs.FACE, Face.ANALOG.key).commit()
        val (pref, holder, _) = bound()
        pref.setOnPreferenceChangeListener { _, _ -> false }
        pref.cellForTest(holder, Face.HEMISPHERE)!!.performClick()
        assertEquals(Face.ANALOG.key,
            PreferenceManager.getDefaultSharedPreferences(context).getString(Prefs.FACE, null))
    }

    /** Every face has a cell, named as the list named it, for a finger and a screen reader. */
    @Test
    fun `every face is on the row with its name`() {
        val (pref, holder, _) = bound()
        val names = context.resources.getStringArray(R.array.face_entries)
        val values = context.resources.getStringArray(R.array.face_values)
        for (face in Face.entries) {
            val cell = pref.cellForTest(holder, face)
            assertNotNull("no cell for $face", cell)
            assertEquals(names[values.indexOf(face.key)], cell!!.contentDescription.toString())
        }
    }
}

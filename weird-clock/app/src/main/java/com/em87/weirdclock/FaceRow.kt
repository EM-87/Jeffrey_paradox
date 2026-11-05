package com.em87.weirdclock

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * The first question in the settings, asked as a row of faces to tap.
 *
 * It was a list row: a tap to open the list and a tap to choose, for the
 * one setting everybody changes and the one that decides what the rest of
 * the screens are about. Now the four faces stand in a row under the
 * title, each a disc with its own glyph and its name, the one the clock
 * is wearing filled in; one tap changes the clock.
 *
 * The same key as the list row it replaces, [Prefs.FACE], with the same
 * values, so a phone updating keeps the face it had and everything that
 * reads the face reads it as before. The names and the order come from
 * the arrays the list used, so the two cannot disagree.
 */
class FaceRowPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_face_row
    }

    /** The faces in the order the list showed them, each with its glyph. */
    private val faces: List<Pair<Face, Int>> = listOf(
        Face.ANALOG to R.drawable.ic_clock_face,
        Face.DIGITAL to R.drawable.ic_face_digital,
        Face.SUNDIAL to R.drawable.ic_face_sundial,
        Face.HEMISPHERE to R.drawable.ic_face_globe
    )

    private fun current(): Face =
        Face.entries.firstOrNull { it.key == getPersistedString(Face.ANALOG.key) } ?: Face.ANALOG

    /** The face's name, as the list named it. */
    private fun nameOf(face: Face): CharSequence {
        val values = context.resources.getStringArray(R.array.face_values)
        val names = context.resources.getStringArray(R.array.face_entries)
        val at = values.indexOf(face.key)
        return if (at in names.indices) names[at] else face.key
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Rows are recycled, so the cells are built afresh every time.
        val row = holder.findViewById(R.id.face_row) as? LinearLayout ?: return
        row.removeAllViews()
        val wearing = current()
        val density = context.resources.displayMetrics.density
        val disc = (56 * density).toInt()
        val on = colour(com.google.android.material.R.attr.colorOnPrimaryContainer)
        val off = colour(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val named = colour(com.google.android.material.R.attr.colorPrimary)
        for ((face, glyph) in faces) {
            val chosen = face == wearing
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                contentDescription = nameOf(face)
                isSelected = chosen
                tag = face
                setOnClickListener { choose(face) }
            }
            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(disc, disc)
                setBackgroundResource(R.drawable.face_cell)
                isSelected = chosen
                setImageResource(glyph)
                imageTintList = ColorStateList.valueOf(if (chosen) on else off)
                val pad = (14 * density).toInt()
                setPadding(pad, pad, pad, pad)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = TextView(context).apply {
                text = nameOf(face)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                setTextColor(if (chosen) named else off)
                val top = (6 * density).toInt()
                setPadding(0, top, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            cell.addView(icon)
            cell.addView(label)
            row.addView(cell)
        }
    }

    /** A tap on a face: the same path a pick from the list took. */
    private fun choose(face: Face) {
        if (face == current()) return
        if (!callChangeListener(face.key)) return
        persistString(face.key)
        notifyChanged()
    }

    private fun colour(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    /** For the tests: the cell of a face, to tap. */
    internal fun cellForTest(holder: PreferenceViewHolder, face: Face): View? =
        (holder.findViewById(R.id.face_row) as? LinearLayout)?.findViewWithTag(face)
}

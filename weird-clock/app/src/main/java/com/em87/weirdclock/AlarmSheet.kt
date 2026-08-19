package com.em87.weirdclock

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/** An alarm sheet parked while its duration is wound on the dial. */
data class AlarmDurationDraft(
    val target: Alarm,
    val draft: Alarm,
    val isNew: Boolean
)

/**
 * The alarm editor on C1, Google-Clock style: a bottom sheet with the time,
 * the weekday strip, the options as plain rows, and delete/save in opposite
 * corners. Deleting asks first, and so does walking away from unsaved work.
 *
 * Last of the four out of MainActivity, and the most tangled — its trip to
 * the dial carries a repetition index and, for an alarm that only exists so
 * the dial has something to write into, a provisional identity that must be
 * taken back if the winding is cancelled. Both now travel as arguments
 * rather than as fields the sheet reaches over and sets.
 *
 * Like the reminder sheet it owns nothing: it edits a copy, and every write
 * goes back through the host.
 */
class AlarmSheet(
    private val host: Activity,
    private val cards: AlarmCards,
    private val alarms: List<Alarm>,
    private val callbacks: Callbacks
) {

    /** Everything this sheet cannot do for itself. */
    interface Callbacks {
        fun animateSheet(
            sheet: com.google.android.material.bottomsheet.BottomSheetDialog,
            content: View
        )

        /** Copies the draft back onto the real alarm, adding it if new. */
        fun commitDraft(target: Alarm, draft: Alarm, isNew: Boolean)

        fun deleteAlarm(alarm: Alarm)

        fun persistAlarms()

        fun notificationPermissionIfNeeded()

        /**
         * Off to C0 to wind one of the alarm's times, and back here after.
         * [isNew] marks an alarm born only so the dial had a target:
         * cancelling the winding takes it away again, but the sheet still
         * comes back, still creating it.
         */
        fun windTime(target: Alarm, draft: Alarm, isNew: Boolean, timeIndex: Int)

        /** Off to C0 to wind how long the thing lasts. */
        fun windDuration(parked: AlarmDurationDraft)

        /** The SAF round trip for a user's own audio file. */
        fun pickAudioFile(target: Alarm, onPicked: () -> Unit)

        /**
         * The phone's own ringtones, alarms and notification sounds.
         *
         * A separate picker from the one that finds a file, because these
         * are not files anybody can browse to: they are whatever this
         * phone happens to ship with, and the system is the only thing
         * that can list them.
         */
        fun pickSystemSound(target: Alarm, onPicked: () -> Unit)
    }

    fun show(alarm: Alarm, seed: Alarm? = null) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(host)
        val view = host.layoutInflater.inflate(R.layout.sheet_alarm_edit, null)
        sheet.setContentView(view)
        callbacks.animateSheet(sheet, view)

        // The sheet edits a copy; nothing is committed until Save. A seed is
        // that same copy handed back after a trip to the dial, so nothing
        // typed before the trip is lost. The copy() of a data class shares
        // the same mutable list, so the repetition list is copied by hand —
        // otherwise adding one silently edited the real alarm, unrefreshed.
        val draft = seed ?: alarm.copy(extraTimes = alarm.extraTimes.toMutableList())
        // What the alarm looked like on opening, to tell an abandoned edit
        // from an untouched sheet.
        val original = alarm.copy(extraTimes = alarm.extraTimes.toMutableList())
        val isNew = seed?.let { !alarms.any { a -> a.id == it.id } } ?: !alarms.contains(alarm)

        val dialsRow = view.findViewById<LinearLayout>(R.id.sheet_dials)
        val nameValue = view.findViewById<TextView>(R.id.sheet_name_value)
        val notesValue = view.findViewById<TextView>(R.id.sheet_notes_value)
        val soundValue = view.findViewById<TextView>(R.id.sheet_sound_value)
        val snoozeValue = view.findViewById<TextView>(R.id.sheet_snooze_value)
        val vibrateSwitch = view.findViewById<SwitchCompat>(R.id.sheet_vibrate)
        val flashSwitch = view.findViewById<SwitchCompat>(R.id.sheet_flash)
        val snoozeLimitRow = view.findViewById<View>(R.id.sheet_row_snooze_limit)
        val snoozeLimitValue = view.findViewById<TextView>(R.id.sheet_snooze_limit_value)
        val daysRow = view.findViewById<LinearLayout>(R.id.sheet_days)

        // One little analog face per time: tapped it goes to the big dial to
        // be wound, held down it offers to drop that repetition. They take
        // whatever room is left over between the sheet's margins and the +
        // button, up to a size that already looks generous.
        lateinit var refreshRef: () -> Unit
        var builtTimes: List<Pair<Int, Int>>? = null
        fun rebuildDials() {
            // The faces wind themselves into place when they are born, which
            // is a nice thing to watch once, on opening — and a nuisance on
            // every tap. They are only born again when the times change.
            val wanted = (0 until draft.timeCount()).map { draft.timeAt(it) }
            if (wanted == builtTimes) return
            builtTimes = wanted
            dialsRow.removeAllViews()
            val density = host.resources.displayMetrics.density
            val count = draft.timeCount()
            val room = host.resources.displayMetrics.widthPixels -
                (40 + 56) * density - 8 * density * count
            val size = minOf(76 * density, room / count).toInt()
            for (index in 0 until draft.timeCount()) {
                val (h, m) = draft.timeAt(index)
                // The editor's dials keep the sky: there is no icon row here
                // to say whether this time is a morning or an evening one.
                val dial = cards.miniDial(h, m, sky = true)
                dial.setOnClickListener {
                    // The alarm has to exist for the dial to write a time
                    // into it — but if it was born just now and the dial is
                    // cancelled, it goes away again.
                    callbacks.commitDraft(alarm, draft, isNew)
                    sheet.dismiss()
                    callbacks.windTime(alarm, draft, isNew, index)
                }
                dial.setOnLongClickListener {
                    dial.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (index == 0) {
                        // The first face is the alarm itself: it can be moved
                        // but not dropped, or there would be no alarm left.
                        Toast.makeText(
                            host, R.string.alarm_remove_time_first, Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        androidx.appcompat.app.AlertDialog.Builder(host)
                            .setTitle(R.string.alarm_remove_time_title)
                            .setMessage(
                                host.getString(
                                    R.string.alarm_remove_time_message,
                                    String.format(Locale.US, "%02d:%02d", h, m)
                                )
                            )
                            .setPositiveButton(R.string.alarm_delete) { _, _ ->
                                draft.extraTimes.removeAt(index - 1)
                                refreshRef()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    true
                }
                dialsRow.addView(
                    dial,
                    LinearLayout.LayoutParams(size, size).apply { marginEnd = 8 }
                )
            }
        }

        fun refresh() {
            rebuildDials()
            nameValue.text = draft.label.ifBlank { host.getString(R.string.alarm_label_hint) }
            notesValue.text =
                draft.notes.ifBlank { host.getString(R.string.reminder_notes_none) }
            soundValue.text = cards.soundLabel(draft.sound)
            snoozeValue.text = if (draft.snoozeMinutes > 0) {
                host.getString(R.string.alarm_snooze_min, draft.snoozeMinutes)
            } else {
                host.getString(R.string.alarm_snooze_off)
            }
            // A limit on something that never happens is not a setting, it
            // is a row in the way. It appears with the snooze and goes with
            // it.
            snoozeLimitRow.visibility =
                if (draft.snoozeMinutes > 0) View.VISIBLE else View.GONE
            snoozeLimitValue.text = cards.snoozeLimitLabel(draft.snoozeLimit)
            view.findViewById<TextView>(R.id.sheet_gentle_value).text =
                cards.gentleLabel(draft.gentleWakeSeconds)
            view.findViewById<TextView>(R.id.sheet_mission_value).text =
                cards.missionLabel(draft.mission, draft.missionLevel)
            view.findViewById<TextView>(R.id.sheet_duration_value).text =
                if (draft.durationMinutes <= 0) {
                    host.getString(R.string.reminder_duration_none)
                } else {
                    host.getString(R.string.reminder_duration_min, draft.durationMinutes)
                }
        }
        // The dials are built before refresh() exists, and a dropped
        // repetition has to redraw them all.
        refreshRef = { refresh() }

        // Weekday toggles.
        val dayButtons = mutableListOf<TextView>()
        val order = cards.weekdayOrder()
        val letters = cards.weekdayLetters()
        fun paintDays() {
            for ((i, button) in dayButtons.withIndex()) {
                val on = (draft.daysMask and (1 shl (order[i] - 1))) != 0
                button.setTextColor(
                    ContextCompat.getColor(
                        host, if (on) R.color.accent else R.color.text_secondary
                    )
                )
                button.alpha = if (on) 1f else 0.5f
            }
        }
        for ((i, letter) in letters.withIndex()) {
            val button = TextView(host).apply {
                text = letter
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 20)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setBackgroundResource(
                    android.R.drawable.list_selector_background
                )
                setOnClickListener {
                    // Turning the last day off leaves no days, which is a
                    // one-shot — not, as it used to be, all seven lighting
                    // straight back up because an empty mask was read as
                    // "every day".
                    draft.daysMask = draft.daysMask xor (1 shl (order[i] - 1))
                    paintDays()
                }
            }
            dayButtons.add(button)
            daysRow.addView(button)
        }
        paintDays()

        view.findViewById<Button>(R.id.sheet_weekdays).setOnClickListener {
            draft.daysMask = Alarm.WEEKDAYS
            paintDays()
        }
        view.findViewById<Button>(R.id.sheet_weekends).setOnClickListener {
            draft.daysMask = Alarm.WEEKENDS
            paintDays()
        }
        view.findViewById<Button>(R.id.sheet_everyday).setOnClickListener {
            draft.daysMask = Alarm.ALL_DAYS
            paintDays()
        }

        view.findViewById<Button>(R.id.sheet_add_time).setOnClickListener {
            if (draft.timeCount() >= Alarm.MAX_TIMES) {
                Toast.makeText(host, R.string.alarm_times_full, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // A new repetition starts four hours after the last one — the
            // usual shape of a three-times-a-day thing.
            val last = draft.allTimes().last()
            val next = (last.first * 60 + last.second + 240) % (24 * 60)
            draft.extraTimes.add(next)
            refresh()
        }

        view.findViewById<View>(R.id.sheet_row_name).setOnClickListener {
            val input = EditText(host).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(draft.label)
                setSelection(draft.label.length)
            }
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(R.string.alarm_label_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    draft.label = input.text.toString().trim()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.sheet_row_notes).setOnClickListener {
            val input = EditText(host).apply {
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                maxLines = 6
                setText(draft.notes)
                setSelection(draft.notes.length)
            }
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(R.string.reminder_notes)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    draft.notes = input.text.toString().trim()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.sheet_row_sound).setOnClickListener {
            cards.pickSound(draft.sound, allowCustom = true) { chosen ->
                draft.sound = chosen
                when (chosen) {
                    Prefs.ALARM_SOUND_CUSTOM -> callbacks.pickAudioFile(draft) { refresh() }
                    Prefs.ALARM_SOUND_SYSTEM -> callbacks.pickSystemSound(draft) { refresh() }
                }
                refresh()
            }
        }

        snoozeLimitRow.setOnClickListener {
            // The stored value joins the list if it is not already in it.
            // An alarm carrying a limit from the version this was one
            // number for the whole app can hold something the list does not
            // offer — and a picker whose "current" is not among its choices
            // silently ticks the first one instead, so opening it and
            // pressing nothing changed the setting.
            val choices = (intArrayOf(0, 1, 2, 3, 5, 10).toSortedSet() +
                draft.snoozeLimit).toList()
            cards.pickFromList(
                R.string.pref_snooze_limit_title,
                choices.map { cards.snoozeLimitLabel(it) },
                choices.indexOf(draft.snoozeLimit)
            ) { which ->
                draft.snoozeLimit = choices[which]
                refresh()
            }
        }

        view.findViewById<View>(R.id.sheet_row_snooze).setOnClickListener {
            val choices = intArrayOf(0, 5, 10, 15)
            cards.pickFromList(
                R.string.alarm_snooze,
                choices.map {
                    if (it == 0) host.getString(R.string.alarm_snooze_off)
                    else host.getString(R.string.alarm_snooze_min, it)
                },
                choices.indexOf(draft.snoozeMinutes)
            ) { which ->
                draft.snoozeMinutes = choices[which]
                refresh()
            }
        }

        // Both of these belong to one alarm and not to the app. A sunrise
        // is for the alarm that wakes you; a mission is for the one you keep
        // turning off and going back to sleep.
        view.findViewById<View>(R.id.sheet_gentle_row).setOnClickListener {
            val choices = GentleWake.CHOICES
            cards.pickFromList(
                R.string.alarm_gentle,
                choices.map { cards.gentleLabel(it) },
                choices.indexOf(draft.gentleWakeSeconds).coerceAtLeast(0)
            ) { which ->
                draft.gentleWakeSeconds = choices[which]
                refresh()
            }
        }

        view.findViewById<View>(R.id.sheet_mission_row).setOnClickListener {
            // One list rather than a row for the kind and another for the
            // rung: the rung means nothing without the sums, and a row that
            // is meaningless most of the time is a row that gets read every
            // time and skipped every time.
            val choices = AlarmCards.MISSION_CHOICES
            val current = choices.indexOfFirst {
                it.first == Mission.required(draft.mission) &&
                    (it.first != Mission.MATHS || it.second == Mission.level(draft.missionLevel))
            }
            cards.pickFromList(
                R.string.alarm_mission,
                choices.map { cards.missionLabel(it.first, it.second) },
                current.coerceAtLeast(0)
            ) { which ->
                draft.mission = choices[which].first
                draft.missionLevel = choices[which].second
                refresh()
            }
        }

        view.findViewById<View>(R.id.sheet_row_duration).setOnClickListener {
            // How long a thing lasts is a duration, so it gets wound on the
            // dial, exactly as the calendar's reminders do it.
            sheet.dismiss()
            callbacks.windDuration(AlarmDurationDraft(alarm, draft, isNew))
        }

        vibrateSwitch.isChecked = draft.vibrate
        vibrateSwitch.setOnCheckedChangeListener { _, checked -> draft.vibrate = checked }
        flashSwitch.isChecked = draft.flash
        flashSwitch.setOnCheckedChangeListener { _, checked -> draft.flash = checked }

        view.findViewById<Button>(R.id.sheet_delete).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(R.string.alarm_delete)
                .setMessage(R.string.alarm_delete_confirm)
                .setPositiveButton(R.string.alarm_delete) { _, _ ->
                    callbacks.deleteAlarm(alarm)
                    sheet.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<Button>(R.id.sheet_save).setOnClickListener {
            callbacks.commitDraft(alarm, draft, isNew)
            callbacks.notificationPermissionIfNeeded()
            sheet.dismiss()
        }

        // Back, or a tap outside, cancels rather than dismisses — which is
        // exactly the "walking away" path, and the only one worth asking
        // about. Save, Delete and the trips to the dial all dismiss instead.
        sheet.setOnCancelListener {
            if (draft != original) {
                androidx.appcompat.app.AlertDialog.Builder(host)
                    .setTitle(R.string.alarm_unsaved_title)
                    .setMessage(R.string.alarm_unsaved_message)
                    .setPositiveButton(R.string.alarm_save) { _, _ ->
                        callbacks.commitDraft(alarm, draft, isNew)
                        callbacks.notificationPermissionIfNeeded()
                    }
                    .setNegativeButton(R.string.alarm_discard, null)
                    .show()
            }
        }

        refresh()
        sheet.show()
    }
}

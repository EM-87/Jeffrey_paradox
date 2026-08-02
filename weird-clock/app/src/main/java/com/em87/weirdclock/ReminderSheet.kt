package com.em87.weirdclock

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import java.util.Locale

/** A reminder sheet parked while its duration is wound on the dial. */
data class ReminderDraft(
    val existing: Reminder?,
    val year: Int,
    val month: Int,
    val day: Int,
    val label: String,
    val hour: Int,
    val minute: Int,
    val duration: Int,
    val rings: Boolean,
    val sound: String,
    val lead: Int,
    val repeat: String,
    val notes: String = ""
)

/**
 * The reminder editor on C-1: the same bottom-sheet shape as the alarm one,
 * with the time still set the app's way — by winding the dial.
 *
 * Third piece out of MainActivity, and the one it took three goes to make
 * possible. It could not leave while it reached for fourteen of the
 * activity's methods, and it did so almost entirely for one thing: the trip
 * to C0 to wind a time or a length. Once that trip became one value handed
 * through one call, and the helpers it shares with the alarm cards had moved
 * to AlarmCards, what was left is the six-method contract below.
 *
 * It owns nothing: every write goes back through the host.
 */
class ReminderSheet(
    private val host: Activity,
    private val cards: AlarmCards,
    private val callbacks: Callbacks
) {

    /** Everything this sheet cannot do for itself. */
    interface Callbacks {
        fun animateSheet(
            sheet: com.google.android.material.bottomsheet.BottomSheetDialog,
            content: View
        )

        /** Write it down, keeping [existing]'s identity when it is an edit. */
        fun commitReminder(existing: Reminder?, draft: ReminderDraft)

        fun deleteReminder(reminder: Reminder)

        /** Off to C0 to wind the time; the sheet comes back either way. */
        fun windTime(draft: ReminderDraft)

        /** Off to C0 to wind how long it lasts. Likewise. */
        fun windDuration(draft: ReminderDraft)

        /** A day already spent can be read and deleted, and nothing else. */
        fun isPastDay(year: Int, month: Int, day: Int): Boolean
    }

    /**
     * The reminder editor: the same bottom-sheet shape as the alarm one,
     * with the time still set the app's way — by winding the dial.
     */
    fun show(
        existing: Reminder?,
        year: Int,
        month: Int,
        day: Int,
        seed: ReminderDraft? = null
    ) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(host)
        val view = host.layoutInflater.inflate(R.layout.sheet_reminder_edit, null)
        sheet.setContentView(view)
        callbacks.animateSheet(sheet, view)

        var label = seed?.label ?: existing?.label.orEmpty()
        var hour = seed?.hour ?: existing?.hour ?: 9
        var minute = seed?.minute ?: existing?.minute ?: 0
        var duration = seed?.duration ?: existing?.durationMinutes ?: 0
        var sound = seed?.sound ?: existing?.sound ?: Prefs.ALARM_SOUND_BELLS
        var lead = seed?.lead ?: existing?.leadMinutes ?: 0
        var repeat = seed?.repeat ?: existing?.repeat ?: Reminder.REPEAT_NEVER
        var notes = seed?.notes ?: existing?.notes.orEmpty()
        // The date can be moved, so it is not the fixed frame it was.
        var onYear = year
        var onMonth = month
        var onDay = day
        // Nothing can be scheduled into a day that is already spent: it may
        // be read and it may be deleted, and that is all.
        val spent = callbacks.isPastDay(year, month, day)

        val nameValue = view.findViewById<TextView>(R.id.rsheet_name_value)
        val timeValue = view.findViewById<TextView>(R.id.rsheet_time_value)
        val durationValue = view.findViewById<TextView>(R.id.rsheet_duration_value)
        val dateValue = view.findViewById<TextView>(R.id.rsheet_date)
        val repeatValue = view.findViewById<TextView>(R.id.rsheet_repeat_value)
        val notesValue = view.findViewById<TextView>(R.id.rsheet_notes_value)
        fun repeatLabel(mode: String): String = host.getString(
            when (mode) {
                Reminder.REPEAT_WEEKLY -> R.string.reminder_repeat_weekly
                Reminder.REPEAT_MONTHLY -> R.string.reminder_repeat_monthly
                Reminder.REPEAT_YEARLY -> R.string.reminder_repeat_yearly
                else -> R.string.reminder_repeat_never
            }
        )
        fun paintDate() {
            dateValue.text = String.format(Locale.US, "%02d/%02d/%04d", onDay, onMonth, onYear)
            repeatValue.text = repeatLabel(repeat)
        }
        paintDate()

        fun refresh() {
            nameValue.text = label.ifBlank { host.getString(R.string.reminder_hint) }
            timeValue.text = String.format(Locale.US, "%02d:%02d", hour, minute)
            durationValue.text = if (duration <= 0) {
                host.getString(R.string.reminder_duration_none)
            } else {
                host.getString(R.string.reminder_duration_min, duration)
            }
            notesValue.text = notes.ifBlank { host.getString(R.string.reminder_notes_none) }
        }

        val repeatModes = listOf(
            Reminder.REPEAT_NEVER, Reminder.REPEAT_WEEKLY,
            Reminder.REPEAT_MONTHLY, Reminder.REPEAT_YEARLY
        )
        view.findViewById<View>(R.id.rsheet_row_repeat).setOnClickListener {
            cards.pickFromList(
                R.string.reminder_repeat,
                repeatModes.map { repeatLabel(it) },
                repeatModes.indexOf(repeat)
            ) { which ->
                repeat = repeatModes[which]
                paintDate()
            }
        }

        // The date at the top is a button: a reminder can change its day
        // without being deleted and made again.
        dateValue.setOnClickListener {
            val picker = android.app.DatePickerDialog(
                host,
                { _, y, m, d ->
                    onYear = y
                    onMonth = m + 1
                    onDay = d
                    paintDate()
                },
                onYear, onMonth - 1, onDay
            )
            picker.setTitle(R.string.reminder_move)
            // Nothing is scheduled backwards, here no more than anywhere.
            picker.datePicker.minDate = System.currentTimeMillis() - 86_400_000L
            picker.show()
        }

        view.findViewById<View>(R.id.rsheet_row_name).setOnClickListener {
            val input = EditText(host).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(label)
                setSelection(label.length)
            }
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(R.string.reminder_name)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    label = input.text.toString().trim()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.rsheet_row_notes).setOnClickListener {
            val input = EditText(host).apply {
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                // Room to write in without the dialog growing without end.
                minLines = 3
                maxLines = 6
                setText(notes)
                setSelection(notes.length)
            }
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(R.string.reminder_notes)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    notes = input.text.toString().trim()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        val alarmSwitch = view.findViewById<SwitchCompat>(R.id.rsheet_alarm)
        val soundRow = view.findViewById<View>(R.id.rsheet_row_sound)
        val snoozeRow = view.findViewById<View>(R.id.rsheet_row_snooze)
        val soundValue = view.findViewById<TextView>(R.id.rsheet_sound_value)
        val snoozeValue = view.findViewById<TextView>(R.id.rsheet_snooze_value)

        // A reminder is a mark on the calendar first; ringing is opt-in, and
        // its settings only exist once you have opted in.
        fun paintAlarmRows() {
            val on = alarmSwitch.isChecked
            soundRow.visibility = if (on) View.VISIBLE else View.GONE
            snoozeRow.visibility = if (on) View.VISIBLE else View.GONE
            soundValue.text = cards.soundLabel(sound)
            snoozeValue.text = cards.leadLabel(lead)
        }
        alarmSwitch.isChecked = seed?.rings ?: existing?.rings ?: false
        alarmSwitch.setOnCheckedChangeListener { _, _ -> paintAlarmRows() }
        soundRow.setOnClickListener {
            // No SAF round trip from here; the file picker belongs to alarms
            // proper.
            cards.pickSound(sound, allowCustom = false) { chosen ->
                sound = chosen
                paintAlarmRows()
            }
        }
        snoozeRow.setOnClickListener {
            // Warning ahead of the thing, not a nag after it.
            cards.pickFromList(
                R.string.reminder_lead,
                cards.leadChoicesList.map { cards.leadLabel(it) },
                cards.leadChoicesList.indexOf(lead)
            ) { which ->
                lead = cards.leadChoicesList[which]
                paintAlarmRows()
            }
        }
        paintAlarmRows()

        // Nothing can be scheduled into a day that is already spent: it may
        // be read and deleted, and that is all. (This block used to sit
        // inside the sound row's listener, so it only ever ran if you tapped
        // that row.)
        if (spent) {
            for (id in intArrayOf(
                R.id.rsheet_date, R.id.rsheet_row_name, R.id.rsheet_row_time,
                R.id.rsheet_row_duration, R.id.rsheet_row_repeat,
                R.id.rsheet_row_notes, R.id.rsheet_row_sound, R.id.rsheet_row_snooze
            )) {
                view.findViewById<View>(id).apply {
                    isEnabled = false
                    alpha = 0.45f
                }
            }
            alarmSwitch.isEnabled = false
            view.findViewById<Button>(R.id.rsheet_save).visibility = View.GONE
        }

        view.findViewById<View>(R.id.rsheet_row_duration).setOnClickListener {
            // How long a thing lasts is a duration, so it gets set the way
            // durations are set here: by winding the countdown dial.
            sheet.dismiss()
            callbacks.windDuration(
                ReminderDraft(
                    existing, onYear, onMonth, onDay, label, hour, minute, duration,
                    alarmSwitch.isChecked, sound, lead, repeat, notes
                )
            )
        }

        // The time row goes to the dial and comes back here. It used to be
        // a one-way trip that saved on the way out — and Save was wired to
        // the very same listener, so there was no way to finish a reminder
        // except through the dial, and no sheet left to return to. Nothing
        // is lifted out of the list on the way any more, because nothing is
        // written until Save.
        view.findViewById<View>(R.id.rsheet_row_time).setOnClickListener {
            sheet.dismiss()
            callbacks.windTime(
                ReminderDraft(
                    existing, onYear, onMonth, onDay, label, hour, minute,
                    duration, alarmSwitch.isChecked, sound, lead, repeat, notes
                )
            )
        }

        view.findViewById<Button>(R.id.rsheet_save).setOnClickListener {
            callbacks.commitReminder(
                existing,
                ReminderDraft(
                    existing, onYear, onMonth, onDay, label, hour, minute,
                    duration, alarmSwitch.isChecked, sound, lead, repeat, notes
                )
            )
            sheet.dismiss()
        }

        view.findViewById<Button>(R.id.rsheet_delete).apply {
            isEnabled = existing != null
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(host)
                    .setTitle(label.ifBlank { host.getString(R.string.reminder_untitled) })
                    .setMessage(R.string.reminder_delete_confirm)
                    .setPositiveButton(R.string.alarm_delete) { _, _ ->
                        existing?.let { callbacks.deleteReminder(it) }
                        sheet.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        refresh()
        sheet.show()
    }
}

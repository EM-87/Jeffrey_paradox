package com.em87.weirdclock

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Where the cycle is written down and read back.
 *
 * Two things, and the order matters: what it says right now, and one
 * button. Everything else — the history, correcting a date, saying how long
 * one lasted — is underneath, because on almost every day the only reason
 * anybody opens this is to answer one of two questions ("when is it due"
 * and "how late am I") or to press the button once.
 *
 * The wording is careful about one thing throughout: never sounding surer
 * than the arithmetic is. [Cycle.Forecast.learned] is false until there are
 * two periods to go on, and while it is false the sheet says so rather than
 * showing a confident date built out of a textbook twenty-eight.
 */
class CycleSheet(
    private val host: Activity,
    private val onChanged: () -> Unit
) {

    /** Today, as the engine counts days. */
    private fun today(): Int = Cycle.today(
        TimeKeeper.nowMs(),
        java.util.TimeZone.getDefault().getOffset(TimeKeeper.nowMs())
    )

    private fun periods(): List<Cycle.Period> = CycleStore.all(host).toList()

    private fun write(list: List<Cycle.Period>) {
        CycleStore.replace(host, list)
        onChanged()
    }

    fun show() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(host)
        val view = host.layoutInflater.inflate(R.layout.sheet_cycle, null)
        sheet.setContentView(view)

        val state = view.findViewById<TextView>(R.id.cycle_state)
        val detail = view.findViewById<TextView>(R.id.cycle_detail)
        val history = view.findViewById<TextView>(R.id.cycle_history)
        val startedButton = view.findViewById<Button>(R.id.cycle_started)

        fun refresh() {
            val now = today()
            val list = periods()
            state.text = headline(list, now)
            detail.text = detail(list, now)
            history.text = historyText(list)
            startedButton.setText(
                if (list.any { now in it.coveredDays() }) R.string.cycle_started_correct
                else R.string.cycle_started
            )
        }

        startedButton.setOnClickListener {
            write(Cycle.record(periods(), today()))
            refresh()
        }

        view.findViewById<View>(R.id.cycle_another_day).setOnClickListener {
            pickADay { day ->
                write(Cycle.record(periods(), day))
                refresh()
            }
        }

        view.findViewById<View>(R.id.cycle_row_length).setOnClickListener {
            val last = Cycle.starts(periods()).lastOrNull() ?: return@setOnClickListener
            val choices = (0..10).toList()
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(R.string.cycle_length_title)
                .setSingleChoiceItems(
                    choices.map { bleedLabel(it) }.toTypedArray(),
                    choices.indexOf(periods().last { it.start == last }.days).coerceAtLeast(0)
                ) { dialog, which ->
                    dialog.dismiss()
                    write(Cycle.setLength(periods(), last, choices[which]))
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.cycle_row_history).setOnClickListener { showHistory { refresh() } }

        refresh()
        sheet.show()
    }

    // ------------------------------------------------------- what it says

    /** The one line somebody came here to read. */
    internal fun headline(list: List<Cycle.Period>, now: Int): String {
        if (list.isEmpty()) return host.getString(R.string.cycle_nothing_yet)
        if (list.any { now in it.coveredDays() }) {
            return host.getString(R.string.cycle_now, Cycle.dayOf(list, now))
        }
        val f = Cycle.forecast(list) ?: return host.getString(R.string.cycle_nothing_yet)
        val late = Cycle.delay(list, now)
        return when {
            Cycle.late(list, now) -> host.resources.getQuantityString(
                R.plurals.cycle_late, late, late
            )
            now > f.expected -> host.getString(R.string.cycle_due_about_now)
            else -> host.resources.getQuantityString(
                R.plurals.cycle_in_days, f.expected - now, f.expected - now
            )
        }
    }

    /**
     * The line under it: the window, and how sure the arithmetic is.
     *
     * Two periods is the least that can teach anything, and until then this
     * says so outright rather than quoting a date. Somebody who has entered
     * one period and is shown "expected 14 March" will believe it, and
     * there is nothing behind it but the number twenty-eight.
     */
    internal fun detail(list: List<Cycle.Period>, now: Int): String {
        val f = Cycle.forecast(list) ?: return host.getString(R.string.cycle_detail_none)
        if (!f.learned) return host.getString(R.string.cycle_detail_guessing, Cycle.DEFAULT_LENGTH)
        return host.getString(
            R.string.cycle_detail_window,
            dayText(f.from), dayText(f.to), f.length
        )
    }

    /** The last few, and what they were worth. */
    internal fun historyText(list: List<Cycle.Period>): String {
        if (list.isEmpty()) return host.getString(R.string.cycle_history_none)
        val gaps = Cycle.gaps(list).takeLast(4)
        val recorded = host.resources.getQuantityString(
            R.plurals.cycle_history_count, list.size, list.size
        )
        if (gaps.isEmpty()) return recorded
        return "$recorded · " + gaps.joinToString(" · ") { "$it" }
    }

    private fun bleedLabel(days: Int): String =
        if (days <= 0) host.getString(R.string.cycle_length_unsaid)
        else host.resources.getQuantityString(R.plurals.cycle_length_days, days, days)

    /** A day as a short date, in the order the phone writes them. */
    internal fun dayText(day: Int): String {
        val (y, m, d) = Cycle.dateOf(day)
        val cal = java.util.Calendar.getInstance().apply { set(y, m - 1, d, 12, 0, 0) }
        return java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            .format(cal.time)
    }

    // ----------------------------------------------------------- the past

    /** A date picker seeded on today, for a period written down late. */
    private fun pickADay(onPicked: (Int) -> Unit) {
        val (y, m, d) = Cycle.dateOf(today())
        android.app.DatePickerDialog(
            host,
            { _, year, month, day -> onPicked(Cycle.epochDay(year, month + 1, day)) },
            y, m - 1, d
        ).apply {
            // Nothing in the future: a period that has not happened is not
            // a record, it is a prediction, and the app makes those itself.
            datePicker.maxDate = TimeKeeper.nowMs()
        }.show()
    }

    /** The whole record, with a way to take a wrong one out again. */
    private fun showHistory(onChanged: () -> Unit) {
        val list = periods().sortedByDescending { it.start }
        if (list.isEmpty()) return
        val labels = list.map { p ->
            val length = if (p.days > 0) " · " + bleedLabel(p.days) else ""
            dayText(p.start) + length
        }
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(R.string.cycle_history_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = list[which]
                androidx.appcompat.app.AlertDialog.Builder(host)
                    .setTitle(dayText(chosen.start))
                    .setMessage(R.string.cycle_forget_confirm)
                    .setPositiveButton(R.string.cycle_forget) { _, _ ->
                        write(Cycle.forget(periods(), chosen.start))
                        onChanged()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

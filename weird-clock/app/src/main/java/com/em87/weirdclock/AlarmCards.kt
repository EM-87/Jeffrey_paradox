package com.em87.weirdclock

import android.content.Context
import android.content.SharedPreferences
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar
import java.util.Locale

/**
 * The alarm micro-cards on C1, and the little faces they are made of.
 *
 * Everything here is presentation: given the list of alarms and what the big
 * dial is currently wearing, it draws the cards. It owns no alarm data and
 * saves nothing — a switch flicked or a card tapped is handed straight back
 * to the host, which is the only thing that writes to the store.
 *
 * It has since become the home for everything the cards and the two sheets
 * share — the little faces, the weekday strip, the sound and lead-time
 * names, and the two pickers — which is what let the reminder sheet leave.
 *
 * Second piece lifted out of MainActivity, and the reason for the order:
 * the alarm and reminder sheets cannot move cleanly while the helpers they
 * share with the cards — the mini dials, the weekday strip, the sound names
 * — still live in the activity. They live here now.
 *
 * A move, not a rewrite: the layout arithmetic, the dial mosaic and the
 * wording are unchanged.
 */
class AlarmCards(
    private val host: Context,
    private val prefs: SharedPreferences,
    private val alarms: List<Alarm>,
    private val dialTheme: () -> ClockTheme,
    private val hoursOnDial: () -> Int,
    private val dialShape: () -> ClockView.DialShape,
    private val onToggled: (Alarm, Boolean) -> Unit,
    private val onOpen: (Alarm) -> Unit
) {

    val adapter = Adapter()

    /**
     * How early a reminder speaks up. Minutes for the same-day nudge, days
     * for the ones you need to prepare for — a birthday is no use to anyone
     * fifteen minutes early.
     */
    fun leadLabel(minutes: Int): String = when {
        minutes <= 0 -> host.getString(R.string.reminder_lead_none)
        minutes < 60 -> host.getString(R.string.reminder_lead_min, minutes)
        minutes == 60 -> host.getString(R.string.reminder_lead_hour)
        minutes < 1440 -> host.getString(R.string.reminder_lead_hours, minutes / 60)
        minutes == 1440 -> host.getString(R.string.reminder_lead_day)
        minutes < 10080 -> host.getString(R.string.reminder_lead_days, minutes / 1440)
        minutes == 10080 -> host.getString(R.string.reminder_lead_week)
        else -> host.getString(R.string.reminder_lead_weeks, minutes / 10080)
    }

    /** Warn-me offsets, in minutes: the same-day nudges, then days out. */
    val leadChoicesList = listOf(0, 15, 30, 60, 1440, 4320, 10080)

    /** A plain single-choice list, the way a row of options should ask. */
    fun pickFromList(
        titleRes: Int,
        labels: List<String>,
        checked: Int,
        onPicked: (Int) -> Unit
    ) {
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                onPicked(which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The sounds on offer, shown as a list. Cycling through them one tap at a
     * time meant walking past "your own file", and every pass through it threw
     * you out into a file browser.
     */
    fun pickSound(current: String, allowCustom: Boolean, onPicked: (String) -> Unit) {
        val sounds = mutableListOf(
            Prefs.ALARM_SOUND_BELLS, Prefs.ALARM_SOUND_DIGITAL, Prefs.ALARM_SOUND_BABY
        )
        if (allowCustom) sounds.add(Prefs.ALARM_SOUND_CUSTOM)
        pickFromList(
            R.string.pref_bell_style_title,
            sounds.map { soundLabel(it) },
            sounds.indexOf(current)
        ) { which -> onPicked(sounds[which]) }
    }

    /** The names the sound picker and the cards both use. */
    fun soundLabel(sound: String): String = host.getString(
        when (sound) {
            Prefs.ALARM_SOUND_DIGITAL -> R.string.alarm_sound_digital
            Prefs.ALARM_SOUND_BABY -> R.string.alarm_sound_baby
            Prefs.ALARM_SOUND_CUSTOM -> R.string.alarm_sound_custom
            else -> R.string.alarm_sound_bells
        }
    )

    inner class AlarmHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dials: LinearLayout = view.findViewById(R.id.alarm_dials)
        val timeBox: View = view.findViewById(R.id.alarm_time_box)
        val next: TextView = view.findViewById(R.id.alarm_next)
        val time: TextView = view.findViewById(R.id.alarm_time)
        val extraTimes: TextView = view.findViewById(R.id.alarm_extra_times)
        val name: TextView = view.findViewById(R.id.alarm_name)
        val days: TextView = view.findViewById(R.id.alarm_days)
        val soundName: TextView = view.findViewById(R.id.alarm_sound_name)
        val snoozeMin: TextView = view.findViewById(R.id.alarm_snooze_min)
        val iconDayNight: ImageView = view.findViewById(R.id.icon_daynight)
        val iconSnooze: ImageView = view.findViewById(R.id.icon_snooze)
        val iconVibrate: ImageView = view.findViewById(R.id.icon_vibrate)
        val iconFlash: ImageView = view.findViewById(R.id.icon_flash)
        val iconCalendar: ImageView = view.findViewById(R.id.icon_calendar)
        val enabled: SwitchCompat = view.findViewById(R.id.alarm_enabled)
    }

    inner class Adapter : RecyclerView.Adapter<AlarmHolder>() {

        override fun getItemCount(): Int = alarms.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmHolder =
            AlarmHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_alarm, parent, false)
            )

        override fun onBindViewHolder(holder: AlarmHolder, position: Int) {
            val alarm = alarms[position]
            val times = alarm.allTimes()
            val analog = alarmsAreAnalog()

            holder.timeBox.visibility = if (analog) View.GONE else View.VISIBLE
            holder.dials.visibility = if (analog) View.VISIBLE else View.GONE
            // Faces wind themselves into place when they are born, so they are
            // only born when they would actually show something different —
            // otherwise every switch flicked anywhere in the list set every
            // clock on screen winding again.
            val signature = listOf(
                analog, times, dialShape(), hoursOnDial(), dialTheme()
            )
            if (holder.dials.tag != signature) {
                holder.dials.tag = signature
                holder.dials.removeAllViews()
                if (analog) fillDials(holder.dials, times)
            }

            holder.time.text =
                String.format(Locale.US, "%02d:%02d", times[0].first, times[0].second)
            // A concept that happens four times a day says so under its
            // first time, rather than pretending to be four alarms.
            holder.extraTimes.text = times.drop(1).joinToString("  ") {
                String.format(Locale.US, "%02d:%02d", it.first, it.second)
            }
            holder.extraTimes.visibility =
                if (times.size > 1) View.VISIBLE else View.GONE

            paintNextRing(holder, alarm)

            holder.name.text = alarm.label.ifBlank { host.getString(R.string.alarm_label_hint) }
            holder.name.alpha = if (alarm.label.isBlank()) 0.45f else 1f

            // The weekday strip: lit letters are the days it rings on. A
            // one-shot has no days to light, so it says what it is instead
            // of showing seven dim letters that mean nothing.
            val letters = weekdayLetters()
            val lit = ContextCompat.getColor(host, R.color.accent)
            val dim = ContextCompat.getColor(host, R.color.text_secondary)
            val strip = SpannableString(letters.joinToString(" "))
            var at = 0
            for ((i, letter) in letters.withIndex()) {
                val dayOfWeek = weekdayOrder()[i]
                val on = (alarm.daysMask and (1 shl (dayOfWeek - 1))) != 0
                strip.setSpan(
                    ForegroundColorSpan(if (on) lit else dim),
                    at, at + letter.length, 0
                )
                at += letter.length + 1
            }
            if (alarm.once) holder.days.setText(R.string.alarm_once)
            else holder.days.text = strip

            // Leading the row: whether this one goes off in the light or in
            // the dark, tinted the same as its dot on the dial. An alarm
            // that rings several times a day is judged by its first.
            val (fh, fm) = times[0]
            val dark = DayNight.isDarkAt(fh, fm)
            holder.iconDayNight.setImageResource(
                if (dark) R.drawable.ic_moon else R.drawable.ic_sun
            )
            holder.iconDayNight.setColorFilter(DayNight.markColor(dialTheme(), dark))
            holder.iconDayNight.contentDescription = host.getString(
                if (dark) R.string.a11y_night else R.string.a11y_day
            )

            // Icons appear only for what is actually switched on.
            holder.soundName.text = soundLabel(alarm.sound)
            val hasSnooze = alarm.snoozeMinutes > 0
            holder.iconSnooze.visibility = if (hasSnooze) View.VISIBLE else View.GONE
            holder.snoozeMin.visibility = if (hasSnooze) View.VISIBLE else View.GONE
            holder.snoozeMin.text =
                host.getString(R.string.reminder_duration_min, alarm.snoozeMinutes)
            holder.iconVibrate.visibility = if (alarm.vibrate) View.VISIBLE else View.GONE
            holder.iconFlash.visibility = if (alarm.flash) View.VISIBLE else View.GONE
            holder.iconCalendar.visibility =
                if (alarm.durationMinutes > 0) View.VISIBLE else View.GONE

            val alpha = if (alarm.enabled) 1f else 0.4f
            holder.time.alpha = alpha
            holder.dials.alpha = alpha
            holder.extraTimes.alpha = alpha
            holder.days.alpha = alpha
            holder.itemView.findViewById<View>(R.id.alarm_icons).alpha = alpha

            holder.enabled.setOnCheckedChangeListener(null)
            holder.enabled.isChecked = alarm.enabled
            holder.enabled.setOnCheckedChangeListener { _, checked ->
                onToggled(alarm, checked)
            }
            holder.itemView.setOnClickListener { onOpen(alarm) }
        }
    }

    /**
     * The faces on an alarm card. One time is one face; two are a pair of
     * equals; from three on, the alarm's own time leads and its repetitions
     * follow smaller — and four of them stack into a 2×2 block rather than
     * running off the side of the card.
     */
    fun fillDials(row: LinearLayout, times: List<Pair<Int, Int>>) {
        val density = host.resources.displayMetrics.density
        val gap = (4 * density).toInt()
        val lead = (46 * density).toInt()
        val small = (28 * density).toInt()

        fun dial(t: Pair<Int, Int>, size: Int, startGap: Boolean, topGap: Boolean) =
            row.addView(
                miniDial(t.first, t.second),
                LinearLayout.LayoutParams(size, size).apply {
                    if (startGap) marginStart = gap
                    if (topGap) topMargin = gap
                }
            )

        when (times.size) {
            1 -> dial(times[0], lead, false, false)
            2 -> {
                // A pair of equals: neither time is the lesser one.
                dial(times[0], lead, false, false)
                dial(times[1], lead, true, false)
            }
            3 -> {
                // The two repetitions stack instead of stretching the card.
                dial(times[0], lead, false, false)
                val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                for ((i, t) in times.drop(1).withIndex()) {
                    column.addView(
                        miniDial(t.first, t.second),
                        LinearLayout.LayoutParams(small, small).apply {
                            if (i > 0) topMargin = gap
                        }
                    )
                }
                row.addView(
                    column,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = gap }
                )
            }
            else -> {
                // All four in a square block, which takes barely more room
                // than the single leading face does on its own.
                val mosaic = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                for (line in 0..1) {
                    val strip = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    for (column in 0..1) {
                        val t = times[line * 2 + column]
                        strip.addView(
                            miniDial(t.first, t.second),
                            LinearLayout.LayoutParams(small, small).apply {
                                if (column > 0) marginStart = gap
                            }
                        )
                    }
                    mosaic.addView(
                        strip,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { if (line > 0) topMargin = gap }
                    )
                }
                row.addView(
                    mosaic,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
    }

    /**
     * Retimes the cards in place. Rebinding the whole list every minute
     * would be a lot of work to change eight characters, and would set
     * every face winding again.
     */
    fun retimeVisible(recycler: RecyclerView) {
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? AlarmHolder
                ?: continue
            val position = holder.bindingAdapterPosition
            if (position !in alarms.indices) continue
            paintNextRing(holder, alarms[position])
        }
    }

    /** The one line that tells you whether you set the alarm right. */
    private fun paintNextRing(holder: AlarmHolder, alarm: Alarm) {
        holder.next.visibility = if (alarm.enabled) View.VISIBLE else View.GONE
        if (!alarm.enabled) return
        holder.next.text = host.getString(
            R.string.alarm_rings_in,
            describeWait(AlarmScheduler.nextOccurrence(alarm) - System.currentTimeMillis())
        )
    }

    /** "2 d 3 h", "7 h 20 min", "45 min" — as coarse as the wait deserves. */
    private fun describeWait(ms: Long): String {
        val minutes = (ms / 60_000L).toInt()
        if (minutes < 1) return host.getString(R.string.alarm_rings_soon)
        val days = minutes / 1440
        val hours = minutes / 60
        return when {
            days > 0 -> host.getString(R.string.duration_d_h, days, hours % 24)
            hours > 0 -> host.getString(R.string.duration_h_m, hours, minutes % 60)
            else -> host.getString(R.string.duration_m, minutes)
        }
    }

    /** Whether alarms show their times on little faces rather than in digits. */
    fun alarmsAreAnalog(): Boolean =
        prefs.getString(Prefs.ALARM_STYLE, Prefs.ALARM_STYLE_ANALOG) != Prefs.ALARM_STYLE_DIGITAL

    /**
     * A small, still face showing one fixed time of day, wearing whatever
     * shape and hour count the big clock wears. Used both on the alarm cards
     * and in the editor.
     */
    fun miniDial(hour: Int, minute: Int): ClockView {
        val fixedMs = (hour * 3_600_000L) + (minute * 60_000L)
        return ClockView(host).apply {
            touchHandsEnabled = false
            pinchZoomEnabled = false
            shakeDropEnabled = false
            showDate = false
            showSecondHand = false
            showDigitalReadout = false
            theme = dialTheme()
            hoursOnDial = this@AlarmCards.hoursOnDial()
            dialShape = this@AlarmCards.dialShape()
            numeralStyle = ClockView.NumeralStyle.NONE
            // Nothing else on a face this small says which seven it means.
            showDayNightToken = true
            // A constant "duration" makes the dial a static clock.
            chronoProvider = { fixedMs }
        }
    }

    /** Weekday order for the strip, honoring the calendar's week start. */
    fun weekdayOrder(): List<Int> {
        val mondayFirst = prefs.getBoolean(
            Prefs.WEEK_START_MONDAY,
            Calendar.getInstance().firstDayOfWeek == Calendar.MONDAY
        )
        val base = listOf(
            Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
        )
        return if (mondayFirst) base.drop(1) + base.first() else base
    }

    fun weekdayLetters(): List<String> {
        val format = java.text.SimpleDateFormat("EEEEE", Locale.getDefault())
        val cal = Calendar.getInstance()
        return weekdayOrder().map { dow ->
            cal.set(Calendar.DAY_OF_WEEK, dow)
            format.format(cal.time).uppercase(Locale.getDefault())
        }
    }
}

package com.em87.weirdclock

import android.content.Context
import android.content.SharedPreferences
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
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
     * The sounds on offer, shown as a list you can hear.
     *
     * Cycling through them one tap at a time meant walking past "your own
     * file", and every pass through it threw you out into a file browser.
     * So: a list. And now that the list is eight long and half of it is
     * animals, tapping a name plays it and the choice is only made when you
     * say so — picking a cockerel blind and finding out at six the next
     * morning is not choosing, it is gambling.
     *
     * "Your own file" is the one that cannot be previewed here: it has no
     * sound until the file browser has been through, which is the whole
     * reason it is last.
     */
    fun pickSound(current: String, allowCustom: Boolean, onPicked: (String) -> Unit) {
        val sounds = Prefs.ALARM_SOUNDS.toMutableList()
        if (allowCustom) sounds.add(Prefs.ALARM_SOUND_CUSTOM)
        var chosen = sounds.indexOf(current).coerceAtLeast(0)
        val player = ChimePlayer()
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(R.string.pref_bell_style_title)
            .setSingleChoiceItems(
                sounds.map { soundLabel(it) }.toTypedArray(),
                chosen
            ) { _, which ->
                chosen = which
                if (sounds[which] != Prefs.ALARM_SOUND_CUSTOM) player.playNamed(sounds[which])
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(sounds[chosen]) }
            .setNegativeButton(android.R.string.cancel, null)
            // Whichever way it closes. A preview left playing over the top
            // of the sheet you have just come back to is worse than no
            // preview at all.
            .setOnDismissListener { player.release() }
            .show()
    }

    /** How long this alarm's screen takes to come up, in words. */
    fun gentleLabel(seconds: Int): String = when {
        seconds <= 0 -> host.getString(R.string.alarm_gentle_off)
        seconds % 60 == 0 -> host.getString(R.string.alarm_gentle_min, seconds / 60)
        else -> host.getString(R.string.alarm_gentle_sec, seconds)
    }

    companion object {
        /**
         * Every mission you can pick, flattened: no mission, one entry per
         * rung of the arithmetic ladder, and shaking.
         */
        val MISSION_CHOICES: List<Pair<String, Int>> =
            listOf(Mission.NONE to Mission.DEFAULT_LEVEL) +
                (1..Mission.LEVELS).map { Mission.MATHS to it } +
                listOf(Mission.SHAKE to Mission.DEFAULT_LEVEL)
    }

    /**
     * Which icon says what this alarm will want.
     *
     * Two, not one: being woken to do arithmetic and being woken to shake
     * the thing are quite different mornings, and a single mark saying
     * only "there is a mission" leaves you opening the alarm to find out
     * which.
     */
    internal fun missionIcon(mission: String?): Int =
        if (Mission.required(mission) == Mission.SHAKE) R.drawable.ic_shake
        else R.drawable.ic_sigma

    /** And what it will want before it stops, rung included. */
    fun missionLabel(mission: String?, level: Int = Mission.DEFAULT_LEVEL): String =
        when (Mission.required(mission)) {
            Mission.MATHS -> host.getString(
                R.string.alarm_mission_maths_level, Mission.level(level)
            )
            Mission.SHAKE -> host.getString(R.string.alarm_mission_shake)
            else -> host.getString(R.string.alarm_mission_none)
        }

    /** The names the sound picker and the cards both use. */
    fun soundLabel(sound: String): String = host.getString(
        when (sound) {
            Prefs.ALARM_SOUND_DIGITAL -> R.string.alarm_sound_digital
            Prefs.ALARM_SOUND_BABY -> R.string.alarm_sound_baby
            Prefs.ALARM_SOUND_RING_BELL -> R.string.alarm_sound_ring_bell
            Prefs.ALARM_SOUND_ROOSTER -> R.string.alarm_sound_rooster
            Prefs.ALARM_SOUND_SNAKE -> R.string.alarm_sound_snake
            Prefs.ALARM_SOUND_WOLF -> R.string.alarm_sound_wolf
            Prefs.ALARM_SOUND_DOG -> R.string.alarm_sound_dog
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
        val iconGentle: ImageView = view.findViewById(R.id.icon_gentle)
        val iconMission: ImageView = view.findViewById(R.id.icon_mission)
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
            // the dark. No colour filter at all — the drawable carries the
            // same tint as every other icon beside it, and forcing pure
            // white made this one glyph stand out from a row it is supposed
            // to belong to. An alarm that rings several times a day is
            // judged by its first.
            val (fh, fm) = times[0]
            val dark = DayNight.isDarkAt(fh, fm)
            holder.iconDayNight.setImageResource(
                if (dark) R.drawable.ic_moon else R.drawable.ic_sun
            )
            // Views are recycled, and a filter set on a previous binding
            // would otherwise still be on this one.
            holder.iconDayNight.clearColorFilter()
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
            // Two things that are easy to set and then forget, and both
            // change what happens at six in the morning: a sunrise for the
            // screen that comes up slowly, and a multiplication sign for
            // the alarm that will want an answer before it stops.
            holder.iconGentle.visibility =
                if (alarm.gentleWakeSeconds > 0) View.VISIBLE else View.GONE
            holder.iconMission.visibility =
                if (Mission.any(alarm.mission)) View.VISIBLE else View.GONE
            // Which mission, not merely that there is one: a sum sign for
            // the one that wants arithmetic, a phone shaking for the one
            // that wants shaking. Two icons for two quite different things
            // to be woken by is worth the extra drawable.
            holder.iconMission.setImageResource(missionIcon(alarm.mission))
            holder.iconMission.contentDescription =
                missionLabel(alarm.mission, alarm.missionLevel)
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
     * The faces on an alarm card. One time is one face filling the block;
     * more than one and they are all the same size, quartered into a 2×2
     * mosaic inside that same block.
     *
     * No lead face, on purpose. A concept that happens four times a day
     * does not have a main one — keeping the alarm's own time big and the
     * repetitions small said it did, and left the mosaic unable to be a
     * mosaic. And the block is the same square whether it holds one face
     * or four, so the icon rows underneath line up all the way down the
     * list however the alarms are set.
     */
    fun fillDials(row: LinearLayout, times: List<Pair<Int, Int>>) {
        val density = host.resources.displayMetrics.density
        val gap = (4 * density).toInt()
        val block = (46 * density).toInt()

        if (times.size == 1) {
            row.addView(
                miniDial(times[0].first, times[0].second),
                LinearLayout.LayoutParams(block, block)
            )
            return
        }

        // Four is the most an alarm can hold, so two rows of two is the
        // whole of it. Centred both ways: two times fill one row and three
        // leave a hole, and neither should sit off in a corner.
        val small = (block - gap) / 2
        val mosaic = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val shown = times.take(4)
        for (line in 0..1) {
            val inLine = shown.drop(line * 2).take(2)
            if (inLine.isEmpty()) break
            val strip = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for ((column, t) in inLine.withIndex()) {
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
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (line > 0) topMargin = gap }
            )
        }
        row.addView(mosaic, LinearLayout.LayoutParams(block, block))
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
    fun miniDial(hour: Int, minute: Int, sky: Boolean = false): ClockView {
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
            // Only where nothing else says it. On the alarm cards the icon
            // at the head of the row already does, and two answers to the
            // same question on one card is one too many — a face that small
            // has no room to spare on a thing already said.
            showMoonPhase = sky
            // A constant "duration" makes the dial a static clock — and
            // saying so out loud is what lets it stop redrawing.
            staticFace = true
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

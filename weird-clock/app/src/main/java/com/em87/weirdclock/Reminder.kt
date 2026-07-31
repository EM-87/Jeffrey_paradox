package com.em87.weirdclock

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar

/**
 * A one-shot dated reminder created from the calendar card: it rings like an
 * alarm at its date and time, then removes itself.
 */
data class Reminder(
    val id: Int,
    val year: Int,
    /** 1–12. */
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val label: String,
    /** How long the event lasts; 0 = a moment, drawn as a dot. */
    val durationMinutes: Int = 0,
    /** Whether it actually rings, or just marks the calendar and the dial. */
    val rings: Boolean = false,
    val sound: String = Prefs.ALARM_SOUND_BELLS,
    /** Ring this many minutes before the event; 0 rings on the dot. */
    val leadMinutes: Int = 0,
    /** [REPEAT_NEVER], [REPEAT_MONTHLY] or [REPEAT_YEARLY]. */
    val repeat: String = REPEAT_NEVER
) {
    /** The date it was first set for, whatever it has repeated since. */
    fun timeInMillis(): Long = Calendar.getInstance().run {
        set(year, month - 1, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /**
     * Whether this reminder falls on the given date — which for a repeating
     * one is any anniversary of it, on or after the day it was created. A
     * monthly reminder set for the 31st simply skips the months that have no
     * 31st rather than sliding into the next one.
     */
    fun occursOn(y: Int, m: Int, d: Int): Boolean = when (repeat) {
        REPEAT_YEARLY -> m == month && d == day && y >= year
        REPEAT_MONTHLY -> d == day && (y > year || (y == year && m >= month))
        else -> y == year && m == month && d == day
    }

    /**
     * The next time this comes round, counting from now.
     *
     * Each candidate is built from the original date rather than by walking
     * a calendar forward from the last one, because adding to a calendar
     * slides: add a year to 29 February and you land on 1 March, and the
     * repair for that used to move the reminder to the 29th of *March* —
     * where, being a valid date, it then stayed for good. A leap-day
     * anniversary belongs on the leap day; it simply waits four years.
     *
     * Months that do not have the day are skipped for the same reason, so a
     * monthly reminder set for the 31st keeps the 31st.
     */
    fun nextTimeInMillis(now: Long = System.currentTimeMillis()): Long {
        val first = timeInMillis()
        if (repeat == REPEAT_NEVER || first > now) return first
        for (step in 1 until 500) {
            val cal = Calendar.getInstance().apply {
                clear()
                if (repeat == REPEAT_YEARLY) {
                    set(year + step, month - 1, 1, hour, minute, 0)
                } else {
                    val months = (month - 1) + step
                    set(year + months / 12, months % 12, 1, hour, minute, 0)
                }
            }
            if (cal.getActualMaximum(Calendar.DAY_OF_MONTH) < day) continue
            cal.set(Calendar.DAY_OF_MONTH, day)
            if (cal.timeInMillis > now) return cal.timeInMillis
        }
        return first
    }

    /** When it actually rings: early by [leadMinutes], if asked. */
    fun ringAtMillis(now: Long = System.currentTimeMillis()): Long =
        nextTimeInMillis(now) - leadMinutes * 60_000L

    companion object {
        const val REPEAT_NEVER = "never"
        const val REPEAT_MONTHLY = "monthly"
        const val REPEAT_YEARLY = "yearly"
    }
}

/** Reminders persisted as JSON in the default SharedPreferences. */
object ReminderStore {

    private const val KEY = "pref_reminders_json"

    private const val NINETY_DAYS_MS = 90L * 86_400_000L

    fun load(context: Context): MutableList<Reminder> {
        val json = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY, null) ?: return mutableListOf()
        val list = mutableListOf<Reminder>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Reminder(
                        id = o.getInt("id"),
                        year = o.getInt("year"),
                        month = o.getInt("month"),
                        day = o.getInt("day"),
                        hour = o.getInt("hour"),
                        minute = o.getInt("minute"),
                        label = o.optString("label", ""),
                        durationMinutes = o.optInt("duration", 0),
                        rings = o.optBoolean("rings", false),
                        sound = o.optString("sound", Prefs.ALARM_SOUND_BELLS),
                        leadMinutes = o.optInt("lead", 0),
                        repeat = o.optString("repeat", Reminder.REPEAT_NEVER)
                    )
                )
            }
        } catch (e: JSONException) {
            // Corrupt store: start fresh rather than crash.
        }
        // A reminder that has passed is still worth having: the calendar
        // lets you open a spent day to read what it held, and that only
        // works if it is still there. They keep for three months — long
        // enough to look back over a season, short enough that the store
        // does not grow forever. Repeating ones never expire.
        val cutoff = System.currentTimeMillis() - NINETY_DAYS_MS
        list.removeAll { it.repeat == Reminder.REPEAT_NEVER && it.timeInMillis() < cutoff }
        return list
    }

    fun save(context: Context, reminders: List<Reminder>) {
        val arr = JSONArray()
        for (r in reminders) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("year", r.year)
                    .put("month", r.month)
                    .put("day", r.day)
                    .put("hour", r.hour)
                    .put("minute", r.minute)
                    .put("label", r.label)
                    .put("duration", r.durationMinutes)
                    .put("rings", r.rings)
                    .put("sound", r.sound)
                    .put("lead", r.leadMinutes)
                    .put("repeat", r.repeat)
            )
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun remove(context: Context, id: Int) {
        val list = load(context)
        if (list.removeAll { it.id == id }) save(context, list)
    }

    fun nextId(reminders: List<Reminder>): Int = (reminders.maxOfOrNull { it.id } ?: 0) + 1
}

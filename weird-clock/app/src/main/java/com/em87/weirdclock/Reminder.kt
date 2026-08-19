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
    /** [REPEAT_NEVER], [REPEAT_WEEKLY], [REPEAT_MONTHLY] or [REPEAT_YEARLY]. */
    val repeat: String = REPEAT_NEVER,
    /**
     * Free text kept with the event and read out by the dial.
     *
     * A label has to be short — it is a line on a card and a name in a
     * bubble — so there was nowhere to put the address, the room number, or
     * what to bring. This is that nowhere. It shows up when the hour hand
     * rests on the mark, which is the moment you are asking about it.
     */
    val notes: String = ""
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
        // A weekly one is the only repeat that does not keep a date: it
        // keeps a weekday, so it is asked of the calendar rather than of
        // the numbers. Whole days between, divisible by seven.
        REPEAT_WEEKLY -> {
            val days = wholeDaysFromStart(y, m, d)
            days >= 0 && days % 7 == 0L
        }
        else -> y == year && m == month && d == day
    }

    /** Midnight-to-midnight days from the reminder's own date to [y]-[m]-[d]. */
    private fun wholeDaysFromStart(y: Int, m: Int, d: Int): Long {
        val from = Calendar.getInstance().apply {
            clear(); set(year, month - 1, day)
        }.timeInMillis
        val to = Calendar.getInstance().apply {
            clear(); set(y, m - 1, d)
        }.timeInMillis
        // Rounded, not truncated: a daylight-saving change makes one of
        // these days 23 or 25 hours long, and an integer division would
        // quietly lose the week that straddles it.
        return Math.round((to - from) / 86_400_000.0)
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
        // A weekly one just steps a week at a time from where it started;
        // there is no short month to skip, and no date to preserve.
        if (repeat == REPEAT_WEEKLY) {
            val cal = Calendar.getInstance().apply { timeInMillis = first }
            while (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 7)
            return cal.timeInMillis
        }
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
        const val REPEAT_WEEKLY = "weekly"
        const val REPEAT_MONTHLY = "monthly"
        const val REPEAT_YEARLY = "yearly"
    }
}

/**
 * The reminders, persisted as JSON in the default SharedPreferences.
 *
 * One list, shared by everything in the process, for the same reason the
 * alarms are: see AlarmStore. Mutated, never replaced.
 */
object ReminderStore {

    private const val KEY = "pref_reminders_json"

    private const val A_YEAR_MS = 366L * 86_400_000L

    private var shared: MutableList<Reminder>? = null

    /** The list. Read it, change it, then [save]. */
    @Synchronized
    fun all(context: Context): MutableList<Reminder> =
        shared ?: read(context).also { shared = it }

    /**
     * Throws the cached list away, so the next [all] reads the store again.
     *
     * Only a restore needs this: everything else changes the list in place
     * and saves it, and re-reading would be the slow way of getting the
     * same answer. A restore rewrites the file underneath the cache, and
     * without this the app would keep serving the alarms it had before.
     */
    @Synchronized
    fun forget() {
        shared = null
    }

    /** Writes down whatever the shared list now says. */
    @Synchronized
    fun save(context: Context) {
        val reminders = shared ?: return
        writeAll(context, reminders)
    }

    private fun read(context: Context): MutableList<Reminder> {
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
                        repeat = o.optString("repeat", Reminder.REPEAT_NEVER),
                        notes = o.optString("notes", "")
                    )
                )
            }
        } catch (e: JSONException) {
            // Corrupt store: start fresh rather than crash.
        }
        // A reminder that has passed is still worth having: the calendar
        // lets you open a spent day to read what it held, and that only
        // works if it is still there.
        //
        // They keep for a year, which used to be three months. The year is
        // what the solar system needs: zoom the Earth's orbit out to the rim
        // and every day of it gets a mark, with a dot on the ones that were
        // busy — and a whole turn of the Earth is exactly a year of them.
        // Three months of memory would have left three quarters of that
        // circle blank whatever the year had actually held.
        val cutoff = System.currentTimeMillis() - A_YEAR_MS
        list.removeAll { it.repeat == Reminder.REPEAT_NEVER && it.timeInMillis() < cutoff }
        return list
    }

    private fun writeAll(context: Context, reminders: List<Reminder>) {
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
                    .put("notes", r.notes)
            )
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun remove(context: Context, id: Int) {
        if (all(context).removeAll { it.id == id }) save(context)
    }

    fun nextId(reminders: List<Reminder>): Int = (reminders.maxOfOrNull { it.id } ?: 0) + 1
}

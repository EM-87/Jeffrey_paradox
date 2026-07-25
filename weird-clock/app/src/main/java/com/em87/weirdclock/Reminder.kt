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
    val durationMinutes: Int = 0
) {
    fun timeInMillis(): Long = Calendar.getInstance().run {
        set(year, month - 1, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }
}

/** Reminders persisted as JSON in the default SharedPreferences. */
object ReminderStore {

    private const val KEY = "pref_reminders_json"

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
                        durationMinutes = o.optInt("duration", 0)
                    )
                )
            }
        } catch (e: JSONException) {
            // Corrupt store: start fresh rather than crash.
        }
        // Reminders that already rang (or were missed by over a day) expire.
        val cutoff = System.currentTimeMillis() - 86_400_000L
        list.removeAll { it.timeInMillis() < cutoff }
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

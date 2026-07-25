package com.em87.weirdclock

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Alarm(
    val id: Int,
    var hour: Int,
    var minute: Int,
    var enabled: Boolean,
    var sound: String,
    /**
     * Which weekdays this alarm repeats on, as a bit per day with bit 0 =
     * Sunday … bit 6 = Saturday. 0 means "the next time this hour comes
     * round", the classic one-shot.
     */
    var daysMask: Int = ALL_DAYS,
    /** Snooze offered on the ring screen: 0 = off, otherwise minutes (5 or 10). */
    var snoozeMinutes: Int = 5,
    /** Optional user label ("Gym", "Pills"), shown when ringing. */
    var label: String = "",
    /** SAF URI of a user-picked audio file, used when [sound] is custom. */
    var soundUri: String = "",
    var vibrate: Boolean = true,
    /**
     * How long the thing this alarm is for lasts. Anything above zero also
     * makes it a dated event, so it shows as a wedge on the dial.
     */
    var durationMinutes: Int = 0,
    /** Strobe the camera flash while ringing. */
    var flash: Boolean = false
) {
    fun ringsOn(dayOfWeek: Int): Boolean =
        daysMask == 0 || (daysMask and (1 shl (dayOfWeek - 1))) != 0

    companion object {
        const val ALL_DAYS = 0b1111111
        const val WEEKDAYS = 0b0111110
        const val WEEKENDS = 0b1000001
    }
}

/** Alarms persisted as a JSON array in the default SharedPreferences. */
object AlarmStore {

    private const val KEY = "pref_alarms_json"

    fun load(context: Context): MutableList<Alarm> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = prefs.getString(KEY, null) ?: return migrateLegacy(prefs)
        val list = mutableListOf<Alarm>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Alarm(
                        id = o.getInt("id"),
                        hour = o.getInt("hour"),
                        minute = o.getInt("minute"),
                        enabled = o.getBoolean("enabled"),
                        sound = o.optString("sound", Prefs.ALARM_SOUND_BELLS),
                        // Older stores kept a repeat keyword; map it onto
                        // the per-day mask.
                        daysMask = o.optInt(
                            "days",
                            when (o.optString("repeat", Prefs.ALARM_REPEAT_DAILY)) {
                                Prefs.ALARM_REPEAT_WEEKDAYS -> Alarm.WEEKDAYS
                                Prefs.ALARM_REPEAT_WEEKENDS -> Alarm.WEEKENDS
                                else -> Alarm.ALL_DAYS
                            }
                        ),
                        // Older stores kept snooze as a boolean (always 5 min).
                        snoozeMinutes = o.optInt(
                            "snoozeMin",
                            if (o.optBoolean("snooze", true)) 5 else 0
                        ),
                        label = o.optString("label", ""),
                        soundUri = o.optString("soundUri", ""),
                        vibrate = o.optBoolean("vibrate", true),
                        durationMinutes = o.optInt("duration", 0),
                        flash = o.optBoolean("flash", false)
                    )
                )
            }
        } catch (e: JSONException) {
            // Corrupt store: start fresh rather than crash.
        }
        return list
    }

    /** Converts the old single-alarm preferences into the first list entry. */
    private fun migrateLegacy(prefs: SharedPreferences): MutableList<Alarm> {
        val list = mutableListOf<Alarm>()
        if (prefs.contains(Prefs.ALARM_ENABLED) || prefs.contains(Prefs.ALARM_TIME)) {
            val time = prefs.getString(Prefs.ALARM_TIME, "07:30") ?: "07:30"
            val parts = time.split(":")
            list.add(
                Alarm(
                    id = 1,
                    hour = parts.getOrNull(0)?.toIntOrNull() ?: 7,
                    minute = parts.getOrNull(1)?.toIntOrNull() ?: 30,
                    enabled = prefs.getBoolean(Prefs.ALARM_ENABLED, false),
                    sound = Prefs.ALARM_SOUND_BELLS
                )
            )
        }
        return list
    }

    fun save(context: Context, alarms: List<Alarm>) {
        val arr = JSONArray()
        for (a in alarms) {
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("hour", a.hour)
                    .put("minute", a.minute)
                    .put("enabled", a.enabled)
                    .put("sound", a.sound)
                    .put("days", a.daysMask)
                    .put("snoozeMin", a.snoozeMinutes)
                    .put("label", a.label)
                    .put("soundUri", a.soundUri)
                    .put("vibrate", a.vibrate)
                    .put("duration", a.durationMinutes)
                    .put("flash", a.flash)
            )
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun nextId(alarms: List<Alarm>): Int = (alarms.maxOfOrNull { it.id } ?: 0) + 1
}

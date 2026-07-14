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
    /** One of [Prefs.ALARM_REPEAT_DAILY], [Prefs.ALARM_REPEAT_WEEKDAYS], [Prefs.ALARM_REPEAT_WEEKENDS]. */
    var repeat: String = Prefs.ALARM_REPEAT_DAILY
)

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
                        repeat = o.optString(
                            "repeat",
                            if (o.optBoolean("weekdays", false)) Prefs.ALARM_REPEAT_WEEKDAYS
                            else Prefs.ALARM_REPEAT_DAILY
                        )
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
                    .put("repeat", a.repeat)
            )
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun nextId(alarms: List<Alarm>): Int = (alarms.maxOfOrNull { it.id } ?: 0) + 1
}

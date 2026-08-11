package com.em87.weirdclock

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app remembers, in one file the user keeps.
 *
 * Uninstalling an Android app destroys its preferences, and this app keeps
 * its alarms and reminders in preferences. That is normally invisible,
 * because an update is not an uninstall — until the day a phone refuses an
 * update for a reason nobody can see from here, and the only way forward is
 * to remove the app and put it back. Then years of alarms go with it.
 *
 * Rather than guess at that refusal, make it survivable. The file is the
 * whole preference store, not a curated subset: alarms, reminders, and every
 * setting down to which hours are highlighted. A backup that quietly leaves
 * something out is worse than none, because you only find out what it left
 * out on the day you need it back.
 *
 * Everything is stored as a typed pair, because SharedPreferences is typed
 * and a boolean read back as a string throws where it is read, not where it
 * was written.
 */
object Backup {

    private const val MAGIC = "weird-clock-backup"
    private const val VERSION = 1

    /** Keys that describe this install rather than this user's clock. */
    private val SKIP = setOf(
        Prefs.COUNTDOWN_ENDS_AT,
        Prefs.COUNTDOWN_TOTAL,
        Prefs.COUNTDOWN_RESULT,
        Prefs.COUNTDOWN_PERSISTENT,
        Prefs.COUNTDOWN_BUBBLE,
        Prefs.NEEDS_REASSEMBLY,
        Prefs.REASSEMBLE_PENDING,
        Prefs.LOCATION_ASKED,
        Prefs.OVERLAY_ASKED,
        // A booking for one particular morning, and the tally that goes
        // with it. Restored onto another phone or another week they would
        // be a promise about an alarm that no longer exists.
        Prefs.NAG_AT,
        Prefs.NAG_ROUNDS
    )

    fun export(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val entries = JSONObject()
        for ((key, value) in prefs.all) {
            if (key in SKIP || value == null) continue
            val typed = JSONObject()
            when (value) {
                is Boolean -> typed.put("t", "b").put("v", value)
                is Int -> typed.put("t", "i").put("v", value)
                is Long -> typed.put("t", "l").put("v", value)
                is Float -> typed.put("t", "f").put("v", value.toDouble())
                is String -> typed.put("t", "s").put("v", value)
                is Set<*> -> typed.put("t", "ss")
                    .put("v", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            entries.put(key, typed)
        }
        return JSONObject()
            .put("magic", MAGIC)
            .put("version", VERSION)
            .put("savedAt", System.currentTimeMillis())
            .put("entries", entries)
            .toString(2)
    }

    /** What a restored file turned out to hold. */
    data class Restored(val alarms: Int, val reminders: Int)

    /**
     * Replaces the whole preference store with the file's contents.
     *
     * Replaces rather than merges: a backup is a photograph of a working
     * clock, and half of one is not a clock. Returns null if the file is not
     * ours, which is the only outcome the caller can do anything about.
     */
    fun import(context: Context, json: String): Restored? {
        val root = try {
            JSONObject(json)
        } catch (e: org.json.JSONException) {
            return null
        }
        if (root.optString("magic") != MAGIC) return null
        val entries = root.optJSONObject("entries") ?: return null

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
        editor.clear()
        val keys = entries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val e = entries.optJSONObject(key) ?: continue
            when (e.optString("t")) {
                "b" -> editor.putBoolean(key, e.optBoolean("v"))
                "i" -> editor.putInt(key, e.optInt("v"))
                "l" -> editor.putLong(key, e.optLong("v"))
                "f" -> editor.putFloat(key, e.optDouble("v").toFloat())
                "s" -> editor.putString(key, e.optString("v"))
                "ss" -> {
                    val arr = e.optJSONArray("v") ?: JSONArray()
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) set.add(arr.optString(i))
                    editor.putStringSet(key, set)
                }
            }
        }
        // Committed, not applied: the stores are re-read on the very next
        // line, and an asynchronous write would let them read the old file.
        editor.commit()

        // The stores cache their list in a static field, so a restore that
        // only rewrote preferences would be invisible until the process
        // died. Drop the caches and count what actually came back.
        AlarmStore.forget()
        ReminderStore.forget()
        return Restored(AlarmStore.all(context).size, ReminderStore.all(context).size)
    }
}

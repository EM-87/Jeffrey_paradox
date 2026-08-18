package com.em87.weirdclock

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Where the recorded periods are kept.
 *
 * On the phone and nowhere else. This app has no network permission and
 * makes no request to anything, so what is written here goes into the same
 * preferences file as the alarms and the reminders, travels in the same
 * backup, and is deleted with the app. That is worth stating plainly for
 * this one, because it is the only thing the app knows about anybody that
 * they would mind a stranger reading.
 *
 * The same shape as [ReminderStore]: one cached list, read once, changed in
 * place and saved. Nothing here decides anything about cycles — that is
 * [Cycle]'s job, and it does it without a Context.
 */
object CycleStore {

    private const val KEY = "pref_cycle_json"

    private var shared: MutableList<Cycle.Period>? = null

    /** The record. Read it, change it, then [save]. */
    @Synchronized
    fun all(context: Context): MutableList<Cycle.Period> =
        shared ?: read(context).also { shared = it }

    /** Throws the cached list away, so the next [all] reads the store again. */
    @Synchronized
    fun forget() {
        shared = null
    }

    @Synchronized
    fun save(context: Context) {
        val periods = shared ?: return
        val arr = JSONArray()
        for (p in periods) {
            arr.put(JSONObject().put("start", p.start).put("days", p.days))
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /** Replaces the record wholesale, sorted, and writes it down. */
    @Synchronized
    fun replace(context: Context, periods: List<Cycle.Period>) {
        shared = periods.sortedBy { it.start }.toMutableList()
        save(context)
    }

    /** Whether anything has ever been recorded. */
    fun anyRecorded(context: Context): Boolean = all(context).isNotEmpty()

    private fun read(context: Context): MutableList<Cycle.Period> {
        val json = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY, null) ?: return mutableListOf()
        val list = mutableListOf<Cycle.Period>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Cycle.Period(o.getInt("start"), o.optInt("days", 0)))
            }
        } catch (e: JSONException) {
            // Corrupt store: start fresh rather than crash. The same choice
            // the alarms make, and the same reason — an app that will not
            // open is worse than one that has lost a list.
        }
        return list.sortedBy { it.start }.toMutableList()
    }
}

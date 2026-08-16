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
    var flash: Boolean = false,
    /**
     * What has to be got right before *this* alarm will stop.
     *
     * On the alarm and not in the settings, because it is a property of one
     * alarm and not of the app: the mission is for the alarm you keep
     * turning off and going back to sleep, and having it apply to the
     * fifteen-minute one that says "take the bread out" is a joke that
     * stops being funny the first time it happens.
     */
    var mission: String = Mission.NONE,
    /** Which rung of the arithmetic ladder, when the mission is a sum. */
    var missionLevel: Int = Mission.DEFAULT_LEVEL,
    /**
     * How long this alarm's screen takes to come up, in seconds; 0 for
     * straight on.
     *
     * Per alarm for the same reason. A gradual sunrise is for the one that
     * wakes you; on a reminder in the middle of the afternoon it is a
     * screen that seems not to have come on.
     */
    var gentleWakeSeconds: Int = 0,
    /**
     * And then the torch, for the sleeper the sunrise does not reach.
     *
     * Distinct from [flash], which strobes from the first second. This one
     * is the second half of a gentle wake and does nothing without one.
     */
    var gentleFlash: Boolean = false,
    /**
     * Free text kept with the alarm and read out by the dial, exactly as a
     * reminder's is.
     *
     * Notes arrived on the calendar side first, which left the dial able to
     * read out an appointment's details and not an alarm's — the same dot,
     * on the same face, answering to a different depth depending on which
     * sheet made it.
     */
    var notes: String = "",
    /**
     * Extra times of day (minutes past midnight) this same alarm also rings
     * at. One alarm is one *concept* — "pills", "stretch" — and a concept
     * can happen four times a day without being four separate alarms.
     */
    var extraTimes: MutableList<Int> = mutableListOf()
) {
    /**
     * A one-shot: rings the next time its hour comes round and then switches
     * itself off. This is what an alarm with no days set has always claimed
     * to be, and for a long time was not — [ringsOn] answered yes to every
     * day and nothing ever retired it, so "set an alarm for seven", which
     * the assistant sends with no days at all, quietly became a daily alarm.
     * [AlarmReceiver] retires it now.
     */
    val once: Boolean get() = daysMask == 0

    /** Any day will do for a one-shot: the first one to arrive is the one. */
    fun ringsOn(dayOfWeek: Int): Boolean =
        once || (daysMask and (1 shl (dayOfWeek - 1))) != 0

    /** Every time this alarm rings at, in order, the first one included. */
    fun allTimes(): List<Pair<Int, Int>> =
        (listOf(hour * 60 + minute) + extraTimes)
            .distinct()
            .sorted()
            .map { it / 60 to it % 60 }

    /** Replaces the [index]-th time; index 0 is the alarm's own hour. */
    fun setTime(index: Int, newHour: Int, newMinute: Int) {
        if (index <= 0) {
            hour = newHour
            minute = newMinute
        } else if (index - 1 < extraTimes.size) {
            extraTimes[index - 1] = newHour * 60 + newMinute
        }
    }

    fun timeAt(index: Int): Pair<Int, Int> =
        if (index <= 0) hour to minute
        else extraTimes.getOrElse(index - 1) { 0 }.let { it / 60 to it % 60 }

    /** How many times this concept happens (1–4). */
    fun timeCount(): Int = 1 + extraTimes.size

    companion object {
        const val ALL_DAYS = 0b1111111
        const val WEEKDAYS = 0b0111110
        const val WEEKENDS = 0b1000001
        const val MAX_TIMES = 4
    }
}

/**
 * The alarms, persisted as a JSON array in the default SharedPreferences.
 *
 * One list, shared by everything in the process. It used to hand each caller
 * its own copy — and there were four of them: the activity, the assistant's
 * door, the receiver that retires a one-shot, and the scheduler. Whoever
 * saved last won, which is how an alarm set by voice could be written out of
 * existence by a screen that had been holding a stale copy since it opened.
 *
 * The instance is mutated, never replaced. Views and sheets hold a reference
 * to it, and swapping it for a freshly loaded one would put every one of them
 * back to reading a copy nobody else writes to — the same bug by another road.
 */
object AlarmStore {

    private const val KEY = "pref_alarms_json"

    private var shared: MutableList<Alarm>? = null

    /** The list. Read it, change it, then [save]. */
    @Synchronized
    fun all(context: Context): MutableList<Alarm> =
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
    /*
     * There was a migration here that copied the app-wide mission and
     * gradual sunrise onto every existing alarm, so that nobody lost a
     * setting they had switched on.
     *
     * It was the wrong call, and it is gone. The old setting was app-wide
     * because there was no alternative, not because somebody meant it to
     * apply to every alarm they own — and the result was that every
     * reminder in the list suddenly wanted a multiplication done before it
     * would stop. Losing a setting is a small annoyance and can be put
     * back in five seconds; a mission on the alarm that says the bread is
     * done is the joke that stops being funny the first time it happens.
     */

    fun save(context: Context) {
        val alarms = shared ?: return
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
                    .put("mission", a.mission)
                    .put("missionLevel", a.missionLevel)
                    .put("gentle", a.gentleWakeSeconds)
                    .put("gentleFlash", a.gentleFlash)
                    .put("notes", a.notes)
                    .put("extraTimes", JSONArray(a.extraTimes))
            )
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    private fun read(context: Context): MutableList<Alarm> {
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
                        flash = o.optBoolean("flash", false),
                        // Both were app-wide settings for one version. An
                        // alarm written before this build carries neither,
                        // and picks up whatever was set globally — see
                        // AlarmStore.adoptGlobals.
                        mission = Mission.required(o.optString("mission", Mission.NONE)),
                        missionLevel = Mission.level(o.optInt("missionLevel", Mission.DEFAULT_LEVEL)),
                        gentleWakeSeconds = o.optInt("gentle", 0),
                        gentleFlash = o.optBoolean("gentleFlash", false),
                        notes = o.optString("notes", ""),
                        extraTimes = o.optJSONArray("extraTimes")?.let { arr ->
                            MutableList(arr.length()) { arr.getInt(it) }
                        } ?: mutableListOf()
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

    fun nextId(alarms: List<Alarm>): Int = (alarms.maxOfOrNull { it.id } ?: 0) + 1
}

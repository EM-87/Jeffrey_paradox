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

    /**
     * What shape this file is in.
     *
     * Written into every file and read back out of one. A file from a
     * later version than this build understands is refused rather than
     * half-read: restoring a photograph of a clock with the parts you do
     * not recognise silently dropped is how somebody loses the alarms they
     * came to this file for.
     *
     * 1 — the preference store, typed.
     * 2 — the same, plus dated restore points written without being asked.
     */
    const val VERSION = 2

    /**
     * Whether a file claims to come from a later version of the app.
     *
     * Version 1 files have no such claim and are read as they always were;
     * everything this build writes claims 2. Anything above is a file this
     * build cannot honestly promise to restore.
     */
    fun tooNew(json: String): Boolean {
        val root = try {
            JSONObject(json)
        } catch (e: org.json.JSONException) {
            return false
        }
        return root.optInt("version", 1) > VERSION
    }

    // ------------------------------------------------- the restore points

    /** The name a restore point written at [atMs] is filed under. */
    fun nameFor(atMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
        return String.format(
            java.util.Locale.US, "%s-%04d-%02d-%02d.json", FILE_STEM,
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    /** The stem every restore point's name begins with. */
    const val FILE_STEM = "weird-clock"

    /**
     * The day a restore point was written, from its name, or null if the
     * name is not one of ours.
     *
     * Read from the name rather than from the file, so a folder full of
     * them can be sorted and pruned without opening any of them — and so a
     * file somebody else put there is left alone rather than counted as a
     * backup and deleted to make room.
     */
    fun savedOn(name: String): Int? {
        val m = Regex("^${Regex.escape(FILE_STEM)}-(\\d{4})-(\\d{2})-(\\d{2})\\.json$")
            .find(name) ?: return null
        val (y, mo, d) = m.destructured
        return CivilDays.epochDay(y.toInt(), mo.toInt(), d.toInt())
    }

    /**
     * Whether it is worth writing another restore point.
     *
     * One a day. More often would fill the folder with fifty copies of a
     * Tuesday and push out the week you actually want; less often and a
     * day's work — a new alarm, a month of the cycle — could go missing
     * between two of them.
     *
     * [lastMs] of zero means there has never been one, which is always due.
     */
    fun dueFor(lastMs: Long, nowMs: Long): Boolean {
        if (lastMs <= 0L) return true
        // Forward only in the sense that matters: a clock corrected
        // backwards must not stop the backups for a day, so any change of
        // civil day either way is a new day.
        return dayOf(lastMs) != dayOf(nowMs)
    }

    private fun dayOf(ms: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return CivilDays.epochDay(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    /** How many days of restore points are worth keeping. */
    const val KEEP = 7

    /**
     * The restore points among [names], newest first.
     *
     * Newest first because that is the one somebody nearly always wants —
     * "put it back the way it was this morning" — and because a list where
     * the useful answer is at the bottom is a list you scroll past your
     * own answer to reach.
     */
    fun pointsIn(names: List<String>): List<String> =
        names.mapNotNull { name -> savedOn(name)?.let { it to name } }
            .sortedByDescending { it.first }
            .map { it.second }

    /**
     * Which of [names] should be deleted to leave [keep] restore points.
     *
     * The oldest go. Anything in the folder that is not one of ours is not
     * in the answer at all — it is somebody else's file in somebody else's
     * folder, and a backup feature that tidies up around itself is a
     * backup feature that deletes a wedding photograph.
     */
    fun prune(names: List<String>, keep: Int = KEEP): List<String> {
        val ours = names.mapNotNull { name -> savedOn(name)?.let { it to name } }
        if (ours.size <= keep) return emptyList()
        return ours.sortedByDescending { it.first }.drop(keep).map { it.second }
    }

    /** Keys that describe this install rather than this user's clock. */
    private val SKIP = setOf(
        Prefs.COUNTDOWN_ENDS_AT,
        Prefs.COUNTDOWN_TOTAL,
        Prefs.COUNTDOWN_RESULT,
        Prefs.COUNTDOWN_PERSISTENT,
        Prefs.COUNTDOWN_BUBBLE,
        Prefs.LOCATION_ASKED,
        Prefs.OVERLAY_ASKED,
        // A booking for one particular morning, and the tally that goes
        // with it. Restored onto another phone or another week they would
        // be a promise about an alarm that no longer exists.
        Prefs.NAG_AT,
        Prefs.NAG_ROUNDS,
        // Where this phone keeps its restore points, and when it last
        // wrote one. Both describe the arrangement rather than the clock,
        // and a folder from another phone is a folder this one cannot
        // write to.
        Prefs.BACKUP_FOLDER,
        Prefs.BACKUP_AT,
        // A webhook key is a secret, and a backup is a plain file in a
        // folder somebody chose — which may be a shared one, and is
        // certainly readable by anything that can read files. Anybody
        // holding it can fire the house. It is worth losing on a restore
        // and the settings row says so.
        Prefs.IFTTT_KEY
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

    // ------------------------------------------- writing one without being asked

    /**
     * Writes today's restore point, if there is a folder to write it in and
     * one is due.
     *
     * Called wherever something worth keeping changes rather than on a
     * schedule: a clock nobody has touched since yesterday has nothing new
     * to save, and a phone that is switched off at midnight would miss a
     * timed one anyway.
     *
     * Everything here can fail for reasons that are none of the user's
     * business — a folder on a card that has been taken out, a permission
     * the system dropped — and none of them is worth interrupting somebody
     * for. A restore point that could not be written is not a broken
     * alarm; it is a backup that will be written tomorrow instead.
     */
    fun autoSave(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val folder = prefs.getString(Prefs.BACKUP_FOLDER, "").orEmpty()
        if (folder.isBlank()) return false
        if (!dueFor(prefs.getLong(Prefs.BACKUP_AT, 0L), nowMs)) return false
        return try {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                context, android.net.Uri.parse(folder)
            ) ?: return false
            if (!tree.canWrite()) return false
            val name = nameFor(nowMs)
            // Today's is replaced rather than joined: two files for one day
            // is one file too many, and the later one is the truer.
            tree.findFile(name)?.delete()
            val file = tree.createFile("application/json", name) ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(export(context).toByteArray())
            } ?: return false
            for (stale in prune(tree.listFiles().mapNotNull { it.name })) {
                tree.findFile(stale)?.delete()
            }
            prefs.edit().putLong(Prefs.BACKUP_AT, nowMs).apply()
            true
        } catch (e: Exception) {
            false
        }
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
        // A file from a later version of the app. Refused rather than
        // half-read: restoring a photograph of a clock with the parts this
        // build does not recognise silently dropped is how somebody loses
        // the very alarms they opened the file for.
        if (root.optInt("version", 1) > VERSION) return null
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
        CycleStore.forget()
        return Restored(AlarmStore.all(context).size, ReminderStore.all(context).size)
    }
}

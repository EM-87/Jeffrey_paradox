package com.em87.weirdclock

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.preference.PreferenceManager

/**
 * Reading the phone's diary, and every reason not to.
 *
 * The first thing this app has ever asked a *permission* for that it did
 * not already have. The internet is granted at install without asking and
 * the location was asked for by a clock face that cannot work without one;
 * this is different in kind, because what is behind it is a list of where
 * somebody will be and who with.
 *
 * So the rules are narrow and they are all here. **Read only** — this
 * never asks for WRITE_CALENDAR and could not create, move or delete an
 * event if it wanted to. **Nothing leaves the phone**: the events are
 * queried, drawn and forgotten, never written to a file, never in a
 * backup, and there is no code path from here to a socket. **Off until
 * asked**, and turning the switch off stops the query rather than hiding
 * the result.
 *
 * Queried each time rather than cached, which is deliberate. A cache of
 * somebody's diary is a copy of somebody's diary, and it would have to
 * live somewhere; the provider is indexed on exactly this query and the
 * window is a day or a month, so there is nothing to gain by keeping one.
 *
 * The rules about what an event *is* are [Agenda], which is arithmetic and
 * has never heard of Android.
 */
object AgendaStore {

    /**
     * One read of the provider, so the tests can hand over a diary.
     *
     * The same seam the weather and the house use. Here it does more work
     * than either: there is no way to put a calendar event into Robolectric
     * that is not itself a bigger fiction than this.
     */
    fun interface Reader {
        fun read(context: Context, fromMs: Long, toMs: Long): List<Agenda.Event>
    }

    /** The real one, replaced in tests. */
    var reader: Reader = Reader { context, fromMs, toMs -> query(context, fromMs, toMs) }

    /** Whether the owner has granted the one permission this needs. */
    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether the switch is on *and* the permission is there. */
    fun wanted(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(Prefs.AGENDA, false) && allowed(context)

    /**
     * The events in a window, or nothing at all.
     *
     * Nothing at all is the answer to both "switched off" and "not
     * allowed", and they are the same answer on purpose: a clock that drew
     * an empty diary differently from a diary it may not read would be
     * telling anybody holding it which of the two had happened.
     */
    fun between(context: Context, fromMs: Long, toMs: Long): List<Agenda.Event> {
        if (!wanted(context)) return emptyList()
        return try {
            reader.read(context, fromMs, toMs).filter { Agenda.worthDrawing(it) }
        } catch (e: Exception) {
            // A provider that is not there, an account being removed
            // underneath, a permission revoked between the check and the
            // query: all of them are one empty diary rather than a clock
            // that will not open.
            emptyList()
        }
    }

    /**
     * The real query, against the instances table.
     *
     * Instances and not events, and that is the whole reason this is three
     * lines rather than three hundred: a repeating event is one row in
     * `Events` with a recurrence rule on it, and expanding an RRULE by
     * hand is a famous way to be quietly wrong about February. The
     * provider has already done it.
     *
     * Declined invitations are left out here rather than in [Agenda],
     * because whether somebody said no is a column and not arithmetic.
     */
    private fun query(context: Context, fromMs: Long, toMs: Long): List<Agenda.Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromMs.toString())
            .appendPath(toMs.toString())
            .build()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR
        )
        val out = ArrayList<Agenda.Event>()
        context.contentResolver.query(
            uri, columns,
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ?",
            arrayOf(CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED.toString()),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && out.size < MOST) {
                out += Agenda.Event(
                    id = cursor.getLong(0),
                    title = cursor.getString(1) ?: "",
                    startMs = cursor.getLong(2),
                    endMs = cursor.getLong(3),
                    allDay = cursor.getInt(4) != 0,
                    colour = cursor.getInt(5)
                )
            }
        }
        return out
    }

    /**
     * How many events one query will carry back.
     *
     * A guard on somebody else's data rather than on ours. A year window
     * over a shared work calendar is thousands of rows, and a dial can
     * draw about a dozen wedges before it stops being a dial — so the
     * limit costs nothing that could have been seen and stops a busy diary
     * from making a clock stutter.
     */
    const val MOST = 400
}

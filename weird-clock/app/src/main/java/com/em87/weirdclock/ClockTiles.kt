package com.em87.weirdclock

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceManager
import java.text.DateFormat
import java.util.Date

/**
 * Quick Settings tiles: the clock's business, said in the one place the user
 * is already looking when they pull the shade down.
 *
 * A tile is not a shortcut with an icon. It reports state — the label under
 * it is refreshed every time the shade opens — so the timer tile says how
 * long is left and the alarm tile says when the next one rings, without
 * anything having to be opened at all.
 */
@RequiresApi(24)
class TimerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // The running countdown's end is written down where the app and the
        // service both read it, so the tile can answer without either.
        val endsAt = prefs.getLong(Prefs.COUNTDOWN_ENDS_AT, 0L)
        val remaining = endsAt - android.os.SystemClock.elapsedRealtime()
        val running = endsAt > 0L && remaining > 0L
        tile.label = getString(R.string.tile_timer_label)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (running) shortWait(remaining) else getString(R.string.tile_none)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_timer)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        openApp(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TIMER, true))
    }

    private fun shortWait(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        return if (minutes >= 60) {
            String.format("%d h %02d", minutes / 60, minutes % 60)
        } else {
            String.format("%d:%02d", minutes, totalSeconds % 60)
        }
    }
}

/** The next alarm, and one tap to the card that owns it. */
@RequiresApi(24)
class AlarmTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val next = AlarmStore.all(this)
            .filter { it.enabled }
            .minOfOrNull { AlarmScheduler.nextOccurrence(it) }
        tile.label = getString(R.string.tile_alarm_label)
        tile.state = if (next != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = next?.let {
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
            } ?: getString(R.string.tile_none)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        openApp(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ALARMS, true))
    }
}

/**
 * Opening an activity from a tile goes through the platform on Android 14+,
 * which unlocks the device first and collapses the shade for us; the old way
 * is still the only one below that.
 */
@RequiresApi(24)
@android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
private fun TileService.openApp(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(
            android.app.PendingIntent.getActivity(
                this,
                8,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
        )
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}

package com.em87.weirdclock

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/**
 * What a chronograph pusher feels like under the thumb.
 *
 * The whole point of a chronograph is that you press it while looking at
 * the thing you are timing, not at the watch, so a pusher that gives
 * nothing back has to be watched to be trusted.
 *
 * It used to ask the view for haptic feedback: LONG_PRESS to start, and
 * VIRTUAL_KEY for everything else. On the phone this is tested on the first
 * is felt and the second is not — VIRTUAL_KEY is the little tick a keyboard
 * makes, a few milliseconds at whatever amplitude the system decides, and
 * through a case it is nothing at all. So starting buzzed and stopping
 * appeared not to work, which is exactly backwards: stopping is the press
 * you most need to be sure of, because it is the one that has to happen at
 * the right instant.
 *
 * Now each press has a shape of its own, played on the vibrator, so they
 * can be told apart without looking: one beat to start, two to stop, a
 * quick three to wipe it. Reset had no feel of any kind before, on either
 * dial, and the countdown's reset still has none anywhere in the code.
 */
object Pusher {

    enum class Feel {
        /** Away it goes: one firm beat. */
        START,

        /** And stop: two, so it is unmistakably not a start. */
        STOP,

        /** A lap recorded while it keeps running: one short tap. */
        LAP,

        /** Wiped: three quick beats, the most eventful of the four. */
        RESET
    }

    /**
     * Off, on, off, on… in milliseconds, as the vibrator wants it.
     *
     * Short on purpose. These fire under a thumb that is already pressing
     * something, and anything longer than a tenth of a second stops reading
     * as a button and starts reading as a notification.
     */
    fun pattern(feel: Feel): LongArray = when (feel) {
        Feel.START -> longArrayOf(0, 45)
        Feel.STOP -> longArrayOf(0, 30, 70, 30)
        Feel.LAP -> longArrayOf(0, 18)
        Feel.RESET -> longArrayOf(0, 16, 45, 16, 45, 16)
    }

    /**
     * Whether the phone has been asked for touch feedback at all.
     *
     * Going to the vibrator directly steps around the setting that
     * performHapticFeedback would have honoured, so it is honoured here
     * instead. An alarm may reasonably shake a phone that was told to keep
     * still; a stopwatch button may not.
     */
    fun wanted(context: Context): Boolean = try {
        Settings.System.getInt(
            context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1
        ) != 0
    } catch (e: Settings.SettingNotFoundException) {
        true
    }

    /** Plays [feel], if the phone is willing and has the hardware. */
    fun play(context: Context, feel: Feel) {
        if (!wanted(context)) return
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = pattern(feel)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}

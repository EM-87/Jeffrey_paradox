package com.em87.weirdclock

object Prefs {
    const val HOURS_PRESET = "pref_hours_preset"
    const val HOURS_CUSTOM = "pref_hours_custom"
    const val NUMERALS = "pref_numerals"
    const val MIRROR = "pref_mirror"
    const val THEME = "pref_theme"
    const val SHOW_DATE = "pref_show_date"
    const val DATE_FORMAT = "pref_date_format"

    /**
     * Day first or month first — see [DateShape].
     *
     * A different question from the one above, and the one people actually
     * come looking for: "date format" is how it is *spelled* (digits, words
     * or Roman numerals), and this is which of the two numbers means what.
     * They were one row, which is why the wrong one kept being opened.
     */
    const val DATE_ORDER = "pref_date_order"
    const val MOON_PHASE = "pref_moon_phase"

    /**
     * The solar system behind the sky token.
     *
     * Hangs off [MOON_PHASE] rather than standing alone, because the whole
     * gesture is a tap on the sun or the moon and there is nothing to tap
     * without one.
     */
    const val ORRERY = "pref_orrery"

    /**
     * The comets, drawn on the solar system.
     *
     * A sub-setting of [ORRERY] and off by default, because four long thin
     * ellipses cross every ring on the dial and somebody who turned the
     * planets on wanted planets. On, they are the only thing on this dial
     * drawn to a shape rather than to a diagram — see [Comets].
     */
    const val COMETS = "pref_comets"

    /**
     * Shadows under the hands, cast by the real sun — see [HandShadow].
     *
     * Off by default. It is a joke about the clock being an object lying
     * flat in the sun with twelve pointing north, and how long the shadows
     * get depends on how far up the sun climbs where you are, which is why
     * it wants the same coarse location fix the sunrise arithmetic uses.
     */
    const val HAND_SHADOWS = "pref_hand_shadows"

    /**
     * Set when the phone refused an exact alarm and the app had to fall
     * back to a one-minute window.
     *
     * Not a setting — a finding. Nothing writes it but [AlarmScheduler].
     */
    const val EXACT_DENIED = "pref_exact_denied"

    /** Whether the warning above has already been shown once. */
    const val EXACT_WARNED = "pref_exact_warned"
    const val ALARM_MARKERS = "pref_alarm_markers"
    const val MINUTE_HAND = "pref_minute_hand"
    const val SECOND_HAND = "pref_second_hand"
    const val SMOOTH_SECONDS = "pref_smooth_seconds"
    const val FAST_HAND = "pref_fast_hand"
    const val TOUCH_HANDS = "pref_touch_hands"
    const val PINCH_ZOOM = "pref_pinch_zoom"
    const val SHAKE_DROP = "pref_shake_drop"
    const val DIAL_SCALE = "pref_dial_scale"
    const val BELLS = "pref_bells"
    const val BELL_STYLE = "pref_bell_style"
    const val BELLS_BACKGROUND = "pref_bells_background"
    const val BELL_PRIORITY = "pref_bell_priority"
    /**
     * The old half-hour switch. Read only to work out what somebody
     * updating from a build that had it was already using; nothing writes
     * it any more.
     */
    const val HALF_HOUR = "pref_half_hour"

    /** How much of the hour the bells mark: the hour, the half, or quarters. */
    const val BELL_MARKS = "pref_bell_marks"
    const val TICKING = "pref_ticking"

    /**
     * How opaque the clock widget is drawn, as a percentage.
     *
     * On the home screen and not in the app's settings, because it is a
     * decision you make while looking at the wallpaper behind it.
     */
    const val WIDGET_ALPHA = "pref_widget_alpha"

    /**
     * The folder the user has given the app to keep restore points in, as
     * a persisted tree URI, or blank if nobody has been asked yet.
     */
    const val BACKUP_FOLDER = "pref_backup_folder"

    /** When the last restore point was written. */
    const val BACKUP_AT = "pref_backup_at"
    const val TEST_BELLS = "pref_test_bells"
    // Legacy single-alarm keys, kept only for migration to AlarmStore.
    const val ALARM_ENABLED = "pref_alarm_enabled"
    const val ALARM_TIME = "pref_alarm_time"
    const val ADVANCED = "pref_advanced"
    // The "put everything back" row and its two flags are gone: it is a
    // button on the dial now, opposite the gear, and a button on the dial
    // needs nothing written down to know whether to be there.
    const val TIME_SPEED = "pref_time_speed"
    const val SOLAR_TIME = "pref_solar_time"
    const val LAST_LONGITUDE = "pref_last_longitude"
    const val LAST_LATITUDE = "pref_last_latitude"

    /**
     * How alarm and event marks are coloured: [DayNight.MARKS_CLOCK] by the
     * dial's two turns, [DayNight.MARKS_SUN] by the real sun. A new key,
     * not the old boolean one — reading a stored boolean as a string throws.
     */
    const val MARK_COLORS = "pref_mark_colors"

    /** Whether the location prompt has already been shown once. */
    const val LOCATION_ASKED = "pref_location_asked"

    /**
     * The way in to the cycle sheet. Not a value — nothing is stored under
     * it; the record itself lives in [CycleStore] under its own key.
     */
    const val CYCLE = "pref_cycle"

    /** The user's own birthday as month * 100 + day; 0 for none. */
    const val BIRTHDAY = "pref_birthday"
    const val ALARM_RAMP = "pref_alarm_ramp"

    // The plain torch was briefly the app's answer and is each alarm's
    // again — see Alarm.flash. Only the wording of its row lives here now;
    // pref_alarm_flash itself is no longer read or written.

    /**
     * The torch that takes over when a gradual sunrise has failed.
     *
     * The app's answer and not each alarm's, which is the opposite way
     * round from the plain torch above — and deliberately. Whether *this*
     * morning should light the room is a thing about the morning; whether
     * a sleeper the light cannot reach wants the light turned up is a
     * thing about the sleeper, and the answer is the same every time.
     */
    const val GENTLE_FLASH = "pref_gentle_flash"

    /**
     * How many minutes an unattended alarm rings before giving up, or 0 for
     * never. Stored as a string because it comes from a ListPreference.
     */
    const val RING_TIMEOUT_MIN = "pref_ring_timeout"

    /** The hours the alarms card dims itself over. Whole hours, wrapping. */
    const val NIGHT_FROM = "pref_night_from"
    const val NIGHT_TO = "pref_night_to"

    /** The row those two hours are set on, one bar with a pin at each end. */
    const val NIGHT_WINDOW = "pref_night_window"

    // The mission and the gradual sunrise were app-wide settings for one
    // version, under pref_mission and pref_gentle_wake. They belong to an
    // alarm now, and the migration that would have carried the old values
    // across was taken out again: it turned every alarm anybody had into
    // one with a mission on it, which is not a thing to do to somebody's
    // alarms without being asked. The three keys are gone with it — a
    // constant nothing reads is a claim that something does.

    /**
     * When an unpassed mission is coming back, and how many times it
     * already has. Both cleared the moment it is dealt with.
     */
    const val NAG_AT = "pref_nag_at"
    const val NAG_ROUNDS = "pref_nag_rounds"

    /**
     * How many times one alarm may be snoozed, from the versions it was one
     * answer for the whole app. It belongs to an alarm now — the one that
     * has to be got up for and the one about the bread want different
     * answers — and this is read once, when an alarm from before the move
     * is loaded, so that a limit somebody had set does not quietly vanish.
     */
    const val SNOOZE_LIMIT = "pref_snooze_limit"

    const val WORLD_CLOCK = "pref_world_clock"
    // Legacy single-city key, kept only for migration to WORLD_TZS.
    const val WORLD_TZ = "pref_world_tz"
    const val WORLD_TZS = "pref_world_tzs"

    const val DIAL_SHAPE = "pref_dial_shape"
    const val ALARM_STYLE = "pref_alarm_style"

    const val ALARM_STYLE_ANALOG = "analog"
    const val ALARM_STYLE_DIGITAL = "digital"

    const val HOURS_CUSTOM_VALUE = "custom"

    const val SHAPE_CIRCLE = "circle"
    const val SHAPE_TRIANGLE = "triangle"
    const val SHAPE_SQUARE = "square"
    const val SHAPE_HEXAGON = "hexagon"
    const val SHAPE_OCTAGON = "octagon"

    const val NUMERALS_NONE = "none"
    const val NUMERALS_ARABIC = "arabic"
    const val NUMERALS_ROMAN = "roman"

    const val DATE_FORMAT_NUMBER = "number"
    const val DATE_FORMAT_TEXT = "text"
    const val DATE_FORMAT_ROMAN = "roman"

    const val FAST_HAND_NONE = "none"
    const val FAST_HAND_TENTHS = "tenths"
    const val FAST_HAND_DECIMAL_MINUTE = "decimal_minute"

    const val BELL_STYLE_COUNT = "count"
    const val BELL_STYLE_SHIPS = "ships"
    const val BELL_STYLE_SINGLE = "single"

    /** The hourly signal off a cheap digital watch, counting the hour. */
    const val BELL_STYLE_BEEP = "beep"

    const val ALARM_SOUND_BELLS = "bells"
    const val ALARM_SOUND_DIGITAL = "digital"
    const val ALARM_SOUND_BABY = "baby"

    /**
     * The zoo, all synthesised.
     *
     * Impressions rather than recordings, and deliberately so: a recording
     * carries a licence and arithmetic does not, and this app ships exactly
     * one sound file. The rattle is the nearest to the real animal and the
     * dog the furthest — see [ChimePlayer] for which is which and why.
     */
    const val ALARM_SOUND_ROOSTER = "rooster"
    const val ALARM_SOUND_SNAKE = "snake"
    const val ALARM_SOUND_WOLF = "wolf"
    const val ALARM_SOUND_DOG = "dog"

    /** The bell at the end of a round, which is what a timer running out is. */
    const val ALARM_SOUND_RING_BELL = "ringbell"

    /**
     * No sound at all: the torch and the vibration on their own.
     *
     * An alarm is not only a noise. Somebody who cannot hear one, or who
     * shares a room with somebody who should not have to, still wants the
     * light and the buzz — and until this the only way to get them was to
     * pick a sound and then turn the volume down, which is a different
     * thing from an alarm that is deliberately quiet.
     */
    const val ALARM_SOUND_SILENT = "silent"

    const val ALARM_SOUND_CUSTOM = "custom"

    /**
     * One of the phone's own ringtones, alarms or notification sounds.
     *
     * Different from [ALARM_SOUND_CUSTOM] only in where the URI came from:
     * a file you went and found, or one the phone already had. Both play
     * the same way, and [playsFromUri] is the question anything downstream
     * actually wants to ask.
     */
    const val ALARM_SOUND_SYSTEM = "system"

    /**
     * Whether this voice is a file somewhere rather than something the app
     * synthesises.
     *
     * Asked wherever a sound is played or judged. It was written out as
     * "== custom" in each of those places, which is exactly the shape of
     * thing that gets missed when a second one is added.
     */
    fun playsFromUri(sound: String?): Boolean =
        sound == ALARM_SOUND_CUSTOM || sound == ALARM_SOUND_SYSTEM

    /** Every sound an alarm can be given, in the order the picker shows them. */
    val ALARM_SOUNDS = listOf(
        ALARM_SOUND_BELLS, ALARM_SOUND_DIGITAL, ALARM_SOUND_RING_BELL,
        ALARM_SOUND_BABY, ALARM_SOUND_ROOSTER, ALARM_SOUND_SNAKE,
        ALARM_SOUND_WOLF, ALARM_SOUND_DOG, ALARM_SOUND_SILENT
    )

    // Legacy repeat keywords, still read when migrating old alarm stores.
    const val ALARM_REPEAT_DAILY = "daily"
    const val ALARM_REPEAT_WEEKDAYS = "weekdays"
    const val ALARM_REPEAT_WEEKENDS = "weekends"


    /** Written by CountdownService for MainActivity to consume on resume. */
    const val COUNTDOWN_RESULT = "pref_countdown_result"

    /** Where a countdown extended from the shade left off. */
    const val COUNTDOWN_ENDS_AT = "pref_countdown_ends_at"
    const val COUNTDOWN_TOTAL = "pref_countdown_total"
    const val COUNTDOWN_PERSISTENT = "pref_countdown_persistent"
    const val COUNTDOWN_BUBBLE = "pref_countdown_bubble"

    /** How a running countdown floats: not at all, or our own overlay. */
    const val COUNTDOWN_FLOAT = "pref_countdown_float"
    const val FLOAT_NONE = "none"
    const val FLOAT_OVERLAY = "overlay"

    /** Where the user last dragged the floating hourglass. */
    const val BUBBLE_X = "pref_bubble_x"
    const val BUBBLE_Y = "pref_bubble_y"
    /** One-time rewrite of day-less alarms when they stopped meaning "daily". */
    const val ONCE_MIGRATED = "pref_once_migrated"

    const val NIGHT_DIM = "pref_night_dim"
    const val WEEK_START_MONDAY = "pref_week_start_monday"
    const val PAST_DAYS = "pref_past_days"

    const val PAST_NONE = "none"
    const val PAST_DIM = "dim"
    const val PAST_CROSS = "cross"
    const val PAST_RING = "ring"

    /** True when the countdown was last driven from the dial (S1), not S0. */
    const val TIMER_ON_DIAL = "pref_timer_on_dial"

    /** Hours highlighted in the accent color, mirrored by the widget. */
    const val SELECTED_HOURS = "pref_selected_hours"
    const val OVERLAY_ASKED = "pref_overlay_asked"
}

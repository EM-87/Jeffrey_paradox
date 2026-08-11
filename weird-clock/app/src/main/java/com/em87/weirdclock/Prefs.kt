package com.em87.weirdclock

object Prefs {
    const val HOURS_PRESET = "pref_hours_preset"
    const val HOURS_CUSTOM = "pref_hours_custom"
    const val NUMERALS = "pref_numerals"
    const val MIRROR = "pref_mirror"
    const val THEME = "pref_theme"
    const val SHOW_DATE = "pref_show_date"
    const val DATE_FORMAT = "pref_date_format"
    const val MOON_PHASE = "pref_moon_phase"
    const val ALARM_MARKERS = "pref_alarm_markers"
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
    /**
     * The old half-hour switch. Read only to work out what somebody
     * updating from a build that had it was already using; nothing writes
     * it any more.
     */
    const val HALF_HOUR = "pref_half_hour"

    /** How much of the hour the bells mark: the hour, the half, or quarters. */
    const val BELL_MARKS = "pref_bell_marks"
    const val TICKING = "pref_ticking"
    const val TEST_BELLS = "pref_test_bells"
    // Legacy single-alarm keys, kept only for migration to AlarmStore.
    const val ALARM_ENABLED = "pref_alarm_enabled"
    const val ALARM_TIME = "pref_alarm_time"
    const val ADVANCED = "pref_advanced"
    const val REASSEMBLE = "pref_reassemble"
    const val REASSEMBLE_PENDING = "pref_reassemble_pending"
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

    /** The user's own birthday as month * 100 + day; 0 for none. */
    const val BIRTHDAY = "pref_birthday"
    const val ALARM_RAMP = "pref_alarm_ramp"

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

    /**
     * What has to be got right before the alarm will stop: nothing, a sum,
     * or shaking the phone.
     */
    const val MISSION = "pref_mission"

    /**
     * When an unpassed mission is coming back, and how many times it
     * already has. Both cleared the moment it is dealt with.
     */
    const val NAG_AT = "pref_nag_at"
    const val NAG_ROUNDS = "pref_nag_rounds"

    /** How many times one alarm may be snoozed before it insists. */
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
    const val ALARM_SOUND_CUSTOM = "custom"

    // Legacy repeat keywords, still read when migrating old alarm stores.
    const val ALARM_REPEAT_DAILY = "daily"
    const val ALARM_REPEAT_WEEKDAYS = "weekdays"
    const val ALARM_REPEAT_WEEKENDS = "weekends"

    const val NEEDS_REASSEMBLY = "pref_needs_reassembly"

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
    const val SAND_GRAINS = "pref_sand_grains"

    /** Hours highlighted in the accent color, mirrored by the widget. */
    const val SELECTED_HOURS = "pref_selected_hours"
    const val OVERLAY_ASKED = "pref_overlay_asked"
}

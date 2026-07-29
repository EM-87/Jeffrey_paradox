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
    const val HALF_HOUR = "pref_half_hour"
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
    const val ALARM_RAMP = "pref_alarm_ramp"
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

    /** How a running countdown floats: not at all, our overlay, or a bubble. */
    const val COUNTDOWN_FLOAT = "pref_countdown_float"
    const val FLOAT_NONE = "none"
    const val FLOAT_OVERLAY = "overlay"
    const val FLOAT_BUBBLE = "bubble"
    const val BUBBLE_X = "pref_bubble_x"
    const val BUBBLE_Y = "pref_bubble_y"
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

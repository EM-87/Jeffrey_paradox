package com.em87.weirdclock

object Prefs {
    const val HOURS_PRESET = "pref_hours_preset"
    const val HOURS_CUSTOM = "pref_hours_custom"
    const val NUMERALS = "pref_numerals"
    const val MIRROR = "pref_mirror"
    const val THEME = "pref_theme"
    const val SHOW_DATE = "pref_show_date"
    const val DATE_FORMAT = "pref_date_format"
    const val SECOND_HAND = "pref_second_hand"
    const val SMOOTH_SECONDS = "pref_smooth_seconds"
    const val FAST_HAND = "pref_fast_hand"
    const val TOUCH_HANDS = "pref_touch_hands"
    const val PINCH_ZOOM = "pref_pinch_zoom"
    const val SHAKE_DROP = "pref_shake_drop"
    const val DIAL_SCALE = "pref_dial_scale"
    const val BELLS = "pref_bells"
    const val BELL_STYLE = "pref_bell_style"
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
    const val WORLD_CLOCK = "pref_world_clock"
    const val WORLD_TZ = "pref_world_tz"

    const val HOURS_CUSTOM_VALUE = "custom"

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

    const val ALARM_REPEAT_DAILY = "daily"
    const val ALARM_REPEAT_WEEKDAYS = "weekdays"
    const val ALARM_REPEAT_WEEKENDS = "weekends"

    const val NEEDS_REASSEMBLY = "pref_needs_reassembly"

    /** Written by CountdownService for MainActivity to consume on resume. */
    const val COUNTDOWN_RESULT = "pref_countdown_result"
}

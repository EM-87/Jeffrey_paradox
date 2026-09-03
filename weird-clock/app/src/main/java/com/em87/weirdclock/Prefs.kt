package com.em87.weirdclock

object Prefs {
    /**
     * Which kind of clock this is — see [Face].
     *
     * The first question the app asks and, for most people, the last: it
     * decides what the other fifty settings are *about*. Not a switch but
     * an axis, because two more faces are already wanted.
     */
    const val FACE = "pref_face"

    /**
     * Whether the face has been chosen at all.
     *
     * Separate from [FACE] rather than read as "is it unset", because a
     * face that defaults to the dial is not the same fact as a face
     * somebody has picked. The first run has one question to ask and has
     * to know whether it has been answered.
     */
    const val FACE_ASKED = "pref_face_asked"

    /**
     * How the digits are made: lit segments, flip cards, or rollers.
     *
     * A digital clock has no hands to grab, and two of these three are
     * mechanisms you can put a finger on — a stack of cards is flicked and
     * a wheel is turned. That is where the gesture for setting a time
     * comes from; see [Prefs.DIGIT_STYLE]'s use in the setting rows.
     */
    const val DIGIT_STYLE = "pref_digit_style"
    const val DIGITS_SEGMENT = "segment"

    /**
     * The plainest one: the phone's own type, at whatever size fits.
     *
     * Everything else on this face is an object — bars behind a mask, a
     * card on a hinge, a drum in a window — and every one of them is a
     * machine somebody has to look at twice. This is the clock for
     * somebody who wants to know what time it is, and it belonged here
     * from the start.
     */
    const val DIGITS_PLAIN = "plain"
    const val DIGITS_CARD = "card"
    const val DIGITS_ROLLER = "roller"

    /** Rome and the calculator, on one panel — see [CometPanel]. */
    const val DIGITS_COMET = "comet"

    /**
     * The alphabet the numbers were written in, which is not a setting any
     * more — see [DigitScript].
     *
     * The key is still read, and only read: somebody who chose Rome has
     * the panel stored here rather than on the mechanism's own key, and
     * [DigitStyle.of] is what turns that back into the clock they had.
     */
    const val DIGIT_SCRIPT = "pref_digit_script"
    const val SCRIPT_ARABIC = "arabic"
    const val SCRIPT_ROMAN = "roman"
    const val SCRIPT_YAUTJA = "yautja"
    const val SCRIPT_COMET = "comet"

    /**
     * The two above, merged — see [DigitScript.ROMAN_COMET].
     *
     * The old two keys are kept rather than deleted, because they are
     * still written down in the settings of every phone that had either
     * of them chosen. [DigitScript.of] reads them and lands on this.
     */
    const val SCRIPT_ROMAN_COMET = "roman_comet"

    /** Twenty-four hours, or twelve with the sun and the moon beside them. */
    const val HOUR_24 = "pref_hour_24"

    /** Whether a single-figure hour is written with a nought in front. */
    const val LEADING_ZERO = "pref_leading_zero"

    /** Whether the colon blinks with the seconds, as a cheap clock's does. */
    const val BLINK_COLON = "pref_blink_colon"

    /**
     * How thick a bar is on the lit displays.
     *
     * A real display's segments are a stamped shape and the pen that
     * stamps them is a decision somebody made once. Three of them here,
     * because the difference between a hairline readout and a heavy one is
     * the difference between a laboratory instrument and an alarm clock.
     */
    const val SEGMENT_WEIGHT = "pref_segment_weight"
    const val WEIGHT_HAIRLINE = "hairline"
    const val WEIGHT_NORMAL = "normal"
    const val WEIGHT_HEAVY = "heavy"

    /**
     * Whether the unlit bars are drawn faintly behind the lit ones.
     *
     * On, because that ghost is the thing that says there is a mechanism
     * here rather than a picture of a number — and off for anybody who
     * wants the time and not the machine.
     */
    const val SEGMENT_GHOSTS = "pref_segment_ghosts"

    /**
     * Whether a bar can be poked out with a finger.
     *
     * Off. This app has always let you knock the hands off a dial and a
     * display with a dead segment is the same joke on the same object, but
     * somebody who chose digits chose them for clarity and a clock that
     * starts lying because a sleeve brushed it is not that.
     */
    const val POKE_SEGMENTS = "pref_poke_segments"

    const val HOURS_PRESET = "pref_hours_preset"
    const val HOURS_CUSTOM = "pref_hours_custom"
    const val NUMERALS = "pref_numerals"
    const val MIRROR = "pref_mirror"
    const val THEME = "pref_theme"
    const val SHOW_DATE = "pref_show_date"

    /**
     * The day of the week, beside the date.
     *
     * The one thing about today a number cannot tell you: 27/08 says
     * nothing until you have counted. Every digital clock ever built has
     * it and this one did not — see [Weekday], which also decides what
     * language it is in, because the three scripts are three different
     * claims about what kind of clock this is.
     */
    const val SHOW_WEEKDAY = "pref_show_weekday"

    /**
     * Whether a face made of digits counts seconds.
     *
     * Its own answer, and not the dial's. The two shared one for five
     * versions on the argument that the question was never about a hand —
     * it was about whether this clock counts that far — and that argument
     * is good until somebody owns both faces. Turning the seconds off on
     * a digital clock then took the second hand off the dial as well, and
     * the only row that did it was two screens deep on the *other* face,
     * called "Second hand". That is not one question asked twice, it is
     * two questions with one answer between them.
     */
    const val DIGITAL_SECONDS = "pref_digital_seconds"

    /**
     * Whether the blinking colon breathes instead of blinking.
     *
     * The same second, spent differently: a blink is a square wave and
     * this is a swell. It hangs off the blink rather than standing beside
     * it because it is not a third thing — it is how the blink is done.
     */
    const val COLON_BREATHES = "pref_colon_breathes"

    /**
     * How long one blink, or one breath, takes.
     *
     * Stored as milliseconds in a string, because it is a list and a list
     * of numbers is still a list. A second is what a cheap clock does and
     * is the default; two is a slow pulse that reads as breathing rather
     * than as a fault, which is what somebody asked for after living with
     * the one-second version for a version.
     */
    const val COLON_PERIOD = "pref_colon_period"

    /**
     * The next alarm, on the face.
     *
     * Standard equipment: a watch has a flag, a lock screen has the time
     * itself. What it answers is not "when is my alarm" — that is what the
     * alarm list is for — it is "did I actually set one", which is the
     * question somebody asks at midnight with the light off.
     */
    const val SHOW_NEXT_ALARM = "pref_next_alarm"

    /**
     * What survives when the clock takes the whole screen.
     *
     * A bedside clock is a clock and nothing else — that is what makes it
     * worth standing a phone on its side for — and a screen with the
     * seconds counting and a date under them is the card again, only
     * bigger. Both default to off, because "full screen" was asked for as
     * a way of getting rid of things.
     */
    const val BEDSIDE_SECONDS = "pref_bedside_seconds"
    const val BEDSIDE_DATE = "pref_bedside_date"

    /**
     * Whether this clock is allowed to ask what the weather is doing.
     *
     * Off, and it is the only setting in this app that turns a network on.
     * Everything else here works with the phone in a drawer; this one asks
     * three public forecast services about a location rounded to about a
     * kilometre, thirty minutes apart, and reads nothing back but numbers.
     *
     * A switch rather than a permission prompt because there is no prompt
     * to have — the internet is granted at install without asking — so the
     * only honest place to put the question is here, with the answer
     * defaulting to no. See [WeatherStore].
     */
    const val WEATHER = "pref_weather"

    /**
     * Whether the clock may look at the phone's own calendar — see
     * [AgendaStore].
     *
     * Off, and the only setting in this app behind a *permission* rather
     * than behind a switch alone. What is on the other side of it is a
     * list of where somebody will be and who with, so the rules are narrow
     * and stated: read only, never written down, never in a backup, and
     * nothing anywhere that could send one of these anywhere.
     */
    const val AGENDA = "pref_agenda"

    /**
     * Whether this clock is allowed to tell the rest of the house what it
     * is doing — see [Ifttt].
     *
     * Off, and the second setting here that turns a network on. Different
     * from the weather in the direction that matters: the weather *asks*
     * and this one *pushes*, so a mistake here happens in somebody's
     * living room rather than on their screen.
     */
    const val IFTTT = "pref_ifttt"

    /**
     * The webhook key, which is a secret and is kept out of the backup.
     *
     * A backup is a plain file in a folder somebody chose, and a key in it
     * is a key anybody who reads that file can fire the house with. So it
     * is on [Backup]'s skip list, and the settings row says out loud that
     * it will need typing again after a restore — a silent gap being the
     * one outcome worse than either.
     */
    const val IFTTT_KEY = "pref_ifttt_key"

    /** How many minutes before an alarm the house is told it is coming. */
    const val IFTTT_LEAD = "pref_ifttt_lead"

    /** Whether the home-screen clock widget carries the date. */
    const val WIDGET_DATE = "pref_widget_date"

    /** Whether the countdown widget writes its time in the phone's type. */
    const val WIDGET_SAND_PLAIN = "pref_widget_sand_plain"

    /**
     * Whether the hourglass widget is a bar of digits rather than sand.
     *
     * Its own answer and not the app's. It used to follow the face, so an
     * hourglass on the home screen turned into a progress bar because
     * somebody had changed the clock inside the app — a widget changing
     * shape for a reason nobody can see from the home screen.
     */
    val widgetHourglassDigits: String get() = WidgetKind.HOURGLASS.pref("digits")

    // ------------------------------------------------------- the sundial

    /**
     * Which of the three instruments the sundial is.
     *
     * Not a skin: a plate on the ground, a plate on a wall and a plate
     * parallel to the equator are three different objects with three
     * different formulae, and the one thing they have in common is the
     * style being parallel to the earth's axis. See [Sundial].
     */
    const val SUNDIAL_KIND = "pref_sundial_kind"

    /** And the shape of the stone, which is the only cosmetic choice. */
    const val SUNDIAL_PLATE = "pref_sundial_plate"

    /**
     * Whether the latitude comes from the phone or from the slider.
     *
     * It has to be settable by hand. A sundial without a latitude is a
     * fan of lines that means nothing, and a face that says "no fix" is a
     * face that does not work indoors, on a plane, or for anybody who
     * will not give a clock their location.
     */
    const val SUNDIAL_LATITUDE_FIXED = "pref_sundial_latitude_fixed"
    const val SUNDIAL_LATITUDE = "pref_sundial_latitude"

    /** Whether the phone is the dial, with an arrow to point it by. */
    const val SUNDIAL_COMPASS = "pref_sundial_compass"

    /** The Latin motto round the rim, and the half-hour marks. */
    const val SUNDIAL_MOTTO = "pref_sundial_motto"
    const val SUNDIAL_HALVES = "pref_sundial_halves"

    /** And whether the hours are cut in Roman numerals, as they were. */
    const val SUNDIAL_ROMAN = "pref_sundial_roman"

    /**
     * Whether the date under the plate is read in the Julian calendar.
     *
     * A switch for eleven versions and now one of three answers — see
     * [SUNDIAL_CALENDAR]. Kept rather than deleted, because it is still
     * written down on every phone that ever turned it on, and
     * [Sundial.Reckoning.of] is what reads it.
     */
    const val SUNDIAL_JULIAN = "pref_sundial_julian"

    /**
     * Which calendar the date under the plate is read in.
     *
     * Ours, Rome's, or Egypt's. Not one of the three is offered as a
     * better calendar: the Julian is what the stone would have been cut
     * under, and the Egyptian civil year — twelve months of thirty days in
     * three seasons, with five days over — is the one Ptolemy and
     * Copernicus both did their arithmetic in, fifteen hundred years
     * apart, because a year that never changes length is a year you can
     * subtract. See [EgyptianCalendar].
     */
    const val SUNDIAL_CALENDAR = "pref_sundial_calendar"

    /**
     * Whether the weather glass and the thermometer stand under the plate.
     *
     * A garden dial was rarely on its own: the pedestal carried these two
     * as well, because between them the three answered the three questions
     * somebody stepping outside actually had. On by default and dead
     * anyway until the weather switch is on, since there is nothing for a
     * needle to point at until then — see [WeatherGlass].
     */
    const val SUNDIAL_GLASS = "pref_sundial_glass"

    /**
     * Whether the home-screen sundial hangs on a wall or lies on the
     * ground.
     *
     * The two projections, and only those: a widget is a picture and the
     * dial you point at the sun is a thing you hold. Its own key rather
     * than the app's, because the plate in the garden and the plate on
     * the home screen are two different objects and there is no reason
     * they should be the same one.
     */
    const val WIDGET_SUNDIAL_WALL = "pref_widget_sundial_wall"

    // ---------------------------------------------------- the hemisphere

    /** Which pole the world is being looked at over, or whether it is a ball. */
    const val HEMISPHERE_VIEW = "pref_hemisphere_view"

    /**
     * Where the sun is pinned, in degrees anticlockwise from the right.
     *
     * The right is where it belongs and is the default: the world is read
     * left to right, so a sun on the right is the afternoon side and the
     * dot climbs towards it all morning. It is a preference because
     * somebody who wants noon at the top is not wrong, they are just
     * thinking of a clock.
     */
    const val HEMISPHERE_SUN_AT = "pref_hemisphere_sun_at"

    /**
     * Whether the phone points at the sun instead of the setting above.
     *
     * The other way of using this face. Nailed down, the sun sits where
     * the row above puts it and the dot is read against it. Pointed, the
     * phone is held flat and turned until the picture agrees with the sky
     * — and then the lit half of the world is the half the light is
     * really coming from, and the dot is standing where you are standing,
     * facing the way you are facing. See [Hemisphere.sunAtFrom].
     */
    const val HEMISPHERE_COMPASS = "pref_hemisphere_compass"

    /** The ring of hours round the world, and the numbers on it. */
    const val HEMISPHERE_RING = "pref_hemisphere_ring"
    const val HEMISPHERE_NUMBERS = "pref_hemisphere_numbers"

    /**
     * The two lights on the rim, and what is armed.
     *
     * The sun's switch is not a cosmetic one: it is the only thing drawn
     * outside the world, so turning it off is what lets the world open to
     * the edge of the screen.
     */
    const val HEMISPHERE_SUN = "pref_hemisphere_sun"
    const val HEMISPHERE_MOON = "pref_hemisphere_moon"
    const val HEMISPHERE_ALARMS = "pref_hemisphere_alarms"

    /**
     * Whether the globe wears the satellite's clouds.
     *
     * NASA publish one complete true-colour photograph of the earth a day,
     * free and keyless, and this is the switch that puts it on the world.
     * On by default and dead until the weather switch is on, because it is
     * the largest thing this app ever downloads — see [CloudStore].
     */
    const val HEMISPHERE_CLOUDS = "pref_hemisphere_clouds"
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

    /**
     * Whether the sky token tracks the moon's phase.
     *
     * It used to be the master switch of the whole sky, with the solar
     * system hung off it, on the reasoning that the glyph is the door and
     * there is nothing to tap without one. True, and the wrong way round:
     * nobody turns on a moon glyph in order to reach the planets. So
     * [ORRERY] is the switch now and it puts the door there itself; this
     * stays for the person who wants the moon on the dial and no planets
     * behind it.
     */
    const val MOON_PHASE = "pref_moon_phase"

    /**
     * The sky: the token on the dial, and the solar system behind it.
     *
     * One switch for the lot. It draws its own door, so it no longer
     * depends on the moon complication being on.
     */
    const val ORRERY = "pref_orrery"

    /**
     * The twelve signs, written round the year ring beside the months.
     *
     * A sub-setting of [ORRERY], and one that knows what century it is in:
     * the signs are a Babylonian invention and the ring can be wound back
     * past them, where drawing them would be the dial claiming something
     * about the sky that nobody had thought of yet.
     */
    const val ZODIAC = "pref_zodiac"

    /**
     * How the month page writes its days.
     *
     * Its own answer rather than the dial's. They were one setting, which
     * is two different questions sharing a row: Roman numerals are a fine
     * thing to have on a clock face, and a grid of thirty-one of them is a
     * puzzle rather than a calendar.
     */
    const val CALENDAR_NUMERALS = "pref_calendar_numerals"

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
     * Which surface the clock's shadow falls on.
     *
     * The whole engine rests on a conceit about where the clock is, and
     * until now there was only one: lying flat on the ground with twelve
     * pointing north. A wall clock is the other half of the world's
     * clocks and casts a quite different shadow — the light comes at the
     * face rather than across it, and the sun going behind the wall puts
     * the shadow out altogether.
     */
    const val SHADOW_SURFACE = "pref_shadow_surface"
    const val SHADOW_GROUND = "ground"
    const val SHADOW_WALL = "wall"

    /**
     * How many marks the dial carries, and whether the minute ticks are
     * among them.
     *
     * Twelve, six, four, none. The middle rung was asked for as eight and
     * is six, because eight marks do not fall on the hours of a twelve-hour
     * dial: twelve over eight is one and a half, so a mark would have to
     * land halfway between two numerals, and the mark would then be
     * pointing at nothing. Six is the next count down that still divides
     * twelve — and it divides twenty-four as well, so the ladder is exact
     * on both faces the app draws.
     */
    const val DIAL_MARKS = "pref_dial_marks"
    const val MARKS_12 = "12"
    const val MARKS_6 = "6"
    const val MARKS_4 = "4"
    const val MARKS_NONE = "none"
    const val MINUTE_MARKS = "pref_minute_marks"

    /** Whether the little world clocks carry second hands of their own. */
    const val WORLD_SECONDS = "pref_world_seconds"

    /**
     * How opaque the clock widget is drawn, as a percentage.
     *
     * On the home screen and not in the app's settings, because it is a
     * decision you make while looking at the wallpaper behind it.
     */
    const val WIDGET_ALPHA = "pref_widget_alpha"

    /**
     * And one for each of the other two widgets.
     *
     * Their own keys rather than the clock's, so that the solar system can
     * be a ghost on the home screen while the clock beside it is solid.
     * Three sliders moving one number would be three lies.
     */
    const val WIDGET_ALPHA_ORRERY = "pref_widget_alpha_orrery"
    const val WIDGET_ALPHA_HOURGLASS = "pref_widget_alpha_hourglass"

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
    /** The row that opens the city picker. */
    const val WORLD_CITIES = "pref_world_cities"
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

package com.em87.weirdclock

/**
 * Which kind of clock this is.
 *
 * Not a skin. A face is a set of capabilities, and everything else follows
 * from what the object physically is: a sundial cannot time a lap, cannot
 * wake you and does not work at night; a globe's natural companion is the
 * sky rather than a grid of days; a digital clock has no dial, so it has
 * nowhere to put a sun to press and no hands to knock off.
 *
 * That is why this is an axis and not a switch. Two more faces are already
 * wanted — the sundial, which would use the shadow engine that is already
 * here, and a lit hemisphere — and the expensive mistake would be to write
 * "is it digital" through the app and have to unpick it when the third one
 * arrives. Each face says what it has; the menu, the cards and the gestures
 * are read off that.
 *
 * Chosen once, at the first run, and rarely changed. Somebody who finds an
 * analogue dial hard work sets it to digital and never opens the settings
 * again — which is the whole reason the choice comes first.
 */
enum class Face(val key: String) {

    /** The dial: hands, a case, and everything this app was built for. */
    ANALOG("analog"),

    /**
     * Digits, full screen.
     *
     * It loses the hourglass, which is an analogue instrument; the solar
     * system, because the glyph you press to open it lives on a dial; and
     * everything about hands, cases and marks. What it keeps is what a
     * person who chose it came for: the time, large, and an alarm.
     */
    DIGITAL("digital"),

    /**
     * A shadow on a plate, which is the oldest clock there is.
     *
     * It loses more than any other face and every loss is the object
     * being honest. There is no alarm, because a shadow cannot wake you;
     * no stopwatch and no countdown, because a sundial has no moving part
     * to start and stop; and after sunset there is no time at all, which
     * is not a fault to be worked around but the whole reason somebody
     * chooses it.
     *
     * What it keeps is what an instrument of its age would have had
     * beside it: sand in a glass for measuring an interval, and a
     * calendar — the two things a sundial genuinely cannot do and that a
     * Roman would have had on the same table.
     */
    SUNDIAL("sundial"),

    /**
     * The earth itself, turning under a nailed-down sun.
     *
     * There is no dial and there are no hands: the sun is fixed to one
     * side of the screen, the world turns beneath it once a day, and the
     * red dot where you are standing is the hand. That is not a metaphor
     * — a clock has always been a model of exactly this, and reading it
     * is reading your own longitude against the sun.
     *
     * Its owner is somewhere between the two others: not the person who
     * wants the time in the largest possible numbers, and not the one who
     * came for a toy that stops working at dusk. So it keeps the alarm
     * and both chronographs — in digits, since there is no dial to put
     * hands on — loses the sand, which is one instrument too many on a
     * face about the planet, and swaps the calendar for the solar system,
     * which is the same picture one step further out.
     */
    HEMISPHERE("hemisphere");

    /**
     * Whether the clock card is a dial with hands on it.
     *
     * A capability rather than "is it the analogue one", because it is
     * about to have more than one false answer: a screenful of digits has
     * no hands, and neither has a lit hemisphere, and the sundial has a
     * shadow where the hands would be. Everything that only makes sense
     * against a moving hand — knocking them off, grabbing one, carrying
     * them from one card to the next — asks this.
     */
    val hands: Boolean
        get() = this == ANALOG

    /**
     * Whether this face is worth turning the phone sideways for.
     *
     * A screenful of digits gets bigger when the screen does; anything
     * round is a circle in a rectangular window and gains nothing from
     * the extra width but a wider margin. This was written expecting the
     * hemisphere to answer yes and it does not, for exactly that reason —
     * a globe is as round as a dial. It stays a capability rather than
     * "is it digital" because the thing it is really asking is whether
     * this face is bounded by the shorter side of the screen.
     */
    val fills: Boolean
        get() = this == DIGITAL

    /** Which cards this face has. A card it cannot support is not there. */
    val cards: Set<Card>
        get() = when (this) {
            ANALOG -> Card.entries.toSet()
            // The hourglass is sand in a glass. There is no digital one.
            DIGITAL -> Card.entries.toSet() - Card.HOURGLASS
            // A shadow cannot wake you and has nothing to start or stop.
            // Sand and a calendar are exactly the two things a sundial
            // cannot do and that its owner would have had anyway.
            SUNDIAL -> setOf(Card.CLOCK, Card.CALENDAR, Card.HOURGLASS)
            // Sand in a glass is an instrument too many beside a turning
            // planet, and the calendar's card holds the solar system here
            // instead — see [showsOrrery].
            HEMISPHERE -> Card.entries.toSet() - Card.HOURGLASS
        }

    /**
     * Whether the calendar's card holds the solar system instead.
     *
     * A grid of days is the wrong neighbour for a face that is already a
     * picture of where the earth is. One step further out is the rest of
     * the family, which is the same idea and the thing somebody looking
     * at a lit hemisphere would want next.
     */
    val showsOrrery: Boolean
        get() = this == HEMISPHERE

    /**
     * Whether there is a month page behind the calendar's button.
     *
     * Not the same as having the card: the turning world has the card and
     * puts the solar system on it, so every row about how a grid of days
     * is drawn — its numerals, its crossed-off days, a birthday, a cycle
     * — is a row about something that is not there.
     */
    val hasCalendar: Boolean
        get() = Card.CALENDAR in cards && !showsOrrery

    /**
     * Whether this face can show the time in other cities.
     *
     * Two faces can and they do it differently — bubbles thrown about a
     * dial, a ladder under the digits — and the other two simply cannot:
     * there is nowhere on a plate or a planet to put five more clocks,
     * and neither of them tried. The rows were on both menus anyway.
     */
    val showsOtherCities: Boolean
        get() = this == ANALOG || this == DIGITAL

    /**
     * Whether this face prints the date anywhere.
     *
     * Three do — under the hands, under the time, cut into the plate. The
     * turning world does not, and a switch offering one was a switch that
     * did nothing at all.
     */
    val showsDate: Boolean
        get() = this != HEMISPHERE

    /**
     * Whether any part of this face is a time written in bars.
     *
     * True of the digits, obviously. It is also true of the turning
     * world, and that is the point of asking it this way: a face with no
     * hands cannot put a dial on an alarm card or a movement inside a
     * chronograph, so both of those become readouts — which makes "which
     * numerals" and "how are they made" real questions on a face that is
     * not itself made of digits. They were hidden there, and the answers
     * went on being used.
     */
    val readsOutInDigits: Boolean
        get() = !hands && cards.any {
            it == Card.ALARM || it == Card.STOPWATCH || it == Card.REVERSE
        }

    /**
     * Whether anything on this face counts seconds.
     *
     * Three do: a second hand, a readout with seconds on it, a
     * chronograph with a screen in it. A shadow does not, and neither
     * does anything else on that face — so a switch marked "second hand"
     * and a mechanical tick under it were sitting on a sundial's menu,
     * under a heading that had already been carefully renamed to "the
     * plate". A picture of the page found it; reading the table did not.
     */
    val countsSeconds: Boolean
        get() = this != SUNDIAL

    /**
     * Whether this clock only works while the sun is up.
     *
     * One face answers yes, and it is not a defect: an instrument that
     * stops at sunset is what somebody chooses when they are not choosing
     * a clock to be woken by. Everything that would ring, count or wake
     * asks this before it offers itself.
     */
    val daylightOnly: Boolean
        get() = this == SUNDIAL

    companion object {

        /** The face stored under [Prefs.FACE], or the dial if none is. */
        fun of(prefs: android.content.SharedPreferences): Face {
            val key = prefs.getString(Prefs.FACE, null)
            return entries.firstOrNull { it.key == key } ?: ANALOG
        }
    }
}

/**
 * Which settings belong to which face.
 *
 * One table, and the default is "both". That is the important half: a
 * setting is common unless somebody says otherwise, so adding a face means
 * revisiting this file and no other — rather than three lists of rows that
 * can quietly disagree with each other and with the screens they describe.
 *
 * The rule for putting a key in here is not "does it look dial-shaped". It
 * is whether the question still *means* anything on the other face. There
 * is no minute hand to switch off on a digital clock, so that row is a row
 * about nothing; but whether the seconds are shown is a real question on
 * both, so it stays common and only its name changes.
 */
object FaceOptions {

    /**
     * The rows only a dial can answer, because they are about a dial:
     * hands, a case, marks round a rim, and the sky behind it.
     */
    private val analogOnly = setOf(
        Prefs.NUMERALS,
        Prefs.DIAL_SHAPE,
        Prefs.HOURS_PRESET,
        Prefs.HOURS_CUSTOM,
        Prefs.MIRROR,
        Prefs.DIAL_MARKS,
        Prefs.MINUTE_MARKS,
        Prefs.HAND_SHADOWS,
        Prefs.SHADOW_SURFACE,
        Prefs.MINUTE_HAND,
        Prefs.SMOOTH_SECONDS,
        Prefs.FAST_HAND,
        Prefs.TOUCH_HANDS,
        Prefs.PINCH_ZOOM,
        Prefs.SHAKE_DROP,
        Prefs.ORRERY,
        Prefs.MOON_PHASE,
        Prefs.COMETS,
        Prefs.ZODIAC,
        Prefs.ALARM_MARKERS,
        Prefs.MARK_COLORS,
        Prefs.ALARM_STYLE,
        // Six sweeping hands on six bubbles, on a face with no hands.
        // The bubbles themselves are readouts now — see [WorldBubbles] —
        // and a readout has no second hand to switch off, so this one row
        // goes and the world clock stays.
        Prefs.WORLD_SECONDS,
        // How the date is spelled. On a screenful of digits that question
        // is already answered by which numerals the digits are — a display
        // made of bars cannot write "August" whatever this row says — so
        // leaving it on the page is leaving a row that does nothing.
        // Which way round the day and the month go is still a real
        // question and stays.
        Prefs.DATE_FORMAT
    )

    /** And the rows only a screenful of digits has. */
    private val digitalOnly = setOf(
        Prefs.DIGITAL_SECONDS,
        Prefs.COLON_BREATHES,
        Prefs.LEADING_ZERO,
        Prefs.BLINK_COLON,
        Prefs.SEGMENT_WEIGHT,
        Prefs.POKE_SEGMENTS,
        // Both of these are rows about a face made of digits. The dial
        // writes its date as a caption under the hands, in a strip with
        // no room for a day beside it, and says what is armed with a
        // marker on the rim rather than with a line of text.
        Prefs.SHOW_WEEKDAY,
        Prefs.SHOW_NEXT_ALARM,
        // And what the clock keeps when it takes the whole screen, which
        // is a question only a face that fills one can be asked.
        Prefs.BEDSIDE_SECONDS,
        Prefs.BEDSIDE_DATE
    )

    /**
     * The rows about how a number is written, wherever one is written.
     *
     * These four were on the digits' own list, which was right when the
     * digits were the only face that drew one. They are not: the world's
     * alarm list is a column of little segment displays and its
     * chronographs have screens where the movement would be, all of them
     * drawn from exactly these settings — which were unreachable on that
     * face while being obeyed on it.
     *
     * The four that stayed behind are the ones only the big readout has:
     * a leading nought, a blinking colon, how thick the bars are, and
     * whether a finger can poke one out. Nothing on an alarm card reads
     * any of them.
     */
    private val readouts = setOf(
        Prefs.DIGIT_STYLE,
        Prefs.DIGIT_SCRIPT,
        Prefs.HOUR_24,
        Prefs.SEGMENT_GHOSTS
    )

    /**
     * The rows about a grid of days, on the faces that have one.
     *
     * The turning world has the calendar's card and the solar system on
     * it, so all four of these were describing a page that face does not
     * open.
     */
    private val needsACalendar = setOf(
        Prefs.BIRTHDAY,
        Prefs.CYCLE,
        Prefs.CALENDAR_NUMERALS,
        Prefs.PAST_DAYS
    )

    /**
     * And the rows about the time somewhere else.
     *
     * A dial throws them about as bubbles and a screenful of digits
     * stacks them under the time. A plate and a planet do neither, and
     * both rows sat on both menus doing nothing — on the world's menu
     * against its owner's explicit "no world clock here".
     */
    private val needsOtherCities = setOf(
        Prefs.WORLD_CLOCK,
        Prefs.WORLD_CITIES
    )

    /** And the rows only a shadow on a plate has. */
    private val sundialOnly = setOf(
        Prefs.SUNDIAL_KIND,
        Prefs.SUNDIAL_PLATE,
        Prefs.SUNDIAL_LATITUDE_FIXED,
        Prefs.SUNDIAL_LATITUDE,
        Prefs.SUNDIAL_COMPASS,
        Prefs.SUNDIAL_MOTTO,
        Prefs.SUNDIAL_HALVES,
        Prefs.SUNDIAL_ROMAN,
        Prefs.SUNDIAL_CALENDAR,
        Prefs.SUNDIAL_GLASS
    )

    /**
     * The rows a face without an alarm has nothing to say about.
     *
     * Not "analogue only" and not "digital only" — these are rows about a
     * thing that rings, on the one face that cannot. A shadow does not
     * wake anybody, so how loudly it does so, how long it goes on for and
     * whether it flashes the torch are three questions about nothing.
     *
     * Kept as its own list rather than folded into the two above, because
     * what it is about is the *card* being missing, and the next face to
     * lose a card will lose a different one.
     */
    private val needsAnAlarm = setOf(
        Prefs.ALARM_RAMP,
        Prefs.RING_TIMEOUT_MIN,
        Prefs.GENTLE_FLASH,
        Prefs.COUNTDOWN_PERSISTENT
    )

    /** And the rows only the turning world has. */
    private val hemisphereOnly = setOf(
        Prefs.HEMISPHERE_VIEW,
        Prefs.HEMISPHERE_SUN_AT,
        Prefs.HEMISPHERE_COMPASS,
        Prefs.HEMISPHERE_RING,
        Prefs.HEMISPHERE_NUMBERS,
        Prefs.HEMISPHERE_MERIDIANS,
        Prefs.HEMISPHERE_CLOUDS
    )

    /** Whether the row at [key] belongs on [face]'s screens. */
    fun shows(face: Face, key: String): Boolean = when {
        key in analogOnly -> face == Face.ANALOG
        key in digitalOnly -> face == Face.DIGITAL
        key in sundialOnly -> face == Face.SUNDIAL
        key in hemisphereOnly -> face == Face.HEMISPHERE
        key in needsAnAlarm -> Card.ALARM in face.cards
        key in readouts -> face.readsOutInDigits
        key in needsACalendar -> face.hasCalendar
        key in needsOtherCities -> face.showsOtherCities
        key == Prefs.SHOW_DATE -> face.showsDate
        // A second hand and a tick are two facts about a *mechanism*, and
        // three of the four faces have not got one. They were shown
        // wherever anything counted seconds, which put a row called
        // "second hand" on a turning planet and made a screenful of
        // digits tick like an escapement. The digits keep their own
        // seconds row — see [Prefs.DIGITAL_SECONDS] — and a shadow and a
        // planet keep nothing, because nothing on either of them ticks.
        key == Prefs.SECOND_HAND || key == Prefs.TICKING -> face.hands
        else -> true
    }

    /**
     * The rows that ask the same question on both faces under different
     * names, and what each face calls them.
     *
     * One stored answer, two names. Somebody who has turned the second hand
     * off and then changes face finds the seconds already gone, which is
     * what they asked for — the question was never about a hand, it was
     * about whether this clock counts that far.
     */
    fun titleFor(face: Face, key: String): Int? = when {
        // The two rows about the *readouts* on a face that is not made of
        // them. On the turning world these say how the little screens on
        // its alarm cards and its chronographs are drawn, which is a true
        // thing about that face and reads, under the digits' own names,
        // like the digital clock's menu turned up on the wrong page.
        key == Prefs.DIGIT_STYLE && face == Face.HEMISPHERE ->
            R.string.pref_readout_style_title
        key == Prefs.DIGIT_SCRIPT && face == Face.HEMISPHERE ->
            R.string.pref_readout_script_title
        // Headings too. "Dial" over three rows that still apply is the app
        // not having noticed which clock it is.
        (key == CAT_DIAL || key == CAT_DIAL_DEEP) && face == Face.DIGITAL ->
            R.string.category_screen
        // Two headings on this face and they had swapped jobs. "The
        // plate" stood over the date's order and the night hours, which
        // are not the plate; "Digits" stood over ten rows about a stone,
        // a style, its numerals and two brass instruments, which are not
        // digits. Only one of the two headings had ever been checked, so
        // both went unnoticed for as long as the face has existed.
        (key == CAT_DIAL || key == CAT_DIAL_DEEP) && face == Face.SUNDIAL ->
            R.string.category_reading
        (key == CAT_DIAL || key == CAT_DIAL_DEEP) && face == Face.HEMISPHERE ->
            R.string.category_world
        // These four rows survive onto the world's page because they are
        // obeyed there, but not as the face: they are the alarm list and
        // the two chronographs, which have screens where their movements
        // would be. "Digits" over them on a face made of planet reads as
        // a heading about the planet.
        key == CAT_DIGITS && face == Face.HEMISPHERE -> R.string.category_readouts
        key == CAT_DIGITS && face == Face.SUNDIAL -> R.string.category_plate
        else -> null
    }

    /**
     * And the rows whose *explanation* is about a dial, on a clock with
     * no dial.
     *
     * Found by looking at the page rather than by reading the table. The
     * keys were right, the titles were right, and three summaries under
     * them said "the dial dims", "under the centre of the dial" and "mini
     * dials showing the time in other cities" — on a clock that has not
     * got one. A row whose title is about you and whose subtitle is about
     * somebody else is worse than a row that is simply missing.
     */
    fun summaryFor(face: Face, key: String): Int? {
        if (face == Face.SUNDIAL) {
            return when (key) {
                // The sun does not dim at ten at night; it sets. And the
                // date under a sundial is cut into the plate, not printed
                // under a set of hands.
                Prefs.NIGHT_DIM -> R.string.pref_night_dim_summary_sundial
                Prefs.SHOW_DATE -> R.string.pref_show_date_summary_sundial
                "pref_advanced" -> R.string.pref_advanced_summary_sundial
                else -> null
            }
        }
        if (face == Face.HEMISPHERE) {
            return when (key) {
                // Neither of these has a dial to talk about, and the door
                // to the second screen does not open onto dial shapes.
                Prefs.NIGHT_DIM -> R.string.pref_night_dim_summary_world
                "pref_advanced" -> R.string.pref_advanced_summary_world
                else -> null
            }
        }
        if (face != Face.DIGITAL) return null
        return when (key) {
            Prefs.NIGHT_DIM -> R.string.pref_night_dim_summary_digital
            Prefs.SHOW_DATE -> R.string.pref_show_date_summary_digital
            Prefs.WORLD_CLOCK -> R.string.pref_world_clock_summary_digital
            // The door to the second screen says what is behind it, and
            // what is behind it is not the same on the two faces.
            "pref_advanced" -> R.string.pref_advanced_summary_digital
            // Nothing here for the rows that show their own value: the
            // preference library refuses a summary on those, and the value
            // is what the reader wanted anyway.
            else -> null
        }
    }

    /**
     * The headings whose rows outlive the dial they were named for.
     *
     * Two of them, on two screens. The third was found by a test that
     * reads every row of every built page looking for the word — which is
     * how a heading with no key at all, on the screen nobody scrolls to
     * the bottom of, turned up still saying Dial.
     */
    const val CAT_DIAL = "cat_dial"
    const val CAT_DIAL_DEEP = "cat_dial_deep"

    /** And the one over the rows about how a number is written. */
    const val CAT_DIGITS = "cat_digits"

}

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
    DIGITAL("digital");

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
     * A screenful of digits gets bigger when the screen does; a dial is a
     * round thing in a rectangular window and gains nothing but a wider
     * margin. That is why this is a capability and not "is it digital":
     * the lit hemisphere that is wanted next fills a landscape window as
     * happily as the digits do, and the sundial does not.
     */
    val fills: Boolean
        get() = this == DIGITAL

    /** Which cards this face has. A card it cannot support is not there. */
    val cards: Set<Card>
        get() = when (this) {
            ANALOG -> Card.entries.toSet()
            // The hourglass is sand in a glass. There is no digital one.
            DIGITAL -> Card.entries.toSet() - Card.HOURGLASS
        }

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
        Prefs.DIGIT_STYLE,
        Prefs.DIGIT_SCRIPT,
        Prefs.HOUR_24,
        Prefs.LEADING_ZERO,
        Prefs.BLINK_COLON,
        Prefs.SEGMENT_WEIGHT,
        Prefs.SEGMENT_GHOSTS,
        Prefs.POKE_SEGMENTS,
        // Both of these are rows about a face made of digits. The dial
        // writes its date as a caption under the hands, in a strip with
        // no room for a day beside it, and says what is armed with a
        // marker on the rim rather than with a line of text.
        Prefs.SHOW_WEEKDAY,
        Prefs.SHOW_NEXT_ALARM
    )

    /** Whether the row at [key] belongs on [face]'s screens. */
    fun shows(face: Face, key: String): Boolean = when {
        key in analogOnly -> face == Face.ANALOG
        key in digitalOnly -> face == Face.DIGITAL
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
        key == Prefs.SECOND_HAND && face == Face.DIGITAL -> R.string.pref_seconds_title
        key == Prefs.TICKING && face == Face.DIGITAL -> R.string.pref_ticking_digital_title
        // Headings too. "Dial" over three rows that still apply is the app
        // not having noticed which clock it is.
        (key == CAT_DIAL || key == CAT_DIAL_DEEP) && face == Face.DIGITAL ->
            R.string.category_screen
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
}

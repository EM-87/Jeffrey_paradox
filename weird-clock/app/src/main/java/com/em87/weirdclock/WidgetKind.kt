package com.em87.weirdclock

/**
 * The widgets this app puts in a launcher's list, and what each one keeps.
 *
 * There is one clock in the app and a home screen is not a settings page,
 * so there are several widgets: somebody who wants the sundial beside the
 * digital clock should be able to drop both. That much existed. What did
 * not was any way to tell them apart or to set one of them: they shared a
 * name, shared an icon, shared one transparency slider, and two of them
 * were called things close enough to each other — "Solar" and "Solar
 * system" — that their owner reasonably took them for the same widget
 * twice.
 *
 * So each kind is a thing here, with its own name, its own icon and its
 * own answers. The answers are kept per kind rather than per widget, and
 * that is a deliberate line rather than an oversight: two dials dropped on
 * the same home screen are the same instrument at two sizes, and a
 * settings screen that made them differ would be a settings screen with
 * every question in it asked twice.
 */
enum class WidgetKind(val key: String, val pinned: Face?) {

    /**
     * The original, which draws whichever clock the app is set to.
     *
     * It is what is on people's home screens today, so it goes on doing
     * exactly that. Everything else on this list is pinned.
     */
    FOLLOWING("app", null),

    DIAL("dial", Face.ANALOG),
    DIGITS("digits", Face.DIGITAL),
    SUNDIAL("sundial", Face.SUNDIAL),
    GLOBE("globe", Face.HEMISPHERE),

    /** The solar system, which is a card rather than a face. */
    ORRERY("orrery", null),

    /** The countdown, which is a card rather than a face too. */
    HOURGLASS("hourglass", null),

    /** And the weather on its own, which was not a widget at all. */
    WEATHER("weather", null);

    /** The key one of this kind's own settings is stored under. */
    fun pref(name: String): String = "pref_widget_${key}_$name"

    /**
     * Where its transparency lives.
     *
     * Its own, which it was not: five of these shared one key, so making
     * the globe a ghost made a ghost of the dial beside it and of the
     * digits beside that. Only the two that already had a key of their own
     * keep it, because somebody's half-faded solar system is not a thing
     * to reset for the sake of a naming scheme — and the rest fall back to
     * the shared one the first time they are asked, so nothing changes on
     * anybody's home screen until they move a slider.
     */
    val alphaKey: String
        get() = when (this) {
            ORRERY -> Prefs.WIDGET_ALPHA_ORRERY
            HOURGLASS -> Prefs.WIDGET_ALPHA_HOURGLASS
            else -> pref("alpha")
        }

    /** And the key it inherits from, once, if it has never been set. */
    val alphaWas: String?
        get() = when (this) {
            ORRERY, HOURGLASS -> null
            else -> Prefs.WIDGET_ALPHA
        }

    /**
     * Whether it is drawn on a card by default.
     *
     * Not a guess: it is what each of them looked like before there was a
     * switch. The globe carries its own black sky and the sundial its own
     * stone, so those two arrived with a background and the dial and the
     * planets did not — which is the inconsistency that was reported, and
     * the answer to it is a switch rather than picking one and changing
     * everybody's home screen.
     */
    val groundByDefault: Boolean
        get() = this == GLOBE || this == SUNDIAL || this == WEATHER ||
            this == HOURGLASS || this == DIGITS

    companion object {

        /** Which kind a provider class is. */
        fun of(providerClassName: String): WidgetKind = when {
            providerClassName.endsWith("AnalogWidgetProvider") -> DIAL
            providerClassName.endsWith("DigitalWidgetProvider") -> DIGITS
            providerClassName.endsWith("SundialWidgetProvider") -> SUNDIAL
            providerClassName.endsWith("WorldWidgetProvider") -> GLOBE
            providerClassName.endsWith("OrreryWidgetProvider") -> ORRERY
            providerClassName.endsWith("HourglassWidgetProvider") -> HOURGLASS
            providerClassName.endsWith("WeatherWidgetProvider") -> WEATHER
            else -> FOLLOWING
        }
    }
}

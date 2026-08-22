package com.em87.weirdclock

/**
 * What was in the sky, and what it was called, in the year being shown.
 *
 * The orrery can be wound five thousand years either way, and for most of
 * that stretch the picture on the glass is a lie in two separate ways.
 * Neptune is on it, and Neptune was found in 1846 — before that the dial
 * is showing eight planets to a world that could name five. And the five
 * it could name were not called Mercury and Venus: they were Gu-utu and
 * Dilbat, then Stilbon and Phosphoros, then Mercurius and Venus, and the
 * names we use are the third or fourth set.
 *
 * So the sky thins out as it is wound back. Uranus goes in 1781 and
 * Neptune in 1846, going backwards; the five that can be seen with an eye
 * go where the records do, and what is left is the Earth, the Moon and the
 * Sun — which is what anybody had before somebody sat down and watched the
 * lights that wander.
 *
 * The dates are all real and none of them is close to arguable. The one
 * judgement call is where the naked-eye planets start, since nobody
 * discovered them — they were always up there and somebody eventually
 * wrote them down. That is put at the Mesopotamian star lists, which is
 * also where this clock's dates start being written in wedges, so the two
 * changes happen together and the dial says the same thing twice.
 */
object SkyAge {

    /**
     * When the wandering stars were first written down.
     *
     * Not a discovery — five of them are plainly visible and always were.
     * It is the point at which somebody was keeping records of where they
     * had got to, which is the first moment a picture like this one means
     * anything at all.
     */
    const val NAKED_EYE_FROM = -3000

    /** Herschel, from a garden in Bath, with a telescope he had built. */
    const val URANUS_FOUND = 1781

    /** Le Verrier worked out where it had to be; Galle looked and it was. */
    const val NEPTUNE_FOUND = 1846

    /** The first year a body is on the dial at all. */
    fun knownFrom(body: Orrery.Body): Int = when (body) {
        // The ground under your feet and the thing that lights the night.
        // There is no year in which nobody knew about these.
        Orrery.Body.EARTH, Orrery.Body.MOON -> Int.MIN_VALUE
        Orrery.Body.URANUS -> URANUS_FOUND
        Orrery.Body.NEPTUNE -> NEPTUNE_FOUND
        else -> NAKED_EYE_FROM
    }

    /** Whether a body is on the dial in a given year. */
    fun isKnownIn(body: Orrery.Body, year: Int): Boolean = year >= knownFrom(body)

    /** Everything that is not there yet, which is what the dial leaves out. */
    fun unknownIn(year: Int): Set<Orrery.Body> =
        Orrery.Body.entries.filterNot { isKnownIn(it, year) }.toSet()

    /**
     * The year an instant falls in, as a number that can be negative.
     *
     * A Calendar never reports one: wound back past the epoch it hands
     * over a cheerful positive year and a separate flag saying which side
     * of it you are on, so a year read without the flag puts 1250 BC in
     * the wrong millennium and every date on this page in the wrong
     * script. The same trap [ClockView] has its own guard against, and it
     * has to be sprung the same way here because the drawing and the date
     * must agree about what year it is.
     *
     * One Calendar, kept, because this is asked on every frame and a fresh
     * GregorianCalendar is not a cheap object. Everything that calls it is
     * on the drawing thread.
     */
    fun yearOf(atMs: Long): Int {
        val cal = scratch
        cal.timeInMillis = atMs
        val year = cal.get(java.util.Calendar.YEAR)
        return if (cal.get(java.util.Calendar.ERA) == java.util.GregorianCalendar.BC) {
            1 - year
        } else {
            year
        }
    }

    private val scratch: java.util.Calendar = java.util.Calendar.getInstance()

    /** And what the dial leaves out at a given instant. */
    fun unknownAt(atMs: Long): Set<Orrery.Body> = unknownIn(yearOf(atMs))

    /**
     * Whose names the sky is going by.
     *
     * Four sets, and the years between them are the years the astronomy
     * changed hands rather than round numbers: the Babylonian names are
     * what the cuneiform tablets call them and they run until the Greeks
     * take the subject over; the Greek names run until Rome; the Latin
     * names are what every European astronomer wrote in until well after
     * they stopped writing in Latin, which is why they are still the roots
     * of ours.
     */
    enum class Era { BABYLONIAN, GREEK, LATIN, MODERN }

    /** Greek astronomy begins, near enough, with Thales and Anaximander. */
    const val GREEK_FROM = -600

    /** And Rome takes it over about when Rome takes everything over. */
    const val LATIN_FROM = -100

    /**
     * The names go modern where the date does.
     *
     * The same year the date stops being written in Roman numerals, so
     * that a wound-back sky never shows a Latin caption over English names
     * or the other way about. It is late for it — English names were in
     * use long before the year two thousand — but the alternative is two
     * changes a few centuries apart, and one line the whole display
     * crosses at once is worth more than either date being exact.
     */
    const val MODERN_FROM = 2000

    /** Which set of names a year goes by. */
    fun eraFor(year: Int): Era = when {
        year < GREEK_FROM -> Era.BABYLONIAN
        year < LATIN_FROM -> Era.GREEK
        year < MODERN_FROM -> Era.LATIN
        else -> Era.MODERN
    }
}

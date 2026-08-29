package com.em87.weirdclock

import kotlin.math.abs

/**
 * The weather, agreed rather than looked up.
 *
 * This clock has never touched the network. Adding weather to it is the
 * first time it will, and the shape of this file is the owner's own
 * instruction about how: *"lo principal yo creo es que lo consultes donde
 * lo consultes, igual mañana se cae"* — whichever service you ask, it may
 * be gone tomorrow. So there is no service. There is a list of them, and a
 * reading is what enough of them agree on.
 *
 * That is not belt and braces, it is the actual design. A clock that reads
 * one API is a clock that stops telling you the weather on the day that
 * API changes its JSON, moves behind a key, or dies — and the person it
 * stops working for has no way of knowing which. Ask three, keep what they
 * agree on, and losing two of them costs nothing but confidence.
 *
 * **Agreement is a median and a tolerance, never an average.** Three
 * sources saying 20°, 20° and 40° average to 26.7, which is a temperature
 * nobody reported and nowhere near the truth; the median is 20, which two
 * of them measured. The odd one out is then discarded rather than blended,
 * and how many were left is reported — see [Trust] — because "two services
 * agree" and "one service is guessing" are different claims and a clock
 * should not make them look the same.
 *
 * Everything here is arithmetic on numbers somebody else fetched. The
 * fetching, the caching and the asking-for-permission are not here.
 */
object Weather {

    /**
     * How long a reading is worth having, in milliseconds.
     *
     * Weather changes slowly and networks fail often, so an hour-old
     * reading is worth far more than no reading. Past that it is a
     * different afternoon.
     */
    const val FRESH_MS = 3L * 60L * 60L * 1000L

    /**
     * How far apart two sources may be and still be called agreeing.
     *
     * Each is about the width of a real disagreement between two forecast
     * models for the same hour and place, rather than a round number: two
     * degrees, three hectopascals, a quarter of the sky, half a millimetre
     * of rain in an hour, eight kilometres an hour of wind.
     */
    const val TEMPERATURE_SLACK = 2.0
    const val PRESSURE_SLACK = 3.0
    const val CLOUD_SLACK = 25.0
    const val RAIN_SLACK = 0.5
    const val WIND_SLACK = 8.0

    /** How much of a reading anybody should believe. */
    enum class Trust {

        /** Nobody answered, or nobody answered with this. */
        NONE,

        /**
         * One source said so and nothing confirmed it.
         *
         * Still worth showing — a lone reading is what is left on the day
         * the other two are down, and that is the day this whole design
         * exists for. Worth showing *differently*, though: the clock draws
         * it faintly, because a number nobody has checked is not the same
         * object as one two services measured.
         */
        LONE,

        /** Two or more agreed, and any that did not were thrown away. */
        AGREED
    }

    /**
     * One service's answer, as far as it went.
     *
     * Every field is optional because every service is: one of them has no
     * pressure, another has no thunder, and a fourth added rain last year.
     * A source that answers half the questions is still worth asking.
     */
    data class Reading(
        val source: String,
        val atMs: Long,
        val temperatureC: Double? = null,
        val pressureHpa: Double? = null,
        val cloudPercent: Double? = null,
        val rainMmPerHour: Double? = null,
        val windKph: Double? = null,
        val thunder: Boolean? = null
    )

    /** One agreed number, and how many said it. */
    data class Agreed(val value: Double?, val trust: Trust) {
        companion object {
            val NOTHING = Agreed(null, Trust.NONE)
        }
    }

    /**
     * What the sky is doing, as far as anybody can be got to agree.
     *
     * [answered] is how many services were heard from at all, which is the
     * number that says whether this clock is talking to anything.
     */
    data class Sky(
        val temperatureC: Agreed = Agreed.NOTHING,
        val pressureHpa: Agreed = Agreed.NOTHING,
        val cloudPercent: Agreed = Agreed.NOTHING,
        val rainMmPerHour: Agreed = Agreed.NOTHING,
        val windKph: Agreed = Agreed.NOTHING,
        val thunder: Boolean? = null,
        val answered: Int = 0,
        val atMs: Long = 0L
    ) {

        /** Whether there is anything here worth drawing. */
        val known: Boolean
            get() = answered > 0 && (
                temperatureC.value != null || cloudPercent.value != null ||
                    rainMmPerHour.value != null || pressureHpa.value != null
                )
    }

    /**
     * The middle value, which is the whole of how this works.
     *
     * Not the average. An average lets one broken service drag the answer
     * anywhere it likes; a median cannot be moved at all until half of
     * them are wrong together.
     */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /**
     * One quantity, agreed.
     *
     * Take the middle value, throw away anything further than [slack] from
     * it, and take the middle of what is left. One survivor is a lone
     * reading; two or more is an agreement; nothing is nothing.
     *
     * The second median matters. After the outliers go, the middle of the
     * survivors is a number the survivors actually bracket — where keeping
     * the first median would hand back a value that the one discarded
     * source had helped to choose.
     */
    fun agree(values: List<Double>, slack: Double): Agreed {
        val middle = median(values) ?: return Agreed.NOTHING
        val kept = values.filter { abs(it - middle) <= slack }
        val settled = median(kept) ?: return Agreed.NOTHING
        return Agreed(settled, if (kept.size >= 2) Trust.AGREED else Trust.LONE)
    }

    /**
     * And a yes-or-no, agreed: whichever answer more of them gave.
     *
     * A tie is not an answer. Two services saying there is lightning and
     * two saying there is not is exactly the case where a clock should
     * draw nothing rather than pick a side.
     */
    fun agree(votes: List<Boolean>): Boolean? {
        if (votes.isEmpty()) return null
        val yes = votes.count { it }
        val no = votes.size - yes
        return when {
            yes > no -> true
            no > yes -> false
            else -> null
        }
    }

    /**
     * Every reading anybody gave, boiled down to one sky.
     *
     * Stale readings are dropped first — see [FRESH_MS] — because a
     * service that answered three hours ago and has been down since is not
     * a service that agrees with anything.
     */
    fun agree(readings: List<Reading>, nowMs: Long, freshMs: Long = FRESH_MS): Sky {
        val fresh = readings.filter { nowMs - it.atMs in 0..freshMs }
        if (fresh.isEmpty()) return Sky()
        return Sky(
            temperatureC = agree(fresh.mapNotNull { it.temperatureC }, TEMPERATURE_SLACK),
            pressureHpa = agree(fresh.mapNotNull { it.pressureHpa }, PRESSURE_SLACK),
            cloudPercent = agree(fresh.mapNotNull { it.cloudPercent }, CLOUD_SLACK),
            rainMmPerHour = agree(fresh.mapNotNull { it.rainMmPerHour }, RAIN_SLACK),
            windKph = agree(fresh.mapNotNull { it.windKph }, WIND_SLACK),
            thunder = agree(fresh.mapNotNull { it.thunder }),
            answered = fresh.size,
            atMs = fresh.maxOf { it.atMs }
        )
    }

    /**
     * What the sky looks like, as one word, for the token that has to draw
     * it in ten pixels.
     *
     * Ordered by what somebody standing outside would say first. Lightning
     * beats rain, rain beats cloud, and cloud has to be more than half the
     * sky before it is worth mentioning — a clear day with one cloud on it
     * is a clear day.
     */
    enum class Look { CLEAR, CLOUDY, OVERCAST, RAIN, STORM }

    /** Which of those five this sky is, or nothing if nobody knows. */
    fun look(sky: Sky): Look? {
        if (!sky.known) return null
        if (sky.thunder == true) return Look.STORM
        sky.rainMmPerHour.value?.let { if (it >= 0.2) return Look.RAIN }
        val cloud = sky.cloudPercent.value ?: return Look.CLEAR
        return when {
            cloud >= 85.0 -> Look.OVERCAST
            cloud >= 50.0 -> Look.CLOUDY
            else -> Look.CLEAR
        }
    }
}

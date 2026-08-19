package com.em87.weirdclock

import kotlin.math.abs
import kotlin.math.max

/**
 * The menstrual cycle, as arithmetic.
 *
 * Everything here works in whole days counted from 1 January 1970, because
 * a cycle is a thing measured in days and nothing else: no hours, no
 * timezones, no daylight saving, no `Calendar` to get an off-by-one from at
 * the end of March. Turning a date into one of those numbers and back is
 * the only calendar work in the file, and it is at the bottom.
 *
 * Three decisions are worth stating up front, because they are what makes
 * the predictions worth reading.
 *
 * **The median, not the average.** One forty-day cycle after an illness
 * would drag an average for the better part of a year. The median of the
 * recent gaps ignores it, which is the correct thing to do with a one-off:
 * it happened, it is in the record, and it is not what usually happens.
 *
 * **A window, not a day.** Cycles vary by a few days in almost everybody,
 * so "the next one is the 14th" is a sentence that will be wrong most
 * months. What can be said honestly is "between the 12th and the 17th",
 * and how wide that is comes out of how much *your* cycles have varied —
 * so somebody regular gets a narrow window and somebody irregular gets an
 * honest wide one, rather than both being told a single confident day.
 *
 * **A gap that is not a cycle is not learned from.** Under [SHORTEST] is
 * almost always two records of one period; over [LONGEST] is almost always
 * a period that was never written down. Neither teaches the engine
 * anything true, so neither is allowed to — but both stay in the record,
 * because the record is the user's and not the engine's.
 *
 * None of this is medicine. It is what the days you wrote down imply about
 * the days you have not got to yet.
 */
object Cycle {

    /** What a cycle is, with nothing to go on. */
    const val DEFAULT_LENGTH = 28

    /** And how long the bleeding lasts, likewise. */
    const val DEFAULT_BLEED = 5

    /**
     * Gaps outside these are not treated as cycle lengths.
     *
     * Not a claim about bodies — plenty of real cycles fall outside this —
     * but about *records*. Two starts nine days apart is far more likely to
     * be one period entered twice than a nine-day cycle, and a gap of sixty
     * days is far more likely to be a month nobody wrote down. Learning
     * from either produces a prediction that is confidently wrong, which is
     * worse than a wide one.
     */
    const val SHORTEST = 21
    const val LONGEST = 45

    /** How many recent cycles the length is learned from. */
    const val LEARN_FROM = 6

    /** The narrowest and widest the window either side of the day may be. */
    const val TIGHTEST = 1
    const val WIDEST = 7

    /**
     * How long before the next period ovulation is reckoned to fall.
     *
     * The half of the cycle *after* ovulation is the steady one — it is the
     * first half that stretches and shrinks — so counting back from the
     * next period is the only way this arithmetic can be done at all. Which
     * also means it is exactly as uncertain as the prediction it counts
     * back from, and the prediction is a window.
     */
    const val LUTEAL_DAYS = 14

    /** One period, as the day it started and how many days it ran. */
    data class Period(val start: Int, val days: Int = 0) {
        /** Every day it covered, the start included. */
        fun coveredDays(): IntRange = start until (start + max(1, if (days > 0) days else DEFAULT_BLEED))
    }

    /**
     * What is expected, and how sure the arithmetic is of it.
     *
     * [learned] is false while there is nothing but the default to go on.
     * A prediction from one recorded period is not a prediction, and
     * anything showing it has to be able to say so rather than dress a
     * guess up as a forecast.
     */
    data class Forecast(
        val expected: Int,
        val from: Int,
        val to: Int,
        val length: Int,
        val learned: Boolean
    )

    /** What a day is, for whatever wants to draw it. */
    enum class Phase { NONE, PERIOD, FERTILE, PREDICTED, LATE }

    // -------------------------------------------------------- the history

    /** The starts, ascending and without repeats. */
    fun starts(periods: List<Period>): List<Int> =
        periods.map { it.start }.distinct().sorted()

    /** The gap in days between each start and the one before it. */
    fun gaps(periods: List<Period>): List<Int> =
        starts(periods).zipWithNext { a, b -> b - a }

    /** The gaps that are plausibly cycles — see [SHORTEST] and [LONGEST]. */
    fun plausibleGaps(periods: List<Period>): List<Int> =
        gaps(periods).filter { it in SHORTEST..LONGEST }

    /**
     * How long a cycle usually is, in days.
     *
     * The median of the last [LEARN_FROM] plausible gaps. With nothing to
     * learn from it is [DEFAULT_LENGTH], and whoever asks can tell the two
     * apart by [Forecast.learned].
     */
    fun typicalLength(periods: List<Period>): Int {
        val recent = plausibleGaps(periods).takeLast(LEARN_FROM)
        if (recent.isEmpty()) return DEFAULT_LENGTH
        val sorted = recent.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            // Two in the middle: the lower of them rather than their
            // average, so the answer is always a whole day somebody's body
            // actually did, and never a day and a half.
            sorted[middle - 1]
        }
    }

    /**
     * How much they vary, as days either side of the expected one.
     *
     * Half the distance from the shortest recent cycle to the longest, so
     * the window covers what has actually happened lately rather than a
     * figure from a textbook. Floored at [TIGHTEST], because nobody is
     * exact to the day and a window of zero would say otherwise; capped at
     * [WIDEST], because a window three weeks wide has stopped being a
     * prediction and become a shrug.
     */
    fun spread(periods: List<Period>): Int {
        val recent = plausibleGaps(periods).takeLast(LEARN_FROM)
        if (recent.size < 2) return TIGHTEST
        return ((recent.max() - recent.min()) / 2).coerceIn(TIGHTEST, WIDEST)
    }

    /**
     * When the next one is due, or null with nothing recorded at all.
     *
     * Counted from the *last* start rather than from an average of them:
     * whatever the body has been doing lately is what it is doing now, and
     * the last period is the only one that has any claim to be recent.
     */
    fun forecast(periods: List<Period>): Forecast? {
        val last = starts(periods).lastOrNull() ?: return null
        val length = typicalLength(periods)
        val give = spread(periods)
        return Forecast(
            expected = last + length,
            from = last + length - give,
            to = last + length + give,
            length = length,
            learned = plausibleGaps(periods).isNotEmpty()
        )
    }

    /**
     * Which day of the cycle [today] is, counting the first day of the last
     * period as day one. Zero when there is nothing recorded, or when today
     * is somehow before it.
     */
    fun dayOf(periods: List<Period>, today: Int): Int {
        val last = starts(periods).lastOrNull() ?: return 0
        val n = today - last + 1
        return if (n >= 1) n else 0
    }

    // ---------------------------------------------------------- the delay

    /**
     * How many days past the expected day it is, or 0.
     *
     * The number a person counts, from the day itself — "I am four days
     * late" is measured from the day it was due, not from the far end of
     * some window. Whether the app should be *saying* anything about it is
     * a different question: see [late].
     */
    fun delay(periods: List<Period>, today: Int): Int {
        val f = forecast(periods) ?: return 0
        return max(0, today - f.expected)
    }

    /**
     * Whether it is late enough to be worth saying so.
     *
     * Past the end of the window, not past the expected day. Being a day
     * past the middle of a window four days wide is not a delay, it is the
     * window doing its job — and an app that announced it every month would
     * be an app that cried wolf every month.
     */
    fun late(periods: List<Period>, today: Int): Boolean {
        val f = forecast(periods) ?: return false
        return today > f.to
    }

    /**
     * The delay this cycle will be recorded as once it arrives.
     *
     * Which is not the same as [delay]: that one is about today, and this
     * one is about a period that has already started, measured against what
     * was expected of it *before* it did. Used to tell "you were five days
     * late last month" from "you are five days late now".
     */
    fun delayOfLast(periods: List<Period>): Int {
        val all = starts(periods)
        if (all.size < 2) return 0
        val before = periods.filter { it.start < all.last() }
        val f = forecast(before) ?: return 0
        return max(0, all.last() - f.expected)
    }

    // --------------------------------------------------------- the phases

    /**
     * The days ovulation is reckoned to be possible around, or null.
     *
     * Counted back from the predicted start — see [LUTEAL_DAYS] — and
     * therefore no surer than that prediction is. Five days before through
     * one day after, which is the standard reckoning and is a *calendar*
     * estimate: it is arithmetic on the days you wrote down, and it knows
     * nothing about you that those days do not say.
     */
    fun fertileWindow(periods: List<Period>): IntRange? {
        val f = forecast(periods) ?: return null
        val ovulation = f.expected - LUTEAL_DAYS
        return (ovulation - 5)..(ovulation + 1)
    }

    /**
     * What [day] is, given everything recorded and where today falls.
     *
     * Ordered by how certain each answer is. A day that was actually bled
     * on is a fact and outranks everything; a day past the end of the
     * window is a delay and outranks a prediction that has already been
     * overtaken; the predicted window outranks the fertile one, because a
     * window drawn over another window has to pick.
     */
    fun phase(periods: List<Period>, day: Int, today: Int): Phase {
        if (periods.any { day in it.coveredDays() }) return Phase.PERIOD
        val f = forecast(periods) ?: return Phase.NONE
        if (day in f.from..f.to) {
            return if (day <= today && late(periods, today)) Phase.LATE else Phase.PREDICTED
        }
        if (day > f.to && day <= today && late(periods, today)) return Phase.LATE
        if (fertileWindow(periods)?.contains(day) == true) return Phase.FERTILE
        return Phase.NONE
    }

    // ------------------------------------------------------- keeping it up

    /**
     * Records a period starting on [day], and hands back the new history.
     *
     * Two rules, both about mistakes rather than about bodies. A start
     * within [SHORTEST] days of one already recorded replaces it, because
     * that is what a correction looks like — you meant the 3rd, you tapped
     * the 4th — and two entries three days apart would otherwise teach the
     * engine a three-day cycle. And the list stays sorted, because
     * everything above reads it in order.
     */
    fun record(periods: List<Period>, day: Int, days: Int = 0): List<Period> {
        val kept = periods.filterNot { abs(it.start - day) < SHORTEST }
        return (kept + Period(day, days)).sortedBy { it.start }
    }

    /**
     * What a tap on a calendar day does to the record.
     *
     * A day already inside a recorded period is being un-marked — that is
     * what tapping a thing that is already on means everywhere else — and
     * any other day starts one. By the period it falls in rather than by
     * its start, so tapping the third day of a period removes that period
     * instead of quietly starting a second one inside it.
     *
     * The sheet is still the place to say how long a period ran; this is
     * for the two-second job the sheet is too much ceremony for.
     */
    fun tapped(periods: List<Period>, day: Int): List<Period> {
        val covering = periods.firstOrNull { day in it.coveredDays() }
        return if (covering != null) forget(periods, covering.start) else record(periods, day)
    }

    /** Whether [day] is inside a period that has been written down. */
    fun marked(periods: List<Period>, day: Int): Boolean =
        periods.any { day in it.coveredDays() }

    /** Takes one out again, by the day it started. */
    fun forget(periods: List<Period>, day: Int): List<Period> =
        periods.filterNot { it.start == day }

    /** Says how long the period that started on [day] ran. */
    fun setLength(periods: List<Period>, day: Int, days: Int): List<Period> =
        periods.map { if (it.start == day) it.copy(days = days.coerceIn(0, 15)) else it }

    /**
     * Older than this many days and it is history rather than record.
     *
     * Kept anyway. Nothing here throws anything away — the point of writing
     * a thing down is that it is still there in two years — but the engine
     * only ever learns from [LEARN_FROM] cycles, so the rest costs nothing
     * but the space to store it.
     */
    fun typicalBleed(periods: List<Period>): Int {
        val told = periods.mapNotNull { it.days.takeIf { d -> d > 0 } }.takeLast(LEARN_FROM)
        if (told.isEmpty()) return DEFAULT_BLEED
        return told.sorted()[told.size / 2]
    }

    // ------------------------------------------------- days, from and to

    /**
     * A calendar date as days since 1 January 1970.
     *
     * The arithmetic itself is in [CivilDays], which the solar system uses
     * too; these three stay here because the cycle talks in days from end
     * to end and reading `Cycle.epochDay` at every call site is what the
     * rest of this file is written for.
     */
    fun epochDay(year: Int, month: Int, day: Int): Int =
        CivilDays.epochDay(year, month, day)

    /** And back again, as year, month, day. */
    fun dateOf(epochDay: Int): Triple<Int, Int, Int> = CivilDays.dateOf(epochDay)

    /** Today, from a wall-clock instant and the zone it is read in. */
    fun today(nowMs: Long, zoneOffsetMs: Int): Int = CivilDays.dayOf(nowMs, zoneOffsetMs)

}

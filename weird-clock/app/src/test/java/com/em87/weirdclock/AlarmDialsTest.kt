package com.em87.weirdclock

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The little faces on an alarm card.
 *
 * An alarm can be one time or up to four, and the four are one concept —
 * pills at eight, twelve, four and eight — not four alarms. Keeping the
 * alarm's own time large and its repetitions small said the opposite, and
 * meant the four could never line up as the 2×2 they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmDialsTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun cards() = AlarmCards(
        host = context,
        prefs = PreferenceManager.getDefaultSharedPreferences(context),
        alarms = emptyList(),
        dialTheme = { ClockThemes.MIDNIGHT },
        hoursOnDial = { 12 },
        dialShape = { ClockView.DialShape.CIRCLE },
        onToggled = { _, _ -> },
        onOpen = { }
    )

    /** Every face on the card, however deeply it is nested. */
    private fun facesIn(view: View): List<ClockView> = when (view) {
        is ClockView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { facesIn(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun sizeOf(face: ClockView): Pair<Int, Int> {
        val p = face.layoutParams
        return p.width to p.height
    }

    private fun row(vararg times: Pair<Int, Int>): LinearLayout {
        val row = LinearLayout(context)
        cards().fillDials(row, times.toList())
        return row
    }

    @Test
    fun `one time is one face`() {
        val faces = facesIn(row(7 to 0))
        assertEquals(1, faces.size)
    }

    /**
     * The bug: with four times there were four faces, but one of them was
     * still the big one and the other three huddled beside it.
     */
    @Test
    fun `four times are four faces, all the same size`() {
        val faces = facesIn(row(8 to 0, 12 to 0, 16 to 0, 20 to 0))
        assertEquals(4, faces.size)
        val sizes = faces.map { sizeOf(it) }.distinct()
        assertEquals("all four must be the same size: $sizes", 1, sizes.size)
    }

    @Test
    fun `and two, and three`() {
        for (times in listOf(
            arrayOf(8 to 0, 20 to 0),
            arrayOf(8 to 0, 14 to 0, 20 to 0)
        )) {
            val faces = facesIn(row(*times))
            assertEquals(times.size, faces.size)
            assertEquals(
                "no face leads when there is more than one",
                1, faces.map { sizeOf(it) }.distinct().size
            )
        }
    }

    /**
     * However many there are, the block they sit in is the same square —
     * otherwise a card with repetitions reaches further than one without,
     * and the icon rows underneath stop lining up down the list.
     */
    @Test
    fun `the block is the same size however many faces are in it`() {
        val single = row(7 to 0).getChildAt(0).layoutParams
        for (count in 2..4) {
            val times = (0 until count).map { (6 + it * 4) to 0 }.toTypedArray()
            val block = row(*times).getChildAt(0).layoutParams
            assertEquals("width with $count", single.width, block.width)
            assertEquals("height with $count", single.height, block.height)
        }
    }

    /** And a face in the mosaic really is smaller than a lone one. */
    @Test
    fun `a mosaic face is smaller than a face on its own`() {
        val alone = sizeOf(facesIn(row(7 to 0)).single()).first
        val quartered = sizeOf(facesIn(row(8 to 0, 12 to 0, 16 to 0, 20 to 0)).first()).first
        assertTrue("$quartered should be well under $alone", quartered < alone)
    }

    // ------------------------------------------------------ what the rows say

    /**
     * Nothing on a picker repeats the name of the row it opened from.
     *
     * A list under "Snooze" whose entries all began "Snooze:" said the word
     * twice and left no room for the answer — and with the three of them
     * switched off the sheet read "Snooze: off / Straight on / None — slide
     * to stop" where it should have read "Off / Off / Off". Three different
     * ways of saying the same thing is three things to work out instead of
     * one thing to see.
     */
    @Test
    fun `no option repeats the name of the row it belongs to`() {
        val cards = cards()
        for ((rowTitle, options) in mapOf(
            R.string.alarm_snooze to listOf(
                context.getString(R.string.alarm_snooze_off),
                context.getString(R.string.alarm_snooze_min, 10)
            ),
            R.string.alarm_gentle to listOf(
                cards.gentleLabel(0), cards.gentleLabel(180), cards.gentleLabel(30)
            ),
            R.string.alarm_mission to listOf(
                cards.missionLabel(Mission.NONE),
                cards.missionLabel(Mission.MATHS, 3),
                cards.missionLabel(Mission.SHAKE)
            ),
            R.string.pref_snooze_limit_title to listOf(
                cards.snoozeLimitLabel(0),
                cards.snoozeLimitLabel(1),
                cards.snoozeLimitLabel(3)
            )
        )) {
            val title = context.getString(rowTitle)
            for (option in options) {
                assertFalse(
                    "'$option' repeats its own row, '$title'",
                    option.contains(title.substringBefore(' '), ignoreCase = true)
                )
            }
        }
    }

    /**
     * And no two options in a list say the same thing at the same end.
     *
     * The sharper version of the rule above, and the one that catches what
     * that one misses. "Once, then get up / 3 times, then get up / 5 times,
     * then get up" repeats nothing from its row's title, so the first test
     * is happy — and yet the list says "then get up" three times and the
     * only thing that varies is the number, which is the part being read.
     * Words every entry shares belong in the title.
     */
    @Test
    fun `no list says the same words in every one of its options`() {
        val cards = cards()
        for ((what, options) in mapOf(
            "the snooze" to listOf(
                context.getString(R.string.alarm_snooze_off),
                context.getString(R.string.alarm_snooze_min, 5),
                context.getString(R.string.alarm_snooze_min, 10)
            ),
            "the sunrise" to listOf(
                cards.gentleLabel(0), cards.gentleLabel(60), cards.gentleLabel(180)
            ),
            // The whole list as the picker shows it, not a slice of it: five
            // rungs that all say "Level" read perfectly well beside "Off"
            // and "Shake the phone", and it is the list somebody reads.
            "the mission" to AlarmCards.MISSION_CHOICES.map { (kind, level) ->
                cards.missionLabel(kind, level)
            },
            "the snooze limit" to listOf(
                cards.snoozeLimitLabel(1), cards.snoozeLimitLabel(3), cards.snoozeLimitLabel(5)
            ),
            "the sounds" to Prefs.ALARM_SOUNDS.map { cards.soundLabel(it) }
        )) {
            // "Off" is left out of the comparison. It is one word by
            // design and shares nothing with anything, so counting it would
            // let a tail on every *other* entry through — which is exactly
            // the shape the sunrise had: "Off / Comes up over 1 minute /
            // Comes up over 3 minutes".
            val off = context.getString(R.string.alarm_snooze_off)
            val words = options.filter { it != off }
                .map { it.split(' ').filter(String::isNotBlank) }
            if (words.size < 2) continue

            // A *phrase*, not a word. A unit shared by every entry is not
            // repetition — "5 minutes / 10 minutes" is two lengths and the
            // word "minutes" is what they are lengths of. Two words in a
            // row at the same end of every entry is something else: it is a
            // sentence that has been copied into each option instead of
            // being written once, in the title.
            fun common(lists: List<List<String>>): Int {
                var n = 0
                while (lists.all { it.size > n && it[n] == lists.first()[n] }) n++
                return n
            }
            val front = common(words)
            val back = common(words.map { it.reversed() })
            assertTrue(
                "$what begins every option with " +
                    "${words.first().take(front)}: $options",
                front < 2
            )
            assertTrue(
                "$what ends every option with " +
                    "${words.first().takeLast(back)}: $options",
                back < 2
            )
        }
    }

    /** And off is Off, in the same word, in all three of them. */
    @Test
    fun `every one of them is switched off the same way`() {
        val cards = cards()
        val off = context.getString(R.string.alarm_snooze_off)
        assertEquals("the sunrise", off, cards.gentleLabel(0))
        assertEquals("the mission", off, cards.missionLabel(Mission.NONE))
        assertEquals("the snooze limit", off, cards.snoozeLimitLabel(0))
        assertTrue("and it is short enough to read at a glance", off.length <= 4)
    }

    // ------------------------------------------------- the marks on the row

    /**
     * A card bound for real, so the little marks under the time can be read.
     *
     * They were only ever checked by looking at a screenshot. Which is
     * worth doing — it is how the two new ones were found to be drawn at
     * all — but a picture I looked at once is not something that goes on
     * being true, and these marks are the only way to tell from the list
     * what an alarm is going to do to you in the morning.
     */
    private fun bind(alarm: Alarm): AlarmCards.AlarmHolder {
        // A card is a MaterialCardView, and Material views refuse to
        // inflate against a context with no theme on it. The application
        // context has none; the app's own theme is what the card is drawn
        // with on the phone, so it is what it is drawn with here.
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        val cards = AlarmCards(
            host = themed,
            prefs = PreferenceManager.getDefaultSharedPreferences(context),
            alarms = listOf(alarm),
            dialTheme = { ClockThemes.MIDNIGHT },
            hoursOnDial = { 12 },
            dialShape = { ClockView.DialShape.CIRCLE },
            onToggled = { _, _ -> },
            onOpen = { }
        )
        val parent = LinearLayout(themed)
        val holder = cards.adapter.onCreateViewHolder(parent, 0)
        cards.adapter.onBindViewHolder(holder, 0)
        return holder
    }

    private fun plain() = Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_BELLS).apply {
        vibrate = false
        snoozeMinutes = 0
    }

    /** Nothing switched on, nothing marked. Most alarms look like this. */
    @Test
    fun `an alarm with nothing set wears no marks`() {
        val holder = bind(plain())
        assertEquals(View.GONE, holder.iconMission.visibility)
        assertEquals(View.GONE, holder.iconGentle.visibility)
        assertEquals(View.GONE, holder.iconFlash.visibility)
        assertEquals(View.GONE, holder.iconVibrate.visibility)
    }

    /**
     * And the two that are easy to set and then forget show up. Both change
     * what happens at six in the morning, and neither is visible anywhere
     * else without opening the alarm.
     */
    @Test
    fun `a mission and a sunrise each put a mark on the card`() {
        val withMission = bind(plain().apply { mission = Mission.MATHS })
        assertEquals(View.VISIBLE, withMission.iconMission.visibility)
        assertEquals(
            "a sunrise nobody asked for must not appear",
            View.GONE, withMission.iconGentle.visibility
        )

        val withSunrise = bind(plain().apply { gentleWakeSeconds = 180 })
        assertEquals(View.VISIBLE, withSunrise.iconGentle.visibility)
        assertEquals(
            "a mission nobody asked for must not appear",
            View.GONE, withSunrise.iconMission.visibility
        )
    }

    /**
     * Which mission, not merely that there is one. Being woken to do
     * arithmetic and being woken to shake the thing are quite different
     * mornings, and one mark for both leaves you opening the alarm to find
     * out which you are in for.
     */
    @Test
    fun `the mark says which mission it is`() {
        val maths = bind(plain().apply { mission = Mission.MATHS })
        val shake = bind(plain().apply { mission = Mission.SHAKE })
        assertTrue(
            "the two missions wear the same mark",
            maths.iconMission.drawable !== shake.iconMission.drawable
        )
        assertEquals(R.drawable.ic_sigma, cards().missionIcon(Mission.MATHS))
        assertEquals(R.drawable.ic_shake, cards().missionIcon(Mission.SHAKE))
    }

    /** And each says out loud what it is, for somebody who cannot see it. */
    @Test
    fun `the mission mark says what it wants out loud`() {
        val holder = bind(plain().apply { mission = Mission.MATHS; missionLevel = 5 })
        val spoken = holder.iconMission.contentDescription?.toString().orEmpty()
        assertTrue("nothing is said about it: '$spoken'", spoken.isNotBlank())
        assertTrue(
            "and the rung is part of what it wants: '$spoken'",
            spoken.contains("5")
        )
    }
}

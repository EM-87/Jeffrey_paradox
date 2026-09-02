package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of screens, written to disk so somebody can look at them.
 *
 * Everything in this app is drawn by hand, and until now the only way to
 * find out what any of it actually looked like was to install it. Under
 * NATIVE graphics Robolectric rasterises for real, so a screen can be laid
 * out, drawn into a bitmap and written to a PNG — which is not the same as
 * a phone in a dark room, but it is the difference between judging a
 * layout and guessing at one.
 *
 * These assert almost nothing on purpose. They are a camera, not a test:
 * the only thing checked is that something was drawn at all, because a
 * blank picture is the one result that would quietly mean nothing was
 * being looked at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * The hour it is right now, so a night window can be built around it.
     *
     * Nights wrap and cannot cover a whole day — dragging the two pins
     * together means "no night", which is the sensible reading of them —
     * so a test that wants it to be night has to say when now is.
     */
    private fun thisHour(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** Draws [view] into a PNG and returns how much of it is not one colour. */
    private fun shoot(view: View, name: String): Float {
        val width = view.width.takeIf { it > 0 } ?: 1080
        val height = view.height.takeIf { it > 0 } ?: 2000
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // How varied the picture is, as the crudest possible check that
        // there is a picture: an all-one-colour bitmap means the screen was
        // never laid out and the file is a photograph of nothing.
        val seen = HashSet<Int>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                seen.add(bitmap.getPixel(x, y))
                x += 7
            }
            y += 7
        }
        return seen.size.toFloat()
    }

    private fun screenOf(activity: android.app.Activity): View =
        activity.findViewById(android.R.id.content)

    @Test
    fun `the ring screen with a sum on it`() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .commit()
        val intent = android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE, 5)
            .putExtra(AlarmScheduler.EXTRA_LABEL, "Work")
            .putExtra(AlarmScheduler.EXTRA_MISSION, Mission.MATHS)
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            assertTrue(shoot(screenOf(c.get()), "ring-maths") > 3f)
        }
    }

    /**
     * The same screen with the keyboard up, which is how it is actually
     * seen: the numeric keypad takes the bottom half, and what it was
     * covering was the question, the box and the button — all three of the
     * things the mission is made of.
     */
    @Test
    fun `the ring screen with a sum, under the keyboard`() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .commit()
        val intent = android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE, 5)
            .putExtra(AlarmScheduler.EXTRA_LABEL, "Work")
            .putExtra(AlarmScheduler.EXTRA_MISSION, Mission.MATHS)
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            val screen = screenOf(c.get())
            // Roughly what is left of a 891dp-tall phone with the numeric
            // keypad up.
            val left = (891 - 300) * context.resources.displayMetrics.density
            screen.measure(
                View.MeasureSpec.makeMeasureSpec(screen.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(left.toInt(), View.MeasureSpec.EXACTLY)
            )
            screen.layout(0, 0, screen.width, left.toInt())
            assertTrue(shoot(screen, "ring-maths-keyboard") > 3f)
        }
    }

    @Test
    fun `the ring screen counting shakes`() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .commit()
        org.robolectric.Shadows
            .shadowOf(context.getSystemService(android.hardware.SensorManager::class.java))
            .addSensor(
                org.robolectric.shadows.ShadowSensor.newInstance(
                    android.hardware.Sensor.TYPE_ACCELEROMETER
                )
            )
        val intent = android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_MISSION, Mission.SHAKE)
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            assertTrue(shoot(screenOf(c.get()), "ring-shake") > 3f)
        }
    }

    /** And the ordinary one, for comparison. */
    @Test
    fun `the ring screen as it has always been`() {
        prefs.edit().clear().putBoolean(Prefs.OVERLAY_ASKED, true)
                            .putBoolean(Prefs.FACE_ASKED, true).commit()
        val intent = android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE, 5)
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            assertTrue(shoot(screenOf(c.get()), "ring-slider") > 3f)
        }
    }

    /**
     * The settings, screen by screen and scrolled all the way down.
     *
     * A setting that exists in the code and cannot be found in the menu is
     * a setting that does not exist. The only way to check that from here
     * is to lay the list out and read it.
     */
    @Test
    fun `every settings screen, top to bottom`() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putBoolean(Prefs.BELLS, true)
            .putBoolean(Prefs.NIGHT_DIM, true)
            .commit()
        Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
            c.setup()
            val fragment = c.get().supportFragmentManager.fragments.first()
                as androidx.preference.PreferenceFragmentCompat
            shootList(fragment, "settings-root")
        }
        for ((name, fragment) in listOf(
            "settings-advanced" to SettingsActivity.AdvancedSettingsFragment(),
            "settings-very-advanced" to SettingsActivity.VeryAdvancedSettingsFragment()
        )) {
            Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
                c.setup()
                c.get().supportFragmentManager.beginTransaction()
                    .replace(R.id.settings_container, fragment).commitNow()
                shootList(fragment, name)
            }
        }
    }

    /**
     * And the same three screens on the face with no hands, which are not
     * the same three screens.
     *
     * Every row about a dial is gone, the digits' own rows are there
     * instead, and two rows have changed their names. A test can say the
     * keys are right; only a picture can say the page still reads as a
     * page rather than as a list with holes in it.
     */
    /**
     * The four instruments, each on its own clock card.
     *
     * One picture of what this app actually is now. There was no shot of
     * the dial's own card at all — every other picture of it is a detail,
     * a shape or a night — so the one thing nobody could look at side by
     * side was the set.
     */
    @Test
    fun `the four faces, side by side`() {
        for (face in Face.entries) {
            prefs.edit().clear()
                .putBoolean(Prefs.OVERLAY_ASKED, true)
                .putBoolean(Prefs.FACE_ASKED, true)
                .putString(Prefs.FACE, face.key)
                .putBoolean(Prefs.SHOW_DATE, true)
                .commit()
            Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val screen = c.get().findViewById<View>(android.R.id.content)
                assertTrue(face.key, shoot(screen, "face-${face.key}") > 3f)
            }
        }
    }

    @Test
    fun `every settings screen on every face`() {
        // Every face, and not the one this was written for. The pictures
        // stopped at the digits while two more faces were added, and what
        // nobody looked at was the page the last of them actually opens:
        // a heading saying Dial, a night switch explaining that the dial
        // dims, a date switch for a face that prints no date, and mini
        // dials offered for other cities to a turning planet.
        for (face in Face.entries - Face.ANALOG) {
            prefs.edit().clear()
                .putBoolean(Prefs.OVERLAY_ASKED, true)
                .putBoolean(Prefs.FACE_ASKED, true)
                .putString(Prefs.FACE, face.key)
                .putBoolean(Prefs.BELLS, true)
                .commit()
            Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
                c.setup()
                val fragment = c.get().supportFragmentManager.fragments.first()
                    as androidx.preference.PreferenceFragmentCompat
                shootList(fragment, "settings-${face.key}-root")
            }
            for ((name, fragment) in listOf(
                "settings-${face.key}-advanced" to SettingsActivity.AdvancedSettingsFragment(),
                "settings-${face.key}-very-advanced" to
                    SettingsActivity.VeryAdvancedSettingsFragment()
            )) {
                Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
                    c.setup()
                    c.get().supportFragmentManager.beginTransaction()
                        .replace(R.id.settings_container, fragment).commitNow()
                    shootList(fragment, name)
                }
            }
        }
    }

    /**
     * A preference list is a RecyclerView, so only what fits is ever laid
     * out. Measured tall enough for the whole list, every row is there.
     */
    private fun shootList(fragment: androidx.preference.PreferenceFragmentCompat, name: String) {
        val list = fragment.listView
        // Every row, not every top-level child. Counting the children of
        // the screen counts *categories*, so a page of eight categories
        // was measured for eight rows and the picture stopped somewhere in
        // the middle of it — which meant the bottom of every settings
        // screen had never been looked at, on any face.
        fun count(group: androidx.preference.PreferenceGroup): Int {
            var n = group.preferenceCount
            for (i in 0 until group.preferenceCount) {
                val row = group.getPreference(i)
                if (row is androidx.preference.PreferenceGroup) n += count(row)
            }
            return n
        }
        val rows = count(fragment.preferenceScreen)
        val tall = 260 * rows + 400
        // Let the activity finish its own layout *first*, then stretch the
        // list and draw it without idling again.
        //
        // The other way round is what this did for eleven versions, and it
        // did nothing at all: measuring a view and then letting the looper
        // run hands it straight back to the activity, which lays it out to
        // the size of the screen. So every settings picture ever taken was
        // one screenful, the bottom of every page had never been looked at
        // by anybody, and the comment here said the opposite.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 1080, tall)
        assertTrue(name, shoot(list, name) > 3f)
    }

    /**
     * An alarm card carrying both of the new marks.
     *
     * There is no automated check on these two icons — they are one-line
     * bindings beside four others that have none either — so this is the
     * only thing standing between "the binding is written" and "the icon
     * is on screen".
     */
    @Test
    fun `an alarm card with a sunrise and a mission on it`() {
        prefs.edit().clear().putBoolean(Prefs.OVERLAY_ASKED, true)
                            .putBoolean(Prefs.FACE_ASKED, true).commit()
        AlarmStore.forget()
        AlarmStore.all(context).add(
            Alarm(1, 7, 30, true, Prefs.ALARM_SOUND_BELLS).apply {
                label = "Work"
                mission = Mission.MATHS
                gentleWakeSeconds = 60
                flash = true
            }
        )
        AlarmStore.save(context)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            app.findViewById<android.widget.ImageButton>(R.id.to_alarms_button).performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val list = app.findViewById<androidx.recyclerview.widget.RecyclerView>(
                R.id.alarms_recycler
            )
            list.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY)
            )
            list.layout(0, 0, 1080, 900)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue(shoot(list, "alarm-card") > 3f)
        }
        AlarmStore.forget()
    }

    /** The night bar, which is the thing in here nobody has ever seen. */
    @Test
    fun `the night hours, with the bar in three states`() {
        for ((name, window) in listOf(
            "night-2207" to (22 to 7),
            "night-1418" to (14 to 18),
            "night-off" to (9 to 9)
        )) {
            val themed = androidx.appcompat.view.ContextThemeWrapper(
                context, R.style.Theme_WeirdClock
            )
            val bar = NightBar(themed).apply {
                setWindow(window.first, window.second)
                measure(
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                layout(0, 0, 1000, measuredHeight)
            }
            assertTrue(name, shoot(bar, name) > 2f)
        }
    }

    /**
     * A month with a cycle marked on it.
     *
     * The bars under the numbers are the whole feature, and how they read
     * against the reminder dots, the birthday star and the little moons is
     * something no assertion can answer. Drawn straight rather than through
     * the app, because the app opens on the clock and this is a question
     * about one page of it.
     */
    @Test
    fun `a month with the cycle marked on it`() {
        val themed = androidx.appcompat.view.ContextThemeWrapper(
            context, R.style.Theme_WeirdClock
        )
        val view = CalendarPageView(themed).apply {
            theme = ClockThemes.MIDNIGHT
            measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1080, 1500)
        }
        // Four months of twenty-eight-day cycles, the last one starting on
        // the twelfth of the month *on screen*, so the picture carries
        // recorded days, the fertile stretch and the predicted window all
        // at once.
        //
        // Anchored to the month the page is showing rather than to the
        // real one. Counting back six days from today put the last period
        // in the previous month for the first week of every month, and
        // the picture came out with nothing marked on it — which this
        // test then reported as a fault in the drawing.
        val anchor = Cycle.epochDay(view.shownYear, view.shownMonth1, 12)
        val record = (1..4).map { Cycle.Period(anchor - (4 - it) * 28, days = 5) }
        val today = anchor + 6
        view.cyclePhases = (1..31).associateWith {
            Cycle.phase(record, Cycle.epochDay(view.shownYear, view.shownMonth1, it), today)
        }.filterValues { it != Cycle.Phase.NONE }
        assertTrue("the month drew nothing", shoot(view, "cycle-month") > 3f)
        assertTrue(
            "and nothing was marked on it",
            view.cyclePhases.values.contains(Cycle.Phase.PERIOD)
        )
    }

    /** And the sheet it is recorded from. */
    @Test
    fun `the cycle sheet`() {
        val themed = androidx.appcompat.view.ContextThemeWrapper(
            context, R.style.Theme_WeirdClock
        )
        val sheet = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.sheet_cycle, null)
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        sheet.layout(0, 0, 1080, sheet.measuredHeight)
        assertTrue("the sheet drew nothing", shoot(sheet, "cycle-sheet") > 2f)
    }

    /**
     * The alarm editor, whole, so the order of its rows can be looked at.
     *
     * The order is checked properly elsewhere, by walking the layout — this
     * is for the half a test cannot judge: whether a sheet with this many
     * rows on it reads as a list of settings or as a wall.
     */
    @Test
    fun `the alarm editor, top to bottom`() {
        val themed = androidx.appcompat.view.ContextThemeWrapper(
            context, R.style.Theme_WeirdClock
        )
        val sheet = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.sheet_alarm_edit, null)
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        sheet.layout(0, 0, 1080, sheet.measuredHeight)
        assertTrue("the sheet drew nothing", shoot(sheet, "alarm-sheet") > 2f)
        assertTrue(
            "the sheet is ${sheet.measuredHeight}px tall — it has to scroll, " +
                "which is the whole reason it must be able to scroll back",
            sheet.measuredHeight > 0
        )
    }

    /**
     * The solar system, at the moment it has the dial to itself.
     *
     * The one picture worth having of this feature: eight rings, eight
     * bodies at the angles the arithmetic puts them at, the Moon on its own
     * little ring around the Earth, and the date underneath. Whether that
     * is legible or a tangle is not a thing anybody can reason their way to.
     */
    @Test
    fun `the solar system on the dial`() {
        prefs.edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .putString(Prefs.THEME, "midnight")
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val clock = c.get().clockForTest()
            clock.toggleOrrery()
            // Past the fade, so the picture is of the thing and not of the
            // hands still leaving.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(900)
            )
            assertTrue(shoot(screenOf(c.get()), "orrery") > 3f)
        }
    }

    /**
     * The dial on a date with a line of planets across it, which is the
     * thing the long press goes looking for and the one state of this
     * feature nobody can check by dragging a finger about for a minute.
     */
    @Test
    fun `the solar system on a day when three planets line up`() {
        prefs.edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val clock = c.get().clockForTest()
            clock.toggleOrrery()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(900)
            )
            assertTrue("nothing lined up to photograph", clock.leapToNextAlignment())
            assertTrue(shoot(screenOf(c.get()), "orrery-aligned") > 3f)
        }
    }

    /**
     * And the same dial wound forward by a drag on Neptune, which is how a
     * date two hundred years out is arrived at.
     */
    @Test
    fun `the solar system, wound a long way forward`() {
        prefs.edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val clock = c.get().clockForTest()
            clock.toggleOrrery()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(900)
            )
            clock.windOrreryForTest(Orrery.Body.NEPTUNE, 140.0)
            assertTrue(shoot(screenOf(c.get()), "orrery-wound") > 3f)
        }
    }

    /**
     * The dial at night, with the planets on it.
     *
     * Night mode drops the whole outfit to thirty per cent so the bedroom
     * stays dark, and the planets are drawn in colours of their own —
     * deliberately, since that is how you tell Mars from Venus. "Not
     * themed" was being read as "not dimmed", and eight bright lamps over a
     * dial turned down for the night is worse than no dial.
     */
    @Test
    fun `the solar system at night`() {
        prefs.edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .putBoolean(Prefs.NIGHT_DIM, true)
            // Anchored on the hour this test is running in, and two hours
            // long. Zero to twenty-three looks like the whole day and is
            // twenty-three twenty-fourths of it: the hour from eleven at
            // night to midnight falls outside, so this passed for years and
            // failed for one hour every evening.
            .putInt(Prefs.NIGHT_FROM, thisHour())
            .putInt(Prefs.NIGHT_TO, (thisHour() + 2) % 24)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val clock = c.get().clockForTest()
            clock.toggleOrrery()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(900)
            )
            assertTrue(shoot(screenOf(c.get()), "orrery-night") > 3f)
        }
    }

    /**
     * The calendar with the sky on it.
     *
     * A ring for an eclipse, a streak for a shower, two dots for an
     * opposition, all at about eight pixels across in the corner of a
     * date cell that already carries a moon, a reminder dot and possibly a
     * star. Whether three marks that small can be told apart — and whether
     * a month with several of them reads as a calendar or as a rash — is
     * not something any assertion answers.
     */
    @Test
    fun `the calendar with the sky on it`() {
        prefs.edit().clear().commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().showCardForTest(Card.CALENDAR)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            c.get().showCardForTest(Card.CALENDAR)
            assertTrue(shoot(screenOf(c.get()), "calendar-sky") > 3f)
        }
    }

    /** And the calendar at night, which is said to stay bright somewhere. */
    @Test
    fun `the calendar at night`() {
        prefs.edit().clear()
            .putBoolean(Prefs.NIGHT_DIM, true)
            // Anchored on the hour this test is running in, and two hours
            // long. Zero to twenty-three looks like the whole day and is
            // twenty-three twenty-fourths of it: the hour from eleven at
            // night to midnight falls outside, so this passed for years and
            // failed for one hour every evening.
            .putInt(Prefs.NIGHT_FROM, thisHour())
            .putInt(Prefs.NIGHT_TO, (thisHour() + 2) % 24)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().showCardForTest(Card.CALENDAR)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            c.get().showCardForTest(Card.CALENDAR)
            assertTrue(shoot(screenOf(c.get()), "calendar-night") > 3f)
        }
    }

    /**
     * The solar system zoomed until the Earth's orbit is the rim.
     *
     * The far end of the pinch: the four outer planets have gone off the
     * edge, the year is marked out in days round the face, and the days
     * with something on them carry a dot outside it — grey behind, bright
     * ahead. Whether that reads as a calendar or as a hedgehog is not a
     * thing anybody can reason their way to.
     */
    @Test
    fun `the solar system zoomed to the year`() {
        prefs.edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .putBoolean(Prefs.ALARM_MARKERS, true)
            .putBoolean(Prefs.ZODIAC, true)
            .commit()
        // A diary with something on it, so the dots have days to sit on.
        val store = ReminderStore.all(context)
        store.clear()
        val cal = java.util.Calendar.getInstance()
        for (offset in listOf(-120, -60, -21, -3, 5, 19, 44, 90, 150)) {
            val d = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, offset)
            }
            store.add(
                Reminder(
                    id = 1000 + offset,
                    label = "Something",
                    year = d.get(java.util.Calendar.YEAR),
                    month = d.get(java.util.Calendar.MONTH) + 1,
                    day = d.get(java.util.Calendar.DAY_OF_MONTH),
                    hour = 10, minute = 0
                )
            )
        }
        ReminderStore.save(context)

        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val clock = c.get().clockForTest()
            clock.toggleOrrery()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(900)
            )
            clock.zoomOrrery(Orrery.MAX_ZOOM)
            assertTrue(shoot(screenOf(c.get()), "orrery-year") > 3f)
        }
        ReminderStore.all(context).clear()
        ReminderStore.save(context)
    }

    /**
     * The planets knocked off their orbits are actually drawn.
     *
     * They were not. The pieces on the floor of the case were painted
     * inside the layer that fades the clock away, and that layer is at
     * alpha zero once the planets have the dial — so a knock made them
     * vanish rather than spill. Nothing but a picture catches that: the
     * bodies existed, rolled about under gravity and reported themselves
     * correctly the whole time.
     */
    @Test
    fun `the fallen planets are on the picture, not only in the list`() {
        prefs.edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val clock = c.get().clockForTest()
            clock.toggleOrrery()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(900)
            )
            // Jupiter, which is the biggest disc on the dial and therefore
            // the one with something to count. In orbit it is there; on the
            // floor it must still be there, in the same colour, somewhere
            // else. The bug painted it at alpha zero, so it was nowhere.
            // The sky's own palette, not the clock's. Space is black in
            // every theme now, so the planets are drawn against black
            // whatever the dial is made of — and a colour taken from the
            // clock's theme is the colour of a planet on a face that is
            // not there any more.
            val jupiter = OrreryDial.colourOf(Orrery.Body.JUPITER, clock.skyThemeForTest())
            val inOrbit = countColour(clock, jupiter)
            assertTrue("Jupiter is not on the dial to begin with", inOrbit > 0)
            clock.knockHandsOff()
            // Long enough for gravity to carry them somewhere they were not
            // before, so "drawn" cannot be confused with "still in orbit".
            // One bitmap, drawn into over and over: forty of a phone-sized
            // canvas is half a gigabyte, and the test ran the heap out.
            val scratch = Bitmap.createBitmap(
                clock.width.coerceAtLeast(1), clock.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val onto = Canvas(scratch)
            repeat(40) {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(
                    java.time.Duration.ofMillis(16)
                )
                clock.invalidate()
                clock.draw(onto)
            }
            scratch.recycle()
            assertTrue("nothing came off", clock.fallenPlanetsForTest().isNotEmpty())
            assertTrue(
                "Jupiter vanished on its way to the floor",
                countColour(clock, jupiter) > 0
            )
            assertTrue(shoot(screenOf(c.get()), "orrery-spilled") > 3f)
        }
    }

    /** How many pixels of one colour a view puts on the glass. */
    private fun countColour(view: View, colour: Int): Int {
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888
        )
        view.draw(Canvas(bitmap))
        val r = (colour shr 16) and 0xFF
        val g = (colour shr 8) and 0xFF
        val b = colour and 0xFF
        var found = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                if (kotlin.math.abs(((p shr 16) and 0xFF) - r) < 6 &&
                    kotlin.math.abs(((p shr 8) and 0xFF) - g) < 6 &&
                    kotlin.math.abs((p and 0xFF) - b) < 6
                ) found++
                x += 2
            }
            y += 2
        }
        return found
    }

    /** How many different colours a view puts on the glass. */
    private fun colourCount(view: View): Int {
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888
        )
        view.draw(Canvas(bitmap))
        val seen = HashSet<Int>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                seen.add(bitmap.getPixel(x, y))
                x += 3
            }
            y += 3
        }
        return seen.size
    }
}

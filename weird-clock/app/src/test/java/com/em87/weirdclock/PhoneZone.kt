package com.em87.weirdclock

import java.util.TimeZone

/**
 * The phone's time zone, for a test that needs the phone to be somewhere.
 *
 * Some things here are drawn from the zone the phone keeps and nothing
 * else — where the clock's shadow falls on the sundial, which day a
 * wound sky is said to be on — so a test of them has to put the phone
 * in a zone. That is one line, and it is a trap.
 *
 * Robolectric's stand-in for the alarm manager remembers "the default
 * time zone" the first time it is loaded, and puts the JVM back to it
 * after every test. Loaded for the first time inside a test that has
 * already moved the phone to Madrid, what it remembers is Madrid, and
 * every test after it in the same run starts there whatever anybody
 * restores — which is how a test of a shadow on a June afternoon came
 * to fail on a day when the weather's tests happened to run first, and
 * on no other day. The order tests run in is not this project's to
 * choose: the ones that failed last time go first.
 *
 * So the zone is moved from here and nowhere else. This looks at where
 * home is before anything is changed, and makes Robolectric look too.
 * [NotesTest] holds every other test to it.
 */
object PhoneZone {

    private val home: TimeZone by lazy {
        // Loaded, and so made to remember, while the zone is still the
        // one the machine really keeps.
        Class.forName("org.robolectric.shadows.ShadowAlarmManager")
        TimeZone.getDefault()
    }

    /** Puts the phone in [zone] until [goHome]. */
    fun move(zone: String) {
        home
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    /** And back to wherever the machine running the tests really is. */
    fun goHome() {
        TimeZone.setDefault(home)
    }
}

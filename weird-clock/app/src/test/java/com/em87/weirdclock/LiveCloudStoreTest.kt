package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The keeping of the hourly frames: which are fetched, when, and what
 * it costs.
 *
 * Against a service made to answer as the real one was heard to on the
 * morning of 24 November 2026 — the newest frame's hour said beside a
 * request that names none, nothing said beside an hour it has, the
 * nearest hour sent and named for one it has just let go of, and a
 * blank for one long gone — with the five hours it had that morning,
 * both pictures of each, as it sent them.
 *
 * What is counted is requests and bytes, because that is what this
 * costs somebody: forty-five kilobytes for an hour's two pictures,
 * three hundred bytes to be told there is no new one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveCloudStoreTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val real = LiveCloudStore.fetch

    private val hour = LiveClouds.HOUR_MS
    private val minute = 60_000L

    private fun at(h: Int): Long = DailyImageStore.msOf("2026-11-24")!! + h * hour

    /** Ten past six that morning, which is when the frames were fetched. */
    private val now = at(6) + 10 * minute

    private fun bytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun jpeg(width: Int, height: Int, colour: Int): ByteArray {
        val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(colour) }
        return ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private val layer = "(satellite:global_longwave_imagery_mosaic)"

    /** The service, as it answered. */
    private inner class Service : LiveCloudStore.Fetch {
        /** The hours it has frames of: the infrared picture of each. */
        val has = HashMap<Long, ByteArray>()

        /** And the visible picture of each. */
        val sees = HashMap<Long, ByteArray>()

        /** What was on the phone of an hour's infrared when its visible picture was asked for. */
        val infraredThereFirst = CopyOnWriteArrayList<String>()

        /** Every request made of it, and how many bytes it sent back for each. */
        val asked = CopyOnWriteArrayList<String>()
        var sent = 0L

        /** Whether it says which hour the newest is. */
        var names = true

        /** Whether it answers at all. */
        var up = true

        override fun get(url: String): LiveCloudStore.Answer? {
            asked += url
            if (!up) return null
            val answer = answer(url)
            sent += answer.bytes.size
            return answer
        }

        private fun answer(url: String): LiveCloudStore.Answer {
            val small = "width=16&height=8" in url
            val time = url.substringAfter("&time=", "")
            val pictures = if (LiveClouds.VISIBLE in url) sees else has
            if (time.isEmpty()) {
                val newest = pictures.keys.max()
                return LiveCloudStore.Answer(
                    if (small) jpeg(16, 8, 0xFF808080.toInt()) else pictures[newest]!!,
                    if (names) "99 Default value used: time=${LiveClouds.stamp(newest)} ISO8601 $layer" else null
                )
            }
            val wanted = LiveClouds.frameTime("time=$time", null)!!
            if (pictures === sees && File(context.cacheDir, "live-${wanted / 60_000L}.jpg").exists()) {
                infraredThereFirst += time
            }
            pictures[wanted]?.let { return LiveCloudStore.Answer(it, null) }
            // The nearest it has; of two as near, the earlier.
            val near = pictures.keys.sorted().minByOrNull { Math.abs(it - wanted) }!!
            if (Math.abs(near - wanted) <= hour) {
                return LiveCloudStore.Answer(pictures[near]!!, "99 Nearest value used: time=${LiveClouds.stamp(near)} ISO8601 $layer")
            }
            return LiveCloudStore.Answer(
                jpeg(720, 360, 0xFFFFFFFF.toInt()),
                "99 No nearest value found on satellite:global_longwave_imagery_mosaic: time"
            )
        }

        /** The requests for whole infrared pictures, as the hours they named. */
        fun frames(): List<String> = asked.filter { "width=720" in it && LiveClouds.LAYER in it }
            .map { it.substringAfter("&time=", "newest").substring(11, 16) }

        /** And for visible ones. */
        fun seens(): List<String> = asked.filter { "width=720" in it && LiveClouds.VISIBLE in it }
            .map { it.substringAfter("&time=", "newest").substring(11, 16) }

        fun stamps(): Int = asked.count { "width=16" in it }
    }

    private lateinit var service: Service

    @Before
    fun theMorningOfThe24th() {
        prefs.edit().clear()
            .putBoolean(Prefs.WEATHER, true)
            .putBoolean(Prefs.HEMISPHERE_CLOUDS, true)
            .putString(Prefs.HEMISPHERE_CLOUDS_KIND, SatelliteClouds.Kind.LIVE.key)
            .commit()
        LiveCloudStore.forget(context)
        service = Service()
        for (h in 1..5) {
            service.has[at(h)] = bytes("nowcoast-ir-2026-11-24T%02d.jpg".format(h))
            service.sees[at(h)] = bytes("nowcoast-vis-2026-11-24T%02d.jpg".format(h))
        }
        LiveCloudStore.fetch = service
    }

    @After
    fun putTheSocketBack() {
        LiveCloudStore.fetch = real
        LiveCloudStore.forget(context)
        TimeKeeper.solarOffsetMs = 0L
    }

    private fun hours(): List<String> = LiveCloudStore.onDisk(context).map { LiveClouds.stamp(it).substring(11, 16) }

    /**
     * The hour is asked first, with a stamp, and then the frames by
     * their hours: the newest and the two before it, then the two
     * before those, then nothing — and nothing is ever asked for twice.
     */
    @Test
    fun `the newest frame and the hours before it, three at a time and each once`() {
        assertTrue(LiveCloudStore.refresh(context, now))
        assertEquals(listOf("03:00", "04:00", "05:00"), hours())
        assertEquals("the hour was not asked first, with a stamp: ${service.asked.first()}", LiveClouds.ask(), service.asked.first())
        assertEquals(listOf("05:00", "04:00", "03:00"), service.frames())
        assertEquals("the visible picture of each hour was not asked for with it", listOf("05:00", "04:00", "03:00"), service.seens())
        assertFalse("a frame was asked for without its hour", service.asked.any { "width=720" in it && "time=" !in it })

        assertTrue(LiveCloudStore.refresh(context, now + 5 * minute))
        assertEquals(listOf("01:00", "02:00", "03:00", "04:00", "05:00"), hours())
        // The two older hours, and the one before them that the service
        // turned out to have let go of: asked once, to find that out.
        assertEquals(listOf("05:00", "04:00", "03:00", "02:00", "01:00", "00:00"), service.frames())
        // No visible picture is asked for of an hour whose infrared was not had.
        assertEquals(listOf("05:00", "04:00", "03:00", "02:00", "01:00"), service.seens())

        // And after that an asking is a stamp and nothing else.
        val before = service.sent
        assertFalse(LiveCloudStore.refresh(context, now + 10 * minute))
        assertFalse(LiveCloudStore.refresh(context, now + 15 * minute))
        assertEquals("a frame was asked for that the phone has or the service has not", 6, service.frames().size)
        assertEquals(5, service.seens().size)
        assertEquals(4, service.stamps())
        val cost = service.sent - before
        println("MEASURE two askings with no new frame: $cost bytes; five hours, both pictures, and the hour before them: $before bytes")
        assertTrue("two askings with nothing new cost $cost bytes", cost < 2_000)

        // The next hour is published: one stamp, and its two pictures.
        service.has[at(6)] = bytes("nowcoast-ir-2026-11-24T01.jpg")
        service.sees[at(6)] = bytes("nowcoast-vis-2026-11-24T01.jpg")
        assertTrue(LiveCloudStore.refresh(context, now + hour))
        assertEquals(listOf("01:00", "02:00", "03:00", "04:00", "05:00", "06:00"), hours())
        assertEquals(7, service.frames().size)
        assertEquals("06:00", service.frames().last())
        assertEquals("06:00", service.seens().last())
        assertEquals(6, service.seens().size)
        // The visible picture is put down before the infrared one, which is what says the hour is there.
        assertEquals("an hour was on the phone before its visible picture was", emptyList<String>(), service.infraredThereFirst.toList())
    }

    /**
     * An hour is both its pictures where the service has both, and its
     * infrared alone where the visible one cannot be had — which is not
     * asked for again.
     */
    @Test
    fun `an hour without its visible picture is the infrared's alone`() {
        // The service has let go of four o'clock's visible picture, and
        // sends three's for it, saying so; and five's is a page of text.
        service.sees.remove(at(4))
        service.sees[at(5)] = "<html>try later</html>".toByteArray()
        assertTrue(LiveCloudStore.refresh(context, now))
        assertEquals(listOf("03:00", "04:00", "05:00"), hours())
        assertEquals(
            "a visible picture was kept that is not a picture, or not of its hour",
            (listOf(3, 4, 5).map { "live-${at(it) / 60_000L}.jpg" } + "live-${at(3) / 60_000L}-v.jpg").sorted(),
            context.cacheDir.list()!!.filter { it.startsWith("live-") }.sorted()
        )
        fun pixels(name: String): IntArray {
            val raw = bytes(name)
            val picture = BitmapFactory.decodeByteArray(raw, 0, raw.size)!!
            return IntArray(picture.width * picture.height).also {
                picture.getPixels(it, 0, picture.width, 0, 0, picture.width, picture.height)
            }
        }
        val alone = LiveClouds.field(pixels("nowcoast-ir-2026-11-24T04.jpg"), null, 720, 360, 0.0, 0.0)
        assertTrue(
            "an hour with no visible picture is not its infrared alone",
            LiveCloudStore.cached(context, at(4))!!.cover.contentEquals(alone)
        )
        val three = LiveCloudStore.cached(context, at(3))!!
        val sun = Hemisphere.subsolar(at(3))
        assertTrue(
            "an hour with both pictures is not made of both, or not with the sun where it stood",
            three.cover.contentEquals(
                LiveClouds.field(
                    pixels("nowcoast-ir-2026-11-24T03.jpg"), pixels("nowcoast-vis-2026-11-24T03.jpg"), 720, 360, sun[0], sun[1]
                )
            )
        )
        assertFalse(three.cover.contentEquals(LiveClouds.field(pixels("nowcoast-ir-2026-11-24T03.jpg"), null, 720, 360, 0.0, 0.0)))
        // Not gone back for: the hour is on the phone.
        val asked = service.seens().size
        LiveCloudStore.refresh(context, now + 5 * 60_000L)
        assertEquals(listOf("02:00", "01:00"), service.seens().drop(asked))
        // A visible picture landing later makes the hour again, while it
        // is the hour held: of both pictures now.
        val four = LiveCloudStore.cached(context, at(4))!!
        assertSame(four, LiveCloudStore.cached(context, at(4)))
        File(context.cacheDir, "live-${at(4) / 60_000L}-v.jpg").writeBytes(bytes("nowcoast-vis-2026-11-24T04.jpg"))
        val again = LiveCloudStore.cached(context, at(4))!!
        assertNotSame("the hour was not made again when its visible picture arrived", four, again)
        assertFalse(again.cover.contentEquals(alone))
    }

    /** A frame is kept under the hour the service says it is of, and under no other. */
    @Test
    fun `a frame is kept by the hour the service names and refused without one`() {
        // The service sends the newest and does not say which hour it is.
        service.names = false
        assertFalse(LiveCloudStore.refresh(context, now))
        assertEquals("a frame of no hour was kept", emptyList<String>(), hours())
        assertEquals("a frame was fetched before its hour was known", 0, service.frames().size)

        // It has let go of four o'clock: asked for it, it sends five's
        // and says so. That is not kept as four's.
        service.names = true
        service.has.remove(at(4))
        assertTrue(LiveCloudStore.refresh(context, now + 5 * minute))
        assertEquals(listOf("05:00"), hours())
        // Nor is anything older gone after, that time or the next.
        assertEquals(listOf("05:00", "04:00"), service.frames())
        assertFalse(LiveCloudStore.refresh(context, now + 10 * minute))
        assertEquals(listOf("05:00", "04:00"), service.frames())
        // The file is the frame it was said to be.
        val kept = File(context.cacheDir, "live-${at(5) / 60_000L}.jpg")
        assertTrue(kept.readBytes().contentEquals(bytes("nowcoast-ir-2026-11-24T05.jpg")))
    }

    /** What is not a picture, or is a picture of nothing, is not kept, and a service that is down costs nothing. */
    @Test
    fun `a blank answer and no answer are both nothing`() {
        service.up = false
        assertFalse(LiveCloudStore.refresh(context, now))
        assertEquals(1, service.asked.size)
        assertEquals(emptyList<String>(), hours())
        service.up = true
        service.has[at(5)] = jpeg(720, 360, 0xFF000000.toInt())
        assertFalse(LiveCloudStore.refresh(context, now + 5 * minute))
        assertEquals("a black picture was kept as a frame", emptyList<String>(), hours())
        service.has[at(5)] = "<html>service unavailable</html>".toByteArray()
        assertFalse(LiveCloudStore.refresh(context, now + 10 * minute))
        assertEquals("a page of text was kept as a frame", emptyList<String>(), hours())
        assertEquals("a part-written file was left behind", 0, context.cacheDir.list()!!.count { it.startsWith("live-") })
    }

    /** Only with the weather on, the clouds on, and this kind chosen. */
    @Test
    fun `nothing is asked unless this kind of cloud is chosen`() {
        for (off in listOf(
            { prefs.edit().putBoolean(Prefs.WEATHER, false).commit() },
            { prefs.edit().putBoolean(Prefs.HEMISPHERE_CLOUDS, false).commit() },
            { prefs.edit().putString(Prefs.HEMISPHERE_CLOUDS_KIND, SatelliteClouds.Kind.VEIL.key).commit() },
            { prefs.edit().remove(Prefs.HEMISPHERE_CLOUDS_KIND).commit() }
        )) {
            theMorningOfThe24th()
            // A frame put on the phone by hand, so there is one to be refused.
            File(context.cacheDir, "live-${at(5) / 60_000L}.jpg").writeBytes(bytes("nowcoast-ir-2026-11-24T05.jpg"))
            assertTrue(LiveCloudStore.cached(context, now) != null)
            // Three hours on that frame is old, and the next would be asked after.
            val later = now + 3 * hour
            assertTrue(LiveCloudStore.stale(context, later))
            assertTrue(LiveCloudStore.due(context, later))
            off()
            assertFalse(LiveCloudStore.wanted(context))
            assertFalse("the next frame is due for a kind of cloud that is not chosen", LiveCloudStore.stale(context, later))
            assertFalse(LiveCloudStore.due(context, later))
            assertFalse(LiveCloudStore.refresh(context, later))
            assertEquals("the service was asked", 0, service.asked.size)
            assertEquals("cloud of a kind not chosen", null, LiveCloudStore.cached(context, now))
        }
    }

    /**
     * When the next frame is asked after: once the newest is an hour and
     * forty-two minutes old, which is when the next was seen to be
     * there, and then not more often than the service's own answer goes
     * stale — or, for a clock asking by itself, every ten minutes.
     */
    @Test
    fun `the next frame is asked after when it is due, and not every minute`() {
        // Nothing on the phone: go.
        assertTrue(LiveCloudStore.stale(context, now))
        assertTrue(LiveCloudStore.due(context, now))
        LiveCloudStore.refresh(context, now)
        // The newest is five o'clock's: nothing to ask until eighteen to seven.
        assertFalse(LiveCloudStore.stale(context, now))
        assertFalse(LiveCloudStore.stale(context, at(5) + LiveCloudStore.NEXT_MS - 1))
        assertTrue(LiveCloudStore.stale(context, at(5) + LiveCloudStore.NEXT_MS))
        assertTrue(LiveCloudStore.due(context, at(5) + LiveCloudStore.NEXT_MS))
        assertEquals(at(6) + 42 * minute, at(5) + LiveCloudStore.NEXT_MS)

        // Asked then, and the service had nothing newer: only the older
        // hours the first fetch had left for the next.
        val asked = at(5) + LiveCloudStore.NEXT_MS
        assertTrue(LiveCloudStore.refresh(context, asked))
        assertEquals(at(5), LiveCloudStore.onDisk(context).last())
        // Somebody back at the phone asks again after four minutes, and not before.
        assertFalse(LiveCloudStore.stale(context, asked + LiveCloudStore.AGAIN_MS - 1))
        assertTrue(LiveCloudStore.stale(context, asked + LiveCloudStore.AGAIN_MS))
        // A clock standing there by itself waits ten.
        assertFalse(LiveCloudStore.due(context, asked + LiveCloudStore.AGAIN_MS))
        assertFalse(LiveCloudStore.due(context, asked + LiveCloudStore.PATIENCE_MS - 1))
        assertTrue(LiveCloudStore.due(context, asked + LiveCloudStore.PATIENCE_MS))
        assertEquals(4 * minute, LiveCloudStore.AGAIN_MS)
        assertEquals(10 * minute, LiveCloudStore.PATIENCE_MS)

        // A phone whose clock has been put back does not wait for the hour it asked at to come round.
        assertTrue(LiveCloudStore.stale(context, at(2)))
        assertTrue(LiveCloudStore.due(context, at(2)))
    }

    /** What a clock standing all day costs, counted. */
    @Test
    fun `a clock standing a day asks a few times an hour and fetches each frame once`() {
        // Each hour's frame is published forty minutes after the hour,
        // as that morning's were seen to be; the clock asks once a minute.
        val from = at(6)
        var t = from
        var fetched = 0
        while (t < from + 24 * hour) {
            val newest = (t - 40 * minute) / hour * hour
            if (newest !in service.has) {
                service.has[newest] = bytes("nowcoast-ir-2026-11-24T03.jpg")
                service.sees[newest] = bytes("nowcoast-vis-2026-11-24T03.jpg")
            }
            if (LiveCloudStore.due(context, t) && LiveCloudStore.refresh(context, t)) fetched++
            t += minute
        }
        val stamps = service.stamps()
        val frames = service.frames().size
        println("MEASURE a clock standing 24 hours: $stamps askings, $frames hours fetched, ${service.sent / 1024} kB in all")
        // Every hour published that day was fetched, and no picture of one twice.
        val whole = service.asked.filter { "width=720" in it }
        assertEquals("a picture was fetched twice", whole.size, whole.toSet().size)
        assertTrue("$frames hours fetched in a day of twenty-four", frames in 24..31)
        assertTrue("only $fetched of the askings brought anything back", fetched >= 24)
        // One asking an hour finds the frame, as near as makes no difference.
        assertTrue("$stamps askings in a day", stamps in 24..30)
        assertTrue("${service.sent} bytes in a day", service.sent < 1536 * 1024)
        // And the phone keeps half a day of them, not all of them.
        val kept = LiveCloudStore.onDisk(context)
        assertTrue("${kept.size} frames kept", kept.size in 11..13)
        assertTrue(kept.last() - kept.first() < LiveCloudStore.KEEP_MS)
    }

    /** The globe is handed the frame nearest the instant it shows, or none. */
    @Test
    fun `the cloud of an instant is the nearest frame's, within three hours`() {
        LiveCloudStore.refresh(context, now)
        LiveCloudStore.refresh(context, now + 5 * minute)
        fun pixels(name: String): IntArray {
            val raw = bytes(name)
            val picture = BitmapFactory.decodeByteArray(raw, 0, raw.size)!!
            return IntArray(picture.width * picture.height).also {
                picture.getPixels(it, 0, picture.width, 0, 0, picture.width, picture.height)
            }
        }
        fun field(h: Int): ByteArray {
            val sun = Hemisphere.subsolar(at(h))
            return LiveClouds.field(
                pixels("nowcoast-ir-2026-11-24T%02d.jpg".format(h)), pixels("nowcoast-vis-2026-11-24T%02d.jpg".format(h)),
                720, 360, sun[0], sun[1]
            )
        }
        // Now, ten past six: five o'clock's, which is the newest.
        val newest = LiveCloudStore.cached(context, now)!!
        assertEquals(720, newest.width)
        assertEquals(360, newest.height)
        assertTrue("not the hour of five o'clock, or not the rule's reading of it", newest.cover.contentEquals(field(5)))
        // One field for one frame, however often it is asked for.
        assertSame(newest, LiveCloudStore.cached(context, now))
        assertSame(newest, LiveCloudStore.cached(context, at(5) - 20 * minute))
        // Wound back, the frame of the hour it is wound to — which is told
        // from the newest by its hour, whatever dates are on the files:
        // three hours fetched in one go can land in one tick of the clock.
        val tick = File(context.cacheDir, "live-${at(5) / 60_000L}.jpg").lastModified()
        for (name in context.cacheDir.list()!!.filter { it.startsWith("live-") }) {
            File(context.cacheDir, name).setLastModified(tick)
        }
        val two = LiveCloudStore.cached(context, at(2) + 10 * minute)!!
        assertNotSame(newest, two)
        assertTrue(two.cover.contentEquals(field(2)))
        assertTrue(LiveCloudStore.cached(context, at(3) + 31 * minute)!!.cover.contentEquals(field(4)))
        assertTrue(LiveCloudStore.cached(context, at(3) + 29 * minute)!!.cover.contentEquals(field(3)))
        // Three hours either side of what the phone has, and no further.
        assertTrue(LiveCloudStore.cached(context, at(5) + LiveCloudStore.NEAR_MS) != null)
        assertEquals(null, LiveCloudStore.cached(context, at(5) + LiveCloudStore.NEAR_MS + 1))
        assertTrue(LiveCloudStore.cached(context, at(1) - LiveCloudStore.NEAR_MS) != null)
        assertEquals(null, LiveCloudStore.cached(context, at(1) - LiveCloudStore.NEAR_MS - 1))
        assertEquals(3 * hour, LiveCloudStore.NEAR_MS)
        // A frame fetched again is a new picture and a new field.
        val file = File(context.cacheDir, "live-${at(5) / 60_000L}.jpg")
        val was = LiveCloudStore.cached(context, now)!!
        file.setLastModified(file.lastModified() + 5_000L)
        assertNotSame(was, LiveCloudStore.cached(context, now))
        // And a file that is not a picture is no cloud, not a crash.
        file.writeBytes(ByteArray(300) { 7 })
        file.setLastModified(file.lastModified() + 10_000L)
        assertEquals(null, LiveCloudStore.cached(context, now))
    }

    /** Half a day is kept, counted back from the newest frame the service has. */
    @Test
    fun `frames older than half a day behind the newest are thrown away`() {
        fun put(atMs: Long, kind: String = "") =
            File(context.cacheDir, "live-${atMs / 60_000L}$kind.jpg").writeBytes(bytes("nowcoast-ir-2026-11-24T01.jpg"))
        put(at(5) - LiveCloudStore.KEEP_MS)
        put(at(5) - LiveCloudStore.KEEP_MS, "-v")
        put(at(5) - LiveCloudStore.KEEP_MS + hour)
        put(at(5) - LiveCloudStore.KEEP_MS + hour, "-v")
        put(at(5) - 30 * hour)
        // A visible picture whose infrared never landed is thrown away with the rest.
        put(at(5) - 20 * hour, "-v")
        // And something else in the cache that is not this store's.
        val other = File(context.cacheDir, "clouds-2026-11-22.jpg").also { it.writeBytes(ByteArray(10)) }
        LiveCloudStore.refresh(context, now)
        assertEquals(listOf("18:00", "03:00", "04:00", "05:00"), hours())
        assertEquals(
            "the visible pictures were not thrown away with their hours",
            listOf(at(5) - LiveCloudStore.KEEP_MS + hour, at(3), at(4), at(5)).map { "live-${it / 60_000L}-v.jpg" }.sorted(),
            context.cacheDir.list()!!.filter { it.endsWith("-v.jpg") }.sorted()
        )
        assertTrue("another store's file went with them", other.exists())
        other.delete()
        assertEquals(12 * hour, LiveCloudStore.KEEP_MS)
    }

    /** Forgetting takes the frames, the field, and what was remembered about the service. */
    @Test
    fun `forgetting leaves nothing behind`() {
        LiveCloudStore.refresh(context, now)
        LiveCloudStore.refresh(context, now + 5 * minute)
        val stamp = File(context.cacheDir, "live-${at(5) / 60_000L}.jpg").lastModified()
        val held = LiveCloudStore.cached(context, now)!!
        LiveCloudStore.forget(context)
        assertEquals(emptyList<String>(), hours())
        assertEquals("a picture was left on the phone", 0, context.cacheDir.list()!!.count { it.startsWith("live-") })
        assertEquals(null, LiveCloudStore.cached(context, now))
        // Asked again at once: forgetting forgot when it last asked.
        assertTrue(LiveCloudStore.stale(context, now + 6 * minute))
        // A file landing afterwards with the same date on it is another picture.
        File(context.cacheDir, "live-${at(5) / 60_000L}.jpg").also {
            it.writeBytes(bytes("nowcoast-ir-2026-11-24T01.jpg"))
            it.setLastModified(stamp)
        }
        assertNotSame("the field of a frame that was thrown away", held, LiveCloudStore.cached(context, now))
        LiveCloudStore.forget(context)
        // And the hour the service was found not to have is asked after again.
        val before = service.frames().size
        LiveCloudStore.refresh(context, now + 20 * minute)
        LiveCloudStore.refresh(context, now + 25 * minute)
        assertEquals(listOf("05:00", "04:00", "03:00", "02:00", "01:00", "00:00"), service.frames().drop(before))
    }

    /** What the settings row says is on the phone. */
    @Test
    fun `the row says which frames the phone has`() {
        assertEquals(context.getString(R.string.clouds_on_phone_none), LiveCloudStore.describe(context))
        LiveCloudStore.refresh(context, now)
        val said = LiveCloudStore.describe(context)
        assertEquals(context.getString(R.string.live_on_phone, 3, "03:00", "05:00"), said)
        assertTrue(said, "3" in said && "03:00" in said && "05:00" in said && "UTC" in said)
    }

    /** On a thread of its own, one at a time, and no more often than asked directly. */
    @Test
    fun `fetched in the background when it is due`() {
        TimeKeeper.solarOffsetMs = now - System.currentTimeMillis()
        LiveCloudStore.refreshInBackground(context)
        LiveCloudStore.refreshInBackground(context)
        val deadline = System.currentTimeMillis() + 15_000L
        while (hours().size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("03:00", "04:00", "05:00"), hours())
        Thread.sleep(200)
        assertEquals("two fetches ran at once, or one ran twice", 1, service.stamps())
        // Nothing is due: neither kind of asking starts another.
        LiveCloudStore.refreshInBackground(context)
        LiveCloudStore.refreshInBackground(context, patient = true)
        Thread.sleep(200)
        assertEquals(1, service.stamps())
        // The next frame is due and the service has not got it. Five
        // minutes after being told so: a clock asking by itself waits,
        // and somebody who has picked the phone up does not.
        fun clockAt(atMs: Long) {
            TimeKeeper.solarOffsetMs = atMs - System.currentTimeMillis()
        }
        clockAt(at(5) + LiveCloudStore.NEXT_MS)
        LiveCloudStore.refreshInBackground(context, patient = true)
        Thread.sleep(300)
        assertEquals("the next frame was not asked after when it was due", 2, service.stamps())
        clockAt(at(5) + LiveCloudStore.NEXT_MS + 5 * minute)
        LiveCloudStore.refreshInBackground(context, patient = true)
        Thread.sleep(300)
        assertEquals("a clock asking by itself asked again after five minutes", 2, service.stamps())
        LiveCloudStore.refreshInBackground(context)
        Thread.sleep(300)
        assertEquals("somebody at the phone was made to wait", 3, service.stamps())
    }
}

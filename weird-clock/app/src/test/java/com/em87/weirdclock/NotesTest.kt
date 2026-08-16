package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notes have to describe what is under them.
 *
 * This app is documented in its own margins rather than anywhere else, so
 * a comment that has come adrift is not a tidiness problem — it is the
 * documentation being wrong. And they do come adrift: when two functions
 * are merged into one, or a property is renamed, or a doc is rewritten
 * above the old one, what is left is a block describing something that no
 * longer exists, sitting immediately above something it never described.
 *
 * Fourteen of them had accumulated. One sat above `chronoSettable`
 * explaining a property that had been deleted two versions earlier; two
 * others had drifted off the only two functions in the file that format
 * the digital readout, leaving both of those undocumented and a third
 * function wearing both descriptions. Every one of them was silent —
 * nothing compiles differently, and nothing reads differently until
 * somebody trusts one.
 *
 * The shape is what makes it findable: a doc block closing and another
 * opening with nothing at all in between can only mean one of them has
 * lost what it belonged to. Cheap to check, so it is checked.
 */
class NotesTest {

    /**
     * The Kotlin sources, read off disk.
     *
     * Gradle runs unit tests with the module directory as the working
     * directory, so this is the module's own source and not a copy of it.
     */
    private fun sources(): List<File> =
        File("src/main/java/com/em87/weirdclock")
            .listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }

    @Test
    fun `the source is where this test thinks it is`() {
        val files = sources()
        assertTrue(
            "no Kotlin found — the working directory is not the module",
            files.size > 20
        )
        assertTrue(
            "ClockView is the biggest thing here and it is missing",
            files.any { it.name == "ClockView.kt" }
        )
    }

    /**
     * No doc block may close onto the opening of another.
     *
     * Both spellings count. A note written on a single line closes its
     * block just as much as a closing marker sitting alone on one does,
     * and the first version of this test only knew the second: an adrift
     * one-liner walked straight past it, which is the shape half the notes
     * in this app are written in.
     *
     * (Written out in words rather than shown, because a closing marker
     * quoted inside a comment closes the comment. Which is how this test
     * first arrived: with the example in it, and the rest of the file
     * outside the comment and not compiling.)
     */
    @Test
    fun `no note has come adrift from what it describes`() {
        val adrift = mutableListOf<String>()
        for (file in sources()) {
            val lines = file.readLines()
            for (i in 0 until lines.size - 1) {
                val here = lines[i].trim()
                val closes = here == "*/" ||
                    (here.startsWith("/**") && here.endsWith("*/") && here.length > 4)
                if (closes && lines[i + 1].trim().startsWith("/**")) {
                    adrift += "${file.name}:${i + 2}"
                }
            }
        }
        assertEquals(
            "a note here describes something that is not under it: $adrift",
            emptyList<String>(), adrift
        )
    }
}

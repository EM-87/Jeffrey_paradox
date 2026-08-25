package com.em87.weirdclock

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * The alphabet the far-future dates are written in.
 *
 * A font, at last. The first two goes at this were reconstructions: the
 * shapes worked out from a photograph of a chart, arm by arm, and argued
 * about — the numerals came out close and the letters never did, because
 * a dozen of them differ by which of eight arms are lit and a photograph
 * at that size cannot tell you.
 *
 * So the whole reconstruction is gone and this is the real thing. Every
 * letter, every digit, and punctuation, which the star glyphs never had:
 * the app can write a *word* in it now, not only a date.
 *
 * Cached, because loading a typeface reads a file and the dial asks for
 * this on the way to drawing a frame.
 */
object Yautja {

    private var cached: Typeface? = null
    private var tried = false

    /**
     * The face, or null if the font could not be loaded.
     *
     * Null rather than a fallback: written in whatever face happens to be
     * to hand, a far-future date is not a joke that has failed, it is an
     * ordinary date with a wrong-looking year. Callers that get null
     * write nothing, the same way the dial writes nothing before there
     * were calendars.
     */
    fun face(context: Context): Typeface? {
        if (!tried) {
            tried = true
            cached = try {
                ResourcesCompat.getFont(context, R.font.yautja)
            } catch (e: Exception) {
                null
            }
        }
        return cached
    }
}

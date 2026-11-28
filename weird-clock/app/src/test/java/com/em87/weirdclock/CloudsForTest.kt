package com.em87.weirdclock

import android.graphics.Bitmap

/**
 * A photograph as cloud, for a test that has a photograph and no store:
 * the same rule the store applies — see [SatelliteClouds.field] — with
 * nothing to fill it from.
 */
internal fun cloudOf(picture: Bitmap): SatelliteClouds.Field {
    val w = picture.width
    val h = picture.height
    val pixels = IntArray(w * h)
    picture.getPixels(pixels, 0, w, 0, 0, w, h)
    return SatelliteClouds.Field(w, h, SatelliteClouds.field(pixels, w, h))
}

/**
 * Waits for a world that is being turned to be handed what it has asked
 * for somewhere else — see [HemisphereView.lookUp]. The asking is done
 * on a thread of its own and the handing over on this one, so both are
 * given their turn.
 */
internal fun HemisphereView.handedWhatItAskedFor() {
    val deadline = System.currentTimeMillis() + 10_000
    do {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        if (!lookingUpForTest()) return
        Thread.sleep(5)
    } while (System.currentTimeMillis() < deadline)
    throw AssertionError("what the world asked for somewhere else never came back")
}

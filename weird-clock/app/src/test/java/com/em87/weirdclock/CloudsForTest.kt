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

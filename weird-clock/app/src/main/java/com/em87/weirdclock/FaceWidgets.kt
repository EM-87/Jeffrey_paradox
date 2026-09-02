package com.em87.weirdclock

/**
 * One widget per clock, so a home screen can hold more than one of them.
 *
 * There was a single clock widget and it drew whichever face the app was
 * set to. That is a sensible thing for a clock to do and the wrong thing
 * for a *widget* to be: a home screen is not a settings page, and somebody
 * who wants the sundial beside the digital clock — or two of the world at
 * two sizes — was being told they could have exactly one clock and it
 * would be whichever one they had last chosen in the app.
 *
 * So the four faces are four entries in the launcher's own list, chosen
 * the way every other widget on the phone is chosen: by looking at them
 * and dragging one out. The original stays, unchanged, because it is what
 * is on people's home screens today and it still follows the app for
 * anybody who wants that.
 *
 * Everything else — the drawing, the transparency, the wake-ups, the
 * settings screen behind the launcher's gear — is [ClockWidgetProvider]'s
 * and is not repeated here. All any of these does is answer the one
 * question that used to be answered by reading a preference.
 */
class AnalogWidgetProvider : ClockWidgetProvider() {
    override val pinned: Face get() = Face.ANALOG
}

class DigitalWidgetProvider : ClockWidgetProvider() {
    override val pinned: Face get() = Face.DIGITAL
}

class SundialWidgetProvider : ClockWidgetProvider() {
    override val pinned: Face get() = Face.SUNDIAL
}

class WorldWidgetProvider : ClockWidgetProvider() {
    override val pinned: Face get() = Face.HEMISPHERE
}

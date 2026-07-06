# Weird Clock 🕰️

An analog clock app for Android with deliberately weird options.

> **Mide el tiempo como quieras** — *measure time however you want.*

## Features

**Dial**
- **Themes** — Midnight, Classic ivory, Neon, Terminal green, Sunset: each
  restyles the face, rim, ticks, numerals and every hand.
- **24-hour dial** — the hour hand makes one revolution per *day* instead of
  per 12 hours, with midnight at the top and noon at the bottom, plus a
  dedicated inner ring of 24 hour marks.
- **Numerals** — Arabic, Roman, or none at all.
- **Mirror mode** — the entire clock runs counterclockwise, as if seen in a
  mirror. Great for confusing guests.
- **Date complication** — shown as numbers (`06/07/2026`), text
  (`Mon 6 Jul`), or Roman numerals (`VI·VII·MMXXVI`).

**Touch**
- **Grab the hands** — drag any hand around the dial with your finger; when
  you let go it springs back to the real time with an overshoot bounce
  (haptic tick when you catch one).
- **Pinch to resize** — pinch the screen to shrink or grow the dial (the
  size is remembered); double-tap to snap back to full size.

**Hands**
- **Second hand** — on/off, and either ticking once per second or gliding in
  a smooth sweep.
- **Decimal time hands** — French Revolutionary time (10 hours per day, 100
  minutes per hour, 100 seconds per minute): a long hand that covers the
  whole day, a short fast one that spins once per decimal minute (86.4 real
  seconds), an inner 10-division ring, and a digital readout like `4.37.82`.

**Sounds** (all synthesized at runtime — the app ships zero audio files)
- **Hourly bells** in three styles:
  - *Count the hour* — grandfather-clock style, 1–12 strikes.
  - *Ship's bell* — nautical watch bells, struck in pairs, 8 bells at the
    watch change, including the odd-numbered half-hour bells.
  - *Single strike* — one bell every hour.
- **Half-hour ding** — a single higher-pitched bell at half past.
- **Ticking sound** — a mechanical "tik" every second.
- **Test button** in settings so you can hear the bells without waiting for
  the top of the hour.

Bells and ticks play while the app is in the foreground (it's a novelty
clock, not a background chime service).

**Home-screen widget**
- Add the Weird Clock widget to your launcher to see the time all day. It
  uses the system's self-updating analog clock machinery with custom dial
  and hand drawables, so it costs the app zero battery. Tapping it opens
  the full app. (System limitation: the widget is a standard 12-hour dial —
  the truly weird stuff lives in the app.)

**Alarms**
- A settings shortcut opens your system clock's new-alarm screen, so alarms
  ring reliably even when Weird Clock isn't running.

The app is localized in English and Spanish.

## Building

Open the `weird-clock/` folder in Android Studio (Koala or newer) and press
Run, or from the command line with a local Gradle installation:

```bash
cd weird-clock
gradle :app:assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 35). Min SDK is 24
(Android 7.0).

## How it works

- `ClockView.kt` — a custom `View` that draws the face, ticks, numerals,
  date and all five hands with `Canvas`, and handles the touch gestures
  (hand dragging with a spring-back `ValueAnimator`, pinch zoom via
  `ScaleGestureDetector`). Mirroring is done by negating the horizontal
  component of every polar coordinate, so the digits stay readable while
  the clock runs backwards.
- `ClockTheme.kt` — the color presets.
- `ChimePlayer.kt` — synthesizes bell strikes from four exponentially
  decaying inharmonic partials and plays them through a static `AudioTrack`.
- `MainActivity.kt` — hosts the clock, keeps the screen on, and runs a small
  scheduler that fires the chimes on minute boundaries.
- `SettingsActivity.kt` — a standard `PreferenceFragmentCompat` screen backed
  by `SharedPreferences`.
- `ClockWidgetProvider.kt` — the home-screen widget.

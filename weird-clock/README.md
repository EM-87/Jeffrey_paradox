# Weird Clock 🕰️

An analog clock app for Android with deliberately weird options.

## Features

**Dial**
- **24-hour dial** — the hour hand makes one revolution per *day* instead of
  per 12 hours, with midnight at the top and noon at the bottom, plus a
  dedicated inner ring of 24 hour marks.
- **Numerals** — Arabic, Roman, or none at all.
- **Mirror mode** — the entire clock runs counterclockwise, as if seen in a
  mirror. Great for confusing guests.

**Hands**
- **Second hand** — on/off, and either ticking once per second or gliding in
  a smooth sweep.
- **Decimal time hand** — an extra cyan hand showing French Revolutionary
  decimal time (10 hours per day, 100 minutes per hour, 100 seconds per
  minute), with its own inner 10-division ring and a digital readout like
  `4.37.82`.

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

- `ClockView.kt` — a custom `View` that draws the face, ticks, numerals and
  all four hands with `Canvas`. Mirroring is done by negating the horizontal
  component of every polar coordinate, so the digits stay readable while the
  clock runs backwards.
- `ChimePlayer.kt` — synthesizes bell strikes from four exponentially
  decaying inharmonic partials and plays them through a static `AudioTrack`.
- `MainActivity.kt` — hosts the clock, keeps the screen on, and runs a small
  scheduler that fires the chimes on minute boundaries.
- `SettingsActivity.kt` — a standard `PreferenceFragmentCompat` screen backed
  by `SharedPreferences`.

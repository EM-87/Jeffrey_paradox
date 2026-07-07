# Weird Clock 🕰️

An analog clock app for Android with deliberately weird options.

> **Mide el tiempo como quieras** — *measure time however you want.*

## Features

**Dial**
- **Themes** — Midnight, Classic ivory, Neon, Terminal green, Sunset: each
  restyles the face, rim, ticks, numerals and every hand.
- **Any number of hours on the dial** — presets for 10 (decimal day),
  12 (classic) and 24 (full day), or any count from 2 to 24 with a slider.
  A 7-hour clock is exactly as ridiculous as it sounds.
- **Numerals** — Arabic, Roman, or none at all.
- **Mirror mode** — the entire clock runs counterclockwise, as if seen in a
  mirror. Great for confusing guests.
- **Date complication** — shown as numbers (`06/07/2026`), text
  (`Mon 6 Jul`), or Roman numerals (`VI·VII·MMXXVI`).

**Touch**
- **Wind the hands** — grab any hand (hit zones are finger-sized and cover
  the whole hand, with priority for the thin second hand) and wind it around
  the dial. The other hands follow proportionally, like real gears: winding
  the hour hand whips the minute hand around and spins the second hand very
  fast. Every hour crossed rings a bell; the hour hand crossing midnight
  plays a calendar chime; winding the second hand rattles off accelerated
  ticks. Release, and true damped-spring physics unwinds the offset — the
  hands spin back the same number of turns, overshoot, wobble and settle.
- **Pinch to resize** — pinch the screen to shrink or grow the dial (the
  size is remembered); double-tap to snap back to full size.
- **Knock the hands off** — shake the phone hard and the hands fall off the
  axis, tumbling and bouncing inside the dial with simple physics. Pick each
  one up with your finger and carry it back to the center to remount it.

**Hands**
- **Second hand** — on/off, and either ticking once per second or gliding in
  a smooth sweep.
- **Fast hand** — an extra small hand over an inner 10-division ring:
  either *tenths of a second* (one turn per second) or one turn per
  *decimal minute* (86.4 real seconds, a nod to French Revolutionary time).

**Sounds** (all synthesized at runtime — the app ships zero audio files)
- **Hourly bells** in three styles, each with its own timbre:
  - *Count the hour* — grandfather-clock style, 1–12 low, long strikes.
  - *Ship's bell* — bright nautical watch bells, struck in pairs, 8 bells at
    the watch change, including the odd-numbered half-hour bells.
  - *Single strike* — one deep, long gong every hour.
- **Half-hour ding** — a single higher-pitched bell at half past.
- **Ticking sound** — a mechanical "tik" every second, played through a
  low-latency SoundPool and scheduled on second boundaries so it stays
  perfectly regular.
- **Test button** in settings that plays a sample of whichever bell style
  is currently selected.

Bells and ticks play while the app is in the foreground (it's a novelty
clock, not a background chime service).

**Home-screen widget**
- Add the Weird Clock widget to your launcher to see the time all day. The
  face is semi-transparent so your wallpaper shows through, and the ticks
  and hands match the app's Midnight theme. It uses the system's
  self-updating analog clock machinery with custom drawables, so it costs
  the app zero battery. Tapping it opens the full app. (System limitation:
  the widget is a standard 12-hour dial — the truly weird stuff lives in
  the app.)

**Alarms**
- The app has its own alarm system: pick a time in settings and it rings
  with the app's synthesized bells even when the app is closed — an exact
  AlarmManager alarm fires a foreground service with a full-screen ringing
  screen that works over the lock screen, repeats daily, survives reboots,
  and auto-stops after 3 minutes.

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

- `ClockView.kt` — a custom `View` that draws everything with `Canvas` and
  handles the gestures. Winding is modeled as a single *virtual time
  offset*: dragging any hand shifts the displayed time, which is what makes
  all hands move in gear-ratio lockstep, and releasing starts an
  underdamped `SpringAnimation` on the offset. The fallen-hands mode is a
  tiny rigid-body simulation (gravity, rim collisions, restitution) run
  per-frame. Mirroring is done by negating the horizontal component of
  every polar coordinate, so the digits stay readable while the clock runs
  backwards.
- `ClockTheme.kt` — the color presets.
- `ChimePlayer.kt` — synthesizes bell strikes from four exponentially
  decaying inharmonic partials and plays them through a static `AudioTrack`.
- `MainActivity.kt` — hosts the clock, keeps the screen on, and runs a small
  scheduler that fires the chimes on minute boundaries.
- `SettingsActivity.kt` — a standard `PreferenceFragmentCompat` screen backed
  by `SharedPreferences`.
- `AlarmScheduler.kt` / `AlarmReceiver.kt` / `AlarmService.kt` /
  `AlarmRingActivity.kt` / `BootReceiver.kt` — the in-app alarm system.
- `ClockWidgetProvider.kt` — the home-screen widget.

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
- **Selectable hours** — tap a numeral to highlight it in the accent color.
  Why? Nobody knows. Toggle one frantically and it falls off the dial.
- **Time speed slider** — run the clock anywhere from 25% to 400% of real
  time (with a warning: it does not allow time travel, and alarms are
  disabled while time is bent).

**Touch**
- **Wind the hands** — grab any hand (hit zones are finger-sized and cover
  the whole hand, with priority for the thin second hand) and wind it around
  the dial. While you hold it the whole mechanism freezes — no ticking, no
  creeping hands. The other hands follow proportionally, like real gears:
  winding the hour hand whips the minute hand around and spins the second
  hand very fast. Every hour crossed rings a bell; the hour hand crossing
  midnight plays a calendar chime; winding the second hand rattles off
  accelerated ticks. Release, and true damped-spring physics unwinds the
  offset — the hands spin back the same number of turns, overshoot, wobble
  and settle. Wind any hand more than 10 full turns and the mechanism
  explodes, throwing every hand off the axis.
- **Pinch to resize** — pinch the screen to shrink or grow the dial (the
  size is remembered); double-tap to snap back to full size.
- **Knock the hands off** — hit the phone hard and the hands (including the
  fast hand) fly off the axis *in the direction of the blow*, then tumble
  and bounce inside the dial under the live accelerometer gravity vector —
  tilt the phone and the debris rolls around. Keep knocking and the
  numerals shake loose too, about a third per hit. Fallen pieces collide
  with each other, and with the hands still mounted on the axis — the
  ticking second hand bats the debris around the dial. While the hands are
  down, a 7-segment digital clock appears under the dial so you can still
  read the time. Pick each hand up and carry it to the center to remount
  it; each numeral must be returned to its own spot on the dial. If it all
  gets out of hand, the **Put everything back** button in settings
  remounts everything in one tap.

**Hands**
- **Second hand** — on/off, and either ticking once per second or gliding in
  a smooth sweep.
- **Fast hand** — an extra small hand over an inner 10-division ring:
  either *tenths of a second* (one turn per second) or one turn per
  *decimal minute* (86.4 real seconds, a nod to French Revolutionary time).

**Sounds** (synthesized at runtime — the only bundled audio file is the
CC0 baby-cry recording used by the alarm)
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
  self-updating analog clock machinery, so it costs the app zero battery.
  On Android 12+ the dial and hands are re-rendered from your current
  settings — theme, hours on the dial, Roman numerals, date — so the widget
  is a copy of the in-app clock; older devices show the static Midnight
  design. Tapping it opens the full app.

**Alarms**
- Swipe left on the clock to reach the alarms page: as many alarms as you
  want, shown as blocks sorted by time. Tap + to add one, tap a time to
  edit it, toggle each alarm on/off, set it to repeat every day, weekdays
  only (Mon–Fri) or weekends only (Sat–Sun), and pick a sound per alarm:
  *bells*, *digital beep* (classic square-wave beep-beep-beep-beep), or
  *crying baby* — a real newborn recording (CC0, by "the_yura" via
  Wikimedia Commons), because humans are hard-wired to get up for that
  sound. A synthesized wail remains as fallback.
- Alarms ring even when the app is closed: an exact AlarmManager alarm
  fires a foreground service with a full-screen ringing screen that works
  over the lock screen, repeats daily, survives reboots, and auto-stops
  after 3 minutes. The old single alarm from settings is migrated
  automatically.

**Chronograph** — the card system mirrors the clock's: press the centered
⏳ button and the dial's hands glide into stopwatch position (the button
becomes 🕐 to go back); then, just as the clock swipes to the alarms, the
stopwatch swipes to a second rendered dial: the countdown. Each dial keeps
its own state and controls.
- **Stopwatch** — the main dial shows elapsed time with a 7-segment digital
  readout and Start/Pause/Reset buttons.
- **Countdown** — its own full dial on the next page; no dialogs: you
  *wind the hands* to set it (minute and
  hour hands take grab priority, no spring-back while setting), with the
  duration magnetized to round values — 5-minute multiples, so quarter,
  half and full hours snap into place; 30-second steps under 5 minutes.
  Press Start and the hands run down to zero, a chime announces the end,
  and while running the hands spring back as usual if you play with them.
- A chronograph can never show less than zero — hands pin at 0 instead of
  winding into negative time.
- The hands stay playable in chrono modes — wind them and they spring back
  just like on the clock, and over-winding 10 turns explodes the mechanism
  here too. But wind one *forward* more than a full turn and a big CHEATER!
  stamp slams across the dial, with a low womp-womp. The spring still
  returns the true time; cheaters gain nothing.

**World clock**
- An optional second mini-dial in the corner shows another time zone
  (17 cities to pick from), styled like the main clock.

**Settings** are split in two: a simple menu with what the average user
looks for (theme, numerals, date, bells, ticking), and an Advanced submenu
hiding the weird machinery (hours on the dial, mirror, touch physics, time
speed, world clock). When pieces are lying at the bottom of the dial, a
"Put everything back" panic button appears at the very top of the menu —
no scrolling required — and remounts everything in one tap. Rotating the
screen no longer resets the chaos: fallen pieces, winding and chrono state
survive orientation changes.

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

APKs are signed with the shared key in `signing/weirdclock.keystore`
(passwords in `app/build.gradle.kts`), so a build from any machine installs
cleanly over any previous install. This is a deliberate convenience for an
APK-distributed hobby app — a Play Store release would need a private key.
Note that Android refuses to install an APK with a *lower* versionCode over
a newer one, so always install the latest build.

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
- `Alarm.kt` / `AlarmScheduler.kt` / `AlarmReceiver.kt` / `AlarmService.kt` /
  `AlarmRingActivity.kt` / `BootReceiver.kt` — the in-app multi-alarm
  system (JSON-persisted list; the next upcoming alarm is armed and each
  firing re-arms the following one).
- `ClockWidgetProvider.kt` — the home-screen widget.

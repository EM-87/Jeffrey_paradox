# Weird Clock 🕰️

An analog clock app for Android with deliberately weird options.

> **Mide el tiempo como quieras** — *measure time however you want.*

## Features

**Dial**
- **Themes** — Midnight, Classic ivory, Neon, Terminal green, Sunset: each
  restyles the face, rim, ticks, numerals and every hand.
- **Dial shapes** — round, triangular, square, hexagonal or octagonal. The
  polygonal faces keep the same angular layout but the boundary breathes in
  and out between corners, and everything pinned to the rim follows it:
  ticks, numerals, the chrono crown and pushers — even the hands stretch
  into the corners as they sweep. Fallen pieces tumble inside the polygon.
- **Any number of hours on the dial** — presets for 10 (decimal day),
  12 (classic) and 24 (full day), or any count from 2 to 24 with a slider.
  A 7-hour clock is exactly as ridiculous as it sounds.
- **Numerals** — Arabic, Roman, or none at all.
- **Mirror mode** — the entire clock runs counterclockwise, as if seen in a
  mirror. Great for confusing guests.
- **Date complication** — shown as numbers (`06/07/2026`), text
  (`Mon 6 Jul`), or Roman numerals (`VI·VII·MMXXVI`).
- **Moon phase** (optional) — a proper lunar complication on the dial,
  computed from the synodic month and drawn with the classic
  terminator-ellipse construction.
- **Alarms on the dial** (optional, on by default) — every enabled alarm
  appears as a small accent wedge at its time on the clock face,
  Sectograph-style.
- **Selectable hours** — tap a numeral to highlight it in the accent color.
  Why? Nobody knows. Toggle one frantically and it falls off the dial.
- **Time speed slider** — run the clock anywhere from 25% to 400% of real
  time (with a warning: it does not allow time travel, and alarms are
  disabled while time is bent).
- **Sun time** — the sundial mode: the whole app runs on local apparent
  solar time (`UTC + longitude·4min + equation of time`), so noon on the
  dial is the moment the sun actually crosses your meridian. One coarse
  location fix, cached, no network; alarms stay on civil time.
- **Dynamic theme (Material You)** — on Android 12+ the dial, hands,
  widgets and floating hourglass dress in your wallpaper's palette.
- **Automatic night mode** (optional) — from 22:00 to 07:00 every color
  dims to 30% (the alarms card included), so the clock glows softly
  instead of lighting the bedroom — and the hourly bells hold their
  tongue until morning.

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
  settings — theme, **dial shape**, hours on the dial, Roman numerals,
  date — so the widget is a copy of the in-app clock, polygonal faces
  included; older devices show the static Midnight design. Tapping it
  opens the full app.

**Alarms**
- Swipe left on the clock to reach the alarms page: as many alarms as you
  want, shown as blocks sorted by time. Tap + to add one; tap a time to
  edit it — the screen jumps to the clock dial running the countdown's
  wind-to-set engine under a "Set alarm time" banner: wind the hands to
  the alarm time (with magnets and haptics) and confirm with ✓. Also toggle each alarm on/off, set it to repeat every day, weekdays
  only (Mon–Fri) or weekends only (Sat–Sun), and pick a sound per alarm:
  *bells*, *digital beep* (classic square-wave beep-beep-beep-beep), or
  *crying baby* — a real newborn recording (CC0, by "the_yura" via
  Wikimedia Commons), because humans are hard-wired to get up for that
  sound. A synthesized wail remains as fallback. Or pick **any audio file
  on your phone** (via the system file picker) as a per-alarm sound — if
  the file ever disappears, the bells take over, because an alarm must
  never fail silently. Each alarm also has a per-alarm snooze setting
  cycling off → 5 min → 10 min, offered on the ring screen and its
  notification alongside Stop. Alarms can carry a label
  ("Gym", "Pills") shown while ringing, and by default ring with gradual
  volume — starting quiet and climbing to full over a minute.
- Alarms ring even when the app is closed: an exact AlarmManager alarm
  fires a foreground service with a full-screen ringing screen that works
  over the lock screen, repeats daily, survives reboots, and auto-stops
  after 3 minutes. The old single alarm from settings is migrated
  automatically.

**Chronograph** — the card system mirrors the clock's: press the centered
⏳ button and the dial's hands glide into stopwatch position (the button
becomes 🕐 to go back); then, just as the clock swipes to the alarms, the
stopwatch swipes to a second rendered dial: the countdown.
- **Drawn case hardware** — entering the chronograph fades watch furniture
  in (and out, on the way back): a big ridged crown at 12, the start/stop
  pusher at 1:30 (accent-tinted while running, thumb side) and a smaller
  reset pusher at 10:30. The pushers are touchable, with press animation
  and haptics, and are the *only* controls: no bottom buttons, no labels.
  Tap the crown and the clock answers with a cuckoo call; tap it five
  times fast and you'll overwind the whole mechanism.
- **Stopwatch** — the main dial shows elapsed time with a 7-segment digital
  readout: MM:SS:CC with live centiseconds under the hour, HH:MM:SS beyond
  (and a proper 7-segment minus sign below zero). Wind the hands
  into negative time and the spring brings it back. The CHEATER stamp only
  fires while the stopwatch is actually running — a stopped one has
  nothing to cheat.
- **Countdown** — its own full dial on the next page, always the same size
  and shape as the main dial (pinching either one resizes both), and the
  chaos travels with you: pieces knocked off on the stopwatch are lying on
  the countdown's floor too. No dialogs: you
  *wind the hands* to set it. Magnets are sticky detents with a
  **progressive grid**: minute steps up to 5 minutes, 5-minute steps to
  half an hour, quarter-hours to two hours, hourly beyond — so sweeping
  across an hour doesn't rattle through 75 detents. And they only engage
  in the **precision band**, the ring between the numerals and the rim
  where your finger goes for fine adjustment; whip the hand around from
  near the center and it spins free, no haptic machine-gun. Grabbing the
  stacked hands from *outside* the dial picks the second hand (for
  seconds-scale countdowns); from inside, the minute hand wins. (The
  alarm-time engine keeps a flat 5-minute grid.)
- **Laps** — while the stopwatch runs, the lower pusher records a lap:
  a ghost second hand freezes on the dial (up to nine, fading with age).
  Stopped, the same pusher resets and clears them.
- The hands stay playable in chrono modes — wind them and they spring back
  just like on the clock, and over-winding 10 turns explodes the mechanism
  here too. But wind one *forward* more than a full turn and a big CHEATER!
  stamp slams across the dial, with a low womp-womp. The spring still
  returns the true time; cheaters gain nothing.

**Hourglass home-screen widget**
- A 1×2 hourglass widget for the launcher: while a countdown runs it
  shows the live sand level and remaining time (updated by the app in
  the foreground and the countdown service in the background); idle,
  the sand rests. Tap to open the app.

**Countdown in the background**
- Leave the app with the countdown running and a **floating hourglass**
  appears over other apps (with the draw-over-apps permission): sand
  drains from the top bulb into the bottom in real time, the remaining
  time printed underneath, in your theme's colors. Drag it anywhere (the
  spot is remembered); tap it to jump back into the app. A setting (on by
  default) turns it off to keep only the notification.
- Either way an ongoing notification keeps the countdown one glance away:
  live remaining time (chronometer) plus a progress bar, in the status
  bar and on the lock screen, with a Cancel action.
  At zero it rings the finish chime even with the app closed, and the
  app resyncs when you come back.

**Calendar (C3)**
- Swipe past the alarms and there's a month calendar drawn in the clock's
  theme: chevrons page through months, tapping the title jumps back to
  today, Sundays in the accent color, today circled — and every single day
  carries its own tiny moon phase. If the dial uses Roman numerals, so does
  the calendar. Obviously.

**Hourglass (S3)**
- The chronograph's third card: the countdown as an actual **particle
  system**. Every grain of sand is a simulated body under the live
  accelerometer gravity vector, and the neck is a gate that only lets
  through as many grains as the clock has earned — the pile drains at
  exactly the countdown's pace. Time becomes physical: **lay the phone
  flat or on its side and the sand can't reach the neck, so the countdown
  stops**; flip the phone upside down and the fallen sand becomes the sand
  still to fall (remaining and elapsed swap, like turning a real
  hourglass). The glass has a proper curved hourglass silhouette, its
  containment is analytic (no grain can ever tunnel out), and you can
  poke the pile with a finger to scatter the sand. It fills the screen
  and zooms with the shared pinch scale of all the dials. Four preset
  buttons below — 3′ 5′ 10′ 15′, one selectable at a time — pour the
  matching amount of sand into the glass.

**The hidden metronome** 🥁
- There is a BPM counter in the app, and no button leads to it. Tap the
  version number in Settings seven times (in the finest Android tradition)
  and a tap-tempo screen opens: tap the beat, read your BPM on a 7-segment
  display, and tap the metronome to have it keep your rhythm, pendulum
  swinging.

**World-clock bubbles**
- World clocks are **bubbles**: add up to six cities (typed with
  autocomplete over the whole timezone database) and each becomes a mini
  dial floating over the main clock, styled like it. They dock in a tidy
  3×2 grid along the top and stay put — until you fling one, or knock the
  phone hard enough to shed the main clock's hands (that shakes every
  bubble loose, tiny hands falling and all). Free bubbles are *buoyant*:
  they drift against the accelerometer's gravity, bobbing toward whatever
  edge is currently up, bouncing off the screen, off each other (waking
  resting bubbles on impact) and off the main dial itself. They fade in
  and out with the chronograph transition like the crown and pushers, and
  "Put everything back" pins them back into the grid.

**Calendar reminders**
- Tap any day in the calendar (C3) to add a dated reminder with its own
  time and label: it rings like an alarm — full-screen, over the lock
  screen, snoozable — then expires. Days with reminders carry an accent
  dot in the calendar, and today's reminders join the alarms as
  Sectograph-style markers on the main dial.

**Settings** are split in two: a simple menu with what the average user
looks for (theme, numerals, date, bells, ticking), and an Advanced submenu
hiding the weird machinery (hours on the dial, mirror, touch physics, time
speed, world clock). When pieces are lying at the bottom of the dial, a
"Put everything back" panic button appears at the very top of the menu —
no scrolling required — and remounts everything in one tap. Rotating the
screen no longer resets the chaos: fallen pieces, winding and chrono state
survive orientation changes.

The app is localized in English and Spanish, and follows the system
light/dark mode — the page backgrounds and UI text switch palettes, while
the dial keeps whichever theme you dressed it in.

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

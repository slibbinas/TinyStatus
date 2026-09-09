# TinyStatus — your TinyMaker printer on your wrist

A Wear OS app that shows what your **TinyMakerWiFi** printer is doing: how much
time is left, which layer, how much resin is still in the vat, and a progress
ring around the screen. It can also **watch the print in the background** and
buzz your wrist when the print ends or the resin runs low, and it can put the
numbers **on your watch face** as complications.

It is not on Google Play, so it is installed by **sideloading**.

## What you need

- A Wear OS 4+ watch (developed and tested on Galaxy Watch 8)
- **TinyMakerWiFi firmware 0.11.0 or newer.** Older builds (from 0.8.4) work
  too, except for the low-resin warning — the API had no `vatLow` back then
- Watch and printer on the **same Wi-Fi network**

## Installing

### Option A: from your phone, no computer

1. **On the watch:** `Settings → About watch → Software`, then tap
   `Software version` five times until `Developer options` appears
2. In there, turn on **`ADB debugging`** and **`Wireless debugging`**
3. **On your phone**, install an app that speaks `adb` over Wi-Fi
   (for example *Wear Installer 2*)
4. On the watch open `Wireless debugging → Pair new device` — it shows a code
5. Pair from the phone using that code, then pick the downloaded
   `tinystatus.apk`
6. **Turn both debugging switches off when you are done** — otherwise the watch
   keeps a permanent "Debugging enabled" notification and buzzes every time the
   connection drops

### Option B: from a computer

You need `adb` (Android SDK platform-tools). Steps 1–2 on the watch are the
same.

```bash
adb pair <watch-ip>:<pairing-port>     # the watch shows the code
adb connect <watch-ip>:<port>
adb install -r tinystatus.apk
```

Use **`install -r`** when updating — it upgrades in place.

The first time you open the app it asks to allow notifications. Say yes, or the
print-finished and resin alerts will have nowhere to go.

## Using it

### The screen

| Where | What |
|---|---|
| Big number | **Time left.** After a print: `DONE`, `CANCELED` or `STOPPED`. Idle: `IDLE`. No answer for 90 s: `OFFLINE` |
| Above it | Layer `232 / 378` |
| Below | Printer state, model name, resin left in the vat |
| Bottom | Seconds since the printer last answered, and the background interval when the watcher is running |
| Ring | Progress, marked every 10 % |

Progress is computed from the layers: the API reports no percentage.

### Gestures

| Gesture | What happens |
|---|---|
| **Swipe up** | Refresh now — the arrow at the bottom spins while it asks |
| **Swipe down** | Leave the app |
| **Tap** | Refresh (same as swipe up) |
| **Long press** | Settings |
| **Swipe sideways** | Next printer, when you have more than one |

Inside settings, a sideways swipe or the Back button goes back.

### Settings

**BACKGROUND** — how often the app checks the printer **while it is closed**:
`Off`, `Every 30 s`, `2`, `5` or `10 min`. With the app open on screen it always
refreshes every 5 seconds regardless of this setting.

The watcher **only runs while a print is running**, because the printer
announces nothing by itself and there is nothing to watch otherwise:

- it **starts** when you close the app while a print is in progress — so the
  habit is: start the print, open TinyStatus once, put your wrist down;
- it **stops** on its own 20 minutes after the print ends, after 30 minutes
  without an answer (it tells you), or when the battery drops below 15 %;
- while it runs, a quiet ongoing notification says `Watching TinyMaker`.

Accuracy: exact while the watch is on your wrist. Left on a table the watch
enters deep doze, and Android stretches the interval to roughly 10 minutes no
matter what you picked. That is a platform limit, not a setting.

**ALERTS** — which events buzz your wrist. They mirror what the printer's own
Telegram/Discord notifications send:

| Switch | Fires when | Default |
|---|---|---|
| Print end | The print finishes, is canceled, or stops early | on |
| Resin low | Resin drops below the printer's warn level (default 5 ml) | on |
| Resin out | Resin hits the stop level (default 2 ml) or the printer pauses to refill | on |
| Paused | You pause the print by hand | off |

Each event is announced **once**, when it happens — not repeated every poll.
The thresholds come from the printer itself (`/api/config`), so changing them
on the printer changes them here too.

One honest limitation: if a print is canceled between two polls and the app
never sees the cancel itself, it reports `Print stopped … not finished` rather
than `canceled`. It says what it knows, not what it guesses.

**PRINTER** — `Auto search` on (the default) finds a single printer by name
(`tinymaker.lan`). Turn it off to enter **up to four printers by IP**: tap
`Add printer`, type the address, `Save`. Tap a printer in the list to edit or
`Remove` it.

Why IPs for multiple printers: every TinyMaker answers to the same name, so the
name cannot tell two of them apart. **Reserve the addresses in your router**
(DHCP reservation) — otherwise a printer can get a new address and the app will
just show `OFFLINE`.

### On your watch face

The app publishes four complication data sources. In your watch face's
complication picker they appear as:

- **TinyMaker progress ring** — a ring plus the percentage (needs a watch face
  slot that accepts `RANGED_VALUE`)
- **TinyMaker progress %** — `62%`
- **TinyMaker time left** — `1h20`
- **TinyMaker resin left** — `7.4ml`

They show `DONE` for 12 hours after a print and `IDLE` when nothing is running.
Tapping one opens the app.

The complications **never fetch anything themselves** — they answer from the
last value the app or the background watcher fetched. That is deliberate: the
system asks providers every time you raise your wrist, and fetching there would
turn every wrist-raise into a Wi-Fi wake-up.

## Battery

Closed and with `BACKGROUND` off, the app does nothing at all. With the watcher
running it wakes for a fraction of a second per interval, holds the CPU only
while the request is in flight, and prefers a Wi-Fi connection that is already
up over waking the radio. Waking the Wi-Fi radio is the expensive part
(3–6 seconds of radio), not keeping it associated.

## Privacy

Everything stays on your network. The app talks only to your printer's local
address, has no account, sends no telemetry, and its permissions are the
minimum for that: network access, a foreground service to watch during a print,
exact alarms for the interval, a wake lock during each request, and
notifications.

## If it shows `OFFLINE`

1. **Is Wi-Fi on the watch?** Watches often route through the phone over
   Bluetooth, and then the printer is unreachable. The app asks for the Wi-Fi
   network explicitly, but Wi-Fi still has to be on
2. **Does the printer answer?** Check in a browser: `http://tinymaker.lan`
3. **The name.** With `Auto search` the app tries `tinymaker.lan`, `tinymaker`
   and `tinymaker.local` in that order. `.local` is there only for completeness:
   Android treats that zone as mDNS and will not resolve it through normal DNS.
   If your router answers to a different name, turn `Auto search` off and enter
   the IP

A short silence is not a failure: the printer gets busy with uploads and SD
work, so the app keeps showing the last values for 90 seconds, saying NOT RESPONDING after 12 s — the seconds
counter at the bottom tells you how old they are — and only then says
`OFFLINE`.

## Building from source

```bash
bash tools/tinystatus/build.sh
```

Needs a JDK and Android build-tools; **no Gradle**. On the first run it
downloads one 700 KB library (`com.google.android.support:wearable:2.9.0`) —
the complication provider base class lives there — extracts `classes.jar` and
keeps it in `libs/`, which is not in git.

The version comes from the git commit count, so `versionCode` always increases;
without that, updates could not install over an older build.

# TinyStatus - your TinyMaker printer on your wrist

A Wear OS app that shows what your **TinyMakerWiFi** printer is doing: how much
time is left, which layer, how much resin is still in the vat, and a progress
ring around the screen. It can also **watch the print in the background** and
buzz your wrist when the print ends or the resin runs low, and it can put the
numbers **on your watch face** as complications.

It is not on Google Play, so it is installed by **sideloading**.

## What you need

- A Wear OS 4+ watch (developed and tested on Galaxy Watch 8)
- **TinyMakerWiFi firmware 0.16.2 or newer** - that is what this was tested
  against. It also runs on **0.11.0**, which is where the resin fields
  (`vatRemainingMl`, `vatLow`) first appeared, and will start on **0.9.0**,
  where `/api/status` itself began - but with less to show.

  Three fields arrived only in **0.17.0**, and without them the app degrades
  rather than breaks: `waitStage` (a canceled print is then told apart from a
  finished one by `stateCode` alone, which is coarser between two polls) and
  `lowResinWarnMl` (the low-resin warning then uses the firmware default of
  5 ml instead of your configured threshold)
- Watch and printer on the **same Wi-Fi network**

## Installing

### Option A: from your phone, no computer

1. **On the watch:** `Settings → About watch → Software`, then tap
   `Software version` five times until `Developer options` appears
2. In there, turn on **`ADB debugging`** and **`Wireless debugging`**
3. **On your phone**, install an app that speaks `adb` over Wi-Fi
   (for example *Wear Installer 2*)
4. On the watch open `Wireless debugging → Pair new device` - it shows a code
5. Pair from the phone using that code, then pick the downloaded
   `tinystatus.apk`
6. **Turn both debugging switches off when you are done** - otherwise the watch
   keeps a permanent "Debugging enabled" notification and buzzes every time the
   connection drops

### Option B: from a computer

You need `adb` (Android SDK platform-tools). Steps 1-2 on the watch are the
same.

```bash
adb pair <watch-ip>:<pairing-port>     # the watch shows the code
adb connect <watch-ip>:<port>
adb install -r tinystatus.apk
```

Use **`install -r`** when updating - it upgrades in place.

The first time you open the app it asks to allow notifications. Say yes, or the
print-finished and resin alerts will have nowhere to go.

### Updating

Versions newer than 0.1.284 update themselves: long press → **APP** →
`Check for updates`. That only looks: it says `Up to date`, or the button turns
into `Update 0.1.xxx`. Nothing is downloaded until you tap it.

- The first time, the watch asks you to allow TinyStatus to install apps - one
  switch, then tap `Update` again.
- If you installed TinyStatus with `adb` or a phone app, that first update also
  shows Android's own `Do you want to update this app?` screen. After it,
  TinyStatus is its own installer and later updates install without that screen.
- Android only installs an APK signed with the same key as the copy you already
  have, so nothing else can come in this way.
- Not while the background watcher is running: installing restarts the app,
  and that would stop watching the print.

## Using it

### The screen

| Where | What |
|---|---|
| Big number | **Time left.** After a print: `DONE`, `CANCELED` or `STOPPED`. Idle: `IDLE`. No answer for 90 s: `OFFLINE` |
| Above it | Printer (only with several) and the model name |
| Below | State, layer `232 / 378`, resin left in the vat |
| Bottom | Seconds since the printer last answered, and the background interval when the watcher is running |
| Ring | Progress, marked every 10 % |

Progress is computed from the layers: the API reports no percentage.

### Gestures

| Gesture | What happens |
|---|---|
| **Swipe down** | Refresh now - the arrow at the bottom spins while it asks |
| **Swipe up** | Leave the app |
| **Tap** | Refresh (same as swipe down) |
| **Long press** | Settings |
| **Swipe sideways** | Next printer, when you have more than one |

Inside settings, a sideways swipe or the Back button goes back.

Pull-to-refresh is the way it is because that gesture means "refresh"
everywhere else, and swipe-up-to-close matches the sibling ValloxWatch app.
Start the swipe from the middle of the screen: from the very top edge the
system pulls its own quick-settings shade instead.

### Settings

**BACKGROUND** - how often the app checks the printer **while it is closed**:
`Off`, `Every 30 s`, `2`, `5` or `10 min`. With the app open on screen it always
refreshes every 5 seconds regardless of this setting.

The watcher **only runs while a print is running**, because the printer
announces nothing by itself and there is nothing to watch otherwise:

- it **starts** when you close the app while a print is in progress - so the
  habit is: start the print, open TinyStatus once, put your wrist down;
- it **stops** on its own 20 minutes after the print ends, or when the battery
  drops below 15 %. If the printer stops answering it keeps trying - for up to
  12 hours while the last thing it saw was a print, 30 minutes otherwise;
- while it runs, a quiet ongoing notification says `Watching TinyMaker`;
- if it cannot reach the printer for 10 minutes during a print, it says so once
  (`Can't reach the printer`) and removes that note when contact returns.

Accuracy: the watcher uses exact alarms, so the interval holds even with the
watch lying on a table in deep doze - measured on a Galaxy Watch 8 at
`Every 2 min`, with the print-finished alert arriving within one interval of
the end. The watch raises its own Wi-Fi for the check when it needs to, so it
works with the phone nearby too.

**ALERTS** - which events buzz your wrist. They mirror what the printer's own
Telegram/Discord notifications send:

| Switch | Fires when | Default |
|---|---|---|
| Print end | The print finishes, is canceled, or stops early | on |
| Resin low | Resin drops below the printer's warn level (default 5 ml) | on |
| Resin out | Resin hits the stop level (default 2 ml) or the printer pauses to refill | on |
| Paused | You pause the print by hand | off |

Each event is announced **once**, when it happens - not repeated every poll.
The thresholds come from the printer itself (`/api/config`), so changing them
on the printer changes them here too.

One honest limitation: if a print is canceled between two polls and the app
never sees the cancel itself, it reports `Print stopped … not finished` rather
than `canceled`. It says what it knows, not what it guesses. The same goes
for a print that ended while the watch could not reach the printer: the
alert says `Print ended` and that it happened out of sight.

**PRINTER** - `By name` on (the default) finds a single printer by name
(`tinymaker.lan`). Turn it off to enter **up to four printers by IP**: tap
`Add printer`, type the address, `Save`. Tap a printer in the list to edit or
`Remove` it.

Why IPs for multiple printers: every TinyMaker answers to the same name, so the
name cannot tell two of them apart. **Reserve the addresses in your router**
(DHCP reservation) - otherwise a printer can get a new address and the app will
just show `OFFLINE`.

### On your watch face

The app publishes four complication data sources. In your watch face's
complication picker they appear as:

- **TinyMaker progress ring** - a ring plus the percentage (needs a watch face
  slot that accepts `RANGED_VALUE`)
- **TinyMaker progress %** - `62%`
- **TinyMaker time left** - `1h20`
- **TinyMaker resin left** - `7.4ml`

They show `DONE` for 12 hours after a print and `IDLE` when nothing is running.
Tapping one opens the app.

The complications **never fetch anything themselves** - they answer from the
last value the app or the background watcher fetched. That is deliberate: the
system asks providers every time you raise your wrist, and fetching there would
turn every wrist-raise into a Wi-Fi wake-up.

## Battery

Closed and with `BACKGROUND` off, the app does nothing at all. With the watcher
running it wakes for a fraction of a second per interval, holds the CPU only
while the request is in flight, and prefers a Wi-Fi connection that is already
up over waking the radio. Waking the Wi-Fi radio is the expensive part
(3-6 seconds of radio), not keeping it associated.

## Privacy

Everything stays on your network. The app talks only to your printer's local
address - and to GitHub, only when you tap `Check for updates` or `Update`. It
has no account, sends no telemetry, and its permissions are the minimum for
that: network access, a foreground service to watch during a print, exact
alarms for the interval, a wake lock during each request, notifications, and
installing its own updates.

## If it shows `OFFLINE`

1. **Is Wi-Fi on the watch?** Watches often route through the phone over
   Bluetooth, and then the printer is unreachable. The app asks for the Wi-Fi
   network explicitly, but Wi-Fi still has to be on
2. **Does the printer answer?** Check in a browser: `http://tinymaker.lan`
3. **The name.** With `By name` the app tries `tinymaker.lan`, `tinymaker`
   and `tinymaker.local` in that order. `.local` is there only for completeness:
   Android treats that zone as mDNS and will not resolve it through normal DNS.
   If your router answers to a different name, turn `By name` off and enter
   the IP

A short silence is not a failure: the printer gets busy with uploads and SD
work, so the app keeps the last values on screen. After 20 seconds without an
answer it says `NOT RESPONDING`, the counter at the bottom turns amber and the
arrow spins to show it is still trying. Only after a minute and a half does it
give up and say `OFFLINE`.

## Building from source

```bash
bash build.sh
```

Needs a JDK and Android build-tools; **no Gradle**. On the first run it
downloads one 700 KB library (`com.google.android.support:wearable:2.9.0`) -
the complication provider base class lives there - extracts `classes.jar` and
keeps it in `libs/`, which is not in git.

The version comes from the git commit count, so `versionCode` always increases;
without that, updates could not install over an older build.

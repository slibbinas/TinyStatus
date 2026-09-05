# TinyStatus — installing on your watch

A Wear OS app that shows your **TinyMakerWiFi** printer at a glance: what it is
printing, which layer, how much time is left, how much resin remains, and a
progress ring around the screen. When a print ends, it shows what was printed
and how long ago.

It is not on Google Play, so it is installed by **sideloading**. There are two
ways; the first needs no computer.

## What you need

- A Wear OS 4+ watch (tested on Galaxy Watch 8)
- **TinyMakerWiFi firmware 0.11.0 or newer.** Older builds (from 0.8.4) work
  too, except for the low-resin warning — the API had no `vatLow` back then
- Watch and printer on the **same Wi-Fi network**

## Option A: from your phone, no computer

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

## Option B: from a computer

You need `adb` (Android SDK platform-tools). Steps 1–2 on the watch are the
same.

```bash
adb pair <watch-ip>:<pairing-port>     # the watch shows the code
adb connect <watch-ip>:<port>
adb install -r tinystatus.apk
```

Use **`install -r`** when updating — it upgrades in place.

## What the app does

- Polls `GET /api/status` every 5 seconds, **only while you are looking at it**.
  Closed, it does nothing at all, so it costs no battery
- Tap anywhere to refresh immediately
- Talks to the printer **on your local network only** — nothing is sent
  anywhere, no accounts, no telemetry
- The only permission is `INTERNET` (plus `ACCESS/CHANGE_NETWORK_STATE`, so it
  can ask for the Wi-Fi network specifically)

## If it shows `OFFLINE`

1. **Is Wi-Fi on the watch?** Watches often route through the phone over
   Bluetooth, and then the printer is unreachable. The app explicitly requests
   the Wi-Fi network, but Wi-Fi still has to be on
2. **Does the printer answer?** Check in a browser: `http://tinymaker.lan`
3. **The name.** The app tries `tinymaker.lan`, `tinymaker` and
   `tinymaker.local` in that order. If your printer answers to a different
   name, change the `HOSTS` list in `MainActivity.java`

A short silence is not a failure: the printer gets busy with uploads and SD
work, so the app keeps showing the last values for 45 seconds with a
`no answer Ns` note, and only then says `OFFLINE`.

## Building from source

```bash
bash tools/tinystatus/build.sh
```

Needs a JDK and Android build-tools; **no Gradle**. The version comes from the
git commit count, so `versionCode` always increases — without that, users could
not install updates over an older build.

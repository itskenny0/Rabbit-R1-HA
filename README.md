# Rabbit R1 — Home Assistant client

An Android app for the Rabbit R1 (running LineageOS) that turns the scroll wheel into a fluent way to control Home Assistant entities — light brightness, fan speed, cover position, media volume — without fighting the WebView slider in the official HA app.

---

## Features

- **Scroll wheel control** — spin to adjust any scalar HA entity (lights, fans, covers, media players). Spring-animated slider gives a satisfying overshoot-and-settle on each turn.
- **Card stack UI with tabs** — one full-screen card per favourite entity; swipe up/down to flip between them, swipe left/right (or wheel-flick) to switch between rearrangeable tab groups.
- **HA Assist (text mode)** — type a prompt and HA's conversation engine handles it; multi-turn context threaded across calls. Settings → Assist.
- **Scenes & Scripts launcher** — tap-fire access to every `scene.*` / `script.*`, with substring search, kind filter chips, pull-to-refresh, and long-press for the `entity_id` + service name. Settings → Scenes & Scripts.
- **Recent Activity feed** — HA's logbook reverse-chronologically, with 12 h / 24 h / 3 d windows and full-text search. Tap any row for a long-form detail toast; long-press to open that entity's `/history` view in HA's web UI. Settings → Recent Activity.
- **Master OFF actions** — one-tap mass off for *all lights*, *all media*, or *all switches* from the Scenes & Scripts screen. HA's `entity_id: "all"` trick under the hood.
- **Templates evaluator** — POST a Jinja2 template to HA's `/api/template` and render against live state. Side-by-side example chips ("Sun elevation", "On lights count") for one-tap discovery. Settings → Templates.
- **Service Caller** — fire any HA service (`automation.reload`, `homeassistant.check_config`, `persistent_notification.create`, …) without leaving the device. JSON data payload editor + result panel with copy-to-clipboard. Settings → Service Caller.
- **Gesture-first navigation** — swipe left for Settings, right for the Favourites picker, tap the value area to toggle on/off; small chevron-back buttons on every sub-screen plus full system-back support.
- **OAuth sign-in** — enter your HA URL once and authenticate in an in-app WebView; tokens encrypted at rest with an AndroidKeystore-wrapped AES-256/GCM key.
- **Three themes** — *Pragmatic Hybrid* (default), *Minimal Dark*, *Colourful Cards*. Switch live in Settings with a side-by-side preview.
- **Backup & restore** — export/import your favourites, tabs, and settings as a single JSON file from Settings.
- **Fully configurable** — wheel step (1/2/5/10%) and acceleration, haptics, keep-screen-on, display mode, on/off pill, area labels, position dots.
- **Built for the R1** — designed around the small portrait display and the physical scroll wheel; handles both `DPAD_UP/DOWN` and `VOLUME_UP/DOWN` keycodes so it works across ROM variants.

## Requirements

- Rabbit R1 running **LineageOS 21 GSI** (Android 14) or **CipherOS** (Android 16).
- A reachable **Home Assistant** instance (local network or remote URL).
- For sane UI scaling on LineageOS GSI: `adb shell wm density 180`.

## Install

Download the latest `r1ha-YYYY.MM.DD.apk` from the [Releases](../../releases) page and install:

```bash
adb install r1ha-YYYY.MM.DD.apk
```

Or copy the APK to the device and open it with a file manager.

## Build from source

**Prerequisites:** JDK 17+, Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
git clone https://github.com/itskenny0/Rabbit-R1-HA.git
cd Rabbit-R1-HA
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The local build uses today's date as the version (`YYYYMMDD` for `versionCode`, `YYYY.MM.DD` for `versionName`); CI passes `APP_VERSION_CODE` / `APP_VERSION_NAME` from the release tag.

## Releasing

Releases are date-tagged. Push a tag in the form `r1ha-YYYYMMDD`:

```bash
git tag "r1ha-$(date +%Y%m%d)"
git push origin "r1ha-$(date +%Y%m%d)"
```

The release workflow builds the APK, renames it to `r1ha-YYYY.MM.DD.apk`, generates release notes from `git log` since the previous tag, and attaches the APK to a stable GitHub Release — no keystore management or repository secrets required.

## License

Released into the public domain via [The Unlicense](LICENSE).

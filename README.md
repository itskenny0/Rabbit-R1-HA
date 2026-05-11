# Rabbit R1 — Home Assistant client

An Android app for the Rabbit R1 (running LineageOS) that turns the scroll wheel into a fluent way to control Home Assistant entities — light brightness, fan speed, cover position, media volume — without fighting the WebView slider in the official HA app.

---

## Features

- **Scroll wheel control** — rotate to adjust any scalar HA entity (lights, fans, covers, media players).
- **Card stack UI** — swipe through your favourite entities; tap to select, scroll to change.
- **OAuth 2 sign-in** — paste your HA URL and authenticate via the browser; long-lived tokens stored in EncryptedSharedPreferences.
- **Theme picker** — Dynamic (Material You), Dark, Light, or AMOLED black.
- **Hardware-aware** — built specifically for the R1's 400 × 400 round display and physical scroll wheel.

## Requirements

- Rabbit R1 running **LineageOS 21 GSI** (Android 14) or **CipherOS** (Android 16).
- A reachable **Home Assistant** instance (local network or remote URL).

## Install

Download the latest `r1ha-YYYY.MM.DD.apk` from the [Releases](../../releases) page and sideload it:

```bash
adb install r1ha-YYYY.MM.DD.apk
```

Or copy the APK to the device and open it with a file manager.

## Build from source

**Prerequisites:** JDK 17+, Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
git clone https://github.com/itskenny0/Rabbit-R1-HA.git
cd Rabbit-R1-HA
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Signed release build (local)

1. Generate a keystore (one-time):

   ```bash
   keytool -genkey -v -keystore release.keystore \
     -alias r1ha -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Add to `local.properties` (never committed):

   ```properties
   RELEASE_STORE_FILE=/path/to/release.keystore
   RELEASE_STORE_PASSWORD=<store-password>
   RELEASE_KEY_ALIAS=r1ha
   RELEASE_KEY_PASSWORD=<key-password>
   ```

3. Build:

   ```bash
   ./gradlew assembleRelease
   ```

### CI / GitHub Actions release

The `release.yml` workflow fires on any `r1ha-YYYYMMDD` tag. It requires four repository secrets:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias (e.g. `r1ha`) |
| `RELEASE_KEY_PASSWORD` | key password |

Once secrets are set, tag and push to publish a release:

```bash
git tag "r1ha-$(date +%Y%m%d)"
git push origin "r1ha-$(date +%Y%m%d)"
```

## License

Released into the public domain via [The Unlicense](LICENSE).

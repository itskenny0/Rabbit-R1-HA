# Rabbit R1 — Home Assistant client

A small Android app for the Rabbit R1 (running LineageOS) that turns the scroll wheel into a fluent way to adjust Home Assistant scalar entities — light brightness, fan speed, cover position, media volume — without fighting the WebView slider in the official HA app.

## Hardware support

- Rabbit R1 running LineageOS 21 GSI (Android 14) or CipherOS (Android 16).
- A working Home Assistant instance reachable from the R1.

## Install

(filled in once the first APK is published)

## Build from source

Requires JDK 17+ and the Android SDK with `platforms;android-35` and `build-tools;35.0.0` installed.

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

Released into the public domain via The Unlicense. See `LICENSE`.

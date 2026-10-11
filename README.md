# Traccar WearOS

A standalone Wear OS app that turns a watch into a GPS tracker for a
[Traccar](https://www.traccar.org/) server. It is a thin Compose UI on top of the
official [Traccar Client SDK](https://www.traccar.org/traccar-client-sdk/), which does
the background tracking, offline queue and upload retries.

> Unofficial project, not affiliated with Traccar. The launcher icon is taken from the
> [traccar-client](https://github.com/traccar/traccar-client) repository (Apache-2.0);
> "Traccar" and its logo belong to their owners.

## Features

- Start/stop tracking from the watch, works without a phone.
- Editable server URL and device ID (defaults to `https://demo.traccar.org/` with a random ID,
  which you then add as a device on the server).
- Position every N meters **or** every N seconds, set on the watch.
- Own permission flow (the SDK's built-in prompt hangs on some Wear OS builds), with a shortcut to
  the app settings when "Allow all the time" is needed.
- Ongoing Activity icon on the watch face while tracking runs; the app closes after Start so the
  screen can turn off.

## Install

Download `TraccarWearOS-1.0.apk` from the
[latest release](https://github.com/gwenvador/TraccarWearOS/releases/latest), then install it on
the watch with wireless debugging (Settings > Developer options > Wireless debugging):

```sh
adb pair <ip>:<pairing-port>        # once, with the pairing code shown on the watch
adb connect <ip>:<port>
adb install TraccarWearOS-1.0.apk
```

Requires Wear OS 3 or later (API 30+). Tested on a Samsung Galaxy Watch Ultra. On first Start the watch asks for location, activity and
notification permissions; location must be set to "Allow all the time" for background tracking.

## Build

Requirements: Android Studio (or JDK 17+ and the Android SDK) with **platform 37**, since the
SDK requires `compileSdk 37`.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The default preset is tuned for fast sports use (5 m, GPS always on, no stop detection), which uses
more battery. Change it in `TraccarWearOSApp.kt` or from the watch screen.

## License

Apache License 2.0, same as the Traccar Client SDK. See [LICENSE](LICENSE).

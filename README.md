[Русская версия](README.ru.md)

# Pulsar

An Android app that keeps your heart rate within a chosen range. It reads a Bluetooth
heart rate sensor in the background and tells you when the heart rate goes above or
below the range: by vibration you can tell apart by touch, and by voice in the headphones.

The app is in early field testing.

## Features

- Works with any Bluetooth LE sensor that implements the standard Heart Rate profile
  (chest straps, armbands, watches in broadcast mode).
- Three modes - Rest, Walk, Training - each with its own heart rate range, alarm and
  vibration. Default walk and training ranges are computed from age.
- Automatic mode selection by heart rate and step cadence.
- Alarms: distinct vibration patterns for "above", "below", "back in range" and
  "sensor lost"; a separate lower bound during sleep; quiet night hours.
- Voice in headphones: current heart rate at an interval, on a shake of the phone, and
  when leaving the range, with the current speed.
- History with pinch zoom, colored by the range of the mode in effect at the time;
  speed from the pedometer or GPS.
- Home screen widget, notification, Live Update / Now Bar on Android 16.
- Writes heart rate to Health Connect (optional).
- Export and import of the whole history (CSV in gzip).
- Interface and voice in 9 languages: English, Russian, German, French, Spanish,
  Italian, Japanese, Korean, Chinese (Simplified).

## Privacy

The app has no internet permission. Heart rate history, steps and settings stay on the
phone; they leave it only if you turn on Health Connect or export the history yourself.

## Requirements

- Android 12 (API 31) or newer; Live Update needs Android 16.
- A Bluetooth LE heart rate sensor (Heart Rate Service 0x180D).
- Headphones for voice messages: the app does not speak through the phone speaker.

## Building

JDK 17 and the Android SDK are required. Put the SDK path into `local.properties`
(`sdk.dir=...`) or set `ANDROID_HOME`.

```bash
./gradlew :app:assembleDebug         # APK in app/build/outputs/apk/debug/
./gradlew :app:installDebug          # install on a connected phone
./gradlew :app:testDebugUnitTest     # unit tests, no device needed
```

Debug builds also keep a field test log (`no_backup/telemetry/`) used to tune the
thresholds; release builds do not.

## Companion apps

Pulsar exposes per-minute average heart rate during walks and workouts through a
read-only content provider (`content://com.puls.app.activity/minutes`). It is guarded by
a signature permission, so only apps signed with the same key can read it.

[Calorie](https://github.com/alexandrslesarenko/calorie), a calorie counter, uses it to
account for walks and workouts in the daily calorie target.

## Disclaimer

Pulsar is not a medical device and must not be used for diagnosis or treatment.
Heart rate from consumer sensors can be inaccurate.

## License

Copyright 2026 Alexandr Slessarenko

Licensed under the [Apache License, Version 2.0](LICENSE).

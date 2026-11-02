# Handover: mirroring a phone to the Zeekr car screen

Goal: mirror Chris's phone (Samsung Galaxy S24 Ultra, SM-S928B, Android 16 / SDK 36) to the
centre screen of a China-market Zeekr (AI OS, renderer advertises as `ZEEKR-2882_CENTER DISPLAY`)
so Waze can be used on the car screen. The car has no Android Auto or Miracast; its
"Video casting" screen is a DLNA/UPnP MediaRenderer reached over the car's own Wi-Fi hotspot.

## Status (2 Nov 2026)

Mirroring **works**: the car displays the live phone screen. Two open problems:

1. **Heavy stutter** on the car screen (not just latency; frames visibly stall).
2. **The app crashed** after mirroring for a while. Crash report not yet captured.

## Fixes already made on top of upstream (weiw0923/ScreenShare)

- UI translated from Chinese to English.
- `MirrorService`: `RESULT_OK` is -1, so the old `code < 0` check rejected every screen-capture
  grant and returned before `startForeground()` (fatal on Android 16). Now enters the foreground
  first and checks `code != RESULT_OK`.
- Removed microphone capture (it recorded the cabin, not phone audio, and its thread could crash
  the app). Waze voice goes over Bluetooth instead.
- `DlnaController`: an absolute `controlURL` (`/x`) was appended to the description's directory
  instead of the host root, so every SOAP call returned 404. Now resolved like a browser, with
  alternates tried on 404.
- Sends `Stop` before `SetAVTransportURI`; retries with no DIDL metadata; tries several
  `protocolInfo` values for the live stream (`playLiveStream`).
- `MirrorServer`: DLNA headers (`transferMode.dlna.org: Streaming`, `contentFeatures.dlna.org`),
  `HEAD` support.
- Encoder: 4 Mbps, 1 s keyframe interval, `KEY_PRIORITY` 0, `KEY_LATENCY` 1; low-latency Wi-Fi lock.
- `CrashLog`: saves uncaught exceptions and shows them on next launch, with a Copy button.
  DLNA refusals show the car's exact SOAP reply plus the resolved control URLs.
- Stable debug signing key (`sender/app/screenshare-debug.jks`) so builds install over each other.

## Build

GitHub Actions (`.github/workflows/build.yml`) builds a debug APK on every push and publishes it
to the `apk` branch as `ScreenShare.apk`. Locally: `cd sender && ./gradlew assembleDebug`
(needs the Android SDK, compileSdk 34, JDK 17), then `adb install -r` the APK.

## Suggested next steps

1. Connect the phone by USB with debugging on, run `adb logcat` while mirroring, and capture the
   crash and any `MediaCodec`/`MirrorServer` errors.
2. Find where the stutter comes from: encoder output rate, socket writes blocking in
   `MirrorServer.broadcast`, the car hotspot's bandwidth, or the car's player. Ideas: lower
   resolution (e.g. 960 or 720 long side) and frame rate (20 to 24 fps), smaller TS packets per
   write, a non-blocking per-client queue that drops frames rather than stalling, steady
   `KEY_REPEAT_PREVIOUS_FRAME_AFTER`, PCR spacing in `TsMuxer`.
3. Landscape output to fit the car screen when Waze is rotated.

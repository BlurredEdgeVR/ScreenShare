# Handover: mirroring a phone to the Zeekr car screen

Goal: mirror Chris's phone (Samsung Galaxy S24 Ultra, SM-S928B, Android 16 / SDK 36) to the
centre screen of a China-market Zeekr (AI OS, renderer advertises as `ZEEKR-2882_CENTER DISPLAY`)
so Waze can be used on the car screen. The car has no Android Auto or Miracast; its
"Video casting" screen is a DLNA/UPnP MediaRenderer reached over the car's own Wi-Fi hotspot.

## Status (2 Nov 2026)

Mirroring **works**: the car displays the live phone screen. Two open problems:

1. **Heavy stutter** on the car screen (not just latency; frames visibly stall). Being tested: a
   "Mirror quality" choice in the app (see below).
2. **The app crashed** after mirroring for a while. The phone's crash log (read over adb at
   09:15) holds only `ForegroundServiceDidNotStartInTimeException` crashes from before the
   08:31 fix, and nothing from the build installed at 08:41. So this was probably the
   foreground-service bug, which is already fixed.

### Mirror quality presets (`MirrorQuality.kt`)

- **As now** (default): 1280 px, 30 fps, 4 Mbps. The setup that first worked; unchanged.
- **Smoother**: 960 px, 24 fps, 2.5 Mbps.
- **Lightest**: 720 px, 20 fps, 1.5 Mbps.

Smoother and Lightest also do two other things:
- They cap the frames sent to the encoder. The phone's screen runs at up to 120 Hz, so without a
  cap the encoder can receive far more frames than it is tuned for.
- They give the car its own send queue. If the car falls about 1 s behind, the queue is dropped
  and the stream resumes at the next keyframe, so a slow car never stalls the encoder.

### Test results (2 Nov, phone streaming over Wi-Fi to a stand-in display on a Mac)

See `tools/stand-in-display/` and the "Testing without the car" section of CLAUDE.md. Each preset
was recorded twice while the phone scrolled: once read at full speed, and once read at 1.2 Mbps
to act like a slow car.

| Preset | Full speed | Slow display (1.2 Mbps) |
|---|---|---|
| As now | 652×1280, **59 fps** (screen runs at 60–120 Hz, no cap), 4.2 Mbps, keyframe every 0.5 s | 4 s of video in 20 s: **16 s behind** and growing |
| Smoother | 488×960, 24 fps, 2.5 Mbps, keyframe every 1 s | stays live (≤1.5 s behind), 9 freezes of ~1.3 s in 20 s |
| Lightest | 366×720, 20 fps, 1.5 Mbps, keyframe every 1 s | stays live, 4 freezes in 20 s |

- Android lets a socket hold about 4 MB unsent, so skip-ahead only worked after the send buffer
  was limited to 64 KB (commit 53ccac5).
- With the frame cap, a static screen sends a frame only about every 1.2 s. Harmless so far.
- Added **Landscape** (1280 px wide, 24 fps, 3 Mbps) and **Landscape light** (960 px wide, 20 fps,
  1.8 Mbps). They always send a sideways picture, so Waze turned sideways fills the car screen.
  Tidal and other upright apps show in the middle with bars on both sides.
- Landscape test with Waze: with the whole screen shared and the phone upright, Waze shows in the
  middle with bars. Waze ignores forced rotation from adb and follows the motion sensor, so
  auto-rotate must be on and the phone physically turned.
- "Share one app" → Waze, phone sideways: Waze fills the 1280×590 frame edge to edge, with no
  status bar or notifications. When Waze is left (home screen), the stream pauses and the display
  keeps the last Waze frame. It resumes within a second of returning. This is the recommended car
  setup: Landscape preset, share Waze only, auto-rotate on, music over Bluetooth.
- The app's page now scrolls, so Stop and Pause stay reachable when the phone is sideways.
- The phone's screen must stay on and unlocked while mirroring. The app now holds a dim-screen
  wake lock for the whole mirroring session, so the screen can't time out; the power button
  still locks it.
- **3-hour drive (2 Nov):** the picture worked the whole time (Landscape, Waze shared on its own). Two problems:
  - No internet: the phone stayed on the casting Wi-Fi for internet. `network_avoid_bad_wifi` was 0, and Android had once seen the casting network validate, so it stuck with it. That setting is now 1 (set over adb).
  - No music over Bluetooth: A2DP stayed connected to the car the whole time, so either Tidal couldn't stream (no internet) or the car mutes Bluetooth while casting. To be confirmed on the next drive.
- Fixes: `WifiRoute` keeps the app's car traffic on Wi-Fi while 4G carries the internet. There is also an optional "Phone sound to car" switch that sends media audio inside the cast stream.
- Next: try Smoother in the car. If it still freezes often, try Lightest; the car's Wi-Fi may
  carry less than 2.5 Mbps.

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

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read first

- **[HANDOFF.md](HANDOFF.md)**: current goal, status, fixes already made, and next steps. The active work is mirroring a phone (Galaxy S24 Ultra, Android 16) to a Zeekr car's built-in DLNA renderer so Waze shows on the car screen. Open problems: stutter on the car screen, and a crash after mirroring for a while. Keep HANDOFF.md up to date when the status changes.
- **[docs/protocols.md](docs/protocols.md)**: SSDP, UPnP AVTransport and MPEG-TS details.

This repo is a fork of `weiw0923/ScreenShare`. The README and `docs/protocols.md` still describe mirroring with mic audio. That was removed in this fork (see below).

## Build and run

There are two separate Gradle projects, `sender/` (phone app) and `tv/` (Android TV receiver). Each has its own wrapper, so run Gradle from inside the project folder. Needs JDK 17 and the Android SDK (compileSdk 34). AGP 8.5.2, Kotlin 2.0.20, Gradle 8.7.

```bash
cd sender && ./gradlew assembleDebug        # -> sender/app/build/outputs/apk/debug/app-debug.apk
adb install -r sender/app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -iE "MirrorService|MediaCodec|AndroidRuntime"   # watch a mirroring session

cd tv && ./gradlew assembleDebug            # receiver app (not built by CI)
./gradlew lint                              # standard AGP lint, no custom config
```

- On this Mac, `adb` is on PATH (Homebrew `android-platform-tools`). Local Gradle builds don't work yet: only JDK 8 and SDK platform 30 are installed. So builds come from CI, and you install them by downloading `ScreenShare.apk` from the `apk` branch and running `adb install -r`.
- There are no tests in either project.
- CI ([.github/workflows/build.yml](.github/workflows/build.yml)) builds only the sender debug APK on every push and force-pushes it to the `apk` branch as `ScreenShare.apk`. That is how builds reach the phone.
- The sender's debug build is signed with the committed `sender/app/screenshare-debug.jks` (password `android`) so new builds install over old ones. Don't replace it, or the phone will need an uninstall first.
- Both apps read `versionName`/`versionCode` from the root [version.properties](version.properties). It is one version for both apps, and MAJOR changes only when the sender↔tv protocol breaks.

## Architecture

### sender (`com.screenshare.send`): zero dependencies, by design

The sender uses only the plain Android SDK. Its UI is built in code in `MainActivity` (no layout XML). Don't add libraries to it.

**Finding a renderer:** `DlnaDiscoverer` sends SSDP `M-SEARCH` and collects `LOCATION` URLs. If nothing answers, `SubnetScanner` probes TCP 49152 across the Wi-Fi /24. `MainActivity.findDisplays` fetches each candidate's description, keeps the ones with AVTransport, and fills in their names. The display used last is kept in activity preferences (`last_tv_ip`, `last_tv_desc`, `last_tv_name`). On launch, `connectSaved()` tries the saved description URL. If nothing answers there, or a display with a different name does, it searches and picks the saved display by name. The Zeekr's renderers listen on a random port (`http://192.168.5.95:<port>/upnp/dev/<uuid>/desc`) that changes when the car starts, so for the car only SSDP finds them. The port-49152 scan only finds the `tv` receiver.

**Wi-Fi binding:** `WifiRoute` works in one of two modes:
- **The phone joins the car's Wi-Fi** (default). It requests any Wi-Fi network (`requestNetwork`), so Android keeps a no-internet hotspot connected. Whether other apps then get internet over 4G is up to Android and Samsung. On the drives so far they didn't.
- **The app joins the car's Wi-Fi.** This is the "Keep 4G internet in the car" button, saved in the `car_wifi` prefs file. The app requests that SSID with a `WifiNetworkSpecifier` (Android 10+). Android makes it a link for this app only, without `NET_CAPABILITY_INTERNET`, so it can't become the default network. The user must forget the network in Wi-Fi settings so the phone doesn't also join it normally. The first request shows Android's "connect to device" prompt. After the link is lost, `ensure(false)` asks again, but only after a loss, never after a cancelled prompt.

In both modes the phone's stream address comes from `WifiRoute.localIp()` (the Wi-Fi's `LinkProperties`). Outgoing connections to the car are pinned to it: `DlnaController` uses `WifiRoute.open()`, and SSDP and the subnet scan use `WifiRoute.bind()`. Don't use `bindProcessToNetwork`. A listening socket created while the process is bound keeps that network's mark, so after a Wi-Fi drop and rejoin (a new `Network`) the car times out connecting to `/live.ts`. Unpinned listeners answer on whichever network a connection arrives on.

**Controlling it:** `DlnaController` is a single-threaded SOAP client for AVTransport.
- `describe()` parses the description with regexes and resolves `controlURL` against `URLBase` or the description URL, the way a browser would. It also stores alternate URL forms. On HTTP 404, `soap()` tries those alternates and remembers the one that works. Any other error stops the retries, because it means the renderer answered.
- `setUriAndPlay()` sends `Stop`, then `SetAVTransportURI` with DIDL metadata, then `Play`. If that fails, it retries once with no metadata.
- `playLiveStream()` additionally walks through several `protocolInfo` strings.
- Failures are collected in `lastError`, `takeErrorLog()` and `info`, and `MainActivity.showReport()` shows them to the user with a Copy button. This is the main way to debug renderer refusals in the field.

**Screen mirroring pipeline:**

`MainActivity.startMirrorFlow` → screen-capture permission → `MirrorService` (foreground service, type `mediaProjection`) → `MediaProjection` VirtualDisplay → `MediaCodec` H.264 encoder (input Surface) → drain loop on `codecExecutor` → `TsMuxer.packetizeVideo` → `MirrorServer.broadcast` → HTTP clients of `http://<phone>:8080/live.ts?k=<token>`

Then `DlnaController.playLiveStream` tells the renderer to fetch that URL.

- `MirrorService.onStartCommand` must call `startForeground()` before anything else, including the result-code check. Android 16 kills the app otherwise. `RESULT_OK` is `-1`, so check `code != RESULT_OK`, not `code < 0`.
- Encoder settings come from a `MirrorQuality` preset. The user picks it in the app, it is saved in the `mirror_quality` preference, and it reaches the service as `EXTRA_QUALITY`.
  - `AS_NOW` (1280 px, 30 fps, 4 Mbps) is the setup that first worked in the car, and it is the default. Keep it unchanged and add new presets instead.
  - All presets use a 1 s I-frame interval and `KEY_REPEAT_PREVIOUS_FRAME_AFTER`. Without that setting, a static screen produces no frames and a new client waits forever for a keyframe.
  - The phone's screen runs at up to 120 Hz, so the newer presets also set `KEY_MAX_FPS_TO_ENCODER`.
  - The `landscape` presets use a fixed sideways frame shaped like the physical screen (taken from `Display.Mode`), whichever way the phone is held at start. `AUTO_MIRROR` fits the phone's picture inside it and follows rotation, so Waze turned sideways fills the frame and an upright app shows in the middle with bars. The other presets size the frame from `resources.displayMetrics` at start, so their shape is locked to the orientation at that moment.
- `MirrorServer` is a hand-rolled HTTP server on port 8080. It needs a random per-session token (`k=`), sends DLNA streaming headers, and supports `HEAD`. A new client gets nothing until the next keyframe, which is prefixed with a fresh PAT/PMT. Joining also triggers `requestSyncFrame()`. A separate `MirrorPsi` thread re-sends PAT/PMT every 400 ms.
- How `broadcast()` sends depends on the preset:
  - With `AS_NOW`, it writes to every client synchronously, under one lock, on the encoder drain thread, so a slow socket stalls encoding.
  - With presets that set `skipWhenBehind`, each client gets its own queue and writer thread. If a client falls more than about 1 s (`maxQueuedBytes`) behind, its backlog is dropped and it resumes at the next keyframe.
- While mirroring, `MirrorService` holds a low-latency Wi-Fi lock and a `SCREEN_DIM_WAKE_LOCK`. A dark or locked phone screen freezes the car's picture, and an activity's `FLAG_KEEP_SCREEN_ON` doesn't help once Waze is in front. Both locks are released in `stopSelfSafely()`.
- If the drain loop fails mid-stream, the stream ends. The error is saved with `CrashLog` and shown the next time the app opens.
- `TsMuxer` is a hand-written MPEG-TS muxer: PAT on PID 0, PMT on 0x1000, H.264 on 0x100 (also the PCR PID), and AAC on 0x101 only if `setHasAudio(true)`. `buildPatPmt()` must build new packets on every call so continuity counters advance. Don't cache it.
- Sound is optional ("Phone sound to car", pref `send_sound`, `EXTRA_SOUND`, off by default). `startAudio()` uses `AudioPlaybackCapture` on the projection, capturing USAGE_MEDIA, GAME and UNKNOWN, and encodes 44.1 kHz stereo AAC into PID 0x101. Android never lets navigation voices be captured, so Waze's voice still needs Bluetooth. `setHasAudio()` is decided before the server starts, because a PMT that lists audio with none arriving can stall a player. The old mic capture (which recorded the car cabin) is gone.

**Car mode and reconnecting:**
- Car mode is the "Car mode" button, plus a static shortcut (`res/xml/shortcuts.xml`) and a pinned shortcut, both sending `ACTION_CAR_MODE`. It reconnects to the saved display, retrying every 3 s while the phone joins the car's Wi-Fi. Then it starts the share prompt with the saved quality and sound settings, and opens Waze once the cast plays.
- The car shows two displays (centre and passenger) on one IP, so the exact description URL is saved as `last_tv_desc` and its name as `last_tv_name` (for example `ZEEKR-2882_CENTER DISPLAY`). Car mode keeps looking for the saved display for 3 minutes (`CAR_WAIT_MS`).
- `MainActivity` passes the car's control URL to `MirrorService` (`EXTRA_CONTROL_URL`). The service's `CarKeeper` thread checks every 2 s:
  - It closes connections whose writes have been stuck for 8 s (`MirrorServer.dropStalled`).
  - Once the car has connected at least once, if no display is connected for 4 s, it re-sends Stop/SetAVTransportURI/Play. It waits while Wi-Fi is down.
  - It gives up after 3 tries that don't hold for 20 s. The notification's Reconnect button starts the tries over.
  - It ends mirroring after 10 minutes without the car, so the screen wake lock isn't held forever.

**Casting files:** `LocalRelay` serves one SAF `content://` URI over HTTP on a random port, with `Range`/206 support so the renderer can seek. The content type comes from the file extension.

**Connection log:** `EventLog` appends to `files/events.log`, which the "Connection log" button shows. It records which network carries the phone's internet (from a default-network callback), Wi-Fi joins and losses, display searches, casts and `CarKeeper` reconnects. After a drive, read it with `adb shell run-as com.screenshare.send cat files/events.log`.

**Crash capture:** `CrashLog` writes uncaught exceptions to `last_crash.txt`, and `MainActivity` shows them on the next launch. `MainActivity` also installs a second handler that shows the stack trace on screen right away.

### tv (`com.screenshare.tv`): unfinished receiver

`RendererService` (foreground, started at boot by `BootReceiver`) combines three parts:
- `SsdpResponder`: answers M-SEARCH and sends periodic `ssdp:alive`.
- `UpnpHttpServer`: serves the description, SCPD and SOAP for AVTransport, RenderingControl and ConnectionManager on port 49152.
- An ExoPlayer instance (Media3) with infinite retry and a watchdog that replays the last URI.

`StatusActivity` hosts the PlayerView. Unlike the sender, it uses AndroidX/Media3 dependencies.

Things to know about it:
- Its UI and notification strings are still in Chinese.
- `UpdateChecker` downloads and installs APKs from the upstream `weiw0923/ScreenShare` GitHub releases.
- It isn't involved in the car work, because the car is its own renderer.

## Testing without the car

`tools/stand-in-display/` contains a stand-in for the car's DLNA screen that runs on the Mac. It needs ffmpeg (`ffplay` and `ffprobe`).

1. Set up the tunnels: `adb reverse tcp:49152 tcp:49152` and `adb forward tcp:18080 tcp:8080`.
2. Back up the app's `shared_prefs/MainActivity.xml` (read it with `adb shell run-as com.screenshare.send`). It holds the car's IP in `last_tv_ip`.
3. Force-stop the app and write `last_tv_ip` = `127.0.0.1`. The phone must still be on some Wi-Fi, because the app needs a `wlan` IP to build the stream URL. Control traffic goes over USB, and the stream goes over Wi-Fi, or over USB if Wi-Fi can't reach the Mac.
4. Run `python3 fake_display.py > display.log`. It records each stream to `recordings/N.ts` and shows it live in an ffplay window. `GET /record?rate=1200` records the current stream again, reading at 1.2 Mbps like a slow car.
5. Tap "Screen mirror" (for example with `phone.py tap "Screen mirror"`). The user has to choose "Share entire screen" and tap Next on the phone.
6. Run `run_test.sh display.log`. It scrolls the phone's Settings app, records once at full speed and once as a slow car, then stops mirroring.
7. Run `analyze.py recordings/*.ts` to get resolution, fps, bitrate, keyframe spacing, frame gaps and decoder warnings. Warnings on the last frame come from cutting the recording off and can be ignored.

Afterwards, restore the prefs backup and remove the tunnels.

## Network ports

| Port | Owner | Purpose |
|---|---|---|
| 1900/udp | both | SSDP multicast `239.255.255.250` |
| 49152 | tv | UPnP description and control. The sender's subnet-scan fallback assumes this port. |
| 8080 | sender | `/live.ts` mirror stream |
| random | sender | `LocalRelay` file stream at `/media` |

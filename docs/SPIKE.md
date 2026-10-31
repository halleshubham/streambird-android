# StreamBird Android - spike

## Goal

Prove, as far as possible, and let the founder verify on a real phone, that a **native app can publish a camera + logo stream over
WebRTC/WHIP to StreamBird's existing MediaMTX ingest**, and **measure what is really being sent**.

Why the measurement matters: the web studio was sometimes scaled down to about 240p because Chrome's WebRTC adaptation (bandwidth
estimate / CPU) shrank the picture. The server only copies the video (`ffmpeg -c:v copy` to YouTube/Facebook/Twitch), so whatever the
phone's encoder sends is exactly what the platforms get. The numbers from this spike decide whether to add RTMP ingest later.

## Why WHIP first

* It is the protocol the backend already speaks: `POST /streams` returns `whipUrl` and the web studio publishes there. **No backend change**, no new ingest to run.
* H.264 is forced first in the offer, because the downstream is RTMP/FLV (`-c:v copy`) which cannot carry VP8/VP9.
* The weak point is WebRTC's congestion control and adaptation. The spike gives it every advantage (start-bitrate hint, `maxBitrate` per tier, "sharp picture" degradation preference, 2 s keyframes) and then *shows* what happens. If it still shrinks the picture on a good network, RTMP is the answer.

## Architecture

```
Camera2 -> SurfaceTextureHelper (OES texture, GL thread)
        -> FrameCompositor   (FBO ring: camera aspect-fill + logo)   <- the "GPU path"
        -> VideoSource -> HW H.264 encoder (MediaCodec, texture input, keyframe every 2 s)
        -> RTCPeerConnection (sendonly video + opus audio, UNIFIED_PLAN)
        -> WHIP POST (application/sdp)  ->  MediaMTX  ->  ffmpeg -c:v copy  ->  YouTube / Facebook / Twitch
```

| Piece | File |
| --- | --- |
| Login / cookie session / pending approval | `data/AuthRepository.kt`, `data/PersistentCookieJar.kt`, `data/ApiClient.kt` |
| Channels + plan limits, setup | `ui/StudioViewModel.kt`, `ui/screens/ChannelsScreen.kt`, `SetupScreen.kt` |
| Stream lifecycle (create, publish, status poll, end, log) | `stream/StreamController.kt` |
| Foreground service (camera, microphone) + wake locks | `stream/StreamingService.kt` |
| Pipeline | `media/StreamEngine.kt` |
| GL compositing (camera + logo) | `media/FrameCompositor.kt` |
| Keyframe every 2 s | `media/KeyFrameEncoderFactory.kt` |
| WHIP | `whip/WhipClient.kt`, `whip/Sdp.kt` (H.264-first SDP reorder) |
| Quality classifier (port of `sendQuality.ts`) | `media/SendQuality.kt` |
| `getStats` parsing, CSV log | `media/StatsParser.kt`, `media/SampleLog.kt` |
| Battery / thermal / network | `media/DeviceTelemetry.kt` |

### Library choice

`io.getstream:stream-webrtc-android:1.3.10` (Maven Central, latest release at the time; resolved fine through the sandbox proxy).
It is a maintained prebuilt of Google's libwebrtc with the standard `org.webrtc` Java API, so every class used here (`Camera2Enumerator`,
`SurfaceTextureHelper`, `TextureBufferImpl`, `VideoFrameDrawer`, `DefaultVideoEncoderFactory`, `RtpTransceiver.setCodecPreferences`,
`RtpParameters.degradationPreference`, `PeerConnection.setBitrate`, `RTCStatsReport`) was checked with `javap` against the AAR.
The old `org.webrtc:google-webrtc-1.0.32006` is unmaintained since 2021, so it was not used. Cost: the debug APK is about 58 MB (four ABIs of native code; a release build with ABI splits would be about a quarter of that).

### GPU path (and the fallback)

`FrameCompositor` is a `CapturerObserver` placed between the camera and the `VideoSource`. For every camera frame it renders into one of
four RGBA textures via an FBO (camera aspect-fill-cropped to 9:16 by `VideoFrameDrawer`, then the logo alpha-blended), calls `glFinish`,
and wraps the texture in a `TextureBufferImpl` (RGB, identity transform) whose release callback frees the slot. The encoder (texture
input) and the preview (`SurfaceViewRenderer`) read the same texture through the shared EGL context. If all four slots are still in use,
the frame is dropped and counted ("Dropped by compositor" on the Live screen).
No CPU pixel copies happen. The I420 `VideoProcessor` fallback was **not** needed and **not** written; if the texture path misbehaves on
some phone it would cost roughly one 1080x1920 NV12/I420 conversion plus an overlay blend per frame (about 3 MB per frame at 30 fps, tens of percent of one CPU core).

### Other design points

* **Orientation** is chosen on the Setup screen (default Portrait) and sent as `orientation` in `POST /streams`; the backend fixes it per stream. Landscape output sizes are the web's `RESOLUTIONS` (960x540 / 1280x720 / 1920x1080); portrait swaps them like `canvasSizeFor` (540x960 / 720x1280 / 1080x1920). Bitrates 1 / 3 / 6 Mbps either way, 30 fps. The camera is opened at 1280x720 (540p, 720p) or 1920x1080 (1080p); `VideoFrameDrawer` rotates each frame from the camera's per-frame rotation and the compositor aspect-fills it into the output.
* **Screen rotation**: a landscape stream locks the activity to sensor-landscape (either way up) while it runs, a portrait stream to portrait. The activity declares `configChanges`, and the engine lives in the application-scoped `StreamController`, so rotating never restarts the stream. The Live screen switches to a side-by-side layout in landscape.
* **Flip camera** (Live screen, shown only if the phone has a front and a back camera): `CameraVideoCapturer.switchCamera(handler, deviceName)` to the explicit other-facing device; the PeerConnection, encoder and compositor keep running. The compositor's un-mirror flag follows the active camera (the web studio never mirrors the published canvas; front frames are un-mirrored here). A failed switch shows a message and keeps the current camera. The CSV gets a `camera back -> front` event row.
* **Resolution tiers**: 540p / 720p / 1080p as above.
* **Picture priority**: "Sharp picture" = `degradationPreference MAINTAIN_RESOLUTION`; off = `BALANCED`. Switchable while live.
* **Start bitrate**: `PeerConnection.setBitrate(min = max/10, start = 70% of max, max)` so the estimator does not spend a minute climbing from about 300 kbps.
* **H.264 profile** switch (High vs Constrained Baseline) and **keyframe every 2 s** switch are on the setup screen so you can compare.
* **Session across restarts**: the `sb_session` cookie is stored in DataStore (app-private, `allowBackup=false`). It is not additionally encrypted; the spike is a debug build.
* Debug builds allow `http://` servers (staging on a LAN); release builds do not.

## Install the APK on your phone

1. On GitHub open **halleshubham/streambird-android -> Releases -> "Spike debug build (latest)"** (tag `spike-latest`; a pre-release).
   Direct file: `https://github.com/halleshubham/streambird-android/releases/download/spike-latest/streambird-spike-debug.apk`
   (Also available as the `streambird-android-debug-apk` artifact of each Actions run.)
2. Download `streambird-spike-debug.apk` on the phone. Allow **Install unknown apps** for the browser/file manager when Android asks. Open the file, tap Install.
3. First launch: sign in with your StreamBird email (a code arrives by email). Allow Camera, Microphone and Notifications when asked.
4. It is a debug build: Play Protect may warn; choose "Install anyway". Uninstall/reinstall the next build is not needed - it updates in place (same debug signing key only if installed from the same CI; if Android refuses, uninstall first).

## TEST CHECKLIST (real phone)

Use a **test** YouTube channel/Facebook page (or an unlisted/private broadcast). Before each run note: phone model, Wi-Fi vs cellular, signal bars.
After each run tap **Share log** on the Live screen (or after End) and send the file back.

First, 2 minutes: **sign in -> pick a channel -> Setup -> 720p -> Go live**. Check, in this order:
1. Does it say LIVE and show the camera preview with your logo in the right corner? (If the preview is black/green/upside down: GL path problem - screenshot it.)
2. Stats panel: **Encoder** must say hardware. If it says SOFTWARE or the Setup screen warns "No hardware H.264 encoder", note it - that alone can be the answer.
3. Does YouTube/Facebook show the stream within about 10-20 s, and is the picture portrait with the logo?

Then the runs:

| # | Scenario | What to do | Look at |
| --- | --- | --- | --- |
| 1 | **Wi-Fi 1080p, 10 min** | Good Wi-Fi, 1080p, sharp picture ON. Leave the screen on. | Sent size stays 1080x1920? bitrate near 6 Mbps? "Limited by" none? Wi-Fi quality; first-minute ramp-up (how long to reach 1080p?) |
| 2 | Wi-Fi 1080p, picture priority OFF | Same, "Balanced" | Does the encoder shrink the picture/fps compared with #1? |
| 3 | **Cellular 720p, 10 min** | Wi-Fi off, 4G/5G | Sent size, bitrate vs the 3 Mbps cap, RTT, loss; amber warning? |
| 4 | **Bad network** | Move to weak signal, or throttle (airplane-mode toggle quickly, or a hotspot far away) | How does it degrade (fps vs size)? Does it recover? Note: no automatic reconnect in the spike. |
| 5 | **Wi-Fi <-> cellular switch** | Start on Wi-Fi, turn Wi-Fi off mid-stream (and the other way) | The log gets a `network wifi -> cellular` row. Does media keep flowing (ICE may restart by itself) or does it say FAILED/DISCONNECTED? |
| 6 | **Screen lock / background** | Lock the screen for 2 min; then switch to another app for 2 min | Notification stays ("LIVE ..."); YouTube keeps playing; sent bitrate/fps in the log unchanged? Does Android kill it (Samsung/Xiaomi battery savers)? |
| 7 | **Battery / heat** | 1080p for 15 min, not charging, then charging | Battery % drop, battery temp, thermal status (NONE -> LIGHT -> MODERATE...). Does `Limited by: cpu` appear when it heats up? |
| 8 | Front camera + keyframe switch | Front camera; "Keyframe every 2 s" off vs on | Mirror direction (the broadcast should NOT be mirrored); on YouTube Studio -> stream health, any "keyframe frequency" warning with the switch off |

**Compare on the platforms** (YouTube Studio -> Live control room -> Stream health, and watch the live page on another device):
resolution YouTube reports (it should say 1080p/720p, portrait), bitrate and frame rate it receives, "keyframe frequency" / "bitrate low" warnings,
audio in sync, how many seconds from the phone to the platform, and whether the quality *looks* as sharp as the preview. Compare with the same stream from the web studio on a laptop.

**What to send back**: the shared log files, phone model + Android version, and which of the scenarios above misbehaved.
The Live screen shows the sent size, fps, bitrate, `qualityLimitationReason`, encoder, RTT/loss, NACK/PLI/keyframes, encode time per frame,
estimated uplink, battery %, battery temperature, thermal status and network type, every 2 s. The panel turns the size amber and shows a banner when the sent
short side is under 75% of the chosen tier or the limitation reason is not `none` (same rule as the web's `sendQuality.ts`).

## Notes from the first phone test and 0.1.1

* First test (Xiaomi 24115RA8EI, Android 15, 720p portrait, Wi-Fi): full 720x1280 at 30 fps, hardware encoder `c2.qti.avc.encoder`, no limitation, 0% loss, bitrate ramped 0.6 -> 2.4 Mbps over about 40 s.
* **"Dropped by compositor" (10 after 30 s)**: the only place a frame is skipped without an error is "all texture slots still held downstream". Both the encoder and the preview hold a texture until they finish with it, and at start-up the MediaCodec encoder and the preview surface take a few hundred ms to come up, so a handful of drops in the first second is expected and harmless (10 frames = 0.33 s at 30 fps). The ring is now 6 slots (was 4) and the counter is split into `droppedNoSlot`, `droppedError` and `lastDropAtFrame`, shown on the Live screen and written to the log when the stream ends. Interpretation: if `lastDropAtFrame` is small (under about 60) it was start-up and does not matter; if it keeps growing, the encoder or preview is not releasing textures fast enough and that needs a look. `droppedError` must be 0.
* **CSV event rows**: the code always wrote 20 cells per row, but a viewer that collapses runs of commas made the device columns appear under width/height. Missing values are now written as `NA`, so every row has the same columns even after such processing. Event text is in the `event` column.

## Status: what is verified and what is not

Verified in the build sandbox (no phone, no emulator, no camera):
* `./gradlew testDebugUnitTest` and `assembleDebug` pass: WHIP exchange against a MockWebServer (POST body/headers, relative and absolute `Location`, errors, DELETE), H.264-first SDP reordering, send-quality classifier, `getStats` parsing and bitrate maths, CSV log, tiers/plan limits, brand-kit choice, crop maths, API parsing of `/auth/me`, `/platform-connections`, `/plans/me`, `/brand-kits`, `/streams` create, `/streams/:id/status`, TURN credentials, cookie persistence, `Origin` header.

**UNVERIFIED on a device** (compiled only): Camera2 capture, the GL compositor (FBO, `VideoFrameDrawer` rotation/crop, logo orientation and blending, front-camera un-mirroring), texture hand-off to the hardware encoder and the preview, H.264 negotiation with the real MediaMTX, the 2 s keyframe wrapper, the foreground service types on Android 14, runtime permissions, thermal/battery readings, the Compose UI layout. Expect some first-run fixes.

## Risks found for the media pipeline

1. **H.264 may not exist.** `DefaultVideoEncoderFactory` only offers H.264 from a *hardware* MediaCodec encoder (libwebrtc's software factory has no H.264). A phone without a usable one (some emulators, a few budget/Chinese-ROM phones, or hardware blocklisted by libwebrtc) offers VP8 only; the app then refuses ("no H.264") instead of publishing something the RTMP side cannot carry. The Setup screen lists the phone's H.264 encoders so this is visible before going live.
2. **GL texture path** is the least certain part: orientation, crop, FBO ring lifetime and cross-context visibility (`glFinish` is used instead of fences). If frames are black/rotated/frozen, the fix is local to `FrameCompositor.kt`.
3. **Keyframes.** libwebrtc's Android H.264 encoder emits a keyframe about every 20 s unless the receiver sends PLI/FIR. RTMP platforms want 2-4 s. The app wraps the encoder to force one every 2 s; if the wrapper misbehaves, turn the switch off and check whether MediaMTX's PLI is enough.
4. **Bandwidth estimation is the same family of problem as the web.** The spike removes the obvious Android disadvantages (start bitrate, max bitrate, degradation preference) but cannot remove the estimator. The log shows how it behaves on real networks. The deciding question for RTMP is: does 1080p hold on real cellular?
5. **Background limits.** A camera|microphone foreground service is required (Android 14 also needs the CAMERA/RECORD_AUDIO permissions granted before it starts; the app asks first). OEM battery savers can still kill it; a partial wake lock and a Wi-Fi low-latency lock are held while streaming. Android 14+ also restricts *starting* a camera service from the background, so the stream cannot be (re)started with the app in the background.
6. **No reconnect.** A Wi-Fi/cellular switch changes the phone's IP, so ICE has to restart (new WHIP session or ICE restart via PATCH). Out of scope for the spike: the app logs network changes and connection state and shows "Connection FAILED ... end and go live again". Needs: WHIP ICE restart (`PATCH` on the resource) or a re-publish to the same path, and a decision on what the platforms see during the gap (RTMP out of MediaMTX would drop).
7. **Thermal throttling** at 1080p30 with a continuous encode + screen on is likely on mid-range phones; watch battery temperature and `Limited by: cpu`.
8. **App size**: 58 MB debug (libwebrtc native for four ABIs).

## What the founder should test first

1. Install, sign in, **720p, one test channel, Wi-Fi**. Confirm preview + logo, "Encoder ... (hardware)", and that YouTube shows the portrait picture. This single run exercises almost every unverified path.
2. Then 1080p on Wi-Fi for 10 minutes with "Sharp picture" and read the stats: sent size and bitrate vs the tier. This is the number that answers "does native WebRTC fix the 240p problem?".
3. Then cellular 720p and a screen-lock run, and send the shared logs.

# StreamBird Android

Native Android client for [StreamBird](https://streambird.app), the browser live-streaming studio and simulcast service.

**This repository currently holds the SPIKE**: the smallest app that proves a phone can publish a camera + logo stream over
WebRTC/WHIP to StreamBird's existing MediaMTX ingest (which relays H.264 to YouTube / Facebook / Twitch with `ffmpeg -c:v copy`),
and that lets you *measure* what is really being sent (size, fps, bitrate, encoder, CPU/bandwidth limitation, battery, heat).
The result decides whether a later version should also offer RTMP ingest.

What the spike does: sign in (email + one-time code), list the channels connected on the website, pick destinations,
choose quality (540p / 720p / 1080p portrait) and picture priority, overlay a logo, go live, and watch live stats; share a CSV log.

What it deliberately does not do: register accounts or connect channels (use the website), brand editing, lower thirds / tickers,
wallpapers, guests, screen share, recording, background removal, reconnect after a network change.

* How to install and what to test on your phone: [docs/SPIKE.md](docs/SPIKE.md)
* **Publishing on Google Play** (1.0.0, signed AAB, upload key, Play Console checklist, listing, Data safety, risks): [docs/PLAY-STORE.md](docs/PLAY-STORE.md)
* New in 1.0.17: the L-shaped frame works whether "Shrink picture while ads show" is on or off (the switch now only governs lower-third and left-third ads); a switched-off ad is shown clearly (Off chip and a warning line on the ad row, the Live ads sheet and the kit editor, "N ads switched off" on the kit card, one note at go-live); every kit ad starts enabled on a new stream (docs/FEATURES.md section 30; no server change). New in 1.0.16: presets UX from a code-and-heuristics audit (docs/UX-AUDIT-PRESETS.md): delete with Undo, Rename and Duplicate in the card menu, channel chips and last-used wording, a "3 of 20" counter, an empty state, "edited" marker and "Unsaved changes" in Customize, warnings on the card, snackbar feedback, wide-screen columns and TalkBack / contrast fixes (docs/FEATURES.md section 29; no server change). New in 1.0.15: a support session can also apply a brand kit to the running stream (ads, logo, date and time) without dropping a frame, move or resize the logo, and put a name banner on the picture; the host has the same name banner on the Live screen (docs/FEATURES.md section 28, docs/API-CONTRACT.md). New in 1.0.14: silent remote support control of a live stream started from the phone: a support session on the website can update the running stream's ads, ticker and date and time over an HTTP long poll (never start or stop it, never camera or quality); the host's own changes win; no indicator, no setting (docs/FEATURES.md section 27, docs/API-CONTRACT.md). New in 1.0.13: the date and time is now compact by default: plain small text with a soft shadow, no box, directly under the logo, taking turns between the time (5 s) and the date (3 s); the 1.0.12 pill stays available as a style (docs/FEATURES.md section 26). New in 1.0.12: an on-air date and time pill on the picture, set in the brand kit, overridable per stream and switchable while live (docs/FEATURES.md section 25). New in 1.0.11: presets store the YouTube visibility (Public / Unlisted / Private), shown on the card and chosen in Customize; presets without one go Public as before (docs/FEATURES.md section 24). New in 1.0.10: every ad row says in words what it is doing (always on, next shows in 2:41, showing now, off, needs Shrink picture) and a kit ad that cannot be applied is named instead of skipped silently (docs/FEATURES.md section 23). New in 1.0.9: presets that show errors and never hide after one failed load, 8 MB brand images with streaming uploads, default title and description on a brand kit (docs/FEATURES.md section 22). New in 1.0.8: L-shaped frame ads (a fourth ad placement: the live picture goes into the window of a full-frame graphic), the kit stream thumbnail, and animated GIF logos on the broadcast (docs/FEATURES.md sections 19-21). New in 1.0.0 (Play compliance: no purchase steering, in-app account deletion; see docs/FEATURES.md section 14). Earlier, 0.5.0-presets (home with saved preset cards, camera-first one-tap Go live, Customize sheet; earlier: unified brand-kit editor, 16:9 / 9:16 sets, picture shrinks while an ad shows, ads, brand kits, thumbnail, share live link, send-quality chip, website look): [docs/FEATURES.md](docs/FEATURES.md)
* Endpoints, headers and payloads the app uses: [docs/API-CONTRACT.md](docs/API-CONTRACT.md)
* What the backend would need for a proper mobile client: [docs/BACKEND-NEEDS.md](docs/BACKEND-NEEDS.md)

## Build

Toolchain: AGP 8.9.1, Kotlin 1.9.24, Compose BOM 2024.06.00, Hilt 2.51.1 + KSP, Gradle 8.11.1, JDK 17, minSdk 26, target/compile 36 (Play requires 36 since 31 Aug 2026).

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew testDebugUnitTest assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug -PstreambirdBaseUrl=https://staging.example.com   # different default server
```

CI (`.github/workflows/build-apk.yml`) builds on every push to `main` and `claude/**`, uploads the APK as an artifact, and on
`claude/android-spike` / `main` publishes it to the `spike-latest` pre-release (and `claude/android-presets` to `presets-latest`, asset `streambird-presets-debug.apk`).

### Release (Google Play)

```bash
# signing: STREAMBIRD_KEYSTORE_FILE / _PASSWORD / STREAMBIRD_KEY_ALIAS / _KEY_PASSWORD as env vars or in an untracked keystore.properties
./gradlew bundleRelease        # app/build/outputs/bundle/release/app-release.aab  (versionName 1.0.17, versionCode 117 by default)
```

Manual CI build: `.github/workflows/release-aab.yml` (needs four signing secrets). Details: [docs/PLAY-STORE.md](docs/PLAY-STORE.md).

## License

AGPL-3.0, see [LICENSE](LICENSE).

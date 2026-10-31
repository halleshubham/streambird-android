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
* New in 0.2.4-brand (brand kits, thumbnail, share live link, send-quality chip, website look): [docs/FEATURES.md](docs/FEATURES.md)
* Endpoints, headers and payloads the app uses: [docs/API-CONTRACT.md](docs/API-CONTRACT.md)
* What the backend would need for a proper mobile client: [docs/BACKEND-NEEDS.md](docs/BACKEND-NEEDS.md)

## Build

Same toolchain as PostizMobile: AGP 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06.00, Hilt 2.51.1 + KSP, Gradle 8.7, JDK 17, minSdk 26, target/compile 35.

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew testDebugUnitTest assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug -PstreambirdBaseUrl=https://staging.example.com   # different default server
```

CI (`.github/workflows/build-apk.yml`) builds on every push to `main` and `claude/**`, uploads the APK as an artifact, and on
`claude/android-spike` / `main` publishes it to the `spike-latest` pre-release.

## License

AGPL-3.0, see [LICENSE](LICENSE).

# What the backend would need for mobile

**Short answer: the spike needs no backend change.** The cookie session plus a manually sent `Origin` header works for a native
client, because `AccountGuard`'s CSRF check only compares the `Origin` *string* with `PUBLIC_BASE_URL`; the app sends it itself.
Nothing was changed in the StreamBird repo.

That is a deliberate use of a browser protection as an API convention, though. For a shipping app the cleaner options are below,
roughly by value.

## 1. Token auth for devices (recommended before a public release)
* Today: `sb_session` cookie + `Origin == PUBLIC_BASE_URL`. Works, but couples the app to a CSRF rule and to the cookie's expiry.
* Wanted: `POST /auth/verify-code` (or a `?client=mobile` variant) that returns `{token, expiresAt}` and accepts `Authorization: Bearer <token>` in `SessionGuard`/`AccountGuard`, **without** the Origin check on the header path (like `x-api-key` already does).
* Refresh/rotation: a refresh token or sliding expiry so a phone that has not been opened for weeks is not logged out; per-device revoke ("log out this phone") in the account settings.
* Not recommended: reusing `x-api-key`. It is a long-lived account secret and `findByApiKey` skips the user/approval checks.
* If the cookie approach stays: make sure `COOKIE_SECURE`/`sameSite=lax` keep working (they do for OkHttp) and document that the app must send `Origin`.

## 2. Session length and `Origin` on staging
* The app lets you point at another server. `PUBLIC_BASE_URL` of that server must be exactly the origin the app sends (`https://host[:port]`, no trailing slash). `LEGACY_HOSTS` origins are also accepted.

## 3. Ingest for the phone
* **WHIP/H.264 from Android works against today's ingest** if the phone has a hardware (or any) H.264 encoder; that is what this spike measures.
* If the measurements show Android WebRTC adaptation is a problem (or reconnects are painful), the alternative is **RTMP ingest** on the server (MediaMTX already speaks RTMP): an endpoint that returns an RTMP publish URL + key for a stream. The phone then sends a fixed-GOP H.264/AAC stream and no bandwidth estimator can shrink the picture. Needs: `rtmpIngestUrl` in the `POST /streams` response (or a `GET /streams/:id/ingest`), and path auth by stream key.
* Keyframe interval: the server cannot ask Android's hardware encoder for 2 s GOPs except via PLI/FIR. Check that MediaMTX sends PLI regularly for WHIP publishers; the app also forces a keyframe every 2 s itself.

## 4. Plans and limits
* `GET /plans/me` has `maxResolution` (sd/hd/fhd/qhd) with the web's `sd = 360p`. The app's smallest tier is 540p, so a plan limited to `sd` still gets 540p in the app. **Decision for the founder**: allow 540p on `sd` plans, or add a 360p tier / make the server expose pixel limits.
* Resolution and destination limits are only enforced client-side today (the web studio caps the picker). If mobile should be strict, enforce `maxResolution` server-side (e.g. reject a WHIP publish above the tier) - not possible with a pure forward today.
* `watermark: true` on Free: for the web, the "Powered by StreamBird" mark is drawn client-side into the studio canvas (`poweredBy.ts`, `drawPoweredBy`) and the server adds a "Powered by StreamBird" line to the platform description (`powered-by.ts`). The spike does neither on the canvas; the description line is added by the server regardless of client. The in-video mark must be drawn in the GL compositor for the app (small job), and, like on the web, a client-drawn mark can be bypassed. **Decision for the founder**: accept that, or stamp it server-side (impossible with `-c:v copy`).
* `GET /brand-kits` returns 403 on Free (the app handles it); a 200 with `[]` plus a `canUseBrandKits` flag in `/plans/me` would be easier for clients than parsing a 403.

## 5. Push / background
* Pending approval is polled every 30 s while the app is open. A push (FCM) "your studio is ready" and a push when a destination fails mid-stream would help mobile users who leave the app.
* Stream health for the notification: the app reads it from `getStats`; `GET /streams/:id/status` is polled every 10 s for destination state only.

## 6. Smaller items
* `/streams/:id/status` has no timestamp of when the relay last received media; "relayInputStatus: connected" is the only ingest-side signal for "is the phone really sending".
* A documented error code (not only text) for "pending approval" / "plan limit" would avoid string matching (`message.contains("pending")` today).
* `PATCH /auth/me/phone` is needed for the `needsPhone` flow in-app (the spike sends the user to the website).

## 7. Re-publish after a network change (0.2.1)
The app recovers a dead transport by re-publishing on a new PeerConnection: `POST <whipUrl>` again (same URL as `POST /streams` returned), then `DELETE` the old `Location`. No backend change was made; these are the details that decide how clean it is:
* **`overridePublisher`**: `MediaMtxService.registerForward` creates the path with `{source: 'publisher', runOnReady, runOnReadyRestart: true}` and no `overridePublisher`. MediaMTX's default is `yes` (a new publisher replaces the old one), and `mediamtx.yml` is not in the repo (Coolify file storage), so this could not be checked. If the global/path default were `no`, every re-publish would be refused (HTTP 400/409 "someone is already publishing") until MediaMTX drops the dead session (its WebRTC read timeout), and the app would only succeed on a later backoff attempt. Please confirm `overridePublisher: yes` (or add it to the path config in `registerForward`).
* **WHIP ICE restart** (`PATCH` on the resource) is not used: MediaMTX 1.21 supports `PATCH` for trickle candidates only, not for an ICE restart.
* **Slate/forwarders**: `GlitchRecoveryService` debounces 10 s, pushes the slate for up to 5 min (`GLITCH_WINDOW_MS`) and stops it within 2 s of the publisher returning. Clean resume needs nothing more. A re-publish makes the path not-ready/ready again, which restarts the ffmpeg forwarders (`runOnReadyRestart`); an early keyframe helps them (the app's encoder wrapper forces one every 2 s; the new encoder's first frame is a keyframe).
* **Nice to have**: `GET /streams/:id/status` already returns `relayInputStatus: connected|idle`; the app could poll it faster (e.g. 2-3 s) during "Reconnecting..." to show "Back online" only when the server really sees the picture. A documented `409` body for "already publishing" would let the app retry faster without string matching.
* A cellular uplink with ~1000 ms RTT and 280 kbps cannot carry 720p; nothing server-side fixes that (a lower tier or Wi-Fi does).

## 8. Orphaned live streams (0.2.3)
After the 0.2.1 native crash two streams stayed "Live" on the dashboard forever (the app crashed right at Go live and never called `/end`). What ends a stream today: (a) an explicit `POST /streams/:id/end`; (b) `GlitchRecoveryService.giveUp` after 5 minutes without a publisher, BUT only for streams whose publisher was observed ready at least once (`watched`; "a stream that was created but never published to isn't a glitch"); (c) `SessionLimitService` at the plan's `maxSessionHours` (Free 2 h; no cap = never). A stream created with `status: live` whose host never published (app crash/kill between `POST /streams` and the first WHIP POST, closed tab, lost connection before connecting) is in none of these cases until its plan cap, and bills wall-clock hours meanwhile (`recordStreamUsage` uses `startedAt`).
Suggested: in `GlitchRecoveryService.tick`, also track LIVE streams that have a `whipUrl` and were never observed ready (a DB query by `startedAt`, so it survives restarts) and auto-end them after N minutes (e.g. 10-15) with the same `streamsService.end()`; or end them when `publisher never connected` for the glitch window. The app now offers an "A stream is still marked live" card (GET /streams, End stream) as a manual fallback and ends the stream itself if its own start fails.

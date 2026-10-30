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

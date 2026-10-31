# API contract used by the spike

Everything below was read from the backend code (`/home/user/streambird`, not modified) and from the web app's own client
(`web-app/src/api/client.ts`). The app talks to **`<server>/api/...`**, default server `https://streambird.app`.

## Session, headers, CSRF

| Item | Value | Source |
| --- | --- | --- |
| Auth | Cookie `sb_session` (httpOnly, sameSite=lax, `Expires` set by the server) | `auth.controller.ts` `setSessionCookie`, `SessionGuard`, `AccountGuard` |
| Alternative auth | `x-api-key: <account API key>` skips the cookie and the Origin check (programmatic use). **Not used**: it is an account-wide secret, not a per-device login. | `AccountGuard` |
| `Origin` | **Required on every non-GET request that uses the cookie** and must equal the server's `PUBLIC_BASE_URL` (or a `LEGACY_HOSTS` origin), else `403 Origin mismatch`. The app sends the configured server's own origin (`scheme://host[:port]`, default port omitted) on every request to that server. | `AccountGuard` CSRF block |
| Body | `Content-Type: application/json` | `client.ts` |
| Errors | Nest default `{"statusCode":4xx,"message":"text" or ["text",...],"error":"..."}` | all controllers |

The cookie is kept in a persistent `CookieJar` (DataStore), so the session survives restarts exactly like the browser.
`/auth/*` routes behind `SessionGuard` (`/auth/me`, `/auth/logout`) do not check Origin; `AccountGuard` routes do.

## Calls

### Login (email + one-time code)

1. `POST /api/auth/request-code` body `{"email":"a@b.dev"}` -> `202 {"message":"If that email is valid, a login code has been sent."}` (same answer for unknown emails).
2. `POST /api/auth/verify-code` body `{"email":"a@b.dev","code":"123456"}` (code is exactly 6 chars) -> `200` + `Set-Cookie: sb_session=...` and
   ```json
   {"user":{"id":"..","email":"a@b.dev","role":"user","approvedAt":null,"phone":null,"needsPhone":false},"accountId":".."}
   ```
   Wrong/expired code -> `400 {"message":"Incorrect code."}` or `"This code is invalid or has expired. Request a new one."` (shown verbatim).
3. `GET /api/auth/me` -> same shape as above. Used on app start and, while pending, every 30 s (the web's `APPROVAL_POLL_MS`).
4. `POST /api/auth/logout` -> `204`, cookie cleared.

**Pending approval**: `user.approvedAt == null && role != "superadmin"` (the guard's rule). Every `AccountGuard` route then answers
`403 "Your account is pending Superadmin approval."`; only `/auth/me` keeps working. The app shows "Your studio is being set up!" with *Check again*.
`needsPhone: true` (phone required, none yet) -> the app asks the user to add the number on the website (`PATCH /auth/me/phone` exists but is not implemented in the spike).

### Channels and limits

* `GET /api/platform-connections` -> `[{"id","platform":"youtube|facebook|twitch|linkedin","externalAccountId","label","isActive","liveSlug","liveLinkUrl"}]`.
  Twitch manual (RTMP) connections appear here as `platform: "twitch"`. Only `isActive` ones are offered.
* `GET /api/plans/me` -> effective limits:
  ```json
  {"planKey":"free","planName":"Free","maxDestinations":1,"maxGuests":1,"maxResolution":"hd","assetStorageMb":0,"watermark":true,"maxSessionHours":2,"planExpired":false, "...": "..."}
  ```
  `maxResolution` is `sd|hd|fhd|qhd`. The app caps ticked destinations at `maxDestinations` and tiers at `maxResolution`
  (sd -> 540p, hd -> 720p, fhd/qhd -> 1080p). `assetStorageMb == 0` means no brand library.
* Connect/add channels is browser-only (`<server>/connections`): the OAuth flows redirect through the browser.

### Brand

* `GET /api/brand-kits` -> `[{"id","name","logoAssetId","logoPosition":"left|right","logoSize":90,"wallpaperAssetId","wallpaperDim","lowerThirdStyle","isDefault","connectionIds":[...]}]`.
  Free plan: `403 "Brand kits are on the paid plans..."` (the app treats it as "no brand logo").
* `GET /api/assets/:id/file` -> the image bytes (`Cache-Control: private, max-age=3600`).
* Which kit: the kit whose `connectionIds` contains the one ticked channel, else `isDefault`, else the first.
* Logo geometry = web `drawLogo` (`useHostStudio.ts`): height = `logoSize` canvas pixels, width by aspect, **16 px** from the top and from the left (`logoPosition: left`) or right edge.
  Canvas pixels are the output size (e.g. 1080x1920), so the same `logoSize` looks smaller at 1080p than at 540p - same as the web.
  Not implemented in the spike: the logo's entrance animation, animated logos (first frame only), lower third, ticker.

### Stream lifecycle

* `POST /api/streams` body
  ```json
  {"title":"Mobile test","visibility":"public","orientation":"portrait","destinationConnectionIds":["<uuid>"]}
  ```
  (`CreateStreamDto`: title <= 200, 1..50 uuid ids; `orientation` defaults to landscape server-side, so the app sends the chosen orientation explicitly, `portrait` or `landscape`; the serializer is configured with `encodeDefaults`, with a regression test.)
  Response (`StreamResponseDto`): `{"id","title","status":"live","whipUrl":"https://<mediamtx>/<path>/whip","orientation":"portrait","studioSessionId","destinations":[{"id","platformConnectionId","status","watchUrl","errorMessage"}], ...}`.
  The stream is `live` as soon as it is created (platform broadcasts are created and MediaMTX forwards are registered); nothing else is called to "start" it.
  Throttled by `StreamCreateThrottlerGuard`.
* `GET /api/streams/:id/status` -> `{"status","relayInputStatus":"idle|connected|null","destinations":[{"id","platformConnectionId","status","platformStatus","viewerCount","watchUrl","errorMessage"}]}`; polled every 10 s (web `DESTINATION_STATUS_POLL_MS`). If `status != "live"` the app stops.
* `POST /api/streams/:id/end` (no body, Origin required) -> stream; also tears down the relay. The app first sends the WHIP `DELETE`, then this.
* `GET /api/studio-sessions/turn-credentials` (public, throttled 20/min) -> `{"iceServers":[{"urls":"stun:..."|[...],"username","credential"}]}`; STUN fallback `stun:stun.l.google.com:19302` (web `DEFAULT_ICE_SERVERS`).

### WHIP (to the MediaMTX host in `whipUrl`, no StreamBird cookie, no Origin)

1. `POST <whipUrl>`, `Content-Type: application/sdp`, body = complete offer SDP (ICE gathering finished or 3 s cap, as the web). H.264 listed first.
2. `201` (any 2xx) + `Location: <resource>` (may be relative: resolved against `whipUrl`) + body = answer SDP.
3. Hang up: `DELETE <resource>` (best effort).

Post-answer sender setup (web `goLive`): `maxBitrate` per tier (1 / 3 / 6 Mbps), `degradationPreference` (maintain-resolution or balanced), keyframes about every 2 s.
Android has no `generateKeyFrame()`, so a wrapper around the H.264 encoder requests a keyframe every 2 s (`KeyFrameEncoderFactory`).

## Added in 0.2.0-brand (brand kits, assets, thumbnail, live link)

Sources: `src/assets/assets.controller.ts`, `brand-kits.service.ts`, `assets.service.ts`, `image.util.ts`, `src/streams/streams.controller.ts`, `thumbnail.util.ts`. All routes are behind `AccountGuard` (cookie + `Origin`).

| Call | Body / response | Notes |
| --- | --- | --- |
| `GET /api/brand-kits` | `[{id,name,logoAssetId,logoPosition,logoSize,wallpaperAssetId,wallpaperDim,lowerThirdStyle,isDefault,connectionIds[]}]` | `403` on Free. The app does not call it when `/plans/me` has `assetStorageMb == 0`. |
| `POST /api/brand-kits` | `{name(1..80), logoAssetId?, logoPosition left\|right, logoSize 16..600, lowerThirdStyle bar\|accent\|glass, isDefault?}` | The first kit becomes the default automatically. |
| `PATCH /api/brand-kits/:id` | same fields, all optional | The app always sends `wallpaperAssetId` and `wallpaperDim` back **unchanged** (it has no wallpaper UI) and an explicit `logoAssetId: null` to clear the logo. `isDefault` only when turning it on. |
| `DELETE /api/brand-kits/:id` | `204` | Its channels fall back to the account default. |
| `PUT /api/brand-kits/connections/:connectionId` | `{brandKitId: uuid \| null}` | One kit per connection; `null` = account default. The editor sends one PUT per changed channel. |
| `GET /api/assets` | `{enabled, quotaBytes, usedBytes, items:[{id,kind,name,contentType,sizeBytes,width,height,createdAt,usedBy[]}]}` | `enabled:false` = object storage not configured on the server; `quotaBytes:0` = Free. |
| `POST /api/assets` | multipart: `kind=logo`, optional `name`, `file` | PNG/JPG/WebP (GIF for logos), <= 4 MB, <= 8192 px. Refusals: `400` (type/size/dimensions), `403` (Free / not enough storage), `503` (storage not enabled). The app pre-checks the same rules and shows the backend message verbatim. |
| `DELETE /api/assets/:id` | `204` | `409 "This file is used by the brand kit ..."` is shown as is. |
| `GET /api/assets/:id/file` | image bytes | Loaded by Coil through the app's OkHttpClient (cookie + Origin). |
| `PUT /api/streams/:id/thumbnail` | multipart field `file` (JPEG) -> `{hasThumbnail, thumbnailUpdatedAt, platformWarnings?: string[]}` | JPEG/PNG, <= 2 MB, >= 640 px wide (`parseThumbnail`). Throttled 10/min. Called in the background after `POST /streams`. |
| `GET /api/platform-connections` | `liveLinkUrl` per YouTube/Facebook connection (`https://streambird.app/stream/<slug>`) | Shown with Copy/Share on Channels, shared from Live. Fallback: destination `watchUrl` from `/streams/:id/status`. |

Brand kit choice for a stream (web `useHostStudio.ts` auto-apply): the kit the user picked on Setup; else the kit of the first ticked destination that has one; else the account default; else the first kit.

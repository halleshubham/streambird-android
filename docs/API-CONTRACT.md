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
`needsPhone: true` (phone required, none yet) -> the app shows the phone step *before* the pending screen (same order as the web's `ProtectedRoute`), see below.

**Sign-up**: there is no separate sign-up call. `verify-code` and `google/mobile` create the account (unapproved, `needsPhone: true` for new users) when the email is new, so the app's login screen is also its sign-up screen.

### Phone step (1.0.4)

`PATCH /api/auth/me/phone` (behind `SessionGuard`, so it works before approval) body `{"phone":"+91 98765 43210"}` (string, max 32 chars; nothing else is sent). The server normalises to E.164 (`normalizePhone`): a bare 10-digit number starting 6-9 (or 0/91 prefixed) is Indian, anything else needs `+` or `00` and the country code, 8-15 digits.
* `200` -> same body as `/auth/me`: `{"user":{...,"phone":"+919876543210","needsPhone":false},"accountId":".."}`. The app applies it like a login (`AuthRepository.applyUser`): stage PENDING if `approvedAt == null`, else READY.
* `400 {"message":"Enter a valid mobile number, with the country code if it is not an Indian number."}` (also class-validator text for a missing or too long field) -> shown inline verbatim (still passed through `neutralizePlanSteering`).
* `401` -> session ended, back to the sign-in screen. `429` -> "Too many tries. Wait a minute and try again." Network -> "Cannot reach the server: ...".

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
| `POST /api/assets` | multipart: `kind=logo`, optional `name`, `file` | PNG/JPG/WebP (GIF for logos), <= 8 MB per file (4 MB before the cap was raised; a thumbnail kind is still <= 2 MB), <= 8192 px. Refusals: `400` (type/size/dimensions), `403` (Free / not enough storage), `503` (storage not enabled). The app pre-checks the same rules and shows the backend message verbatim. |
| `DELETE /api/assets/:id` | `204` | `409 "This file is used by the brand kit ..."` is shown as is. |
| `GET /api/assets/:id/file` | image bytes | Loaded by Coil through the app's OkHttpClient (cookie + Origin). |
| `PUT /api/streams/:id/thumbnail` | multipart field `file` (JPEG) -> `{hasThumbnail, thumbnailUpdatedAt, platformWarnings?: string[]}` | JPEG/PNG, <= 2 MB, >= 640 px wide (`parseThumbnail`). Throttled 10/min. Called in the background after `POST /streams`. |
| `GET /api/platform-connections` | `liveLinkUrl` per YouTube/Facebook connection (`https://streambird.app/stream/<slug>`) | Shown with Copy/Share on Channels, shared from Live. Fallback: destination `watchUrl` from `/streams/:id/status`. |

Brand kit choice for a stream (web `useHostStudio.ts` auto-apply): the kit the user picked on Setup; else the kit of the first ticked destination that has one; else the account default; else the first kit.

## Ads (0.3.0)

Source: the web studio's ads.ts / backend `brand-kits.service.ts` + DTOs. Asset kind `ad`: `POST /api/assets` multipart `kind=ad`, `file`, optional `name` (PNG/JPG/WebP/GIF, <= 8 MB, <= 8192 px); `GET /api/assets` lists them (`usedBy` includes ad usage); deleting a used file is `409 "used by the brand kit(s) ..."` (shown verbatim).

`GET /api/brand-kits` (and the POST/PATCH responses): every kit has `ads: [{placement: "lower_third"|"side_third"|"full_screen", assetId, enabled, mode: "continuous"|"interval", showSec, everySec}]` ordered lower_third, side_third, full_screen; continuous ads have `showSec`/`everySec` = null. `everySec` is the HIDDEN time between showings (not the period).

`POST`/`PATCH /api/brand-kits`: `ads` has REPLACE-ALL semantics: omitted or null = unchanged (POST: none), `[]` = clear. The app sends `ads` ONLY when the user changed them, so other edits never touch them, and sends `[]` for "Remove ads". Rules: max 3, one per placement, assets owned and of kind `ad`, `showSec` integer 1..3600, `everySec` integer 1..86400, interval needs both (400 otherwise); a non-empty `ads` on a plan with `watermark: true` (Free) is `403 "Your own ads are on the paid plans..."` (clearing is always allowed); kits also need `assetStorageMb > 0`; a logo/wallpaper cannot use an `ad` asset (400); conflicts 409. The app does not call the kit editor's ad actions until `GET /plans/me` has loaded (`watermark`, `assetStorageMb`).

## 16:9 / 9:16 sets and picture shrink (0.4.0-orientation)

Source: web PR "brand-orientation" (`brand-kits.service.ts`, `brand-kit.dto.ts`). Deployed with that PR; the app tolerates their absence.

Kit response/body fields: landscape `logoAssetId`, `wallpaperAssetId`, `ads`; portrait `logoAssetIdPortrait`, `wallpaperAssetIdPortrait`, `adsPortrait`; shared `logoPosition`, `logoSize`, `wallpaperDim`, `lowerThirdStyle`, `adsShrinkPicture` (boolean, default true). `ads` / `adsPortrait` are replace-all per set (omitted or null = unchanged, `[]` = none). The logo/wallpaper ids use `!== undefined` on PATCH, so an explicit `null` clears. A stream uses the set of its `orientation`; portrait falls back to the landscape logo only. Asset upload `kind` for wallpapers is `wallpaper` (no GIF). The app sends a new field to PATCH only when it changed, so an older server never sees it.

## Presets (0.5.0-presets)

Saved go-live setups. The app tolerates the endpoints being absent (404, 405, 501 = no presets feature): the presets section is hidden and one implicit "Quick start" card is shown. Any other failure (offline, 5xx, a page that is not JSON) never hides it (1.0.9).

| Call | Body / response | Notes |
| --- | --- | --- |
| `GET /api/presets` | `[{id, name, config, lastUsedAt}]` | Most recent first (the app re-sorts by `lastUsedAt` descending, never-used last). Rows without an `id` are skipped; unknown fields ignored; a wrapper `{items:[...]}` is also accepted. |
| `POST /api/presets` | `{name, config}` -> `201` preset | Name trimmed, at most 80 characters (app-side cap). |
| `PATCH /api/presets/:id` | `{name?, config?}` -> preset | Only the given parts are sent. `config` is sent whole. |
| `DELETE /api/presets/:id` | `204` | |
| `POST /api/presets/:id/use` | `200` preset | Sets `lastUsedAt`; sent in the background when a saved preset is gone live with. Failures are ignored. |

`config` = `{orientation?: "landscape"|"portrait", destinationIds?: string[] (platform connection ids), brandKitId?: string|null, quality?, tickerEnabled?: boolean, tickerText?: string (<= 200)}`. The server rejects unknown keys, so the app writes exactly these six and leaves null fields out (a preset without `brandKitId` means "automatic"). Every field is optional when reading.

`quality`: the app has no server-side quality value of its own (streams are created without one), so it uses the web's plan resolution names: `sd` = 540p, `hd` = 720p, `fhd` = 1080p. Reading also accepts `540p|720p|1080p`, `p540|p720|p1080` and `qhd` (as 1080p), any case; an unknown value leaves the current quality alone. **To be confirmed against the backend.**


## Account deletion (Play requirement; coded against this contract, backend built separately)
Session cookie auth + `Origin`, like every other route.
| Call | Answer | Notes |
| --- | --- | --- |
| `GET /api/account/delete/preview` | `{isOnlyAdmin:boolean, memberCount:number, activeSubscription:{plan:string,nextRenewal:string\|null}\|null, liveStream:boolean, connectionsCount:number, assetsCount:number}` | the app tolerates missing / null / wrong-typed fields (defaults: false, 1, null, false, 0, 0) |
| `POST /api/account/delete/request` | `204` | emails a 6-digit code to the signed-in user |
| `DELETE /api/account` body `{code:string, confirmCompanyDeletion?:boolean}` | `204` | `confirmCompanyDeletion` is sent only as `true` when the user ticked the box. `409` = confirmation required but missing; `400`/`401` = wrong or expired code; `403` = superadmin (not deletable) |
A `404` on any of them (older server) shows "Account deletion is not available on this server yet. Contact support@shackyapps.in". Web path: `https://streambird.app/delete-account`.

## Sign in with Google (native, removed from the app in 1.0.6)
`POST /api/auth/google/mobile {"idToken"}` still exists on the server but **the app no longer calls it** (the Credential Manager sign-in was removed). The app signs in with Google only through the browser flow below.

## Continue with Google: browser flow (1.0.5; the only Google sign-in of the app since 1.0.6)
PKCE-style handoff that needs no Google client, Play services or SHA-1 registration on the phone. Server: `GET /api/auth/google/app`, `POST /api/auth/google/app/redeem`.
1. The app opens `GET <server>/api/auth/google/app?challenge=<challenge>` in a Chrome Custom Tab. `challenge` = base64url(SHA-256(verifier)), 43 chars, no padding; `verifier` = 43-char base64url of 32 secure random bytes, kept in the app (persisted 10 minutes) and never put in a URL.
2. The server redirects to Google and, after the login, serves a page that navigates to `streambird://auth?code=<code>` (or `streambird://auth?error=google_failed`).
3. The app (intent filter on `MainActivity`: scheme `streambird`, host `auth`, VIEW + DEFAULT + BROWSABLE) parses `code` / `error`, then `POST /api/auth/google/app/redeem` body `{"code":"<code>","verifier":"<verifier>"}` (nothing else). Success answers like `POST /api/auth/verify-code`: `200`, `Set-Cookie: sb_session=...`, `{"user":{"id","email","role","approvedAt","phone","needsPhone"},"accountId":".."}`, applied like every login.
| Status | Meaning | App text |
| --- | --- | --- |
| 400 / 401 | bad body, unknown / used / expired code, verifier does not match | "Google sign-in could not be completed. Try again or use the email code." |
| 404 | server without the endpoints | "Google sign-in is not available on this server yet. Use the email code." |
| 429 | rate limited | "Too many sign-in attempts. Wait a minute and try again." |
Network failures show "Cannot reach the server: ...". The app ignores a link with no pending verifier or one older than 10 minutes (friendly message, no request), never logs the code or verifier, and clears the verifier after every redeem attempt.

## L-shaped frame ads and kit thumbnail (1.0.8)
Source: web PR "L-shape + thumbnail" (`brand-kits.service.ts`, `brand-kit.dto.ts`, `create-stream.dto.ts`) and the GIF fallback PR. The app tolerates the fields being absent and a 400 from an older server.

| What | Contract |
| --- | --- |
| Ad placement `l_shape` | New value next to `lower_third` / `side_third` / `full_screen`, accepted in the kit `ads` and `adsPortrait` arrays with the same entry shape `{placement, assetId, enabled, mode, showSec, everySec}` (asset kind `ad`). Up to 4 ads (one per placement) per orientation. Responses are ordered lower_third, side_third, full_screen, l_shape. Replace-all is unchanged: omitted or null = unchanged, `[]` = none, and a list WITHOUT `l_shape` removes the frame, so the app sends every placement that has a picture. Free plan (`watermark: true`): non-empty `ads` is `403` as for the other ads. An older server answers `400` ("placement must be one of ..."), which the editor turns into "This server does not support L-shaped frame ads yet." |
| Kit field `thumbnailAssetId` | On `GET` / `POST` / `PATCH /api/brand-kits`. `PATCH`: omitted = unchanged, `null` = clear (the app sends it only when it changed). The asset must be the account's own `image/jpeg` or `image/png`, <= 2 MB, >= 640 px wide, otherwise `400` ("The thumbnail must be a JPG or PNG image." / "... is too large -- the limit is 2 MB." / "... is too small -- it must be at least 640px wide ..."). One per kit, shared by both orientations. An older server omits it on GET (the app reads null) and may answer `400` to it. |
| Asset kind `thumbnail` | `POST /api/assets` multipart `kind=thumbnail` (plus `file`, optional `name`). The app pre-checks JPG/PNG, 2 MB, 640 px. A file used by a kit cannot be deleted (`409 "This file is used by the brand kit ..."`) and `usedBy` includes the kit. |
| `POST /api/streams` `thumbnailBrandKitId` | Optional uuid. Omitted = automatic (the destination's kit, else the account default kit); a kit id = that kit; `null` = none. The app sends it ONLY when the user explicitly chose a kit that has a `thumbnailAssetId` (so only servers that know the field see it); otherwise it is omitted. A thumbnail picked on the phone is still uploaded with `PUT /api/streams/:id/thumbnail` after the stream exists and replaces whatever the kit put there. |

## Error envelope (checked against the real server, 1.0.9)

Every error goes through the server's `AllExceptionsFilter`: `{"statusCode":400,"message":<what Nest produced>,"timestamp":"..."}` where `<what Nest produced>` is an OBJECT `{"message": "text" | ["text", ...], "error": "Bad Request", "statusCode": 400}` for thrown HTTP exceptions (a ValidationPipe failure has the ARRAY, e.g. `["config.each value in destinationIds must be a UUID"]`), or a plain string for unexpected errors (`"Internal server error"`). `ApiClient.errorMessage` reads strings and arrays at any depth, joins arrays with ", ", and falls back to "Request failed (<status>)". `ApiException.body` keeps the first 300 raw characters for the diagnostics log only.

## Presets, as verified against the server (1.0.9)
`destinationIds` and `brandKitId` must be v4 UUIDs (`@IsUUID('4')`); `quality` is `sd|hd|fhd|qhd`; `orientation` `landscape|portrait`; unknown keys at any level are a 400 (`forbidNonWhitelisted`); an account keeps at most 20 presets (409 "You can keep up to 20 presets. Delete one to save another."); a destination or kit that is not the account's is 422; `PATCH` replaces `config` wholesale; `DELETE` is 204; `POST /presets/:id/use` also returns `effectiveConfig` and `warnings` (ignored by the app). `GET` is a bare array ordered by `lastUsedAt` desc (nulls last). Only 404 / 405 / 501 on `GET /presets` mean "no presets feature".

## Brand image size (1.0.9)
Per file 8 MB for `kind` logo, wallpaper and ad (was 4 MB; the server's `MAX_ASSET_BYTES`), 2 MB for `kind=thumbnail` and `PUT /streams/:id/thumbnail`, 8192 px. The app streams the multipart file part with an exact `Content-Length`; its upload client waits 60 s per stalled write and 120 s for the answer.

## Brand kit default title and description (1.0.9)
`GET/POST/PATCH /api/brand-kits` carry `title` (<= 100) and `description` (<= 5000), `string | null`, trimmed by the server; PATCH omitted = unchanged, `null` or blank = cleared. The app sends them on PATCH only when changed and on POST only when non-blank, and treats absent fields (older server) as null. Meaning: defaults for streams that use the kit; `POST /streams` with a non-blank `title`/`description` keeps the stream's own, a blank/omitted one takes the resolved kit's. The stream answer may include `titleFromKit` / `descriptionFromKit` (booleans); the app parses them leniently (any JSON, anything else = unknown) and only logs them. The app never sends `description` on `POST /streams` and sends the resolved title (typed, else the kit's default, else "Mobile stream").

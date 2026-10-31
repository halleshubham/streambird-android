# 0.2.0-brand: what was added

Scope of the app (decided): log in, see the integrated channels, brand setup, start a stream. No guests, wallpaper, background removal, screen share or recording.

## Look and feel
* The real StreamBird bird (`web-app/public/logo.png`, trimmed, copied to `res/drawable-nodpi/logo_bird.png`) is the launcher icon (adaptive, 108 dp, bird in the safe zone on `#F5F3FF`, monochrome layer for themed icons, legacy `mipmap-*` PNGs), the Android 12+ splash icon (`Theme.StreamBird.Starting`, core-splashscreen), the header of the login / "Your studio is being set up!" / "One more step" screens and the top bar of Channels. The "test logo" overlay is the same bird.
* `ui/theme/Theme.kt` maps the website tokens (`web-app/src/index.css`: `--accent #7c3aed`, `--text`, `--text-h`, `--bg`, `--card-bg`, `--border`, `--error`, `--warning`, and the dark set) onto a Material 3 light/dark scheme that follows the system, with 6 dp / 10 dp shapes and the website's type scale.
* `ui/screens/SbComponents.kt`: `Button` / `OutlinedButton` / `TextButton` / `Card` with the website's 6 dp radius, ghost-outline secondary buttons and bordered panels (declared in the screens' package so existing screens pick them up), `PlatformBadge` (YouTube red, Facebook blue, Twitch purple), `StatusBadge`, `Segmented` (pill control for visibility, quality, picture priority, logo position, lower-third style), `BirdTopBar`, `SubTopBar`, `PageHeading`. The Go live button carries the broadcast icon.

## 1. Brand kits (paid plans)
Channels -> "Brand kits" (or Setup -> Manage brand kits).
* **Brand screen** (`BrandScreen`): kits with a logo preview (position and size drawn as on air, GIF animated via Coil's GIF decoder), "Default" badge, the channels using each kit, "Use" for the next stream (default: Automatic = web rule), Edit, New. File library: used / quota bar, files with size, dimensions, `usedBy`, delete (a `409 used by the brand kits ...` is shown verbatim), "Upload a logo from the gallery".
* **Editor** (`BrandKitEditorScreen`): name, logo (library picker or upload; PNG/JPG/WebP/GIF, <= 4 MB, <= 8192 px, checked on the phone by `brand/ImageHeader.kt` and again by the backend), left/right, size slider 16..600, lower-third style bar/accent/glass (stored only; the app draws none), "use as account default", channel assignment (`PUT /brand-kits/connections/:id`, unticked = default), delete. Editing sends `wallpaperAssetId` / `wallpaperDim` back unchanged.
* **Setup**: when "Brand kit logo" is chosen, a Brand kit section (Automatic or a specific kit). The compositor (unchanged) draws the logo of the chosen kit; the kit is fetched again at go-live so edits apply.
* **Free plan** (`assetStorageMb == 0`): the screen explains it and links to `<server>/billing`; no brand calls are made. **Storage not enabled** (`/assets` `enabled:false`): amber notice, uploads disabled.
* An **animated GIF** logo is animated in the preview only; the GL compositor uses its first frame (`BitmapFactory` decodes one frame). Animating it on air is out of scope.

## 2. Thumbnail at start
Setup -> "Thumbnail (optional)" (Android photo picker, no storage permission). After `POST /streams` succeeds the app decodes the picture off the main thread (subsampled, EXIF-rotated, transparency flattened to white), fits it into 1280x720 (landscape) / 720x1280 (vertical stream; the server's `orientation` decides), never narrower than 640 px (backend minimum), JPEG quality 90 -> 42 and then smaller sizes until under 2 MB (`brand/ThumbnailPlan.kt`), and `PUT /streams/:id/thumbnail`. This runs in parallel to the WHIP publish, so go-live is never delayed, and failures never affect the stream. The Live screen shows a dismissible notice: uploaded, or the `platformWarnings` (e.g. YouTube needs a verified channel), or the error text.

## 3. Share live link
Channels: each connection with a `liveLinkUrl` shows the link with Copy (clipboard) and Share (ACTION_SEND, "Watch me live: <link>"). Live: "Share live link" shares the links of the destinations being streamed to (`liveLinkUrl`, else the destination `watchUrl`).

## 4. Send-quality chip
Top of the Live screen: "Sending 720x1280 - 30 fps - 2.4 Mbps", green dot; amber with the reason when limited. Same rules as `sendQuality.ts` (bandwidth, cpu, short side < 75 % of the chosen size; size check skipped in the first 6 s; `other` alone is not flagged). Texts: "Your connection is limiting quality. Try a lower quality or a better connection." / "Your phone is struggling. Try a lower quality." / "The picture is being sent smaller than the Np you chose. Try a lower quality or a better connection." The detailed stats table is behind a collapsed **Details** section; **Share log** is unchanged.

## Not verified on a device
Everything above is compiled and unit-tested (parsing, payloads, multipart bodies, sizing/compression decisions, share text, chip mapping, theme tokens) but **not run on a phone or emulator**: all new UI and its layout/colours in light and dark, the adaptive icon and splash rendering (including the themed monochrome icon), Coil image loading with the session cookie, GIF animation, the photo picker, real image decoding/EXIF rotation, uploads against the real backend (R2 storage, `platformWarnings` from YouTube/Facebook), the share sheet and clipboard.

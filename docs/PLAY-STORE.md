# Publishing StreamBird Android on Google Play

Everything needed for the first Play release (1.0.0, versionCode 100; current defaults 1.0.6 / 106 add in-app account creation and Google sign-in through the browser (section 10)). Prepared 2026-11-03. Nothing here has been run on a device.
Listing texts to copy-paste are in `docs/play-store/listing/`, graphics in `docs/play-store/`.

## 0. Release build facts

| Item | Value |
| --- | --- |
| applicationId | `com.shackyapps.streambird` (changed from the debug-build id `app.streambird.android`; the Kotlin package stays `app.streambird.android`) |
| Version | name `1.0.0`, code `100` (single source: top of `app/build.gradle.kts`, override with `-PstreambirdVersionCode=` / `-PstreambirdVersionName=`; debug builds were 1..18 so they stay below) |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 (see "Target API level") |
| Toolchain | AGP 8.9.1, Gradle 8.11.1, Kotlin 1.9.24, JDK 17 (AGP 8.5.2 cannot compile against API 36) |
| Bundle | `./gradlew bundleRelease` -> `app/build/outputs/bundle/release/app-release.aab`; ABI, density and language splits enabled |
| Signing | upload key from env vars or untracked `keystore.properties` (below). Without them the build still works but is NOT Play-uploadable |
| Minify / resource shrinking | OFF for 1.0.0 (see "Minify decision") |

### Signing the release (nothing secret is in the repo)

Create `keystore.properties` in the repo root (git-ignored) or export the same names as environment variables:

```
STREAMBIRD_KEYSTORE_FILE=/absolute/path/streambird-upload.jks
STREAMBIRD_KEYSTORE_PASSWORD=...
STREAMBIRD_KEY_ALIAS=streambird-upload
STREAMBIRD_KEY_PASSWORD=...
```

`./gradlew bundleRelease` then signs the AAB with the upload key. With none of them set, `assembleRelease` falls back to the debug key (so forks and CI still build) and `bundleRelease` produces an AAB signed with the debug key: Play will reject it. Always check `keytool -printcert -jarfile app-release.aab` shows the upload key's fingerprint before uploading.

The first upload keystore was generated outside the repo (RSA 4096, alias `streambird-upload`, 10000 days; PKCS12, so the store and key passwords are the same value). Keep the `.jks` and its password in a password manager and back them up. **Play App Signing (recommended, and mandatory for new apps that publish an AAB):** Google holds the real app signing key; the key above is only the *upload* key. If it is lost or leaked you ask Play support (Play Console > Setup > App signing > "Request upload key reset") to register a new upload key; the app keeps its identity. Never enrol a self-managed app signing key.

Upload key certificate fingerprints (public) are listed in the hand-off report; read them any time with `keytool -list -v -keystore streambird-upload.jks -alias streambird-upload`.

### CI release build (`.github/workflows/release-aab.yml`)

Manual (Actions > "Release AAB (Google Play)" > Run workflow). Add four repository secrets first: `STREAMBIRD_KEYSTORE_BASE64` (`base64 -w0 streambird-upload.jks`), `STREAMBIRD_KEYSTORE_PASSWORD`, `STREAMBIRD_KEY_ALIAS`, `STREAMBIRD_KEY_PASSWORD`. If any is missing the run fails at the first step with a message that names them. The AAB is kept as the workflow artifact `streambird-release-aab` (30 days, not a GitHub release). The existing debug workflow is untouched.

### Target API level (checked 2026-11-03)

Google's "Target API level requirements" page (support.google.com/googleplay/android-developer/answer/11926878): new apps and updates must target **Android 16 (API 36)** from 31 Aug 2026 (extension for existing apps ended 1 Nov 2026). The app therefore targets and compiles against 36. This needed AGP 8.9.1 + Gradle 8.11.1. Behaviour changes to re-test on a real device (the code was only compiled and unit-tested):
* Android 16 ignores `screenOrientation`/resizability restrictions on large screens (tablets and foldables >= 600dp): the portrait-locked `MainActivity` can be shown in landscape or resized there. Phones are unaffected. The app has no layout for tablets: expect stretched screens.
* Edge-to-edge is enforced since API 35; already in effect with target 35.
* Predictive back is on by default for target 36 (the app uses `BackHandler`-style handling; check that Back during a live stream still moves the app to the background).
* Foreground service types (camera|microphone) are unchanged; permissions must be granted before the service starts (the app already asks first).

### 16 KB page size (Play requirement for apps targeting Android 15+, from 1 Nov 2025)

See the result in section 8 (filled in from the actual bundled `.so` files).

### Minify decision

`isMinifyEnabled` and `isShrinkResources` stay **false** for 1.0.0. Reasons: libwebrtc looks Java classes and methods up by name from native code (JNI), Hilt/Dagger, kotlinx.serialization, Coil/GIF decoding and the crash reporter add more reflective entry points, and there is no device here to prove a shrunk build starts and streams. A release crashing at startup or in `PeerConnectionFactory` is worse than a bigger download. Follow-up (1.1): add keep rules (`-keep class org.webrtc.** { *; }`, `-keepclasseswithmembernames class * { native <methods>; }`, serialization `$$serializer` rules, Hilt generated classes are handled by the Hilt consumer rules), build `minifyEnabled=true`, upload to the internal track and run a full go-live test (camera, ticker, ads, GIF, reconnect, crash report) on 2-3 real phones before shipping. Upload the R8 mapping file with that release.

## 1. First-release checklist (Play Console)

1. **Developer account**: pay the one-time fee, verify identity (and for organisations a D-U-N-S number). Personal accounts created after 13 Nov 2023 must also do closed testing before production (step 7). Verify your contact details and a support email.
2. **Create app**: All apps > Create app. Name `StreamBird: Go Live Everywhere` (30/30 characters), default language English (India or United States), App (not game), Free, accept declarations. Content of the app is free to download; plans are bought elsewhere (see risk R2).
3. **Play App Signing**: on first upload to a new app, Play enrols you in Play App Signing automatically for AABs. Keep the upload key in section 0. Do not upload a self-managed signing key.
4. **App content** (Policy > App content), complete every card: Privacy policy (`https://streambird.app/privacy`), App access (section 6g), Ads (section 6f), Content rating (6f), Target audience (6f), Data safety (section 4d), Government apps (No), Financial features (none), Health (none), **Foreground service permissions** (section 5), **Account deletion URL** (section 6g/R3: required before production), News (not a news app).
5. **Store listing**: texts from `docs/play-store/listing/`, icon, feature graphic, 2-8 phone screenshots (section 3), category, contact email, website `https://streambird.app`.
6. **Internal testing track first** (Testing > Internal testing): create release, upload `streambird-1.0.0-release.aab`, add up to 100 testers by email list, roll out. Available within minutes, no review needed for internal. Install through the opt-in link on 2-3 real phones and test the full flow (login, channel list, go live to a test YouTube channel, ticker, brand kit, reconnect by toggling Wi-Fi, end stream).
7. **Closed testing (required for NEW personal accounts)**: checked on Google's help page "Testing requirements for new personal developer accounts" (support.google.com/googleplay/android-developer/answer/14151465) on **2026-11-03**: at least **12 testers opted in** to a closed test, opted in continuously for at least **14 days** (a tester who leaves and re-joins resets the count to consecutive days). Then Dashboard > "Apply for production"; Google usually answers in about a week. Organisation accounts are not subject to this rule. Plan for ~3 weeks from the first closed-test rollout to production.
8. **Pre-launch report**: after the first closed/internal upload Play runs the app on test devices (Firebase Test Lab). The login needs a real code, so give it the review account (6g) under Release > Testing > Pre-launch report > App access, or accept the failed login screens.
9. **Production**: create the production release (same AAB or a newer versionCode), choose countries, release notes (`whats-new-1.0.0.txt`), start a staged rollout (for example 20%), submit for review.
10. Every later release needs a higher `versionCode` (the build uses 100; use 101, 102, ...).

## 2. Store listing (drafts)

Texts live in `docs/play-store/listing/` (`app-name.txt`, `short-description.txt`, `full-description.txt`, `whats-new-1.0.0.txt`). Lengths checked: name 30/30, short description 80/80, full description 2554/4000, what's new 253/500.

* **App name**: `StreamBird: Go Live Everywhere` (30 characters, the limit). Play rejects names with promotional claims ("best", "#1") and emoji; this one is a product name plus a tagline. If review objects to the tagline use `StreamBird`.
* **Short description**: "Go live from your phone to YouTube, Facebook and Twitch at once, with your logo."
* **Full description**: see file. Only real features: camera-first one-tap go live, presets, brand kits (logo, position/size, 16:9 and 9:16 sets), ticker, own ads, StreamBird mark and banner "depending on your account's plan", reconnect after network change, send-quality chip, thumbnail, live link share, crash report share. It states that a StreamBird account is needed (sign in with email and a one-time code; it no longer tells people to register on the website) and that channels are connected on the website, and mentions account deletion. It does not mention prices, paid plans, plan costs or where to buy.
* **Scheduling**: the Android app does not create scheduled streams (that is on the website); the description therefore does not claim it. If you want "schedule from the website" in the text, say it as "Plan your streams on the StreamBird website" only after you confirm a scheduled stream can be started from the app (not verified).
* **Category**: Application > **Video Players & Editors** (it is a video production/streaming tool). "Social" is wrong (no social graph); "Tools" is the fallback.
* **Tags** (pick up to 5 from Play's list): Video streaming / Live streaming, Video editor, Social video (choose what the console offers).
* **Contact**: email `support@shackyapps.in` (already used inside the app for "Email support"; confirm this is the one to show publicly), website `https://streambird.app`, phone optional (new personal accounts may need one for verification, not shown publicly unless you enter it in the listing).
* **Privacy policy URL**: `https://streambird.app/privacy`. It must name the mobile app, camera/microphone/Google STUN use and the deletion path (R3). Not verified that the page already covers the mobile app.
* **What's new (1.0.0)**: see file.

## 3. Graphics

| Asset | Spec | File |
| --- | --- | --- |
| App icon | 512x512 PNG, 32-bit, <= 1 MB, full square (Play rounds it) | `docs/play-store/streambird-icon-512.png` (the launcher bird on `#F5F3FF`, generated by `make-graphics.py`) |
| Feature graphic | 1024x500 PNG/JPG, no alpha, keep text inside the centre | `docs/play-store/streambird-feature-graphic-1024x500.png` (brand purple gradient, bird, tagline) |
| Phone screenshots | 2-8 required, 9:16 portrait **1080x1920** PNG/JPG (min 320 px, max 3840 px, long side <= 2x short side), <= 8 MB each | to capture on a real phone |
| 7-inch / 10-inch tablet screenshots | optional; the app is phone-only in practice | skip |

Screenshots to capture (suggested order, 1080x1920, light theme, a test account with 2 channels; blur real channel names): 1. Home with presets ("Ready when you are"), 2. camera-first Go live screen with destination chips, 3. Customize sheet (quality, brand kit), 4. Brand kit editor (logo slot), 5. Live screen with the send-quality chip and ticker, 6. Ads sheet, 7. Channels with live link and Share. Do not show real viewer names, tokens, or other people's faces. To produce exact sizes from a phone with a different resolution: `convert in.png -resize 1080x1920^ -gravity center -extent 1080x1920 out.png`.

## 4. Data safety form (drafted from the code)

Based on reading the app (`data/`, `diag/`, `media/`, `brand/`, `stream/`) and the backend docs. Answer what is true after the founder checks the backend/privacy page. "Collected" in Play means data leaves the device to you or a third party.

**Data collected / shared**

| Play data type | Collected | Shared | Purpose | Optional | Notes |
| --- | --- | --- | --- | --- | --- |
| Email address (Personal info) | Yes | No | Account management, app functionality | No | login code sent by email; the session cookie is kept on the phone |
| User IDs (account id, session cookie) | Yes | No | Account management | No | |
| Video (Photos and videos > Videos) | Yes (live camera picture) | Declare **Yes, shared** with the destination platforms the user chooses | App functionality | No | live picture goes to StreamBird's media server (MediaMTX over WebRTC/WHIP) and is relayed to YouTube/Facebook/Twitch; not recorded by StreamBird. Play allows "user-initiated transfer to a third party" to be excluded from "sharing": confirm in the console wording; if unsure declare shared |
| Audio (Audio files > Voice or sound recordings) | Yes (live microphone audio) | same as video | App functionality | No | same path as video; no recording |
| Photos (Photos and videos > Photos) | Yes, only what the user picks | No | App functionality | Yes | logo, ad pictures and thumbnail picked with the Android photo picker, uploaded to the StreamBird server (stored in object storage for paid plans) |
| Other user-generated content / App info and performance (Crash logs, Diagnostics) | **No, not automatically**: the app has no analytics or crash SDK. The crash report (device model, Android version, last 200 app events) is only shared when the user taps Share | n/a | n/a | n/a | if you consider a user-initiated share as collection, declare "Crash logs", optional, app functionality. The simple answer is that nothing is sent automatically |
| Device or other IDs / Location / Contacts / Financial info / Messages | No | | | | no permission or code for them. No payments in the app |
| Approximate IP address | Not declared as a type; STUN: if the StreamBird TURN credentials cannot be fetched the app falls back to Google's public STUN server (`stun.l.google.com:19302`), so the phone's IP is visible to Google. Declare under "Device or other IDs"? No: mention in the privacy policy | | | | |

**Security practices**: data encrypted in transit: Yes (HTTPS for the API; WebRTC media is encrypted with DTLS-SRTP; cleartext `http://` server addresses are refused except local/dev). Users can request data deletion: **Yes** (in the app: Account > Delete account; on the web: `https://streambird.app/delete-account`; answer Yes only once the backend and the web page are live, see R3). Data is not collected through an Independent Security Review. Committed to Play Families policy: No.

**Google sign-in (1.0.2)**: when the user taps "Continue with Google" the Google account's email address and name are shared with the StreamBird server (through the browser flow: the server's own OAuth callback, then a one-time code the app redeems at `/api/auth/google/app/redeem`). This is the same "Email address" data type already declared (account management); name is covered by "Name" under Personal info: declare it as collected too if the server stores it. Not shared with third parties.

**Third-party SDKs**: No Google Play services SDK is used for sign-in (Google sign-in runs in the browser); no third-party SDK that collects data. Bundled libraries: stream-webrtc-android (WebRTC), OkHttp, Coil, Jetpack. No ads SDK, no analytics, no Firebase.

## 5. Permissions and foreground service declarations

| Permission | Why (plain wording for the console) | Declaration needed |
| --- | --- | --- |
| `CAMERA` | Shows the camera on the Go live screen and sends it as the live video the user chose to broadcast | Runtime prompt only. Camera is a hardware requirement (`uses-feature camera required=true`), so devices without a camera cannot install |
| `RECORD_AUDIO` | Sends the microphone as the live audio of the stream | Runtime prompt |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Talk to StreamBird, send the stream, detect Wi-Fi/mobile changes to reconnect, keep the Wi-Fi lock while live | Normal permissions |
| `MODIFY_AUDIO_SETTINGS` | Audio routing needed by WebRTC while streaming | Normal |
| `WAKE_LOCK` | Keep the stream running with the screen off | Normal |
| `POST_NOTIFICATIONS` (Android 13+) | The foreground-service notification shown while live, and "Reconnecting / Back online" | Runtime prompt |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE` | `StreamingService` runs `foregroundServiceType="camera|microphone"` so streaming continues when the user switches app or turns the screen off | **Yes: Play Console > App content > Foreground service permissions.** For each type (Camera, Microphone) describe the user-facing feature ("live streaming from the camera/microphone that the user starts and can see in the notification") and upload a short video (30 s to 2 min, unlisted YouTube link or file) that shows: tap Go live, grant permissions, notification shown, switch to another app while still live, return and End stream. No `mediaProjection` type is used (screen share is not in the app) |

Nothing else is requested (no location, storage, contacts, SMS).

## 6. Policy answers

**(f) Content rating (IARC questionnaire)**: category "All other app types"/"Utility, productivity, communication or other". Answer: no violence, sexual content, language, controlled substances or gambling is produced by the app itself. The app does enable **user-generated live content to be broadcast to other platforms (YouTube/Facebook/Twitch)** but is not a place where users interact with each other and share content inside the app: answer "Users can interact / share content with each other?" **No** (viewers watch on the third-party platforms) and "shares user location" No. Expect a rating such as Everyone / PEGI 3 / possibly Teen (UGC streaming). Do not select "Users can purchase digital goods": there is no in-app purchase.
**Target audience**: age groups **18+** (safest) or 13+; YouTube/Facebook/Twitch themselves require 13+ and live streaming, and StreamBird accounts are created by adults paying for plans. Not designed for children, so the **Families policy does not apply** and "Appeals to children" = No.
**Ads declaration**: "Does your app contain ads?" **No third-party ads**. Accurate wording: StreamBird shows no ads in the app. It can *render the host's own ads onto the broadcast picture* (brand-kit ads) and, on the Free plan, a StreamBird banner and "Powered by StreamBird" mark on the stream; these are part of the video the user broadcasts, not ads shown to the app's user. Answer "No" to the ads question and explain this in the notes if the console has a free-text field; if a reviewer disagrees, answer "Yes" and the only effect is an "Contains ads" label.

**(g) App access (login)**: users can create an account in the app (email code or "Continue with Google"; new accounts add a mobile number and are approved by hand), so say so in the declaration. The app needs an account and email codes, so reviewers cannot log in without help. **Google review account**: `oauthtestks@gmail.com` (a test user on the Google OAuth consent screen, section 10) signs in without a code; tell the reviewers they can also use it with "Continue with Google" or by typing the address in the email field. Create a dedicated review account on the existing backend mechanism: the backend env var for review accounts (`users/review-accounts.ts`, read at call time; see backend docs "Review accounts") lists emails that are auto-approved, moved to the `REVIEW_ACCOUNT_PLAN` (default `pro`), and **skip the emailed code** (typing the address signs in). Steps for the founder: add a throwaway address (e.g. `play-review@shackyapps.in`) to that list in Coolify, redeploy, connect a test YouTube channel/Twitch to that account on the website (the app cannot connect channels) and verify login in the app. Draft for the Play Console "App access" instructions:

> The app requires login. Open the app, enter the email address `play-review@shackyapps.in` and tap Continue. No password or code is needed for this review account: it signs in immediately. The account is on a Pro plan with a test channel connected. To test streaming: on the home screen tap "Go live now" (or "Pick channels and set up by hand"), allow camera, microphone and notification permissions, tap Go live, then End stream. Channels are connected on the website (https://streambird.app/connections); this has already been done for the review account.

(Replace the email; remove it from the review list after approval if you wish. Do not put the review account's real channel credentials in the instructions. Review accounts that bypass the code are a deliberate auth bypass: keep the list to throwaway addresses.)

## 7. Risks and what is missing

* **R1: 16 KB page size** - see section 8.
* **R2: Payments policy (Play Billing) - RESOLVED BY REMOVAL in this build.** The app has no in-app purchases and, since this change, nothing in it steers to a non-Play payment method: the "See plans" buttons (Brand screen, ads in Setup, ads in the kit editor), the `<server>/billing` links, the text "Upgrade to keep brand kits ...", every "paid plans" / "Free plan" sentence shown to the user, and the login button "New here? Register on the website" are gone. Where a plan lacks a feature the app only says "... not included in this account's plan" (no link, button, price or call to action), server error texts that mention upgrade/plans/pricing/billing are replaced by the same neutral sentence, and the listing (`full-description.txt`, `whats-new-1.0.0.txt`) no longer says "paid plans" or tells people to register on the website. **Honest caveat**: the StreamBird web app does sell plans, and the account the app signs into may have been bought there. Google's rules on external offers / alternative billing changed in 2026 (secondary sources only, NOT checked against Google's official pages; India eligibility unconfirmed). **Re-check Google's current Payments and external-offers policy before ever adding a link, price or plan wording back**, and decide then between Play Billing, an eligible external-offers programme, or staying "no steering" as now. Reviewer-facing risk that remains: a reviewer may still ask how plans are bought; the answer is "outside the app, on the website, the app does not mention it".
* **R3: Account deletion (Play requirement) - built in the app, depends on the backend.** In-app path: Home > Account > Delete account (preview of what is deleted, emailed 6-digit code, optional company-deletion confirmation, "Delete my account permanently"; see FEATURES.md section 14). Web URL for the Play Console "Delete account" form: **`https://streambird.app/delete-account`** (the page and the backend endpoints `GET /api/account/delete/preview`, `POST /api/account/delete/request`, `DELETE /api/account` are being built separately; contract in API-CONTRACT.md). **Before submitting**: confirm the endpoints and the web page are live on production (until then the app shows "Account deletion is not available on this server yet. Contact support@shackyapps.in"), that deletion really removes the data (streams, connections/tokens, assets in object storage, brand kits, presets, sessions, user; payment records kept only where the law requires, stated in the privacy policy), and that the privacy policy names the deletion path. Not verified against the real backend or on a device.
* **R4: WebRTC / device compatibility**: streaming uses hardware H.264 encoders; the field log shows device-specific issues (a Xiaomi native crash fixed in 0.2.2). Play's pre-launch report and the closed test devices are the first real diversity; expect device-specific reports. The `camera` hardware requirement excludes camera-less devices. Not verified on tablets/foldables or Android 16 (target 36).
* **R5: No device verification**: all UI, camera, GL, GIF, reconnect and permission flows are unit-tested/compiled only (see FEATURES.md "Not verified on a device"). Test the release AAB (universal APK from bundletool, section 9) on a phone before the first rollout.
* **R6: Unshrunk build**: bigger download; no R8 mapping to upload. See Minify decision.
* **R7: Review login** needs the review account (6g) and a connected test channel; without it the review fails at login.
* **R8: Free-plan marks on the stream** and "Powered by StreamBird" are client-drawn; not a Play issue.
* **R9: Content**: since users broadcast arbitrary live content, keep a reporting contact (support email) in the listing; YouTube API Services policy requires the privacy policy to link to Google's privacy policy, as the website does for YouTube (not verified for the mobile text).

## 8. Native libraries and 16 KB alignment

Checked on the 1.0.0 AAB with `readelf -lW` (PT_LOAD `p_align`) and `zipalign -c -P 16` on the bundletool universal APK (2026-11-03):

| ABI | `libjingle_peerconnection_so.so` (stream-webrtc-android 1.3.10) | `libdatastore_shared_counter.so` | Result |
| --- | --- | --- | --- |
| arm64-v8a | LOAD align 0x4000 (16 KB) | 0x4000 | OK |
| x86_64 | 0x4000 | 0x4000 | OK |
| armeabi-v7a | 0x1000 (4 KB) | 0x4000 | fine: the 16 KB rule applies to 64-bit ABIs only |
| x86 | 0x1000 (4 KB) | 0x4000 | fine: 32-bit |

ELF alignment of every 64-bit library is 16 KB, and AGP 8.9.1 zip-aligns uncompressed `.so` files to 16 KB (`zipalign -P 16` verification successful). **So the bundled WebRTC is 16 KB compatible; no newer artifact is needed.** Not run on a 16 KB-page device or emulator. The AAB keeps all four ABIs the library ships (arm64-v8a, armeabi-v7a, x86, x86_64) and Play delivers only the one that matches each device; x86/x86_64 cover emulators and Chromebooks, and removing them would save no download size for phones.

Upload key certificate (public): SHA-256 `E2:81:9E:04:6D:3C:47:EA:61:FE:CB:92:51:EA:6B:8E:A1:AF:04:87:F8:15:5C:F8:B5:EC:85:79:77:11:12:AF`, SHA-1 `D5:28:AC:30:A6:58:D5:A2:08:2D:D7:B2:03:78:0C:FD:39:CA:B1:42`. Use them to confirm the upload key in Play Console > App signing.

## 9. Testing the exact release configuration

`docs/play-store/` does not ship the binaries. To build a sideloadable universal APK from the AAB with the upload key:

```bash
java -jar bundletool-all-1.18.1.jar build-apks --bundle=app-release.aab --output=test.apks --mode=universal \
  --ks=streambird-upload.jks --ks-key-alias=streambird-upload --ks-pass=file:pass.txt --key-pass=file:pass.txt
unzip test.apks universal.apk     # install with adb install universal.apk
```

A universal APK signed with the upload key cannot update a Play-installed copy (Play re-signs with its own key): uninstall first. Debug and release builds cannot update each other either.

## 10. Google sign-in (browser flow, 1.0.6)
"Continue with Google" opens the server's web OAuth flow in a Chrome Custom Tab and comes back through a `streambird://auth` link (docs/FEATURES.md sections 17 and 18, docs/API-CONTRACT.md). **No Android OAuth client and no SHA-1 registration are needed**: nothing is matched against the app's signing key, so sideloaded, CI debug and Play-signed builds all work, and Google Play services is not required. (Up to 1.0.5 the app used Credential Manager, which needed each signing SHA-1 registered and failed with `[16] Account reauth failed` on the founder's phone; that code and the `androidx.credentials` / `googleid` libraries are gone. Any Android OAuth client created in Google Cloud for it can be deleted, nothing uses it.)

**OAuth consent screen**: while the Google OAuth app is in *Testing* only the listed test users (for example `oauthtestks@gmail.com`) can sign in. For everyone else publish it to *Production*; the app asks only for the basic scopes (`email profile openid`, no sensitive scopes), which should not need the full verification review, but Google may still ask for a brand verification (app name, logo, privacy policy link, authorised domain). The server's web client / consent screen is the one used by this flow and by the website's Google login.

Data safety is unchanged: the Google account email/name is shared with the StreamBird server through the browser flow (the server reads it on its own OAuth callback); no new data type, no Google SDK (`androidx.browser` is a UI library). The temporary verifier lives in app-private storage for at most 10 minutes.

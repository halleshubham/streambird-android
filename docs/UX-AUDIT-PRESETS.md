# UX audit: presets in the Android app (version 1.0.16)

**Method and its limits.** This audit is based on the Compose code and the docs (`docs/FEATURES.md` sections 13, 22, 24, `docs/API-CONTRACT.md`, the web's `PresetsSection.tsx`), evaluated against Nielsen's 10 heuristics, Material Design 3 guidance and Android accessibility guidance (TalkBack semantics, 48 dp targets, 4.5:1 contrast, 200 % font, no colour-only meaning). **No device, emulator or screenshot was available**: nothing below was looked at on a screen. Layout statements are derived from the code; contrast numbers are computed from the colour tokens (WCAG relative luminance). Line numbers refer to `main` at 1.0.15 (the state audited), not to the files after this change.

Scope: Home "Ready when you are" cards, the card menu, "+ New preset", the camera-first screen and its Customize sheet, the "Save as preset / Update / Delete" block, the preset states in `PresetController` (loading, empty, error, offline, old server), `PresetLogic` summaries and plan-limit notes, the 20 limit, ordering, staleness, and the Account bits.

## 1. Flow inventory

```
HOME ("Ready when you are")
 |- loading ............ s.loading spinner | presets list loads in the background (Quick start card shown meanwhile)
 |- error / no channels  Retry | "Connect channels on the website"
 |- cards (last used first)
 |    |- first card (gradient): tap anywhere / "Go live now" ---------------------> CAMERA SCREEN (no customize)
 |    |- other cards: tap anywhere / "Go live" ----------------------------------> CAMERA SCREEN
 |    |- "..." menu: Edit ------------------------------------------------------> CAMERA SCREEN + Customize sheet open
 |    |              Rename (1.0.16, dialog) | Duplicate (1.0.16) | Delete (Undo snackbar 1.0.16; before: dialog)
 |    '- no presets / old server: one implicit "Quick start" card (no menu)
 |- "+ New preset" (3 of 20; at 20: explanation instead) ------------------------> CAMERA SCREEN + Customize (implicit default)
 |- links: set up by hand (Channels -> Setup) | Brand kits | website | Refresh
 '- feedback: snackbar (confirmations, errors, Undo)   [before: inline banners]

CAMERA SCREEN (GoLiveScreen)  title - channel chips (toggle) - Go live (red) - summary line - Customize
 |- "Preset: <name> (edited: quality, ticker)"  [edited part: 1.0.16]
 |- plan notes under the title (channel lost, quality above plan, kit deleted)
 |- Go live -> permission flow -> stream starts; a saved preset also gets POST /use (last used) in the background
 '- CUSTOMIZE sheet
      |- preset block: Save as preset (new) | Preset: <name> (open)  -> name, visibility, "3 of 20", Save / Update / Delete / Save as new
      |      states: blank name | duplicate name (warning) | at 20 | saving | saved | error | "Deleted ... Undo"
      '- SetupFields: title, visibility*, shape, quality, picture priority, logo overlay, brand kit, ads, ticker, date/time, thumbnail,
         camera, experiments  (* shown once: in the preset block when the server has presets)

PresetController states: not loaded -> loaded+available(items) | loaded+available(empty) | loaded+loadError | loaded+unavailable (404/405/501)
  write ops: save / update / rename / duplicate (busy -> notice | error) ; delete: hidden -> Undo window -> server delete (or restore on failure)
```

## 2. What is already good (kept)

* One tap from Home to the camera screen, one more to go live; presets never block going live (`markUsed` is silent).
* Honest failure model since 1.0.9: only 404/405/501 hide the feature, other failures keep the cards and say "Could not load your presets" with a retry.
* Server words reach the user (limit message, validation); local blank-name check; "That preset no longer exists" for presets deleted elsewhere; delete of a preset already gone counts as done.
* The plan-limit adjustments (lost channel, quality above plan, deleted kit) are applied safely and explained, never silently.
* Visibility is explicit everywhere (summary shows Public / Unlisted / Private; the picker title names the value; the phone's Public fallback is documented).
* List is re-read on every Home open, so presets made on the web or another phone appear; the order (last used first) is stable and predictable.
* The name field starts with a useful proposal ("YouTube + Facebook") so Save works at once.
* Web parity: same terms ("Ready when you are", "Go live now", "New preset", Edit, Delete).

## 3. Findings

Severity: Critical = blocks a task or loses data; High = frequent confusion, accessibility blocker or data risk; Medium = friction, inconsistency, partial accessibility; Low = polish. **No Critical finding.** Status: Implemented (this change) / Deferred (reason).

| # | Sev | Where (1.0.15) | What the user experiences | Principle | Fix | Status |
|---|-----|----------------|---------------------------|-----------|-----|--------|
| 1 | High | `HomeScreen.kt:126`, `GoLiveScreen.kt:253` | Delete asks "Delete X?" and is final. A mis-tap on the neighbouring "..." item loses the preset, its settings and its last-used rank. | Error prevention / user control (undo beats confirm); M3: prefer undo for reversible-feeling deletes | Delete hides the card and shows "Deleted X  Undo" (8 s). The server delete runs after the window, so Undo is an exact restore (same id, same last-used time, no re-create that could hit the 20 limit). Failure puts the card back. Same in the Customize block (inline Undo row). | Implemented |
| 2 | High | `SbComponents.kt:171` (`Segmented`), `SetupScreen.kt:120` (logo radios) | TalkBack reads each option as a radio button but never says which one is selected, so Visibility, Quality, Shape, Priority are unusable without sight. | Accessibility: state must be exposed (selected) | `selectable(selected, role = RadioButton)` + `selectableGroup()`; logo rows are one selectable row. Affects every `Segmented` in the app. | Implemented |
| 3 | High | `HomeScreen.kt:145,162,168,178` | The whole card is clickable, so TalkBack merges the card and its inner "Go live" button and menu into one node (inner actions may be unreachable); several identical "Go live now" buttons; the menu is just "Preset options" (which one?). | Accessibility: reachable, named controls | Info area is its own button ("Open X"); Go live button says "Go live now with X"; menu says "More options for X"; warning rows say "Heads up: ...". | Implemented |
| 4 | High | `GoLiveScreen.kt:165` (no marker), `:229` | After changing quality or channels while a preset is open, nothing says the setup now differs from the preset; the host cannot tell whether "Update preset" would change anything, and may go live with an edited setup believing it is the preset. | Visibility of system status; recognition over recall | Pure `presetChanges` compares the open setup with what the preset applies today: camera screen shows "Preset: X (edited: quality, ticker)"; the block says "Unsaved changes: Quality, Ticker" or "Matches the saved preset". Lost channels / plan lowering are not counted as edits. | Implemented |
| 5 | High | card menu `HomeScreen.kt:178-185` | Only Edit and Delete. To fix a typo in a name or to make a variant the host must open the camera screen, change things and retype. The API supports PATCH name and POST. | Flexibility and efficiency | Menu: Edit, Rename (dialog, PATCH name only), Duplicate (POST same config, "<name> copy", unique, <= 80), Delete. "Save as a new preset" also in the sheet while a preset is open. | Implemented |
| 6 | High | `GoLiveScreen.kt:229` + `PresetController.save` | "Update preset" PATCHes the whole `config` from a snapshot taken when the card was opened. If the same preset was changed on the web meanwhile, that change is overwritten silently. | Error prevention; data integrity | Needs an `updatedAt`/ETag from the server (not in the contract) or a re-read plus compare plus confirm dialog. The risk window is small (host edits while the screen is open). | Deferred: no version field; a compare-and-confirm flow is possible later without a contract change, see decisions |
| 7 | High | `GoLiveScreen.kt:180` | Destination chips are 36 dp high (below 48 dp) and have no selected/checked state for TalkBack (selection is shown by colour and a "+ " prefix only visually). | Accessibility: targets, state | 48 dp min height, `selectable(role = Checkbox)`. | Implemented |
| 8 | Medium | `HomeScreen.kt:118-124` | Success/error appear as inline banners that push the cards down (4 s, not announced); an error stays until the next action. Feedback differs between Home (banner) and Customize (inline text). | Consistency; visibility of status; M3 snackbars for transient feedback | Home: one `SnackbarHost` for confirmations, errors (with Dismiss) and Undo (auto-announced). Customize: inline lines are live regions. | Implemented |
| 9 | Medium | `HomeScreen.kt:194`, `GoLiveScreen.kt:248` | No count; the 20 limit is found out by typing a name and getting the server's 409 after Save. | Error prevention; system status | "+ New preset" shows "3 of 20 presets"; at 20 it becomes an explanation card; Save/Duplicate are off with the reason; Duplicate at 20 is refused before any request. | Implemented |
| 10 | Medium | `GoLiveScreen.kt:229` (`remember`), name field | Typed name lost on process death; only "blank" is validated; a duplicate name is silently allowed (two cards that look alike); no IME action, no sentence capitalisation. | Error prevention; mobile forms | `rememberSaveable`; blocking reason for blank, soft warning for duplicates (the server allows them); counter; `ImeAction.Done` closes the keyboard; `imePadding` on the sheet. Same field in Rename. | Implemented |
| 11 | Medium | `HomeScreen.kt:74` | An account with no preset sees only a "Quick start" card and "+ New preset": nothing explains what a preset is or that it is made from Customize. | Help; onboarding of the feature | Empty-state card "Make your first preset" (what it saves, two ways to make one); disappears with the first preset. | Implemented |
| 12 | Medium | `HomeScreen.kt:74` | Older server: the "Quick start" card appears with no explanation; "Quick start" and "preset" are never connected. | Consistent terms; help | Caption "Saved presets are not available on this server yet. Quick start uses your current setup." | Implemented |
| 13 | Medium | `HomeScreen.kt:74` (`ps.loaded` unused) | The list loads in the background while the Quick start card is shown, then the cards replace it under the finger; no progress. | Visibility of status | "Loading your presets..." row (live region) above the cards. The swap itself remains (blocking the card for a slow server would also block going live). | Implemented (row); swap Deferred |
| 14 | Medium | `HomeScreen.kt` (no use of `ps.busy`) | Duplicate/rename/delete show no progress on Home; a double-tap could send twice. | System status; error prevention | Linear progress under the heading (live region); Rename/Duplicate/Delete are disabled while a write runs. | Implemented |
| 15 | Medium | `HomeScreen.kt:150-151,165`, `PresetLogic.kt:165` | Summary is one long joined line that wraps mid-sentence (YouTube + Facebook + Twitch + ...); the name is one line with no ellipsis (clipped at large font); a deleted kit reads "Brand kit brand". | Recognition over recall; accessibility: 200 % font | Channels as chips, details on a quiet line, "Used 3 days ago", name up to 2 lines with ellipsis, stacked layout (no row that squeezes), "Brand kit" for an unknown kit (test updated deliberately). | Implemented |
| 16 | Medium | cards | A card gives no sign that its channel was disconnected or its quality is above the plan; that is only learned after opening the camera screen. | Visibility of status; error prevention | `presetWarnings` (same notes as the camera screen) shown on the card with a warning icon and "Heads up:" for TalkBack; "N not connected" chip. | Implemented |
| 17 | Medium | `GoLiveScreen.kt:241` (`Amber` text), `:240` green notice | Amber D97706 text on white is about 3.2:1 and the success green 16A34A about 3.3:1 (below 4.5:1). | Accessibility: contrast | `warningTextColor()` (B45309, about 5.0:1) and `okTextColor()` (15803D, about 5.0:1), brighter variants in dark; used for all preset text. The tinted `Notice` boxes use the normal text colour and were left. | Implemented (preset UI); other Amber text elsewhere in the app Deferred (out of scope) |
| 18 | Medium | `HomeScreen.kt:96` (`LazyColumn`) | On tablets, foldables and landscape (Android 16 ignores the portrait lock on large screens, target SDK 36) the cards stretch across the whole width. | Adaptive layouts (M3) | `LazyVerticalGrid(Adaptive(340 dp))`: one column on phones, more on wide screens; the first card, empty state and links span the full width. | Implemented |
| 19 | Medium | `SbComponents.kt:187`, `SetupScreen.kt:96-156` | Screen and section titles are plain text; TalkBack users cannot jump by heading in the long Customize sheet. | Accessibility: headings | `heading()` on `PageHeading` and a new `SectionTitle` for the Customize sub-sections. | Implemented |
| 20 | Medium | `GoLiveScreen.kt:234` | The sheet mixes settings a preset stores with ones it does not (title, logo, ads, thumbnail, date/time, camera); users expect "Save" to keep what they see. | Match between system and user's world; help | Two lines in the block: what a preset remembers / "Not saved in a preset: ...". | Implemented |
| 21 | Medium | `GoLiveScreen.kt:244-249` | While a preset is open the only choices are Update and Delete; saving a variant needs a detour. | Flexibility | "Save as a new preset" (uses the name field, duplicate warning applies). | Implemented |
| 22 | Medium | `GoLiveScreen.kt:199,276` | "Customize" tap target about 36 dp, round icons 44 dp. | Accessibility: 48 dp | `heightIn(min = 48.dp)`, 48 dp icons; title has an `onClickLabel` and 48 dp. | Implemented |
| 23 | Medium | `PresetController.writeError` (`:187`) | 422 reads "One or more destinationIds do not belong to this account" (jargon, no next step); 429 shows the raw message. | Plain language; error recovery | Server words kept, plus "A channel or brand kit in it may no longer be on your account; open the preset, check them and save again."; 429 "Wait a minute and try again". | Implemented |
| 24 | Medium | `HomeScreen.kt:150` | The gradient card says "LAST USED" with no time; other cards show nothing about use. | Recognition | "Used today / yesterday / 3 days ago / 2 weeks ago / Never used" on real cards. | Implemented |
| 25 | Low | `GoLiveScreen.kt:190` | Go live is greyed with no reason while the plan loads. | Disabled states explain why | Label "Loading your plan...". | Implemented |
| 26 | Low | `HomeScreen.kt:94,112` | "Refresh channels" does not refresh presets (they refresh on open). | Consistency | "Refresh channels and presets" refreshes both. | Implemented |
| 27 | Low | Home "Go live now" | The label suggests the broadcast starts; it opens the camera screen where the red Go live is the second tap. Same behaviour and label as the web. | Match between system and real world | Keep (task: unchanged, web parity). | Deferred: founder decision (see section 6) |
| 28 | Low | list order | Only "most recently used first". No pin, default or manual order; the card you use most can sink after a one-off use. | Flexibility | Needs a device-local pin (server has no field). | Deferred: no server support; local-only pin would differ per device |
| 29 | Low | Home | No pull to refresh (Material 3 1.2.1 in this BOM has no stable `PullToRefresh`). | Efficiency | The Refresh button now covers presets too. | Deferred: needs a Compose BOM bump |
| 30 | Low | `AccountScreen.kt:45` | Delete-account list says "Your presets and settings" without a count (the preview has none). | Recognition | Needs a server field. | Deferred: contract |
| 31 | Low | Customize sheet on tablets | `ModalBottomSheet` is as wide as the screen; fields stretch. | Adaptive layouts | Cap the sheet width. | Deferred: needs a device to judge |
| 32 | Low | cards | Swipe or TalkBack custom actions on a card (instead of the menu) would be faster for TalkBack. | Efficiency | `customActions` semantics. | Deferred: menu is reachable now (finding 3) |
| 33 | Low | Home vs Brand kits | Brand kits keep a confirm dialog for delete; presets now use Undo. | Consistency | Kits own uploaded assets, so a deferred delete is not equivalent; decision for the founder. | Deferred: decision |

Principle coverage: reduced motion (no new animation is used; the snackbar and progress bar use Material defaults), dark theme (chips and text use theme colours; the gradient card is the same in both themes as before), one-handed reach (primary action of the camera screen is already in the lower panel; Home's primary card is at the top, see 27/28), landscape on phones (the app is portrait-locked on phones, unchanged).

## 4. What changed (summary)

* New pure logic: `preset/PresetUx.kt` (limit, name checks, duplicate naming, error words, last-used wording, card warnings, edited detection), `presetSummaryParts` in `PresetLogic.kt`.
* `PresetController`: `beginDelete` / `undoDelete` / `commitDeleteIfDue` / `commitPendingDelete` (injectable clock), `duplicate`, `rename`, hidden-ids filter so a re-read cannot resurrect a card during the Undo window.
* UI: `ui/screens/PresetUi.kt` (card, menu, new-preset card, empty state, name field, changes line, previews), `HomeScreen.kt` (grid, snackbar, rename dialog), `GoLiveScreen.kt` (preset block, edited marker, targets), `SbComponents.kt` / `SetupScreen.kt` (selectable, headings, contrast helpers).
* No server contract change: Duplicate = existing `POST /presets` (same known keys), Rename = existing `PATCH /presets/:id` with only `name`, Undo = delay before the existing `DELETE`.

## 5. Data-integrity choice for Undo

Two options: (a) delete on the server now and re-POST on Undo, (b) hide, wait, delete after the window. (a) changes the preset id and loses `lastUsedAt`, can fail at the limit race, and leaves a ghost if the re-POST fails. (b) never touches the server until the window ends, so Undo is exact and a crash inside the window just keeps the preset. The cost of (b): within the 8 s the server still has the preset (a second device would still see it; creating or duplicating a preset commits the waiting delete first so the 20 limit is not hit). (b) was chosen.

## 6. Decisions for the founder

1. Keep "Go live now" (opens the camera screen, like the web) or rename to "Open camera"? (finding 27)
2. Should Brand kits get the same Undo? Kits own uploaded files, so it is not the same operation (33).
3. Pin / default preset: device-local only, or ask the server for a field (28)?
4. "Update preset" overwrite protection against edits made on the web (6): needs `updatedAt` from the server, or accept a compare-and-confirm using the config.
5. Undo window length: 8 s (long enough for TalkBack users; short enough that a second device sees the change soon).

## 7. Not verified

No device or emulator: visual result of every screen, TalkBack announcements and focus order, 200 % font clipping, the snackbar position with the gesture bar, the adaptive grid on a tablet/foldable, keyboard behaviour in the sheet. `@Preview` functions exist in `PresetUi.kt` (light, dark, 200 % font) but their renders were not reviewed.

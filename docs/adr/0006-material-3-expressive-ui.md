# ADR-0006: Material 3 Expressive Design System in Jetpack Compose

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

The user requested that Aether use **Android Expressive** (Material 3 Expressive), Google's latest evolution of Material Design for Android (introduced with Android 16 / Material 3 Expressive Compose libraries). Material 3 Expressive emphasizes spring-based physics motion, rich shape morphing, expressive typography scales, vibrant dynamic color contrast, and new expressive components (`LoadingIndicator`, `FloatingToolbar`, `ButtonGroup`, `SplitButton`, `MediumFlexibleTopAppBar` / `LargeFlexibleTopAppBar`, `ContainedLoadingIndicator`).

## Decision

We will build all UI screens in **Jetpack Compose** using the **Material 3 Expressive** library (`androidx.compose.material3:material3` 1.4.0-alpha / 1.5.0+ expressive APIs and `androidx.compose.material3:material3-adaptive-navigation-suite`) with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`:

1. **`MaterialExpressiveTheme` & `MotionScheme.expressive()`:**
   - Wrap the application in `MaterialExpressiveTheme` (or `MaterialTheme` configured with `MotionScheme.expressive()`), ensuring all spatial and effects animations use spring physics (`motionScheme.defaultSpatialSpec()`, `fastSpatialSpec()`, `slowSpatialSpec()`, `defaultEffectsSpec()`) rather than legacy cubic-bezier tween curves.
2. **Dynamic Color & Expressive Color Roles:**
   - Default to Android Dynamic Color (`dynamicDarkColorScheme` / `dynamicLightColorScheme`) on Android 12+, with a curated Nextcloud-inspired Expressive seed palette fallback.
   - Utilize container hierarchy (`surfaceContainerLowest` through `surfaceContainerHighest`, `primaryContainer`, `tertiaryContainer`) to create visual depth and grouping without heavy divider lines.
3. **Expressive Typography & Shapes:**
   - Use variable font weights and emphasized typography tokens (`TitleLargeEmphasized`, `HeadlineMediumEmphasized`, `LabelLargeEmphasized`) for key metrics (storage quota, sync status, server title).
   - Use `MaterialShapes` and shape morphing (`Morph` via `androidx.graphics:graphics-shapes`) for interactive states, sync status badges, and the `LoadingIndicator` / `ContainedLoadingIndicator`.
4. **Expressive Component Usage:**
   - **Onboarding & Login v2 Screen:** Animated expressive hero illustration using morphing `MaterialShapes`, clean server URL input with protocol validation, and a `ContainedLoadingIndicator` while waiting for Login Flow v2 Custom Tab completion.
   - **Sync & Storage Dashboard:** Expressive storage ring/progress bar, segmented `ButtonGroup` / `ToggleButton` for quick filter toggles (Pinned, Camera Backup, Active Transfers), and `LargeFlexibleTopAppBar`.
   - **Quick Share Upload Sheet:** Bottom sheet (`ModalBottomSheet`) or dedicated expressive dialog activity when sharing files from other apps (`ACTION_SEND`), featuring destination folder chips, upload progress with morphing indicators, and spring-animated completion states.

## Rationale

- **Modern Android Feel:** Aligns Aether with Android 16's system UI and Google's first-party apps.
- **High Scannability for Companion App:** Because Aether's UI is a focused companion dashboard (with file management delegated to SAF), Material 3 Expressive makes status indicators, sync progress, and storage metrics tactile, lively, and immediately legible.

## Consequences

- **Positive:** Cohesive, state-of-the-art Android UI with spring physics and expressive components out of the box.
- **Negative / Mitigation:** Requires opting into `@ExperimentalMaterial3ExpressiveApi` and including `androidx.graphics:graphics-shapes` in Gradle dependencies.

## Implementation notes: expressive redesign (2026-10-21)

The first pass used expressive components inside a stock layout. The redesign makes the layout itself expressive:

- **Colour-block heroes.** Storage (primary container) and camera backup (tertiary container) are large cards with an asymmetric shape (`HeroShape`/`HeroShapeAlt`: three 48 dp corners, one 12 dp), oversized `displayLargeEmphasized` numbers and a `CircularWavyProgressIndicator` ring. Login uses a full-width primary-container panel with only its bottom corners rounded.
- **`MorphingShapeBadge`** (`ui/components/Expressive.kt`): a `MaterialShapes` badge that morphs to a new polygon with `defaultSpatialSpec()` when state changes (idle Cookie9Sided, syncing Sunny and rotating, error Pentagon) and rotates only while work is in flight. It replaces plain icons as the status signal on the dashboard, transfer rows, camera backup and the share sheet.
- **Connected lists.** Transfers and pinned items use `groupedShape`: 28 dp outer corners, 6 dp inner corners, 2 dp gaps.
- **Navigation.** A `ShortNavigationBar` (Pinned, Camera, Transfers, labels always visible) switches dashboard sections. "Open in System Files" is a separate `ExtendedFloatingActionButton` (tertiary container) at bottom-end that collapses to an icon when the list scrolls. This replaced the first redesign's `HorizontalFloatingToolbar`, whose vibrant pill with an embedded FAB looked disjointed from the rest of the page and hid two of three tab labels.
- **Buttons.** Main actions use `ButtonDefaults.shapes()` (round-to-square press morph) and `ButtonDefaults.LargeContainerHeight`/`MediumContainerHeight`. Camera options are a connected `ToggleButton` group instead of switch rows.
- **Typography.** Display and headline `*Emphasized` styles are heavier (ExtraBold/Bold) so key numbers read as graphics.
- Dynamic Color remains the default, with the fixed palette as fallback below Android 12.

## Implementation notes: Camera settings inside the tile (2026-10-23)

The separate Camera backup screen and its route are gone. The Camera tab's tile (`tertiaryContainer`) expands in place, via `animateContentSize` with `motionScheme.defaultSpatialSpec()`, to reveal `CameraBackupSettings`: turn on or off, destination account, albums, destination folder and the Wi-Fi / charging / videos toggles. The tile's button reads "Set up" (backup off), "Settings" (on) and "Done" (expanded). When backup is off the expansion is the same, with "Turn on backup" first; the photo permission is requested when that button is pressed.

## Implementation notes: text and accessibility conventions (2026-10-24)

- **All user-facing text in `ui/` lives in `res/values/strings.xml`**, with `plurals` for counts. Composables read it with `stringResource` / `pluralStringResource`; code outside composition uses the `@ApplicationContext`. Lint rejects `LocalContext.current.getString` (it ignores configuration changes), so read the string in composition and capture it. `AetherError.describe()` in `core/` still returns English and surfaces in transfer rows and listing errors; localising it is a separate change.
- **Cards read as one unit.** Storage and transfer rows merge their descendants for TalkBack. The quota gauge and status badge are decorative; they carry only a short description (percent used, or transfers in progress), because the text beside them already states the numbers.
- **Icons that carry meaning** (upload or download in a transfer row, the pin menu) have a `contentDescription`; icons beside a text label do not.
- **Sign-out stays in the account menu** and pinning stays in the Pinned tab's FAB menu. There is no settings screen, in line with ADR-0001's minimal companion app. The empty Pinned tab names the pin button so a new user can find it.

## Implementation notes: swipe between dashboard tabs (2026-10-25)

- **A `HorizontalPager` replaces `AnimatedContent`.** Each tab is a full page with its own `LazyColumn`, so the header and storage hero slide with the section and every tab keeps its own scroll position. Swiping stops at the first and last tab (no wrap-around).
- **The view model still owns the selected tab** (nav bar taps, `SavedStateHandle`). The pager follows `state.tab` with `animateScrollToPage`, and a settled swipe reports back through `selectTab`. The pager's first settled page is skipped, because the view model's real tab may not have reached the UI yet and the default page would overwrite it.
- **Only the settled page is reported back.** Using the current page would make a multi-tab jump from the nav bar (Pinned to Transfers) pass through Camera and cancel its own animation.
- The Files FAB expands or collapses from the scroll state of the page currently showing.

## Implementation notes: floating toolbar for dashboard actions (2026-10-25)

This supersedes the 2026-10-21 note on the "Open in System Files" FAB and the 2026-10-24 note that pinning lives in the Pinned tab's FAB menu.

- **One `HorizontalFloatingToolbar` replaces the FAB menu and the extended FAB.** The earlier toolbar was rejected as *navigation* (a vibrant pill with an embedded FAB that hid two tab labels). Navigation stays in the `ShortNavigationBar`; the toolbar now holds *actions* only, so that objection does not apply.
- **"Files" is the toolbar's FAB on every tab**, the same colour and size everywhere. The toolbar stays `expanded = true`: collapsed, it draws a larger FAB (about 96 dp against 56 dp), so the button changed size from tab to tab.
- **The actions beside it follow the page showing**, read from `pagerState.currentPage` so they change during a swipe instead of after it settles. Pinned has Pin files, Pin folder and Sync now; Camera has Back up now and Pause backup; Transfers has Retry failed and Clear finished. At most three per tab keeps the toolbar's width similar. Sync now is disabled, not hidden, when nothing is pinned, so the width does not jump.
- **Camera and Transfers actions are placeholders** that show a snackbar until the behaviour is wired up (Clear finished and Retry failed have since been implemented; see below). Cancel all was left out on purpose: a stray tap would cancel active uploads.
- **Actions are icon buttons**, each with a tooltip and a content description (no visible labels, unlike the FAB menu items it replaces).
- **The list on each pager page fills the page** (`fillMaxSize()`). `HorizontalPager` centres its pages vertically, which had left short tabs such as Transfers floating mid-screen since the swipe change.

## Implementation notes: Clear finished (2026-10-25)

- **Clear finished removes only the selected account's completed transfers.** Failed rows stay because they are what Retry failed acts on, and conflict rows stay because they still need a decision (their "Show folder" button). Other accounts' history is untouched; it is not on screen to be cleared.
- **No confirmation and no undo.** Completed rows are only history, and the sync worker already prunes finished rows after a day, so there is nothing to protect.
- **The button is disabled, not hidden, when the Recent list has no completed row.** It follows the rows shown (the latest 10), so completed rows older than that are not counted when deciding whether to enable it.

## Implementation notes: Retry failed (2026-10-25)

- **Retry failed acts on the selected account's failed rows.** Conflict rows are excluded because they need a decision, not a retry. The button is disabled, not hidden, when the Recent list has no failed row.
- **Every failed upload keeps what it needs to be sent again.** Uploads from the share sheet and SAF write-backs keep their staged file after a final failure (they used to delete it) and the row records the file, MIME type and `If-Match` ETag (database version 3). Pruning removes failed rows after 7 days, other finished rows after 1 day, and deletes a pruned row's staged file with it.
- **Camera backup now shows its failures.** An item that fails for a reason other than being offline or a server outage gets one failed row (keyed `image:<id>` or `video:<id>`, replaced rather than duplicated on each scan) and loses it when it uploads. The bytes never leave MediaStore, so retrying runs a camera scan (`CameraBackupWorker.runNow`, any network, ignoring the Wi-Fi and charging rules). The backed-up log still decides what to send, so a retry cannot duplicate a photo.
- **Downloads retry through a pinned sync.**
- **A retried row is removed when its retry is queued**, because the new attempt writes its own row. A failed row whose staged file is gone stays, and the snackbar says how many could not be retried ("Retrying 2 transfers. 1 can't be retried because its file is gone").

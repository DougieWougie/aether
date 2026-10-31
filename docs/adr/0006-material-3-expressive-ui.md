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
- **Some Camera and Transfers actions were placeholders** that show a snackbar until the behaviour is wired up (Clear finished, Retry failed, Back up now and Pause backup have since been implemented; see below, so no placeholders remain). Cancel all was left out on purpose: a stray tap would cancel active uploads.
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

## Implementation notes: Back up now (2026-10-25)

- **It runs the normal camera scan**, so it also picks up items that failed or were skipped earlier (they were never logged as backed up). It is the same run Retry failed starts for camera items (`CameraBackupWorker.runNow`). Album and video choices still apply.
- **It ignores the Wi-Fi and charging rules** and needs only some network: the user asked for it now, and the rules still govern automatic runs.
- **Backup off or no photo permission is reported, not hidden.** The button stays enabled and answers with a snackbar and a Set up action that opens the Camera tile's settings (the tile's fold state is hoisted to the dashboard for this). With backup on but the permission revoked, the settings show an "Allow access" button.
- **Feedback.** A snackbar says "Backing up now", then "Nothing new to back up" if the run reports that it tried no uploads (`CameraBackupWorker` returns the count as output data). The button is disabled while a run is executing, and the tile's badge spins for this run too (`observeRunning` now includes it).

## Implementation notes: Pause / resume backup (2026-10-25)

- **Pause is its own state, not the on/off switch.** `BackupSettings.paused` leaves the account, albums, folder, constraints and the backed-up log alone. It only means something while backup is on: switching backup off, or on again, clears it.
- **Pausing cancels the schedules and any run in progress** (`SyncScheduler.applyBackup` cancels the trigger, periodic and Back up now work). The worker also checks `paused` itself, so a run that slips through does nothing. An interrupted upload is simply scanned again on resume, because the log decides what to send.
- **Pause lasts until the user resumes.** There is no timer, so a forgotten pause stays visible on the tile instead of quietly restarting.
- **Resume uploads everything taken while paused**, since `backupSinceMillis` is untouched, and starts a scan straight away rather than waiting for the next trigger.
- **How it shows.** The toolbar button reads "Resume backup" with a play icon while paused, and is disabled when backup is off. The tile's title becomes "Backup paused" and its badge stops being the spinning Sunny shape. Back up now while paused answers "Camera backup is paused" with a Resume action instead of overriding the pause.

## Implementation notes: Camera tile layout (2026-10-28)

- **The tile shows state; a bottom sheet holds settings.** The header carries the status title, a settings icon button and the on/off `Switch`. Settings open in a `ModalBottomSheet`, so the dashboard no longer reflows when they open. The sheet's open state is still hoisted to the dashboard so the Back up now snackbar's Set up action can open it.
- **On/off is the switch.** Turning on asks for photo access first, and the switch only flips once it is granted. While backup is off, the sheet ends with a "Turn on backup" button, so setting up still finishes with one tap. Once on, the sheet closes itself. When backup is on but the photo permission was revoked, the tile shows an error banner with "Allow access".
- **Settings are grouped by question:** what to back up (albums, include videos), where it goes (account, destination folder), when to back up (Wi-Fi only, charging only). The toggles are whole-row switches, not chips, because a chip row could not say "Videos" is a content choice and not a condition.
- **The tile summarises the settings as plain text** (albums, then the Wi-Fi / charging / videos rules) rather than pill chips, so nothing on the tile looks tappable unless it is. The tile's "Open Photos" text button replaces the hidden tap-the-header gallery shortcut.
- **The destination folder saves when the field loses focus or the sheet closes**, not only on the keyboard's Done.

## Implementation notes: Compact Camera tile (2026-10-31)

The tile was a tall card (a 96sp storage figure, a spinning badge, album and rule summaries, the photos/videos split, a last-backup line) that mostly repeated the Storage hero and the settings sheet. It is now one status row.

- **One row, about 72dp:** a small camera icon, the title, one status line and the switch (the settings icon button stays). Tapping the title area opens the settings sheet. The surface uses `shapes.extraLarge` rather than the hero shape, which only suits a tall card.
- **The status line says what backup is doing:** "Back up new photos and videos automatically." (off), "Backup paused", "Backing up 4 of 12", "Up to date · 2 min ago", or "Waiting for new photos" before the first upload.
- **Progress is real.** `CameraBackupWorker` counts the items it will upload (those not yet in the backed-up log) and calls `setProgress` after each one, finished or failed. `SyncScheduler.observeBackupProgress` exposes it, and the tile draws a determinate `LinearProgressIndicator` while a run is going (indeterminate until the count arrives). Runs with nothing to upload no longer start a foreground service.
- **Problems show only when there is one, each with its action:** the missing photo permission ("Allow access to photos and videos") and failed items ("N items couldn't be backed up" → View, which switches to the Transfers tab where Retry failed lives). The failed count is `TransferDao.observeFailedBackupCount`, camera-backup rows only.
- **Details moved into the settings sheet:** a "Details" card at the top (folder size on the server, the photos/videos split, Open Photos). Album and Wi-Fi/charging/video summaries left the tile, since the sheet already shows them.
- **Not done:** a "Waiting for Wi-Fi / charging" reason. WorkManager keeps the content-trigger job enqueued while idle, so there is no signal that distinguishes "blocked by a constraint" from "nothing to upload".

## Implementation notes: Take photo on the Camera tab (2026-10-31)

- **The Camera tab's main action is taking a photo.** The floating toolbar's FAB there is Take photo; Open in System Files, which is the FAB on Pinned and Transfers, becomes the third action beside Back up now and Pause backup, so it stays one tap away and the toolbar keeps its three-action width.
- **The system camera app does the capture** (`ActivityResultContracts.TakePicture`, `ACTION_IMAGE_CAPTURE`), so there is no CAMERA permission and no in-app camera UI (ADR-0007's "use the platform" rule). Photos only for now.
- **Where the photo goes.** `CapturePhotoUploader` gives the camera app a file in the app's cache through `FileProvider` (`<applicationId>.captures`, per-URI grant). A MediaStore row marked `IS_PENDING` was tried first and does not work: only its owner may open it, so the camera app crashed with "Only owner is able to interact with pending/trashed item". A taken photo is copied to a staging file and queued with `UploadDocumentWorker` to `<backup folder>/YYYY/MM/AETHER_yyyyMMdd_HHmmss.jpg`, using camera backup's account if it has one and otherwise the selected account. It works with camera backup off or paused. A copy is also added to the gallery (`Pictures/Aether`) as a camera app would.
- **It shows under Transfers at once.** The uploader inserts a `QUEUED` transfer row when the photo is taken, so it is visible (as waiting) even offline, and `UploadDocumentWorker` continues that row instead of adding its own. When it finishes the Recent group counts it as uploaded.
- **No double upload.** The gallery copy is written to the backed-up log before it is made visible to MediaStore, so a camera backup scan skips it instead of racing the queued upload (a create-only upload would otherwise end up as a conflict copy).
- **Missing year/month folders are created.** `UploadRequest.createParents` makes `UploadDocumentWorker` `MKCOL` each missing folder first (a 405 means it exists). `TransferRetrier` sets it for new-file retries so a retried capture still works.
- **The reserved file survives process death** (saved with `rememberSaveable`), and a cancelled or failed capture deletes its empty file (and cache files older than a day are swept). After a taken photo the snackbar says it is uploading, with View to open Transfers.

## Implementation notes: Transfer thumbnails (2026-10-31)

- **Pictures show themselves in the Transfers list.** A transfer row's 48dp direction badge is replaced by a rounded thumbnail when the transfer is an image (its recorded MIME type, else its file name). Everything else keeps the badge, as does any image whose picture can't be made, so a row never ends up with a blank slot.
- **Where the picture comes from.** A row that still has its staged file (queued, running, or failed and waiting for Retry failed) is thumbnailed from that file with `ThumbnailUtils`. A completed transfer asks the system provider (`ContentResolver.loadThumbnail` on its document URI), which serves the cached copy or the server's preview, the same way the Pinned tab does. Conflict rows are skipped: their path is the original, not the conflict copy that was saved.
- **Loaded once per row, not per progress tick.** The load is keyed on the row, its state, its staged path and its remote path, so the byte counter updating does not reload it.
- **Direction is still announced.** The thumbnail carries the same "Upload"/"Download" content description the icon had.

## Implementation notes: Sync now on Transfers, undo for Clear finished (2026-10-31)

- **The Transfers tab's main action is Sync now.** The toolbar FAB there starts a pinned-files sync (if anything is pinned) and a camera backup scan (if backup is on, not paused and allowed to read photos), then says so in a snackbar, or says there was nothing to sync. Open in System Files is now the FAB only on Pinned; on Camera and Transfers it sits beside the tab's other actions, so the toolbar keeps its width.
- **Clear finished can be undone.** The view model reads the account's completed rows before deleting them and hands them to the screen, which shows "Cleared N finished transfers" with Undo. Undo re-inserts the same rows (same ids, so the Recent order is unchanged). Staged files are not touched by Clear, so there is nothing else to restore. Dismissing the snackbar or leaving the tab makes the clear final.

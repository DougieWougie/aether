# Aether improvement plan

Derived from the review of Aether against its aims: Nextcloud as a Dropbox replacement, a Material 3 Expressive
interface, simplicity, security by default and privacy by default.

Work through the phases in order. Each task lists the files involved and a definition of done. Tick the box when done.

Significant changes to authentication, sync, provider behaviour or UI design must update or add an ADR in
`docs/adr/` (see `CLAUDE.md`).

---

## Phase 1: Security hardening (small, self-contained)

### 1.1 Enforce HTTPS and a same-host check when adding an account
- [x] `auth/AccountRepository.kt`: `addAccount()` currently calls `normalizeServerUrl(login.server, allowInsecure = true)`.
  Reject any non-HTTPS server URL returned by the poll step.
- [x] Verify the host in the poll response matches the host the user entered at the start of the flow (carry the
  expected host through `LoginSession` / `SavedStateHandle`).
- [x] Remove the `allowInsecure` parameter if nothing else needs it, or default it off everywhere.
- [x] Tests: extend `LoginFlowV2ClientTest` / `AetherCredentialsTest` with an `http://` poll response and a host
  mismatch; both must fail.

**Done when:** a poll response with `http://` or a different host cannot create an account.

### 1.2 Restrict redirects on the shared OkHttp client
- [x] `di/AppModule.kt`: set `followSslRedirects(false)` on the client.
- [x] Decide how to handle cross-host redirects for requests carrying `Authorization` (OkHttp strips the header on
  cross-host redirects; confirm and add a test with `MockWebServer`).
- [x] Tests in `WebDavClientTest`: an HTTPS-to-HTTP redirect is not followed.

**Done when:** credentials can never be sent over plain HTTP via a redirect.

### 1.3 Clean up all account data on sign-out
- [x] `data/DocumentRepository.forgetAccount()` / `auth/AccountCleaner.kt`: also delete the account's entries in
  `cacheDir/thumbnails` and any leftover files in the staging directory.
- [x] Decide what happens to a staged upload that has not yet been sent when its account is removed: its work is
  cancelled and the staged file deleted. Its transfer row goes with the account's other rows, not shown as failed.
- [x] Test with `DocumentDiskCacheTest` style coverage.

**Done when:** after sign-out, no file belonging to that account remains in app storage.

### 1.4 Fix thumbnail filename collisions across accounts
- [x] `provider/AetherDocumentsProvider.openDocumentThumbnail()`: replace `accountName.hashCode()` with the SHA-256
  based `DocumentDiskCache.fileName()` (or a per-account subdirectory). Done in 1.3: thumbnails live in a per-account
  subdirectory (`DocumentDiskCache.thumbnailFor`).
- [x] Cap the thumbnail cache size, or evict by age. Least-recently-used, capped at 32 MB across accounts, separate
  from the 512 MB file cache.

**Done when:** two accounts with the same `fileId` never share a thumbnail file.

### 1.5 Make auth failures visible
- [x] `sync/CameraBackupWorker.kt`: on `Unauthorized`, post a notification asking the user to sign in again and show
  the state on the dashboard, instead of silently returning `Result.failure()`.
- [x] Apply the same to `PinnedSyncWorker` and `UploadDocumentWorker`.
- [x] Confirm the provider's `AuthenticationRequiredException` path opens `MainActivity` on the login screen.

**Done when:** a revoked app password produces a visible, actionable message within one sync cycle.

---

## Phase 2: Privacy polish

- [x] **Conflict notification:** set `setVisibility(VISIBILITY_PRIVATE)` and provide a public version without file
  names (`sync/TransferNotificationHelper.kt`).
- [x] **Direct Share shortcuts:** add a setting to turn them off (default on, or off if you prefer privacy first), and
  clear published shortcuts when the account is removed (`ui/share/DirectShareShortcuts.kt`).
- [x] **Roots list summary:** show only the host when a single account exists, the full account name when there are
  several (`AetherDocumentsProvider.queryRoots()`).
- [x] **User-Agent:** decide whether to drop `Build.MODEL` (`di/AppModule.userAgent()`). Record the decision.
- [x] **ADR:** document that local data (Room, pinned files, staging) is stored unencrypted in private app storage
  and relies on file-based encryption. List what is stored (paths, album names, ETags).
- [x] **Optional:** an app lock (biometric) and `FLAG_SECURE` for the companion app. Decide and record in an ADR; do
  not add it without a decision.

---

## Phase 3: Closing the Dropbox gap

### 3.1 Be honest about sync semantics
- [x] README: state clearly that Aether is on-demand access plus selective offline pinning, not a two-way synced
  folder. Link ADR-0004.
- [x] Decide whether two-way folder sync is in scope. If not, add it to a "Not planned" section of the README.

### 3.2 Conflict handling
- [x] Let the user open or compare a conflict copy from the dashboard row (at minimum, open the folder in system
  Files).

### 3.3 Camera backup resilience
- [x] After a reinstall, the duplicate check relies on remote size matching. Consider storing a content hash or
  using the server ETag/checksum so re-uploads are avoided more reliably.

---

## Phase 4: UI quality and maintainability

- [x] **Strings:** move all hardcoded user-facing text in `ui/` into `res/values/strings.xml` (login, dashboard, FAB
  menu, dialogs). `AetherError.describe()` in `core/` still returns English; see the provider errors task.
- [x] **Split `ui/dashboard/DashboardScreen.kt`** (now 191 lines) into focused files: quota card, transfers, pinned list,
  camera tile, account menu.
- [x] **Discoverability:** review where "Sign out" and "Pin" live. Consider surfacing sign-out in a settings area and
  explaining pinning with an empty state. Decision: sign-out stays in the account menu (no settings screen, ADR-0001);
  the empty Pinned tab now points at the pin button. Recorded in ADR-0006.
- [x] **Provider errors:** `AetherDocumentsProvider.orThrow()` maps nearly everything to `FileNotFoundException`.
  Use `EXTRA_ERROR` / `EXTRA_INFO` on listings where possible, and surface locked-file and quota errors in the
  dashboard or a notification. Done: 507 is its own `InsufficientStorage` error (not retried), the provider throws
  `IllegalStateException` rather than `FileNotFoundException` for anything but not-found, and failed uploads already
  show the reason in their dashboard row. Listings already used `EXTRA_ERROR`.
- [x] **Accessibility:** check content descriptions, TalkBack order, touch target sizes and font scaling. Done by code review (merged semantics, gauge and transfer icon descriptions).
  Font scaling and TalkBack order are not verified on a device; do that in the usability check.
- [x] **Usability check:** run the app and walk through first sign-in, first pin, first backup and sign-out as a new
  user. Record friction found.

  Walked through on an emulator (API 36) on 2026-10-24: first sign-in screen, first pin, camera backup setup,
  sign-out and a 2.0 font scale on the login screen. The sign-in itself could not be completed (no real server, and the
  fake one has no Login Flow v2), so the account was seeded against the fake Nextcloud from the instrumented tests.
  Worked well: system pickers and permission prompts appear at the moment of intent; the pin flow ends on a clear
  "Kept on this device" row; login scales to 2.0 without clipping. Friction found, most useful first:

  - **Fixed: raw network error on the login screen.** An unreachable server shows `Failed to connect to /10.0.2.2:9`, the
    exception text. Say what happened ("Couldn't reach that server. Check the address and your connection.").
  - **Fixed: sign-out was one tap with no confirmation** and no hint that pinned files and queued uploads for the account are
    removed. Add a confirmation dialog that says so.
  - **Notification permission is asked for on first arrival at the dashboard**, with no context. Ask when the first
    transfer or backup starts, or explain first.
  - **The storage card dominates when it fails.** "Couldn't load / Not found" takes the top third of every tab and the
    message is terse. Collapse it to a one-line banner with Retry.
  - **Camera tile:** "All albums" appears twice (summary chip and Albums card); the settings stay expanded after
    "Turn on backup" so "Done" is below the fold; the Files FAB overlaps the destination folder field; "0 items from
    this phone" reads oddly before the first scan; the destination folder is free text with no folder picker.
  - **Option chips** (Wi-Fi, Charging, Videos): selected and unselected differ only by tint and outline. Add a check
    mark to selected chips.
  - **Pinned rows** show no state (downloaded? when?), and Unpin has no undo.
  - **Server field:** the helper text "HTTPS is used unless you say otherwise" is cryptic, and the example
    `cloud.example.com` only appears once the field is focused.
  - The system file picker opened by "Pin files" carries no hint about what the selection is for.
  - TalkBack order was not checked; it needs a screen reader session.

---

## Phase 5: Test coverage

- [x] Add an `androidTest` source set (instrumented tests).
- [x] `AetherDocumentsProvider`: query roots, list children, open for read, open for write then upload, rename,
  move, delete, against `MockWebServer`. Uses a small in-memory fake Nextcloud (`support/FakeNextcloud`) instead of
  scripted responses, so background refreshes do not make tests order-dependent.
- [x] `UploadDocumentWorker`: `If-Match` conflict producing a conflict copy; chunked upload path.
- [x] `CameraBackupWorker`: album filter, since-timestamp, duplicate detection.
- [x] Add the instrumented tests to CI (emulator job), or document how to run them locally.

---

## Status

Phases 1 to 5 are done. The usability walkthrough produced a list of friction points (under Phase 4); none are fixed yet.

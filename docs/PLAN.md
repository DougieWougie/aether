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
- [ ] After a reinstall, the duplicate check relies on remote size matching. Consider storing a content hash or
  using the server ETag/checksum so re-uploads are avoided more reliably.

---

## Phase 4: UI quality and maintainability

- [ ] **Strings:** move all hardcoded user-facing text in `ui/` into `res/values/strings.xml` (login, dashboard, FAB
  menu, dialogs).
- [ ] **Split `ui/dashboard/DashboardScreen.kt`** (682 lines) into focused files: quota card, transfers, pinned list,
  camera tile, account menu.
- [ ] **Discoverability:** review where "Sign out" and "Pin" live. Consider surfacing sign-out in a settings area and
  explaining pinning with an empty state.
- [ ] **Provider errors:** `AetherDocumentsProvider.orThrow()` maps nearly everything to `FileNotFoundException`.
  Use `EXTRA_ERROR` / `EXTRA_INFO` on listings where possible, and surface locked-file and quota errors in the
  dashboard or a notification.
- [ ] **Accessibility:** check content descriptions, TalkBack order, touch target sizes and font scaling.
- [ ] **Usability check:** run the app and walk through first sign-in, first pin, first backup and sign-out as a new
  user. Record friction found.

---

## Phase 5: Test coverage

- [ ] Add an `androidTest` source set (instrumented tests).
- [ ] `AetherDocumentsProvider`: query roots, list children, open for read, open for write then upload, rename,
  move, delete, against `MockWebServer`.
- [ ] `UploadDocumentWorker`: `If-Match` conflict producing a conflict copy; chunked upload path.
- [ ] `CameraBackupWorker`: album filter, since-timestamp, duplicate detection.
- [ ] Add the instrumented tests to CI (emulator job), or document how to run them locally.

---

## Suggested order and sizing

| Order | Task | Size |
|---|---|---|
| 1 | 1.1 HTTPS and host check | S |
| 2 | 1.2 Redirect policy | S |
| 3 | 1.3 and 1.4 Cleanup and thumbnails | S |
| 4 | 1.5 Auth failure visibility | M |
| 5 | Phase 2 privacy polish | S to M |
| 6 | 3.1 Share links | L |
| 7 | Phase 4 UI maintainability | M |
| 8 | Phase 5 instrumented tests | L |
| 9 | 3.2 to 3.4 | S to M |

Sizes: S = under half a day, M = about a day, L = several days.

# Task: fix the findings from the Aether code review

You are working in the Aether repo (native Android Nextcloud client). Read `CLAUDE.md` and the ADRs in `docs/adr/` first; they are binding. A code review produced the findings below. They came from reading the source, not from running it, so treat each one as a hypothesis: confirm it in the code (and with a failing test where practical) before changing anything. If a finding turns out to be wrong, say so and skip it.

## How to work

- Plan first. Produce a plan that groups the work into the pull requests listed under "Delivery", and wait for approval before editing.
- One branch and one PR per group, branched from `main`. Do not commit to `main`.
- Write the failing test first for every behaviour fix, then the fix.
- Before each PR: `./gradlew testDebugUnitTest lintDebug`, plus `./gradlew connectedDebugAndroidTest` for anything touching the provider, workers, Room or MediaStore (needs an emulator: `android emulator start <avd>`). Report results truthfully, including anything you could not run.
- Any change to auth, sync, SAF provider behaviour or the UI design system must update or add an ADR.
- No new dependencies unless a finding names one; any added must be GPLv3-compatible. Do not add a third-party WebDAV library (ADR-0005).
- Do not add Claude attribution to commits or PRs.
- Keep each change minimal. Do not refactor beyond what a finding asks for.

Paths below are relative to `app/src/main/kotlin/com/aether/nextcloud/` unless they start with `app/` or `.github/`.

## Priority 1: data-safety fixes

### 1. Concurrent opens of one file can corrupt the cached copy
`data/DocumentRepository.kt` `ensureLocalFile` has no per-document lock, and `dav/WebDavClient.kt` `downloadFile` always writes to the same `<target>.part`. Two simultaneous opens of an uncached file write into one `.part` file.
- Fix: serialise `ensureLocalFile` (and `ensurePinnedCopy`, `adoptUploadedFile`) per document id with a `Mutex`; the second caller should reuse the first caller's result.
- Done when: an instrumented test opens the same uncached document from several threads against `FakeNextcloud` (with a delayed GET), gets identical correct bytes each time, and the server sees one GET.

### 2. Pending edits are invisible to reads, and back-to-back saves create false conflicts
`provider/AetherDocumentsProvider.kt` `openForWrite` takes the `If-Match` ETag from the Room cache at open time. If a second save is staged before the first upload finishes, it carries the old ETag, gets a 412, and `sync/UploadDocumentWorker.kt` saves it as a "Conflict" copy of the user's own edit. A read in that window also returns the old server bytes.
- Fix: while an upload for a document is queued or running, serve the newest staged file for reads and for seeding `rw`/`wa` opens; make a later queued upload use the ETag the previous upload produced, not the one captured at open time.
- Done when: a test saves twice before the first upload completes and ends with one file on the server holding the second content, no conflict copy, and a read between the saves returns the first save's bytes.

### 3. A stopped upload is shown as failed, and its staged file can be pruned while still pending
`sync/UploadDocumentWorker.kt` marks the transfer `FAILED` ("Cancelled") on any `CancellationException`, including WorkManager stopping the worker because a constraint was lost. The rerun only looks for `QUEUED` rows (`data/local/dao/TransferDao.kt` `queuedUploadFor`), so it inserts a duplicate row. `sync/PinnedSyncWorker.kt` prunes `FAILED` rows after 7 days and deletes their `sourcePath`.
- Fix: when the worker is stopped and will be rescheduled, set the row back to `QUEUED`; only a real user or account cancellation is `FAILED`. Make sure pruning never deletes a staged file that still has pending work.
- Done when: a test stops a running upload, reruns it, and finds exactly one transfer row that ends `COMPLETED`.

### 4. Camera backup most likely strips GPS location from photos
The manifest has no `ACCESS_MEDIA_LOCATION` and nothing calls `MediaStore.setRequireOriginal`, so EXIF location should be redacted when `sync/ContentUriUploadSource.kt` reads a MediaStore item.
- First confirm on an emulator with a geotagged photo.
- Fix: declare and request `ACCESS_MEDIA_LOCATION` alongside the media permission, and open originals with `setRequireOriginal`; fall back to the redacted stream if the permission is denied. Update ADR-0004 or ADR-0008.
- Done when: a geotagged photo uploaded by `CameraBackupWorker` keeps its location on the fake server.

### 5. `allowBackup="false"` does not stop device-to-device transfer on Android 12+
`app/src/main/AndroidManifest.xml` has no `android:dataExtractionRules`.
- Fix: add a rules file excluding all domains for both cloud backup and device transfer, and reference it. Record it in ADR-0008.

## Priority 2: security hardening

6. `auth/AetherAccountAuthenticator.kt` `getAuthToken` returns the raw app password. Nothing in the app calls it. Return an unsupported-operation error bundle instead; update its test and ADR-0003.
7. `data/DocumentRepository.kt` `createEmptyFile` probes for a free name, then does a plain PUT. Pass `createOnly = true` so a file created in between is not overwritten; on 412, pick the next name.
8. `dav/NextcloudOcsClient.kt` `downloadPreview` writes straight to the final path, so a concurrent thumbnail request can serve a half-written file. Write to `.part` and rename, as `downloadFile` does.
9. `auth/LoginFlowV2Client.kt` checks the poll endpoint and login URL for `https` only. Also require the poll endpoint's host and port to match the server the user entered.
10. `.github/workflows/*.yml` pin actions by tag. Pin every action to a commit SHA (keep the version as a comment) and add a Dependabot config for `github-actions` and `gradle`.

## Priority 3: performance

11. `sync/PinnedSyncWorker.kt` `doWork` computes pin roots by comparing every pinned row with every other and parsing both ids each time. Use the existing `DocumentDao` query for directly pinned items instead.
12. `PinnedSyncWorker.syncNode` / `syncFile` issue a `PROPFIND Depth: 0` per subfolder and per file right after the parent listing returned the same metadata. Pass the listed entity down and skip the redundant request. Assert the request count in `PinnedSyncWorkerTest`.
13. `sync/CameraBackupWorker.kt` `backUpNewMedia` runs one Room query per MediaStore item on every trigger. Load the account's backed-up ids once into a set.
14. `DocumentRepository.ensureLocalFile` always does a `PROPFIND` before serving a cached copy. Skip it when the document's metadata was refreshed within a short window (a few seconds); keep the offline fallback.
15. Small ones, only if trivial: hoist the `Regex` in `dav/WebDavXmlParser.kt` `isSuccess` to a constant; stop walking the whole thumbnail directory after every new thumbnail in `openDocumentThumbnail`.

## Priority 4: usability

16. Error text is hardcoded English: `core/AetherResult.kt` `describe()`, and "Cancelled" in the workers. Move the messages to `strings.xml` and resolve them at display time; store an error code (not prose) in `TransferEntity.error`, with a Room migration and schema export plus a migration test.
17. A SAF write-back that fails permanently has no notification. Add one on the existing notification helper (`sync/TransferNotificationHelper.kt`) with a Retry action that calls `TransferRetrier`.
18. Implement `copyDocument` with the existing `WebDavClient.copy` and set `FLAG_SUPPORTS_COPY` where permissions allow.
19. Add `FLAG_SUPPORTS_RECENTS` and `queryRecentDocuments`, answered from the Room cache only.

## Priority 5: tests and CI

20. Add `./gradlew assembleRelease` to `.github/workflows/release.yml` so R8 and resource shrinking are exercised.
21. Replace the fixed `Thread.sleep` calls in `app/src/androidTest` (about ten, mostly 1.1 s waits for MediaStore's one-second timestamps) with fixture-set timestamps or the existing await helper.
22. Add an API-level matrix (29 and 36 alongside 34) to the full instrumented job in `release.yml`.
23. Propose, but do not implement without approval: moving `DocumentRepositoryTest` and the `ShareUploadViewModel` tests to JVM with Robolectric, running the Compose screen tests the same way (the revisit condition in ADR-0009), and a JaCoCo coverage floor for `dav`, `data` and `sync`.

## Out of scope: write up as proposals only

Give a short design note for each, with trade-offs and the ADR it would change; do not implement:
- Streaming reads for large uncached files via `StorageManager.openProxyFileDescriptor` and HTTP range requests.
- Resumable Chunked Upload v2 (persist the upload directory id and resume from the last chunk).
- Retrying app-password revocation when sign-out happens offline.
- Opt-in trust of user-installed CAs for self-hosted servers.
- Parallel downloads in pinned sync and camera backup.
- Extracting the provider's in-flight/error/search state into a JVM-testable class, and putting `UploadDocumentWorker.enqueue` behind an injected interface.

## Delivery

Open the PRs in this order, each independently mergeable:
1. Findings 1 and 8 (download atomicity and locking)
2. Findings 2 and 3 (write-back correctness)
3. Finding 4 (media location)
4. Findings 5, 6, 7, 9 (hardening)
5. Finding 10 and 20–22 (CI and tests)
6. Findings 11–15 (performance)
7. Findings 16–17 (errors and failure notification)
8. Findings 18–19 (provider features)

Finish with a summary listing, per finding: confirmed or not, what changed, which tests cover it, and what you could not verify. Put the out-of-scope proposals and finding 23 in that summary.

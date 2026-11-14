# ADR-0004: Hybrid Sync Model: On-Demand SAF + Offline Pinning & Camera Backup via `WorkManager`

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

Mirroring an entire Nextcloud instance to mobile flash storage is impractical due to device storage constraints and battery restrictions. Conversely, a purely online-only client fails when the user has no connectivity or wants automatic Dropbox-like photo/folder backup.

## Decision

We will implement a **Hybrid Synchronization Architecture**:

1. **On-Demand Browsing & Streaming (Default):**
   - Directory metadata (`etag`, `fileId`, `mimeType`, `size`, `lastModified`) is cached on-demand in a local **Room** database (`DocumentEntity`) as the user navigates via SAF (`DocumentsProvider`).
   - File contents opened via `openDocument()` are downloaded into an LRU disk cache (`context.cacheDir/document_cache`) verified by Nextcloud's `ETag` header. If the cached `ETag` matches the latest metadata, the file is served immediately from local disk.
   - Edits made by external apps via SAF (`openDocument` with `"w"` or `"wt"` mode) write to a local staging file; on `ParcelFileDescriptor.OnCloseListener`, an expedited `WorkManager` upload job (`UploadDocumentWorker`) pushes the updated file to Nextcloud using `If-Match: <etag>` to prevent silent conflict overwrites.

2. **Selective Offline Pinning:**
   - Users can pin specific files or folders for offline availability (`isPinned = true` in Room).
   - Pinned files are stored in persistent app storage (`context.filesDir/pinned/`) and kept up to date via periodic and manual `WorkManager` sync workers (`PinnedSyncWorker`) that compare Nextcloud `ETag`s (`PROPFIND` with `oc:etag`).

3. **Automatic Camera Roll / Folder Backup:**
   - Uses `WorkManager` with `ContentUriTriggers` watching `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` and `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` (plus user-configurable network constraints: Unmetered Wi-Fi vs. Roaming/Cellular, Charging-only).
   - New media items are uploaded to a configurable Nextcloud folder (e.g., `/Photos/Camera Uploads/YYYY/MM/`) using WebDAV chunked uploads for large videos.

## Rationale

- **Battery & OS Compliance:** Android 14/15+ strictly enforces background execution limits. `WorkManager` with `ContentUriTriggers` and `Expedited` work requests (backed by `FOREGROUND_SERVICE_DATA_SYNC` when needed) is the official, battery-safe Android mechanism.
- **ETag Efficiency:** Nextcloud propagates folder `ETag` changes upward to the root directory whenever any descendant file changes. Checking a folder's `ETag` via a single `PROPFIND` (`Depth: 0`) determines in O(1) whether any subtree sync is needed.

## Consequences

- **Positive:** Fast browsing, minimal storage footprint, conflict-safe writes via `If-Match`, and reliable background photo backup.
- **Negative / Mitigation:** Requires clear conflict handling if both local and remote copies change while offline. In case of `412 Precondition Failed` (`If-Match` mismatch), Aether will save the local edit alongside the original as `<filename> (Conflict <timestamp>).<ext>` and notify the user.

## Implementation notes

- **Reads download before returning.** `openDocument("r")` makes sure a validated local copy exists (PROPFIND for the current ETag, then GET if the cached `cachedEtag` differs) and hands out a seekable `ParcelFileDescriptor` on it. Streaming range reads through `StorageManager.openProxyFileDescriptor` is a possible later optimisation for very large media; it is not implemented.
- **Offline fallback.** If the ETag check fails with a network error and a local copy exists, that copy is served rather than failing the read.
- **Conflict-safe by construction.** Uploads of existing documents send `If-Match: "<etag>"`; uploads of new documents send `If-None-Match: *` (or `Overwrite: F` on the chunked-upload `MOVE`). Either precondition failing (`412`) saves `<name> (Conflict yyyy-MM-dd HHmmss).<ext>` next to the original and raises a notification.
- **Pinned folder shortcut.** A pinned folder's `cachedEtag` records the folder ETag at its last *complete* sync; `PinnedSyncWorker` skips the whole subtree when the server ETag still matches. Pinning is done from the dashboard through the system document picker (`ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE` scoped to Aether), consistent with ADR-0001.
- **Camera backup** records every uploaded item in `BackedUpMediaEntity`, only considers media added after the user enabled backup, never overwrites an existing remote file (same name + same size counts as "already uploaded"; otherwise the MediaStore id is appended to the name), and reads `content://` URIs directly via `ContentUriUploadSource` without a staging copy. `ContentUriTriggers` are one-shot, so the worker re-queues its trigger after every run, with a 12-hour periodic scan as a safety net.
- **WorkManager** is initialised on demand from `AetherApp` (Hilt worker factory); the default `WorkManagerInitializer` is removed in the manifest.

## Implementation notes: locked files (2026-10-21)

HTTP 423 from the server maps to `AetherError.Locked` (a file held by the web editor or Files Lock). `UploadDocumentWorker` treats it like a transient failure and retries with backoff before giving up, keeping the staged bytes meanwhile; the transfer row reads "This file is locked on the server…". Provider calls (delete, rename, open) surface it as a `FileNotFoundException` carrying the same message; DocumentsUI itself only shows a generic failure.

## Implementation notes: album selection (2026-10-23)

Camera backup can be limited to chosen MediaStore albums (buckets).

- **Setting.** `BackupSettings.albums` is a list of `BackupAlbum(id, name)`, stored as JSON in DataStore. `null` means every album and is the default, so existing installs keep their behaviour and albums created later are included. An explicit list that is empty is never produced by the UI (the last album cannot be unchecked), and the worker treats it as "nothing to back up".
- **Query.** `CameraBackupWorker` adds `AND BUCKET_ID IN (…)` to its MediaStore selection when a list is set. Changing the list reschedules the content-URI trigger like any other backup setting.
- **Only new photos.** The `backupSinceMillis` cut-off still applies to every album. Adding an album does not upload the photos it already contained; only items added after backup was enabled are considered. This keeps enabling or widening backup from flooding the server, matching the original rule.
- **Log.** `BackedUpMediaEntity` gained nullable `bucketId` and `bucketName` (Room schema version 2, `MIGRATION_1_2`, schema exported). Items uploaded before this change have no album recorded and are not counted per album.
- **Picking.** The album list comes from a MediaStore scan (`MediaAlbumRepository`), so with partial photo access only the photos shared with Aether are counted. The picker is a bottom sheet opened from an Albums card in the expanded Camera tile on the dashboard, which also shows the selected albums as chips.
- **Thumbnails.** Provider thumbnails are kept in `cacheDir/thumbnails/<account>/` and capped at 32 MB across accounts, evicting the least recently used first (`DocumentDiskCache.trimThumbnails`). They have their own budget so previews never push file copies out of the 512 MB cache.
- **Thumbnail freshness.** A thumbnail file name includes a hash of the document's ETag, so a file edited on the server is fetched again rather than served from an old preview; older thumbnails of the same file and size are deleted when the new one is stored. A preview download checks for cancellation between reads and removes its partial file when cancelled.
- **Duplicate detection by content.** Camera backup never overwrites. Every backup upload sends `OC-Checksum: SHA1:<hex>` (on the `PUT`, or on the final `MOVE` of a chunked upload), so the server verifies the bytes and keeps the hash. When a file with the same name and size already exists, the worker compares the server's `oc:checksums` SHA-1 with the local one: equal means it is the same item (for example after a reinstall) and it is recorded without uploading; different means it is a new item and gets a media-id suffix. If the server holds no SHA-1 for the file (uploaded by a client that sent none), the size match alone decides, as before. Cost: one extra read of each item before upload; `PROPFIND` also asks for `oc:checksums`.

## Implementation notes: download atomicity (2026-11-14)

- **One download per document.** `DocumentRepository.ensureLocalFile`, `ensurePinnedCopy` and `adoptUploadedFile` hold a per-document-id `Mutex`. Two simultaneous opens of an uncached file used to write the same `<target>.part` and one failed on rename; now the second caller waits, finds the first caller's copy valid by ETag, and reuses it, so the server sees one `GET`. `ensurePinnedCopy` calls the unlocked body of `ensureLocalFile` because the mutex is not reentrant. Locks are per document, so different files still download in parallel.
- **Atomic thumbnails.** `NextcloudOcsClient.downloadPreview` writes to a unique `<name>.<uuid>.part` sibling and renames it into place, like `downloadFile`, so a concurrent thumbnail request never serves a half-written image.

## Implementation notes: write-back ordering and stopped uploads (2026-11-14)

- **Pending saves are the newest bytes.** When a SAF write descriptor closes, the provider inserts a `QUEUED` upload row for the staged file before enqueuing the worker (the worker continues that row, as it already did for captured photos). While a row for a document is `QUEUED` or `RUNNING`, `openDocument` for reading, and the seeding of `rw`/`wa` opens, use the newest such staged file (`DocumentRepository.pendingUploadFile`) instead of the server's bytes. The close callback is asynchronous, so a read issued in the same instant as the close can still miss the save; this is inherent to `ParcelFileDescriptor.OnCloseListener`.
- **Chained `If-Match`.** Uploads of one document are already serialised (`APPEND_OR_REPLACE` unique work per account and path). A finished plain overwrite now returns its new ETag as worker output (`previous_upload_etag`), which WorkManager merges into the input of the next upload queued for that file; that ETag replaces the one captured when the file was opened, which was stale by then. A conflict copy or a failure returns no ETag, so the next save keeps its original `If-Match` and still conflicts with a remote edit rather than overwriting it.
- **Stopped is not failed.** A `CancellationException` in `UploadDocumentWorker` sets the row back to `QUEUED` (WorkManager stopped the worker for a lost constraint or timeout and will run it again, which continues the same row) unless the account no longer exists, which is the only real cancellation (`AccountCleaner`). `TransferDao.prunableSources` skips any staged file that another row still has `QUEUED` or `RUNNING`, so pruning an old failed row can no longer delete a file that has pending work. `PinnedSyncWorker` still marks a stopped download `FAILED`; downloads have no staged file and a rerun starts a new row.

## Implementation notes: fewer requests and queries (2026-11-14)

- **Pin roots come from SQL.** `PinnedSyncWorker` asks `DocumentDao.pinnedRoots()` (pinned rows whose parent is not pinned) instead of comparing every pinned row with every other and parsing both ids each time. A pinned item whose pinned ancestor is not its direct parent (a deep item pinned while only a grandparent is pinned) now counts as its own root until the next sync flags the folders between; at worst it is visited twice, never missed.
- **No `PROPFIND Depth: 0` for what a listing just returned.** `syncNode` and `syncFile` receive the entry the parent's `Depth: 1` listing produced (it carries the local state, including the ETag the subtree was last synced at). Only a pinned root is looked at on its own, to read its ETag. A tree of N folders and files used to cost N extra requests per sync. `ensureLocalFile`/`ensurePinnedCopy` take that entry as `justListed`.
- **A short freshness window for opens.** `DocumentRepository` remembers (in memory, 512 entries, least recently used out) when each document's metadata last came from the server. For `freshnessWindowMs` (3 s) after that, `ensureLocalFile` serves a valid local copy without another `PROPFIND`; a viewer that opens, reads and seeks no longer pays one request per call. A change made on the server inside those seconds is seen on the next open after the window. The offline fallback is unchanged (it applies when the `PROPFIND` fails). Tests set the window to 0, so tests that edit the server behind the app's back still see every open check; two tests cover the window itself.
- **Camera backup loads the backed-up ids once** per scan (`BackedUpMediaDao.backedUpKeys`) instead of one lookup per MediaStore item on every trigger.
- **Thumbnails trim in batches.** `trimThumbnailsWhenDue` walks the thumbnail directory only after about an eighth of its budget in new thumbnails has been stored, not after every one. The budget can be exceeded by that much between trims.

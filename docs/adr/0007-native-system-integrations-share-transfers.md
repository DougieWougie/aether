# ADR-0007: Native Android System Integrations (Share Target, `MediaStore`, Foreground Transfer Notifications)

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

To provide a seamless Dropbox-like experience while relying on Android's native capabilities, users need frictionless ways to send files into Nextcloud from any app, back up their camera roll automatically, and monitor large uploads/downloads in the system notification shade.

## Decision

We will implement three core Android platform integrations alongside `DocumentsProvider`:

1. **System Share Sheet Target (`ACTION_SEND` & `ACTION_SEND_MULTIPLE`):**
   - Expose a dedicated `ShareUploadActivity` (with Material 3 Expressive bottom-sheet presentation) that accepts `Intent.ACTION_SEND` and `Intent.ACTION_SEND_MULTIPLE` for `*/*` MIME types.
   - Support Direct Share (`ShortcutManagerCompat` / `SharingShortcuts`) publishing the user's most recently used or pinned Nextcloud folders as direct targets in the Android System Share Sheet.
   - Stream shared `content://` URIs via `ContentResolver.openInputStream()` directly into an expedited `WorkManager` upload job.

2. **`MediaStore` & `PhotoPicker` Camera Backup Integration:**
   - Register `WorkManager` background workers triggered by `ContentUriTriggers` on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` and `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`.
   - Use scoped storage permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, and `READ_MEDIA_VISUAL_USER_SELECTED` on Android 14+) or allow users to pick specific folders/photos for backup.
   - Maintain a local Room table (`BackedUpMediaEntity`) storing the `MediaStore` `_ID`, `DATE_MODIFIED`, size, and SHA-256 / ETag to guarantee idempotent uploads without duplicates.

3. **Foreground Transfer Notifications (`FOREGROUND_SERVICE_DATA_SYNC`):**
   - Large file uploads (using Nextcloud Chunked Upload v2) and offline pin downloads promote their `CoroutineWorker` to foreground execution via `setForeground(ForegroundInfo(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))`.
   - Display grouped, progress-centric Android notifications with a native "Cancel" action (`WorkManager.getInstance(context).createCancelPendingIntent(id)`).

## Rationale

- **Native User Workflows:** Users rarely open a cloud storage app first to upload a file; they share from their gallery, scanner, or browser via the Android Share Sheet, or rely on silent background camera backup.
- **Resilient Transfers:** Promoting long-running `WorkManager` jobs to `FOREGROUND_SERVICE_DATA_SYNC` prevents the OS from killing multi-gigabyte video uploads while providing transparent progress in the notification shade.

## Consequences

- **Positive:** Feels built into the Android OS; supports Direct Share targets and resilient background uploads.
- **Negative / Mitigation:** Android 14+ requires declaring `android:foregroundServiceType="dataSync"` in `AndroidManifest.xml` and requesting `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` permissions, as well as `POST_NOTIFICATIONS` at runtime for visible progress bars.

## Implementation notes

- **Share uploads stage a copy.** The URI grant attached to a share intent is tied to the receiving activity, which is gone long before an expedited `WorkManager` job may run (offline, retries). `ShareUploadActivity` therefore copies each shared item into `filesDir/staging` before enqueueing `UploadDocumentWorker`, and the worker deletes it after a settled upload. Camera backup, which holds a durable `READ_MEDIA_*` grant, streams from `MediaStore` URIs directly.
- **Destination folders** are chosen with the system folder picker (`ACTION_OPEN_DOCUMENT_TREE`, initial URI = the Aether root); only Aether folders are accepted. The four most recent destinations are remembered in DataStore and published as Direct Share targets (dynamic shortcuts in category `com.aether.nextcloud.category.SHARE_TARGET`, declared via `res/xml/shortcuts.xml` on the launcher activity).
- **Foreground promotion** happens for uploads above the 10 MB chunking threshold and for user-triggered pin syncs. Background-started (non-expedited) workers cannot start a foreground service on Android 12+, so `setForeground` is best-effort there.

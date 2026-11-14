# ADR-0001: System `DocumentsProvider` (SAF) as Primary File Interface

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

Cloud storage applications on Android traditionally build full custom file browsers inside the app, duplicating functionality already present in Android's system file manager (Files by Google / `DocumentsUI`) and requiring custom integrations for third-party apps to open, edit, or save files.

Our core principle for Aether is to **leverage default capabilities in Android as far as possible** while providing a Dropbox-like experience backed by Nextcloud.

## Decision

We will implement a custom `android.provider.DocumentsProvider` (`AetherDocumentsProvider`) registered under the `android.content.action.DOCUMENTS_PROVIDER` intent action as the primary user and app interface for browsing, opening, creating, renaming, moving, and deleting Nextcloud files.

The standalone Aether application UI will remain a **Minimal Companion UI** focused strictly on:
1. Onboarding & Nextcloud Login Flow v2 authentication
2. Sync status dashboard, active transfers, and pinned offline items overview
3. Automatic Camera Roll / Folder backup configuration
4. Storage quota, cache management, and account settings
5. Shortcut action to launch the system file manager directly into the Aether root (`Intent.ACTION_VIEW` with `DocumentsContract.buildRootUri(...)`)

## Rationale

- **Zero Redundancy:** Android's system `DocumentsUI` already provides grid/list views, search, sorting, drag-and-drop, file previews, and multi-window support.
- **Universal App Compatibility:** Any Android app using `ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`, or `ACTION_OPEN_DOCUMENT_TREE` immediately gains native read/write access to the user's Nextcloud files as if they were local storage.
- **Native Offline & Loading States:** `DocumentsContract` natively supports `COLUMN_FLAGS` (`FLAG_SUPPORTS_WRITE`, `FLAG_SUPPORTS_DELETE`, `FLAG_SUPPORTS_RENAME`, `FLAG_SUPPORTS_MOVE`, `FLAG_SUPPORTS_THUMBNAIL`, `FLAG_PARTIAL`) and `DocumentsContract.EXTRA_LOADING` to indicate in-flight network fetches to the system UI.
- **Streaming Reads & Writes:** Using `ParcelFileDescriptor.open()` or `StorageManager.openProxyFileDescriptor()` / pipe descriptors allows streaming reads/writes without blocking the Binder thread.

## Consequences

- **Positive:** Dramatically smaller UI surface area to maintain; deep OS integration matching Google Drive and Dropbox's native SAF roots.
- **Negative / Mitigation:** `DocumentsProvider` methods (`queryChildDocuments`, `queryDocument`) can be called on Binder threads and expect fast responses. We must maintain a local Room metadata cache (`DocumentEntity`) and return cached results immediately with `DocumentsContract.EXTRA_LOADING = true` while triggering an asynchronous WebDAV `PROPFIND` refresh that calls `ContentResolver.notifyChange()` upon completion.

## Implementation notes: copy and recents (2026-11-14)

- **Copy.** `copyDocument` is implemented with the existing `WebDavClient.copy` (`COPY` with `Overwrite: F`). The copy goes into the target folder under the first free name (`DocumentRepository.uniqueChild`, so copying into the same folder makes `name (1).ext`) and nothing is ever replaced. `FLAG_SUPPORTS_COPY` is set on every non-root document the user can read (Nextcloud permission `G`): copying needs only read access to the source, so a read-only share can be copied, unlike moved or deleted. The destination is checked when it is chosen: `C` (create files) for a file, `K` (create folders) for a folder, otherwise a `SecurityException`. Copying a folder into itself or a descendant, an account root, and copying across accounts are refused. A copied folder's contents are listed lazily like any other folder.
- **Recents.** The root advertises `FLAG_SUPPORTS_RECENTS` and `queryRecentDocuments` answers from Room only: the account's 64 most recently modified files (no folders), by the server's modified time. It never waits for or triggers a request, so the Recent view opens at once and offline. Its limits follow from that: it shows only what the cache has seen, so files in folders that were never opened are absent, and "recent" means modified, not opened.

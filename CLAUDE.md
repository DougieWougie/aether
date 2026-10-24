# CLAUDE.md — Aether (Native Android Nextcloud Client)

## Project Overview

**Aether** is a modern, native Android cloud storage client that works like Dropbox but uses **Nextcloud** as its backend.
Its defining engineering philosophy is: **Leverage default Android platform capabilities as far as possible, avoid reinventing system components, and use Material 3 Expressive for the companion UI.**

Instead of building a redundant in-app file manager, Aether integrates directly into Android's **Storage Access Framework (`DocumentsProvider`)**, **`AccountManager`**, **System Share Sheet (`ACTION_SEND`)**, **`MediaStore` / `WorkManager`**, and **`androidx.browser` Custom Tabs**.

All architectural decisions and rationales are recorded in [`docs/adr/`](docs/adr/README.md). Read the ADRs before altering core architectural patterns.

---

## License

Aether is licensed under the **GNU General Public License v3.0 or later** (see [`LICENSE`](LICENSE)). Only add dependencies whose licenses are GPLv3-compatible (Apache-2.0, MIT and BSD are fine).

---

## Core Architectural Decisions (Summary)

1. **[ADR-0001](docs/adr/0001-saf-documents-provider-primary-interface.md): System `DocumentsProvider` First**
   - File browsing, opening, creating, renaming, moving, and deleting happen natively through Android's system file manager (`DocumentsUI`) and SAF file pickers via `AetherDocumentsProvider`.
   - The in-app UI is a **Minimal Companion App** for Login Flow v2, Sync & Transfer Dashboard, Offline Pin Management, Camera Backup configuration, and Account/Quota Settings.
2. **[ADR-0002](docs/adr/0002-nextcloud-login-flow-v2-custom-tabs.md): Nextcloud Login Flow v2 via Custom Tabs**
   - Authentication uses `POST <server>/index.php/login/v2`, launches the returned `login` URL in an `androidx.browser.customtabs.CustomTabsIntent`, and polls `poll.endpoint` with `token=<poll.token>` until HTTP `200 OK` returns `server`, `loginName`, and `appPassword`.
   - **Never** use an embedded `WebView` for authentication.
3. **[ADR-0003](docs/adr/0003-android-account-manager-credentials.md): Android `AccountManager` for Credentials**
   - Accounts and Nextcloud `appPassword` credentials are stored in Android's native `AccountManager` via `AetherAccountAuthenticator` (`AbstractAccountAuthenticator`), not custom isolated credential stores.
4. **[ADR-0004](docs/adr/0004-hybrid-sync-offline-pinning-workmanager.md): Hybrid Sync Model**
   - On-demand directory metadata (`Room`) and file content (`LRU disk cache` validated by Nextcloud `ETag`) for SAF reads/writes.
   - Selective offline pinning (`isPinned = true`) synced via `WorkManager` (`PinnedSyncWorker`).
   - Automatic Camera Roll backup triggered via `WorkManager` `ContentUriTriggers` on `MediaStore`.
5. **[ADR-0005](docs/adr/0005-lightweight-webdav-xmlpullparser-okhttp.md): Zero-Bloat WebDAV Stack**
   - Built directly with `OkHttp`, `kotlinx.serialization.json`, and Android's native `org.xmlpull.v1.XmlPullParser` (`android.util.Xml`).
   - Do **not** add heavy third-party WebDAV libraries (`sardine`, `nextcloud-android-library`, `dav4jvm`).
6. **[ADR-0006](docs/adr/0006-material-3-expressive-ui.md): Material 3 Expressive (`@ExperimentalMaterial3ExpressiveApi`)**
   - Built with Jetpack Compose and Material 3 Expressive (`MaterialExpressiveTheme`, `MotionScheme.expressive()`, `LoadingIndicator`, `ContainedLoadingIndicator`, `ButtonGroup`, `LargeFlexibleTopAppBar`, `MaterialShapes` morphing, and Dynamic Color).
7. **[ADR-0007](docs/adr/0007-native-system-integrations-share-transfers.md): Native System Integrations**
   - System Share Sheet receiver (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) with Direct Share shortcuts (`ShortcutManagerCompat`).
   - Foreground service progress notifications (`FOREGROUND_SERVICE_DATA_SYNC`) for large transfers and chunked uploads.

---

## Tech Stack & Libraries

- **Language:** Kotlin 2.1+ (Coroutines, Flow, `kotlinx.serialization`)
- **Min SDK:** 29 (Android 10 — Scoped Storage baseline) | **Target SDK:** 36 (Android 16) | **Compile SDK:** 37 (required by current OkHttp 5.x / AndroidX releases)
- **UI Framework:** Jetpack Compose with **Material 3 Expressive** (`androidx.compose.material3:material3:1.4.0-alpha+` / `1.5.0-alpha+`, `androidx.graphics:graphics-shapes`)
- **DI:** Hilt (`com.google.dagger.hilt.android`)
- **Networking:** `OkHttp3` (HTTP/2, custom WebDAV verbs, streaming `RequestBody`/`ResponseBody`)
- **XML Parsing:** Android platform `org.xmlpull.v1.XmlPullParser` (with `net.sf.kxml:kxml2` in `testImplementation` for JVM unit tests)
- **Local Persistence:** Jetpack `Room` (metadata cache, offline pins, transfer queue, backed-up media log) + Jetpack `DataStore` (user preferences/sync constraints)
- **Background Work:** Jetpack `WorkManager` (`CoroutineWorker` + Hilt Worker integration)
- **Browser Auth:** `androidx.browser:browser` (Custom Tabs)

---

## Project Package Structure

```text
com.aether.nextcloud/
├── auth/
│   ├── AetherAccountAuthenticator.kt   # AbstractAccountAuthenticator implementation
│   ├── AuthenticatorService.kt         # Bound service for android.accounts.AccountAuthenticator
│   ├── AccountRepository.kt            # Wrapper around Android AccountManager
│   └── LoginFlowV2Client.kt            # POST /index.php/login/v2 & polling logic
├── dav/
│   ├── WebDavClient.kt                 # PROPFIND, GET, PUT, MKCOL, MOVE, DELETE, Chunked Upload v2
│   ├── WebDavXmlParser.kt              # Streaming XmlPullParser for DAV: and oc:/nc: namespaces
│   ├── NextcloudOcsClient.kt           # OCS User/Quota info (/ocs/v2.php/cloud/user) & Preview URLs
│   └── model/
│       └── DavResource.kt              # Parsed WebDAV resource (href, fileId, etag, mimeType, size, etc.)
├── provider/
│   ├── AetherDocumentsProvider.kt      # System SAF DocumentsProvider implementation
│   ├── DocumentId.kt                   # Structured DocumentId helper (<accountName>:<remotePath>)
│   └── StreamingPipeHelper.kt          # ParcelFileDescriptor streaming & OnCloseListener upload trigger
├── data/
│   ├── local/
│   │   ├── AetherDatabase.kt           # Room database
│   │   ├── dao/
│   │   │   ├── DocumentDao.kt
│   │   │   ├── TransferDao.kt
│   │   │   └── BackedUpMediaDao.kt
│   │   └── entity/
│   │       ├── DocumentEntity.kt       # Cached WebDAV metadata + isPinned + localCachePath
│   │       ├── TransferEntity.kt       # Active/queued uploads & downloads
│   │       └── BackedUpMediaEntity.kt  # MediaStore backup tracking
│   └── cache/
│       └── DocumentDiskCache.kt        # ETag-validated disk cache & persistent pinned storage
├── sync/
│   ├── UploadDocumentWorker.kt         # SAF write-back & Share Sheet upload worker (Chunked v2 support)
│   ├── PinnedSyncWorker.kt             # Periodic/on-demand ETag sync for pinned files/folders
│   ├── CameraBackupWorker.kt           # MediaStore ContentUriTriggers photo/video backup worker
│   └── TransferNotificationHelper.kt   # FOREGROUND_SERVICE_DATA_SYNC notification builder
├── ui/
│   ├── theme/
│   │   ├── Theme.kt                    # MaterialExpressiveTheme + MotionScheme.expressive()
│   │   └── Type.kt                     # Expressive & Emphasized Typography
│   ├── login/
│   │   ├── LoginScreen.kt              # Server input, Custom Tab launcher, morphing polling indicator
│   │   └── LoginViewModel.kt
│   ├── dashboard/
│   │   ├── DashboardScreen.kt          # Scaffold, navigation bar, sign-in banner
│   │   ├── AccountHeader.kt            # Header and account menu
│   │   ├── StorageHero.kt              # Quota gauge and stat chips
│   │   ├── TransfersSection.kt         # Active transfers and conflict rows
│   │   ├── PinnedSection.kt            # Pinned items and the FAB menu
│   │   ├── CameraSection.kt            # Camera backup tile
│   │   ├── DashboardComponents.kt      # Shared section title, empty state, formatting
│   │   └── DashboardViewModel.kt
│   ├── share/
│   │   ├── ShareUploadActivity.kt      # ACTION_SEND / ACTION_SEND_MULTIPLE modal sheet
│   │   └── ShareUploadViewModel.kt
│   └── backup/
│       └── CameraBackupSettings.kt
└── di/
    └── AppModule.kt                    # Hilt modules
```

---

## Key Protocol & Implementation Specifications

### 1. Nextcloud Login Flow v2 (`LoginFlowV2Client`)
- **Initiation:** `POST https://<server>/index.php/login/v2` with header `User-Agent: Aether (Android <SDK>)`.
- **Response (`200 OK`):**
  ```json
  {
    "poll": {
      "token": "string",
      "endpoint": "https://cloud.example.com/index.php/login/v2/poll"
    },
    "login": "https://cloud.example.com/index.php/login/v2/flow/..."
  }
  ```
- **Polling:** `POST <poll.endpoint>` with form body `token=<poll.token>` every 2,000ms.
  - `404 Not Found`: User has not finished authenticating yet; continue polling.
  - `200 OK`: Parse `{"server": "...", "loginName": "...", "appPassword": "..."}`.
- **Persistence:** Store `poll.endpoint` and `poll.token` in `SavedStateHandle` so polling survives activity configuration changes or process recreation while the user is in the Custom Tab or a 2FA app.

### 2. WebDAV & Chunked Upload v2 (`WebDavClient`)
- **Authentication:** HTTP Basic Auth header `Authorization: Basic base64(<loginName>:<appPassword>)`.
- **Base File URL:** `<server>/remote.php/dav/files/<loginName>/<relativePath>`
- **`PROPFIND` Request Body:**
  ```xml
  <?xml version="1.0" encoding="utf-8" ?>
  <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
    <d:prop>
      <d:resourcetype/>
      <d:getcontentlength/>
      <d:getcontenttype/>
      <d:getlastmodified/>
      <d:getetag/>
      <oc:fileid/>
      <oc:permissions/>
      <oc:size/>
      <nc:has-preview/>
      <oc:favorite/>
    </d:prop>
  </d:propfind>
  ```
- **Chunked Upload v2 (Files > 10 MB):**
  1. `MKCOL <server>/remote.php/dav/uploads/<loginName>/<uuid>`
  2. Split stream into 5 MB–10 MB chunks: `PUT <server>/remote.php/dav/uploads/<loginName>/<uuid>/<00001..N>` with header `OC-Total-Length: <totalBytes>`.
  3. Assemble: `MOVE <server>/remote.php/dav/uploads/<loginName>/<uuid>/.file` with header `Destination: <server>/remote.php/dav/files/<loginName>/<targetPath>` and `OC-Total-Length: <totalBytes>`.

### 3. `AetherDocumentsProvider` Rules
- **Document ID Format:** `<accountName>:<normalizedRemotePath>` (e.g., `alice@cloud.example.com:/Documents/notes.md`). Root document ID is `<accountName>:/`.
- **Non-Blocking Queries (`queryChildDocuments`):**
  - Query Room `DocumentDao` synchronously on the Binder thread.
  - If the directory is not cached or its cache timestamp is stale (> 60s), return the `MatrixCursor` with whatever cached rows exist, set `extras.putBoolean(DocumentsContract.EXTRA_LOADING, true)`, register `cursor.setNotificationUri(context.contentResolver, notifyUri)`, and launch a coroutine to run `PROPFIND Depth: 1`, update Room, and call `context.contentResolver.notifyChange(notifyUri, null)`.
- **Safe Writes (`openDocument` with `"w"`):**
  - Return a `ParcelFileDescriptor` backed by a temporary staging file via `ParcelFileDescriptor.open(stagingFile, mode, handler) { exception -> ... }`.
  - When closed without error, enqueue an expedited `UploadDocumentWorker` passing the previous `ETag` (`If-Match`) to prevent overwriting concurrent remote edits.

### 4. Material 3 Expressive Guidelines
- Always annotate composables using expressive APIs with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- Use `MaterialTheme.motionScheme.defaultSpatialSpec()` / `fastSpatialSpec()` for layout, expansion, and shape morphing animations—**never** use hardcoded `tween()` where a spring spec belongs.
- Use `LoadingIndicator` / `ContainedLoadingIndicator` instead of legacy `CircularProgressIndicator` where appropriate.
- Include an **"Open in System Files"** primary action (`ExtendedFloatingActionButton`, with section navigation in a `ShortNavigationBar`) on the Dashboard that launches `Intent(Intent.ACTION_VIEW)` with `DocumentsContract.buildRootUri(AUTHORITY, accountRootId)`.

---

## Build, Lint & Test Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run JVM unit tests (WebDAV XML parser, Login v2 polling, Repository logic, DocumentId)
./gradlew testDebugUnitTest

# Run Android lint checks
./gradlew lintDebug
```

---

## Coding Standards

- **Strict Kotlin Null-Safety & Explicit Error Handling:** Use sealed interfaces (`Result` / domain error types) for network and WebDAV operations.
- **No Blocking on Main Thread:** All disk IO and `OkHttp` calls run on `Dispatchers.IO`.
- **Preserve Documentation & ADRs:** Any significant change to authentication, sync, SAF provider behavior, or UI design system must update or add an ADR in `docs/adr/`.

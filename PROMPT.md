# Claude Code Prompts for Aether

Below is the **Master Implementation Prompt** to kick off building Aether in Claude Code, followed by optional **Phase-by-Phase Prompts** if you prefer incremental execution.

---

## Option A: Master Kick-Off Prompt (Full Scaffolding & Implementation)

Copy and paste the following prompt directly into Claude Code:

```markdown
Read `CLAUDE.md` and all Architecture Decision Records in `docs/adr/` (`0001` through `0007`) before writing any code.

We are building **Aether**, a native Android Nextcloud client (`com.aether.nextcloud`) that works like Dropbox, leverages built-in Android capabilities (`DocumentsProvider` SAF, `AccountManager`, Custom Tabs, `WorkManager`, `MediaStore`, System Share Sheet), and uses **Material 3 Expressive** in Jetpack Compose.

Please execute the implementation in the following structured phases, verifying compilation and unit tests after each phase:

### Phase 1: Gradle Project Scaffolding & Dependencies
1. Initialize a clean, single-module (or clean package-modularized) Android Gradle project (Kotlin DSL `build.gradle.kts`, Version Catalog `gradle/libs.versions.toml`, Gradle Wrapper).
2. Configure `minSdk = 29`, `compileSdk = 36`, `targetSdk = 36`, Kotlin 2.1+, Compose Compiler plugin, KSP, and Hilt.
3. Add dependencies:
   - Jetpack Compose BOM + `androidx.compose.material3:material3` (`1.4.0-alpha15` or latest expressive-enabled alpha/beta) + `androidx.graphics:graphics-shapes`
   - `androidx.browser:browser` (Custom Tabs)
   - `com.squareup.okhttp3:okhttp` + `org.jetbrains.kotlinx:kotlinx-serialization-json`
   - Jetpack `Room` (`room-runtime`, `room-ktx`, `room-compiler` via KSP)
   - Jetpack `WorkManager` (`work-runtime-ktx`) + `androidx.hilt:hilt-work`
   - Jetpack `DataStore` (`datastore-preferences`)
   - Unit testing: `junit`, `kotlinx-coroutines-test`, `com.squareup.okhttp3:mockwebserver`, `net.sf.kxml:kxml2` (so `XmlPullParserFactory` works in pure JVM tests).

### Phase 2: Authentication (`AccountManager` + Nextcloud Login Flow v2) — ADR-0002 & ADR-0003
1. Implement `LoginFlowV2Client` (`POST /index.php/login/v2` and `POST <poll.endpoint>` with `token=<poll.token>`). Handle HTTPS normalization and HTTP 404 (pending) vs 200 OK (authenticated).
2. Implement `AetherAccountAuthenticator` (`AbstractAccountAuthenticator`), `AuthenticatorService`, and `res/xml/authenticator.xml` for account type `com.aether.nextcloud.account`.
3. Implement `AccountRepository` wrapping `AccountManager` to store/retrieve accounts, `appPassword`, server URL, and WebDAV base path, and broadcast root changes to `DocumentsContract.buildRootsUri(...)`.
4. Write JVM unit tests for `LoginFlowV2Client` using `MockWebServer`.

### Phase 3: Lightweight WebDAV & OCS Client (`OkHttp` + `XmlPullParser`) — ADR-0005
1. Implement `WebDavXmlParser` using `org.xmlpull.v1.XmlPullParser` (`XmlPullParserFactory.newInstance()`) to parse `207 Multi-Status` responses (`DAV:`, `http://owncloud.org/ns`, `http://nextcloud.org/ns`) into `DavResource` models (`href`, `fileId`, `isDirectory`, `contentLength`, `contentType`, `lastModified`, `etag`, `permissions`, `hasPreview`, `isFavorite`).
2. Implement `WebDavClient` supporting:
   - `propfind(url, depth)`
   - `downloadFile(url, targetFile, onProgress)`
   - `uploadFile(url, sourceFile, mimeType, ifMatchEtag, onProgress)`
   - `uploadChunkedV2(serverUrl, user, sourceFile, targetRemotePath, onProgress)` (`MKCOL` uploads dir -> `PUT` chunks -> `MOVE .file`)
   - `mkcol(url)`, `delete(url)`, `move(sourceUrl, destUrl, overwrite)`
3. Implement `NextcloudOcsClient` for user quota (`GET /ocs/v2.php/cloud/user?format=json` with `OCS-APIRequest: true`) and thumbnail preview URLs (`/index.php/core/preview?fileId=...&x=256&y=256&a=1`).
4. Write comprehensive JVM unit tests for `WebDavXmlParser` and `WebDavClient` using realistic Nextcloud `PROPFIND` XML fixtures and `MockWebServer`.

### Phase 4: Local Persistence, Disk Cache & System `DocumentsProvider` (SAF) — ADR-0001 & ADR-0004
1. Create the Room database (`AetherDatabase`) with `DocumentEntity`, `TransferEntity`, and `BackedUpMediaEntity` + DAOs.
2. Implement `DocumentDiskCache` managing both ETag-verified on-demand cache (`cacheDir/document_cache`) and persistent offline pinned storage (`filesDir/pinned`).
3. Implement `AetherDocumentsProvider` (`DocumentsProvider`):
   - `queryRoots`: Return a root for each Nextcloud account in `AccountManager`, including storage quota (`COLUMN_AVAILABLE_BYTES`, `COLUMN_CAPACITY_BYTES`) and `FLAG_SUPPORTS_CREATE | FLAG_SUPPORTS_IS_CHILD`.
   - `queryDocument` & `queryChildDocuments`: Serve immediately from Room; if stale or missing, attach `DocumentsContract.EXTRA_LOADING = true`, register notification URI, and trigger an asynchronous `PROPFIND` refresh that calls `contentResolver.notifyChange(...)`.
   - `openDocument`: Serve from `DocumentDiskCache` if `ETag` matches; otherwise stream/download from `WebDavClient`. For write modes (`"w"`, `"wt"`), write to a staging file and use `ParcelFileDescriptor.open(..., handler) { ... }` to enqueue an expedited `UploadDocumentWorker` with `If-Match: <etag>` on close.
   - `createDocument`, `deleteDocument`, `renameDocument`, `moveDocument`, and `openDocumentThumbnail`.

### Phase 5: Background Sync, Offline Pinning & System Integrations — ADR-0004 & ADR-0007
1. Implement `UploadDocumentWorker` (handles SAF write-backs and Share Sheet uploads, promoting to `FOREGROUND_SERVICE_DATA_SYNC` with progress notifications for large files and handling `412 Precondition Failed` conflicts by saving a conflict copy).
2. Implement `PinnedSyncWorker` (compares folder/file `ETag`s and downloads changed pinned files).
3. Implement `CameraBackupWorker` triggered via `WorkManager` `ContentUriTriggers` on `MediaStore` images/videos, deduplicated via `BackedUpMediaDao`.

### Phase 6: Material 3 Expressive Companion UI — ADR-0006
1. Configure `MaterialExpressiveTheme` with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`, `MotionScheme.expressive()`, Dynamic Color, and Expressive Typography.
2. Build `LoginScreen`: Expressive morphing hero shape (`MaterialShapes`), server URL validation, Custom Tab launch (`CustomTabsIntent`), and `ContainedLoadingIndicator` while polling Login Flow v2.
3. Build `DashboardScreen`: `LargeFlexibleTopAppBar`, expressive storage quota visual card, segmented `ButtonGroup` for Active Transfers / Offline Pins / Camera Backup status, and a prominent **"Open in System Files"** action launching `DocumentsUI` at the Aether root.
4. Build `ShareUploadActivity`: Expressive modal bottom sheet handling `ACTION_SEND` and `ACTION_SEND_MULTIPLE` to pick a Nextcloud destination folder and enqueue `UploadDocumentWorker`.
5. Register all components, permissions, authorities, and intent filters cleanly in `AndroidManifest.xml`.

After completing all phases, run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` and fix any issues until both pass cleanly.
```

---

## Option B: Interactive / Step-by-Step Prompts

If you want to guide Claude Code one milestone at a time and review diffs between steps:

1. **Step 1 (Foundation & Auth):**
   > "Read `CLAUDE.md` and `docs/adr/0002*` + `0003*`. Scaffold the Gradle project with Material 3 Expressive and implement Phase 1 and Phase 2 (`LoginFlowV2Client`, `AetherAccountAuthenticator`, `AccountRepository`) along with `MockWebServer` unit tests. Run `./gradlew testDebugUnitTest`."

2. **Step 2 (WebDAV Engine):**
   > "Read `docs/adr/0005*`. Implement Phase 3 (`WebDavXmlParser` with `XmlPullParser`, `WebDavClient` including Chunked Upload v2, and `NextcloudOcsClient`). Add unit tests for `PROPFIND` parsing and chunked uploads and run `./gradlew testDebugUnitTest`."

3. **Step 3 (SAF `DocumentsProvider` & Room Cache):**
   > "Read `docs/adr/0001*` and `0004*`. Implement Phase 4 (`AetherDatabase`, `DocumentDiskCache`, and `AetherDocumentsProvider` with non-blocking Room queries, `EXTRA_LOADING`, and `If-Match` write-back staging). Add unit tests for `DocumentId` and cache validation."

4. **Step 4 (Workers & Expressive UI):**
   > "Read `docs/adr/0006*` and `0007*`. Implement Phase 5 (`UploadDocumentWorker`, `PinnedSyncWorker`, `CameraBackupWorker`) and Phase 6 (Material 3 Expressive theme, `LoginScreen`, `DashboardScreen`, and `ShareUploadActivity`). Run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest`."

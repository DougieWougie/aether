# Architecture Decision Records (ADRs) — Aether

This directory contains the Architecture Decision Records (ADRs) for **Aether**, a native Android Nextcloud client built around Android's built-in platform capabilities and Material 3 Expressive design.

## Index

| ADR | Title | Status | Summary |
| :--- | :--- | :--- | :--- |
| [ADR-0001](0001-saf-documents-provider-primary-interface.md) | System `DocumentsProvider` (SAF) as Primary File Interface | Accepted | Expose Nextcloud files natively via Android's Storage Access Framework instead of building a redundant in-app file manager. |
| [ADR-0002](0002-nextcloud-login-flow-v2-custom-tabs.md) | Nextcloud Login Flow v2 via Custom Tabs | Accepted | Authenticate using Nextcloud Login Flow v2 (`POST /index.php/login/v2`) with `androidx.browser` Custom Tabs and HTTP polling, avoiding `WebView`. |
| [ADR-0003](0003-android-account-manager-credentials.md) | Native `AccountManager` for Account & Credential Storage | Accepted | Store server URLs, usernames, and Login v2 App Passwords in Android's native `AccountManager` via `AbstractAccountAuthenticator`. |
| [ADR-0004](0004-hybrid-sync-offline-pinning-workmanager.md) | Hybrid Sync Model: On-Demand SAF + Offline Pinning & Camera Backup via `WorkManager` | Accepted | Combine on-demand streaming in SAF with selective offline pinning, `MediaStore` camera backup, and `WorkManager` background jobs. |
| [ADR-0005](0005-lightweight-webdav-xmlpullparser-okhttp.md) | Lightweight WebDAV Stack (`OkHttp` + Android `XmlPullParser`) | Accepted | Implement WebDAV (`PROPFIND`, `MKCOL`, `PUT`, `DELETE`, `MOVE`, chunked uploads) using `OkHttp` and Android's built-in `org.xmlpull.v1.XmlPullParser` without heavy 3rd-party WebDAV SDKs. |
| [ADR-0006](0006-material-3-expressive-ui.md) | Material 3 Expressive Design System in Jetpack Compose | Accepted | Build the companion UI (Login, Sync Dashboard, Share Upload Sheet, Settings) using Jetpack Compose and Material 3 Expressive (`androidx.compose.material3` alpha/expressive APIs, `MotionScheme.expressive()`, morphing shapes, dynamic color). |
| [ADR-0007](0007-native-system-integrations-share-transfers.md) | Native Android System Integrations (Share Target, `MediaStore`, Foreground Transfer Notifications) | Accepted | Integrate directly with Android's Share Sheet (`ACTION_SEND`), `MediaStore` change observers for camera backup, and Foreground Services (`FOREGROUND_SERVICE_DATA_SYNC`) for large transfers. |
| [ADR-0008](0008-local-data-storage-and-privacy.md) | Local Data Storage and Privacy Posture | Accepted | Local data is unencrypted private app storage relying on file-based encryption; backups off; no device model in the User-Agent; private notifications; no app lock for now. |
| [ADR-0009](0009-ci-test-scope.md) | CI Test Scope | Accepted | CI runs lint, JVM tests and instrumented logic tests; the Compose screen tests run locally because they are unreliable on the CI emulator. |
| [ADR-0010](0010-tiered-ci-pr-gate-and-release-suite.md) | Tiered CI | Accepted | Pull requests run unit tests, conditional lint and an instrumented `@Smoke` subset; the full instrumented suite and coverage run on `release/**` branches. |
| [ADR-0011](0011-saf-search-via-webdav-search.md) | File Search via WebDAV `SEARCH` | Accepted | The Files app search box queries the whole account through Nextcloud's WebDAV `SEARCH`, answered from Room at once and refreshed in the background. |

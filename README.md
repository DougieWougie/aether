<p align="center">
  <img src="assets/aether_logo.svg" alt="Aether logo" width="160" height="160">
</p>

<h1 align="center">Aether</h1>

<p align="center">
  A native Android client for <a href="https://nextcloud.com">Nextcloud</a> that works like Dropbox:<br>
  your files show up in Android's own file manager, with no second file browser to learn.
</p>

<p align="center">
  <a href="https://github.com/DougieWougie/aether/actions/workflows/ci.yml"><img src="https://github.com/DougieWougie/aether/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue" alt="License: GPL-3.0-or-later"></a>
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84" alt="Android 10+">
</p>

> **Status:** early development (v0.1.0). It is not yet on Google Play.

## Why Aether

Most cloud clients ship their own file manager next to the one Android already has. Aether does the opposite: it
plugs into the platform and keeps its own UI small.

- **Files live in the system file manager.** Aether is a Storage Access Framework `DocumentsProvider`, so your
  Nextcloud shows up in the Files app and in every app's file picker, and any app can open or save to it.
- **Sharing is native.** Send files to Nextcloud from the Android share sheet.
- **Sign-in is the browser's job.** Login uses Nextcloud Login Flow v2 in a Custom Tab, so 2FA, SSO and password
  managers just work. There is no embedded `WebView`.
- **Credentials live in `AccountManager`**, as an app password you can revoke from the Nextcloud web UI.

## Features

- Browse, open, create, rename, move and delete files through the system file manager.
- Safe write-back: edits made by other apps are staged and uploaded with `If-Match`, so a change made on the server
  in the meantime is never overwritten. On a conflict your version is saved as a conflict copy.
- Search from the Files app's search box across your whole Nextcloud, including the file-type and date filters.
- Offline pinning of files and folders, kept up to date in the background by comparing ETags.
- Automatic camera backup of new photos and videos, with network and charging constraints.
- Large uploads use Nextcloud's chunked upload v2.
- Clear errors, such as a file that is locked on the server because it is open in a web editor.
- A small companion app in Material 3 Expressive with your profile picture, a storage ring, transfers, pinned files
  and camera backup settings.

## How syncing works

Aether is **on-demand access plus selective offline pinning**, not a two-way synced folder.

- Files are fetched when an app opens them and kept in a size-limited cache. Folder listings are cached briefly and
  refreshed in the background.
- Files and folders you pin are downloaded and kept current by comparing ETags, so they work offline.
- Edits made through the system file manager are uploaded when the file is closed.
- Camera backup is one-way: new photos and videos go to the server, and nothing comes back.

Unpinned files are not on your phone until you open them. See [ADR-0004](docs/adr/0004-hybrid-sync-offline-pinning-workmanager.md).

### Not planned

- **A two-way synced folder** that mirrors your whole Nextcloud into local storage (the Dropbox desktop model).
  Aether leaves file browsing to the system file manager and keeps local copies to what you pin, which keeps storage
  use and background work small. If you need a full mirror, use the official Nextcloud app.

## How it works

| Area | Approach |
| :-- | :-- |
| File access | `DocumentsProvider` backed by a Room metadata cache and an ETag-validated LRU disk cache |
| Auth | Login Flow v2 over Custom Tabs, credentials in `AccountManager` |
| Network | `OkHttp` and Android's `XmlPullParser` for WebDAV, with no WebDAV library |
| Background work | `WorkManager` for uploads, pinned sync and camera backup (`ContentUriTriggers`) |
| UI | Jetpack Compose, Material 3 Expressive, Dynamic Color |

Every significant decision is written up in [`docs/adr`](docs/adr/README.md).

## Requirements

- Android 10 (API 29) or newer
- A Nextcloud server you can reach over HTTPS

## Building

You need JDK 17 and the Android SDK (compile SDK 37). Create a `local.properties` pointing at the SDK if Android
Studio hasn't already (`sdk.dir=/path/to/Android/Sdk`).

```bash
./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lintDebug            # Android lint
```

### Release builds

`./gradlew assembleRelease` minifies with R8 and, when signing is configured, signs the APK. Without a key it
produces `app-release-unsigned.apk`. To sign, copy `keystore.properties.example` to `keystore.properties` and fill it
in, or set `AETHER_KEYSTORE_FILE`, `AETHER_KEYSTORE_PASSWORD`, `AETHER_KEY_ALIAS` and `AETHER_KEY_PASSWORD` (for CI).
Neither the properties file nor any `*.jks` is committed. Create an upload key with:

```bash
keytool -genkeypair -v -keystore aether-upload.jks -alias aether-upload -keyalg RSA -keysize 4096 -validity 10000
```

Upload `app/build/outputs/mapping/release/mapping.txt` to Play Console with each release so crash traces are readable.

Pull requests run the unit tests, lint (when production code or build files change) and a small instrumented smoke set; the full instrumented suite runs on `release/**` branches. The `CI gate` check summarises them and is the one to require in branch protection. See [ADR-0010](docs/adr/0010-tiered-ci-pr-gate-and-release-suite.md); to reuse the approach elsewhere, see [`docs/ci/tiered-ci-prompt.md`](docs/ci/tiered-ci-prompt.md).

## Privacy

Aether talks only to the Nextcloud server you sign in to: no analytics, no ads, no crash reporting, no third parties.
Read the [privacy policy](https://dougiewougie.github.io/aether/privacy/) and [ADR-0008](docs/adr/0008-local-data-storage-and-privacy.md).

## Contributing

Read [`CLAUDE.md`](CLAUDE.md) and the [ADRs](docs/adr/README.md) before changing the architecture. In short:
lean on Android platform components, keep WebDAV on `OkHttp` and `XmlPullParser`, and record significant changes
to auth, sync, the provider or the UI design in an ADR.

## License

Aether is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).

# ADR-0008: Local Data Storage and Privacy Posture

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

Aether keeps metadata and file content on the device so that the system file manager stays fast and pinned files work offline (ADR-0004). A review against the project's privacy-by-default aim asked what is stored, how it is protected, and what the app tells the server and the lock screen about the user.

## Decision

1. **Local data is not encrypted by the app.** Room, DataStore, cached and pinned files, staged uploads and thumbnails live in the app's private storage (`filesDir`, `cacheDir`, `databases/`). Other apps cannot read them, and Android's file-based encryption protects them at rest while the device is locked. Aether adds no second layer (such as SQLCipher), because the key would sit on the same device and the extra dependency and key management would buy little against the threats that FBE does not already cover.
2. **Credentials stay in `AccountManager`** (ADR-0003), never in these stores.
3. **Backups are off.** `android:allowBackup="false"`, so none of this leaves the device through Android backup or device transfer.
4. **The User-Agent no longer includes the device model.** It is `Aether (Android <SDK>)`. The server learns the app and the Android version, not which phone. Nextcloud uses the User-Agent as the device name in the user's list of app passwords, so each Aether login appears there as `Aether (Android <SDK>)`; users with several phones can tell them apart by the creation date.
5. **Notifications that name files or accounts are private.** They use `VISIBILITY_PRIVATE` with a public version that names neither (conflicts, sign-in required).
6. **Direct Share folder shortcuts can be turned off** from the account menu. Turning them off removes the published shortcuts; signing out of an account removes the shortcuts that pointed into it.
7. **The system Files roots list shows only the host** when there is one account, and the full account name only when several accounts need telling apart.
8. **No app lock and no `FLAG_SECURE` for now.** The companion app shows quota, transfer names and backup settings. Files themselves are opened in other apps through the system, where an Aether lock would not apply, so a lock would protect little and add a screen to every launch. Revisit if the dashboard starts showing file contents or previews.

## What is stored

| Where | What |
| :--- | :--- |
| Room `documents` | Remote path, name, MIME type, size, modified time, ETag, file id, permissions, pin flag, local cache path, per account |
| Room `transfers` | Remote path, direction, state, byte counts, error text, per account |
| Room `backed_up_media` | MediaStore id, remote path, size, modified time, album (bucket) id and name, per account |
| DataStore `settings` | Camera backup settings (account, remote folder, chosen album names, conditions), recent upload folder ids, accounts needing sign-in, Direct Share switch, sync constraints |
| Disk, per account | Cached copies (512 MB LRU), pinned copies, staged uploads, thumbnails (32 MB LRU) under a directory named by a hash of the account name |
| Avatar cache | The account's profile picture |

Signing out removes all of the above for that account (ADR-0003, implementation notes).

## Consequences

- **Positive:** No key management; a smaller dependency set; one clear statement of what is on the device.
- **Negative:** A device with file-based encryption defeated (unlocked device, rooted device, or a forensic image taken while unlocked) exposes file names, album names and cached content. Users who need more should rely on device encryption and a screen lock.
- **Negative:** App-password entries in Nextcloud lose the phone model.

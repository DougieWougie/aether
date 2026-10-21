# ADR-0003: Native `AccountManager` for Account & Credential Storage

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

After completing Nextcloud Login Flow v2, the app receives a `server` URL, `loginName`, and `appPassword`. We need a secure, OS-integrated mechanism to store credentials, associate them with system sync/storage providers, and handle token revocation or deletion cleanly.

## Decision

We will use Android's native **`AccountManager`** and implement a custom **`AbstractAccountAuthenticator`** (`AetherAccountAuthenticator`) registered via an `android.accounts.AccountAuthenticator` service (`res/xml/authenticator.xml`):

- **Account Type:** `com.aether.nextcloud.account` (or application package equivalent)
- **Account Name:** `<loginName>@<host>` (e.g., `alice@cloud.example.com`)
- **Password:** The Nextcloud `appPassword` stored via `AccountManager.addAccountExplicitly(account, appPassword, userdataBundle)`
- **UserData Bundle:** Stores `KEY_SERVER_URL`, `KEY_LOGIN_NAME`, `KEY_USER_DISPLAY_NAME`, and `KEY_WEBDAV_BASE_URL` (`/remote.php/dav/files/<loginName>/`).

When an account is removed from Android System Settings -> Accounts, `AccountManager` automatically notifies the app, and we will attempt a best-effort `DELETE /ocs/v2.php/core/apppassword` call (with header `OCS-APIRequest: true`) and purge local Room/file caches.

## Rationale

- **Native OS Visibility:** The Nextcloud account appears in Android's system *Settings -> Passwords & accounts*, where users expect cloud accounts to live.
- **OS-Managed Encryption:** `AccountManager` stores credentials in the system's encrypted credential database managed by the OS (`system_server`), avoiding custom key management boilerplate.
- **Seamless Integration with `DocumentsProvider` and `WorkManager`:** `AetherDocumentsProvider` can query `AccountManager.get(context).getAccountsByType(...)` directly to populate roots in `queryRoots()`, and respond to `AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION` by calling `DocumentsContract.buildRootsUri()` notification.

## Consequences

- **Positive:** Deep adherence to Android's native account architecture; built-in support if multi-account is enabled in the future.
- **Negative / Mitigation:** Requires boilerplate `AuthenticatorService` and `res/xml/authenticator.xml`. When the user taps "Add Account" from Android System Settings, our `AbstractAccountAuthenticator.addAccount()` returns an `Intent` launching Aether's Login Flow v2 screen with `AccountAuthenticatorResponse`.

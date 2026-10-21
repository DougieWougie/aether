# ADR-0002: Nextcloud Login Flow v2 via Custom Tabs

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

Nextcloud supports multiple authentication mechanisms, including legacy Basic Auth (username/password), WebView-based flow v1, and **Login Flow v2**. Users often configure Two-Factor Authentication (2FA), WebAuthn/Passkeys (FIDO2), or SSO (OIDC/SAML) on their Nextcloud instances.

Embedded `WebView` components are discouraged for authentication because they do not share browser sessions, block FIDO2/WebAuthn hardware/platform passkeys in many configurations, and expose the host app to raw credential handling.

## Decision

We will exclusively use **Nextcloud Login Flow v2** combined with AndroidX Browser **Custom Tabs** (`androidx.browser:browser`) and background HTTP polling:

1. Normalize the user-entered server URL (`https://<server>`), enforcing HTTPS by default.
2. Send `POST <server>/index.php/login/v2` with a descriptive `User-Agent` (e.g., `Aether Android`).
3. Parse the JSON response containing:
   - `poll.token`: The token used for polling.
   - `poll.endpoint`: The polling URL.
   - `login`: The browser URL to open.
4. Launch `login` in an `androidx.browser.customtabs.CustomTabsIntent` (configured with dynamic Material 3 color scheme parameters).
5. Start a coroutine in the login `ViewModel` (persisted across activity backgrounding/recreation via `SavedStateHandle`) that polls `POST <poll.endpoint>` with body `token=<poll.token>` every 2 seconds (handling HTTP `404` as "still waiting for user").
6. On HTTP `200 OK`, parse `server`, `loginName`, and `appPassword`, persist the account in `AccountManager`, and transition to the authenticated state.

## Rationale

- **Passkey & 2FA Support:** Custom Tabs use the user's default system browser, supporting WebAuthn/Passkeys, password managers, and existing SSO sessions seamlessly.
- **No Redirect URI / Deep Link Fragility:** Login Flow v2 uses client-side polling against the `poll.endpoint` token rather than custom URL schemes (`nc://`), eliminating intent-hijacking vectors and browser redirect quirks.
- **Revocable App Passwords:** Nextcloud generates a dedicated, device-specific `appPassword` that the user can revoke at any time in Nextcloud Security Settings.

## Consequences

- **Positive:** Zero `WebView` dependency or security surface; full support for all Nextcloud 2FA and SSO providers.
- **Negative / Mitigation:** Polling must survive process/activity lifecycle changes while the user switches to the Custom Tab or an authenticator app. We store `poll.endpoint` and `poll.token` in `SavedStateHandle` (or DataStore during pending auth) so polling resumes immediately when the user returns to Aether.

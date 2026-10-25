# ADR-0009: CI Test Scope — Logic on the Emulator, Interface Tests Locally

- **Status:** Accepted (amended by [ADR-0010](0010-tiered-ci-pr-gate-and-release-suite.md): which suite runs when)
- **Date:** 2026-10-24

## Context

The project has JVM unit tests, instrumented logic tests (provider, workers, repositories, migrations, view models) and instrumented Compose screen tests, all in `app/src/androidTest/.../ui/screens`. The screen tests drive the real `MainActivity` and stubbed system intents. On the CI emulator (API 34, `google_apis`, `x86_64`, default screen) they were unreliable in ways that said nothing about the app:

- The default screen is too small for the lazy dashboard to compose the camera settings.
- Compose reported pending recompositions while a sync was running.
- Stubbed `ACTION_VIEW` intents were not seen, and Espresso timed out waiting for window focus.

Fixes (waiting for the first hierarchy, tolerating idle timeouts, a larger AVD profile) reduced the failures from 9 to 3 but did not remove them. A required check that fails for environmental reasons trains people to ignore it.

## Decision

1. **CI runs** lint, all JVM unit tests, and the instrumented tests outside `com.aether.nextcloud.ui.screens`, using `-Pandroid.testInstrumentationRunnerArguments.notPackage=com.aether.nextcloud.ui.screens`.
2. **Interface (Compose screen) tests are not required in CI.** They stay in the repository and run locally with `./gradlew connectedDebugAndroidTest` on a developer's emulator.
3. **New interface tests go in `ui.screens`**; tests of logic that need an emulator go elsewhere so CI keeps running them.

## Consequences

- **Positive:** The required check reflects app logic and is stable.
- **Negative:** Screen regressions are not caught on push; they are found only when someone runs the full suite locally.
- **Revisit** if the screen tests can be made reliable on CI (a managed device, a fixed AVD profile, or a Robolectric/JVM Compose setup); the exclusion is one line in `.github/workflows/ci.yml`.

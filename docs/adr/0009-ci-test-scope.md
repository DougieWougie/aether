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

## Implementation notes: Robolectric on the JVM (2026-11-14)

The revisit condition above ("a Robolectric/JVM Compose setup") is met for the tests that do not need a real activity, MediaStore or a running WorkManager. Robolectric (4.18, Apache-2.0) is a `testImplementation` dependency.

- **What moved to the JVM** (`testDebugUnitTest`, so it runs in the PR gate's unit job with no emulator): `DocumentRepositoryTest`, `ShareUploadViewModelTest` and `ShareUploadViewModelEdgeCasesTest`, `MimeTypesTest` (including `MimeTypeMap` extension guessing), `PinnedSyncWorkerTest` and `UploadDocumentWorkerFailureTest` (via `TestListenableWorkerBuilder`), and the composable-only screen tests `DashboardSectionsTest`, `AccountMenuTest` and `MorphingHeroTest`. They run against the app's real Hilt graph (Room, `AccountManager`, WorkManager configuration, OkHttp) and the same `FakeNextcloud` as the device tests, which now lives in `app/src/sharedTest` and is compiled into both test source sets together with `AetherTestRule`, `Await` and `ComposeSupport`.
- **What stays on a device**, and why: the activity-driven screen tests (`AppFlowTest`, `DashboardActionsTest`, `CameraSettingsFlowTest`, `LoginWaitingTest`, `ShareSheetTest`: they launch `MainActivity` or `ShareUploadActivity` with stubbed system intents, the setup that was unreliable on the CI emulator); everything that needs the real MediaStore (camera backup, capture, media albums, `ContentUriUploadSource`); the SAF `ContentResolver`/Binder tests, `AccountManager` binder service tests, Room migrations, and end-to-end WorkManager scheduler tests (`UploadDocumentWorkerTest`, `RetryFailedNotificationEndToEndTest`, `ShareUploadEndToEndTest`). The `ui.screens` exclusion in CI therefore stays for the activity screen tests.
- **Settings** (`app/src/test/resources/robolectric.properties`): API 35, because Robolectric's API 36 sandbox needs a Java 21 runtime and the build and CI use 17; a phone-sized screen (`w411dp-h891dp`), since the default 320x470dp is too small for the lazy lists, the same problem as the small CI emulator; and native graphics mode, which real image decoding (`ThumbnailUtils`) and Compose need.
- **Threads.** Under Robolectric the test thread is the main thread, where Room refuses the synchronous reads these tests make. `AetherTestRule` therefore runs the test body on a worker thread when it finds itself on the main thread, and `RobolectricAetherRule` keeps running the main looper meanwhile, so view models and `Dispatchers.Main` still work.
- **Coverage.** The JaCoCo agent needs `includeNoLocationClasses` for classes loaded by Robolectric's class loader; it is set for all test tasks, and the combined `coverageReport` counts the Robolectric tests.
- **Cost.** Robolectric downloads its `android-all` jar from Maven Central on first use (cached afterwards), and the JVM unit run grows from a few seconds of plain tests to about two minutes including the Robolectric ones. Test classes using it have to be kept off-device-only APIs: no `UiAutomation`, no MediaStore, no WorkManager execution.

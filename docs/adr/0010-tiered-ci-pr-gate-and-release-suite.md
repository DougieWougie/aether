# ADR-0010: Tiered CI — Fast Pull Request Gate, Full Suite on Release Branches

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Every push to a pull request and to `main` ran lint, the JVM unit tests and the whole instrumented logic suite. The emulator job took about 9.5 minutes of a 6–11 minute run, lint another 3, and the JVM tests 25 seconds. Slow feedback on every push is the main cost; most of that time protects against regressions that are only relevant when a release is cut.

## Decision

1. **Pull request gate** (`ci.yml`), all jobs in parallel:
   - **Unit tests** (`testDebugUnitTest`) always.
   - **Lint** (`lintDebug`) only when the change touches `app/src/main`, `app/src/debug`, a `*.gradle.kts` file, `gradle/`, `gradle.properties`, a lint config or `.github/`. A skipped job counts as passing for a required check.
   - **Instrumented smoke tests**: only classes annotated `@Smoke` (`support/Smoke.kt`), selected with `-Pandroid.testInstrumentationRunnerArguments.annotation=com.aether.nextcloud.support.Smoke`. The set is the Room migration test, the SAF provider test and the upload worker test: the areas a JVM test cannot cover and where a regression is costliest. They are skipped when the change touches only the Compose UI (`ui/` sources, `values`/`drawable`/`mipmap-anydpi` resources, the `ui/screens` tests), the docs or other files outside the app and build; anything else under `app/src/main`, `app/src/debug`, `app/src/androidTest`, `app/schemas`, the Gradle files or `.github/` runs them.
   - **CI gate**: a final job that fails unless every job above succeeded, accepting a skip only where one is expected (lint or smoke when `changes` says it is not needed, smoke outside pull requests). It is the only check the branch rulesets require. Requiring the jobs individually would let a failed `changes` job skip `Lint`, which reports success.
2. **Push to `main`** runs lint and unit tests only. The smoke tests already passed on the pull request.
3. **Release branches** (`release/**`, plus manual dispatch) run `release.yml`: lint, unit tests, the full instrumented logic suite and the combined coverage report.
4. **Compose screen tests stay out of CI**, as in [ADR-0009](0009-ci-test-scope.md); `release.yml` keeps the `notPackage=...ui.screens` exclusion. Run them locally before cutting a release branch.
5. **Tagging is by annotation, not package.** Adding a test to the smoke set means adding `@Smoke`; the smoke set should stay small (aim for under 3 minutes on the emulator, excluding boot).

## Consequences

- **Positive:** Typical pull requests drop to the longest of unit tests, lint (when relevant) and a smoke run, with no full-suite emulator wait. `main` runs no emulator job.
- **Negative:** An instrumented regression outside the smoke set is found when a release branch is pushed, not on the pull request. Release branches must therefore be cut early enough to fix what they find.
- **Follow-up (not done here):** Move instrumented tests that need no device (mime guessing, repositories, view models, `SyncScheduler`) to Robolectric or the JVM so the gate covers more without an emulator.
- **Revisit** the smoke set if a post-merge regression escapes it; add `@Smoke` to the class that would have caught it.

## Implementation notes: pinned actions, release checks and API matrix (2026-11-14)

- **Actions are pinned to commit SHAs**, each with its release as a trailing comment (`# v7.0.1`), because a tag can be moved to different code. `.github/dependabot.yml` updates the `github-actions` and `gradle` ecosystems weekly; Dependabot rewrites the SHA and the comment together, and each update goes through the normal CI gate.
- **The release job builds the release variant.** `Lint and unit tests` in `release.yml` also runs `assembleRelease`. Without keystore secrets that produces an unsigned APK, which is enough to exercise R8 and resource shrinking, the part of the build that no debug-only check covers.
- **The full instrumented job runs on API 29, 34 and 36** (`minSdk`, the level the PR gate uses, and `targetSdk`), with `fail-fast: false` so one level's failure does not hide the others, and a report artifact per level. The PR gate is unchanged: smoke tests on API 34 only.
- **Fixed sleeps removed from the instrumented tests.** The 1.1 s waits existed because MediaStore stamps items in whole seconds and camera backup only takes items added after the "since" stamp; the tests now set that stamp (a few seconds back, or an item's own `DATE_ADDED` to exclude it). The remaining waits poll for an observable state (a failed transfer row, no staging file left, no queued work) with `awaitTrue`. The only `Thread.sleep` calls left are the polling interval inside `awaitTrue` and `FakeNextcloud`'s deliberate PUT delay.

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

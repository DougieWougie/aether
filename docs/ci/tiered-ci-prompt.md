# Prompt: split CI into a fast pull request gate and a release suite

A project-agnostic prompt for Claude Code (or any coding agent). Paste it into a repository whose CI is too slow. Replace nothing; the agent discovers the specifics. Written from the Aether work recorded in [ADR-0010](../adr/0010-tiered-ci-pr-gate-and-release-suite.md).

---

```markdown
CI on this repository is too slow for day-to-day development. Redesign it as tiers: a fast gate that
decides whether a pull request can merge, and a thorough suite that runs when a release is being cut.
Start by measuring, then propose, then implement on a new branch. Do not change application code.

## 1. Measure before proposing
- Read the CI configuration and list every job and step.
- Pull timings for the last ~10 runs (for GitHub: `gh run list`, then `gh run view <id> --json jobs`).
  Report per-step durations. Identify the critical path: jobs run in parallel, so only the slowest matters.
- Count the tests by kind (unit / integration / UI / end-to-end) and note which need a device,
  emulator, browser, container or network.
- Record how the project is built and tested locally (README, build files) and which test
  runner and filtering mechanisms it supports (tags, categories, annotations, packages, markers).
- Read any existing ADRs / decision records on CI or testing; do not contradict them silently.

## 2. Propose three tiers
Tier 1, PR gate. Goal: feedback in a few minutes, no flakiness, no heavy infrastructure.
  - Static analysis, type checks and fast deterministic tests (logic, no UI, no real devices).
  - Run jobs in parallel, not in series.
  - Make expensive checks conditional on the paths that can affect them (e.g. lint only when production
    code or build files change; skip everything for docs-only changes). A *skipped* job counts as passing for
    a required check, so this does not block merges; do not use workflow-level `paths:` filters on
    required checks, because a workflow that never starts leaves the check pending forever.
  - Include a small SMOKE subset of slow tests (heavy infrastructure) covering what fast tests cannot:
    schema migrations, the main integration boundary with the OS/database/network, the core write path.
    Select it by an explicit tag or annotation on the test, not by directory, so moving a file cannot
    silently remove it from the gate. Keep it to a few minutes; if it grows, trim it.
Tier 2, mainline. Goal: confirm the merge result without repeating work.
  - Re-run the fast tier on the merge result. Do not repeat the smoke subset; it already passed on the PR.
Tier 3, release candidate. Goal: everything, before shipping.
  - Triggered by a push to `release/**` branches and by manual dispatch (ask me if I want tags, a
    schedule, or both).
  - Full lint, full unit and integration suites, the slow/device suite, coverage reports, anything
    expensive. Upload reports as artifacts.
  - Tests known to be unreliable in CI stay out until fixed, and the exclusion is written down.

## 3. Ask me before implementing
Ask only what you cannot decide from the repo: what counts as a release candidate, whether tier 1 may drop
the slow suite entirely or keep a smoke subset, which tests are in the smoke set if it is unclear,
whether main should run the slow suite, and which tests are known-flaky. Give a recommended default
with each question.

## 4. Implement
- Create a feature branch. Keep application code unchanged; the only code edits are the tags/annotations.
- Put duplicated environment setup (emulator host prep, service containers) in a reusable unit
  (composite action, reusable workflow, or a shared script) rather than copying it.
- Keep caching and concurrency settings: cancel superseded PR runs, never cancel mainline runs.
- Add path-based gating through a small job that computes an output, then `if:` on the dependent job.
  For pull requests the checkout is the merge commit, so `git diff HEAD^1 HEAD` with `fetch-depth: 2`
  gives the changed files without an API call or a third-party action.
- Add a "how to add a test to the smoke set" note where contributors will find it.
- Write or update an ADR: context (with the measured timings), decision (what runs where), consequences
  (the regression window you are accepting and the follow-ups). Update the ADR index and README.

## 5. Verify and report
- Validate workflow syntax (e.g. `actionlint`) and run the local equivalents of each tier's commands.
- Push the branch and confirm from a real run that: the gate jobs run in parallel, a docs-only PR skips the
  conditional jobs, only tagged smoke tests run, and the release workflow starts on a `release/**` push.
- Report before/after durations for the critical path, what is now caught later than before, and the
  follow-up opportunities (e.g. moving device-only tests onto the JVM or an in-process simulator,
  sharding, build caching). Do not do those follow-ups in this change.
```

---

## Notes on the approach

- **Where the savings come from:** removing the slowest job (usually the emulator or browser job) from the
  critical path of every push, not from making each test faster.
- **The trade you accept:** a regression outside the smoke set surfaces on the release branch instead of the
  PR. Cut release branches early enough to fix what they find, and add `@Smoke`-style tags to whichever class
  would have caught an escaped bug.
- **Tag, don't locate:** annotation- or marker-based selection (JUnit categories, pytest markers, Jest
  projects, Go build tags) survives refactors; package- or directory-based exclusion does not.
- **Required checks and skipped jobs:** configure branch protection on job names that always exist. Job-level
  `if:` skips report success; a workflow-level `paths:` filter that prevents the workflow from starting does not.

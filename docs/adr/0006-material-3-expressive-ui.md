# ADR-0006: Material 3 Expressive Design System in Jetpack Compose

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

The user requested that Aether use **Android Expressive** (Material 3 Expressive), Google's latest evolution of Material Design for Android (introduced with Android 16 / Material 3 Expressive Compose libraries). Material 3 Expressive emphasizes spring-based physics motion, rich shape morphing, expressive typography scales, vibrant dynamic color contrast, and new expressive components (`LoadingIndicator`, `FloatingToolbar`, `ButtonGroup`, `SplitButton`, `MediumFlexibleTopAppBar` / `LargeFlexibleTopAppBar`, `ContainedLoadingIndicator`).

## Decision

We will build all UI screens in **Jetpack Compose** using the **Material 3 Expressive** library (`androidx.compose.material3:material3` 1.4.0-alpha / 1.5.0+ expressive APIs and `androidx.compose.material3:material3-adaptive-navigation-suite`) with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`:

1. **`MaterialExpressiveTheme` & `MotionScheme.expressive()`:**
   - Wrap the application in `MaterialExpressiveTheme` (or `MaterialTheme` configured with `MotionScheme.expressive()`), ensuring all spatial and effects animations use spring physics (`motionScheme.defaultSpatialSpec()`, `fastSpatialSpec()`, `slowSpatialSpec()`, `defaultEffectsSpec()`) rather than legacy cubic-bezier tween curves.
2. **Dynamic Color & Expressive Color Roles:**
   - Default to Android Dynamic Color (`dynamicDarkColorScheme` / `dynamicLightColorScheme`) on Android 12+, with a curated Nextcloud-inspired Expressive seed palette fallback.
   - Utilize container hierarchy (`surfaceContainerLowest` through `surfaceContainerHighest`, `primaryContainer`, `tertiaryContainer`) to create visual depth and grouping without heavy divider lines.
3. **Expressive Typography & Shapes:**
   - Use variable font weights and emphasized typography tokens (`TitleLargeEmphasized`, `HeadlineMediumEmphasized`, `LabelLargeEmphasized`) for key metrics (storage quota, sync status, server title).
   - Use `MaterialShapes` and shape morphing (`Morph` via `androidx.graphics:graphics-shapes`) for interactive states, sync status badges, and the `LoadingIndicator` / `ContainedLoadingIndicator`.
4. **Expressive Component Usage:**
   - **Onboarding & Login v2 Screen:** Animated expressive hero illustration using morphing `MaterialShapes`, clean server URL input with protocol validation, and a `ContainedLoadingIndicator` while waiting for Login Flow v2 Custom Tab completion.
   - **Sync & Storage Dashboard:** Expressive storage ring/progress bar, segmented `ButtonGroup` / `ToggleButton` for quick filter toggles (Active Transfers, Pinned Offline, Camera Backup), and `LargeFlexibleTopAppBar`.
   - **Quick Share Upload Sheet:** Bottom sheet (`ModalBottomSheet`) or dedicated expressive dialog activity when sharing files from other apps (`ACTION_SEND`), featuring destination folder chips, upload progress with morphing indicators, and spring-animated completion states.

## Rationale

- **Modern Android Feel:** Aligns Aether with Android 16's system UI and Google's first-party apps.
- **High Scannability for Companion App:** Because Aether's UI is a focused companion dashboard (with file management delegated to SAF), Material 3 Expressive makes status indicators, sync progress, and storage metrics tactile, lively, and immediately legible.

## Consequences

- **Positive:** Cohesive, state-of-the-art Android UI with spring physics and expressive components out of the box.
- **Negative / Mitigation:** Requires opting into `@ExperimentalMaterial3ExpressiveApi` and including `androidx.graphics:graphics-shapes` in Gradle dependencies.

## Implementation notes: expressive redesign (2026-10-21)

The first pass used expressive components inside a stock layout. The redesign makes the layout itself expressive:

- **Colour-block heroes.** Storage (primary container) and camera backup (tertiary container) are large cards with an asymmetric shape (`HeroShape`/`HeroShapeAlt`: three 48 dp corners, one 12 dp), oversized `displayLargeEmphasized` numbers and a `CircularWavyProgressIndicator` ring. Login uses a full-width primary-container panel with only its bottom corners rounded.
- **`MorphingShapeBadge`** (`ui/components/Expressive.kt`): a `MaterialShapes` badge that morphs to a new polygon with `defaultSpatialSpec()` when state changes (idle Cookie9Sided, syncing Sunny and rotating, error Pentagon) and rotates only while work is in flight. It replaces plain icons as the status signal on the dashboard, transfer rows, camera backup and the share sheet.
- **Connected lists.** Transfers and pinned items use `groupedShape`: 28 dp outer corners, 6 dp inner corners, 2 dp gaps.
- **Navigation.** `HorizontalFloatingToolbar` (vibrant colours) replaces the tab selector and top app bar on the dashboard; the selected tab expands to show its label via `animateContentSize(fastSpatialSpec())`. "Open in System Files" is the toolbar's own FAB.
- **Buttons.** Main actions use `ButtonDefaults.shapes()` (round-to-square press morph) and `ButtonDefaults.LargeContainerHeight`/`MediumContainerHeight`. Camera options are a connected `ToggleButton` group instead of switch rows.
- **Typography.** Display and headline `*Emphasized` styles are heavier (ExtraBold/Bold) so key numbers read as graphics.
- Dynamic Color remains the default, with the fixed palette as fallback below Android 12.

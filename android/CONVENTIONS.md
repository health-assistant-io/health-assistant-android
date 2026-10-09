# CONVENTIONS.md — app/android + app/shared

## Style
- **ktlint is the source of truth** for formatting. Run `./gradlew :app:ktlintFormat`
  before committing; `./gradlew :app:ktlintCheck` must be green (it is part of
  `./gradlew build`). No wildcard imports, lexicographic import order, final newline.
- Kotlin idiomatic; prefer `val`, expression bodies, scoped functions only when
  they aid readability.
- Minimal comments — KDoc on public API only. Do not leave commented-out code.
- Follow what already exists; when in doubt, match the neighboring file.

## Naming / packages
- App: `io.healthassistant.android` (+ sub-packages: `.di`, `.source`,
  `.settings`, `.monitoring`, `.work`, `.webview`, `.ui`, `.ui.navigation`,
  `.ui.components`, `.ui.theme`).
- Shared core: `io.healthassistant.shared` (+ `.sync`, `.healthconnect`,
  `.source`, `.onboarding`, `.data`).
- Bridge SDK (in `core/.../kotlin-sdk/`): `io.healthassistant.bridge`.

## UI / navigation
- **Screen + Route pattern:** each destination is a pure `FooScreen`
  (state + lambdas, no collection — Compose-testable) + a stateful `FooRoute`
  (collects flows, owns coroutines, wires lambdas). Home/Insights/Records/Profile
  are tabs; Settings/Sync are detail screens.
- **Navigation:** Jetpack `NavHost` in `AppRoot`. Top-level tabs live in
  `ui/navigation/HATab` (shown in the bottom bar via `HABottomBar`); detail
  routes live in `ui/navigation/HARoutes`. Tab taps use
  `popUpTo(startDestination) + launchSingleTop` — **do not** add
  `saveState`/`restoreState` (it breaks navigation back to the start tab).
- **Design system:** colors/typography/shapes/spacing come from
  `ui/theme/` tokens (`MaterialTheme.spacing`, `HATheme`). No hard-coded `dp`,
  `Color`, or font literals in screens; no hard-coded user-facing strings
  (use `res/values/strings.xml` via `stringResource`).
- **UI redesign track:** shipped in R-phases (R1–R8, see
  the internal (gitignored) `dev/plans/UI-REDESIGN-PLAN.md`); plain language for all user-facing copy.

## Tests
- **Test-first.** Every new module lands with at least one JVM unit test.
- JVM unit tests go in `src/test/`; instrumented (device) tests in
  `src/androidTest/`. Pure logic stays JVM-testable (no Android deps) so the
  fast `testDebugUnitTest` loop covers it; only Health Connect / WebView /
  WorkManager glue needs a device.
- Compose UI tests use **`createComposeRule()`** + `setContent` + a fake state
  object (NOT `createAndroidComposeRule<MainActivity>` — the activity already
  sets content and throws). The debug variant registers the host activity via
  `debugImplementation(ui-test-manifest)`.
- A phase is "done" only when its full test gate (see AGENTS.md) is green.

## Versions
- The version catalog `gradle/libs.versions.toml` is the single source for all
  versions. Do not hardcode versions in module `build.gradle.kts` — add them to
  the catalog and reference via `libs.*`.
- Bumping a toolchain version (AGP/Kotlin/Gradle) is a Phase-0-class change:
  re-validate `./gradlew build` and update AGENTS.md.

## Commits
- `app/` is its own git repo (separate from `core/`). Mobile-app code commits
  here; `kotlin-sdk` and backend changes commit in `core/`.
- Build must be green before commit (`./gradlew build`).
- Keep commits scoped to one phase / one concern.

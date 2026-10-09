# Contributing to the Health Assistant Android App

Thank you for contributing! This guide covers the dev setup, the phased delivery
model, how to run tests, and how to add features.

## Prerequisites

- **Android Studio** (latest stable — Koala/Ladybug). Bundles the Android SDK +
  a JDK + the SDK/AVD managers + Gradle sync. On Linux, enable **KVM**
  (`/dev/kvm`, add your user to the `kvm` group) for the emulator.
- **JDK 25** on the host (`/usr/lib/jvm/java-25-openjdk-amd64`). The host's
  `openjdk-21` install may be corrupted — use 25 for the Gradle launcher.
- **Android SDK** at `~/Android/Sdk` (shared between Android Studio + opencode).
- A reachable **Health Assistant** instance with a bridge integration instance
  bound to a patient (configure it in the web UI).

## First build

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk
cd app/android
./gradlew build    # compile + unit tests + ktlint + Android Lint (all modules)
```

If this is green, the entire toolchain works. See
[Troubleshooting](./TROUBLESHOOTING.md) for the common failure modes.

## Project structure

```
app/
├── shared/     # KMP shared core (sync engine, mapper, onboarding, reads)
└── android/    # Gradle composite root: :app (Compose) + includeBuild("../shared")
    └── app/    # the Compose app + Health Connect + WorkManager + WebView host
```

The Kotlin bridge SDK lives in the **sibling `core/ repo** at
`integrations/health_assistant_bridge/kotlin-sdk/` and is consumed via Gradle
composite `includeBuild`. Both repos must be checked out under the same
`Health-Assistant/` parent.

## Running the app

```bash
./gradlew :app:installDebug     # install on a connected device
adb shell am start -n io.healthassistant.android/.MainActivity
```

Open the app → scan the QR (or paste the connection code) from your server's
bridge instance detail screen → **Connect**.

## Testing

Four layers (see [the mobile skill](../../core/.opencode/skills/mobile/SKILL.md)
§2 for the full matrix):

```bash
./gradlew :app:testDebugUnitTest         # JVM unit tests (fast loop)
./gradlew :shared:test :kotlin-sdk:test  # shared core + SDK (incl. HMAC parity)
./gradlew :app:ktlintCheck               # lint gate
./gradlew :app:connectedDebugAndroidTest # instrumented/Compose UI tests (device)
```

**The HMAC parity test** (`SigningParityTest`) is the headline correctness gate
— it asserts the Kotlin `signRequest` matches the Python `sign_request`
byte-for-byte. If you touch `Signing.kt`, regenerate the golden vectors.

**Compose UI tests** use `createComposeRule()` + `setContent { … }` per screen.
On MIUI/POCO devices the instrumented run needs Developer Options →
**Background activity launch** (or the MIUI-optimization toggle) enabled, and
the `*.test` APK install needs **Install via USB**; otherwise
`connectedDebugAndroidTest` hangs or fails. See TROUBLESHOOTING.md.

## How to add features

| Task | Where | Recipe |
|---|---|---|
| Add a tab screen | `app/android/app/.../ui/` | Add a pure `FooScreen.kt` + a stateful `FooRoute.kt`; register the route in `AppRoot`'s `NavHost` (and a tab in `navigation/HATab` if it's a top-level destination). |
| Add a reusable component | `app/android/app/.../ui/components/` | `MetricCard`, `HcIcon`, `InsightsChart`, `ManualEntryCard` live here — pure, reusable, no screen-specific state. |
| Add a detail screen (with back button) | `app/android/app/.../ui/` | A full-`Scaffold` screen reached from a tab (e.g. Settings, Sync); add a route in `navigation/HARoutes` and a `composable(...)` in `AppRoot`. |
| Add a bridge read path | `core/.../provider.py` + `app/shared/data/` | Filter by `_bound_patient_id`! Add a typed reader in `BridgeReads` (e.g. `listObservations`). |
| Add an outbox item type | `app/shared/sync/SyncItems.kt` | Build the `OutboxItem`, enqueue into `SqliteOutboxStore`. |
| Change the Kotlin SDK | `core/.../kotlin-sdk/` | Mirror python-sdk; update models + signing; bump version in 3 places. |

**UI test pattern:** Compose screens are tested in isolation with
`createComposeRule()` (the app's debug variant registers the host activity via
`debugImplementation("androidx.compose.ui:ui-test-manifest")`) + the
composable's own `setContent { … }` + a fake state object — never via
`AppRoot`/`MainActivity` (its credential-driven nav makes it non-deterministic).

Full recipes in the mobile skill §12.

## Commit conventions

- **Two git repos**: app code (`app/android`, `app/shared`) commits in `app/`;
  Kotlin-SDK + backend + the mobile skill (`core/.opencode/skills/mobile/`)
  commit in `core/`. A cross-repo change = two commits (one per repo) in the
  same session.
- **ktlint before commit**: `./gradlew :app:ktlintFormat` then verify
  `./gradlew :app:ktlintCheck` is green.
- **Build green before commit**: `./gradlew build`.
- **Version coordination**: when bumping the SDK or shared version, bump it in
  `build.gradle.kts` AND the consumer's dependency AND (for the SDK)
  `manifest.json` `sdks.kotlin` — together.
- **Never push by default** — local commits only; push when explicitly asked.

## CI & the sibling core/ checkout

The Gradle build is a **composite**: `shared/` lives here, but the bridge
Kotlin SDK is included from `../../core` (the two-repo model — see
[architecture.md](./architecture.md)). CI (`ci.yml`, `release.yml`) clones
the public [health-assistant](https://github.com/health-assistant-io/health-assistant)
repo to the sibling path so the default resolves unchanged; other layouts
can point `HA_KOTLIN_SDK_DIR` at the SDK instead. Release APKs: `release.yml`
signs with repo secrets (`KEYSTORE_BASE64` + password/alias secrets) when
configured, else falls back to debug signing (see the workflow).

## Releases

Tag-driven, via the family-unified version manager:

1. Changelog-first: user-visible changes get a `CHANGELOG.md` `[Unreleased]`
   entry **in the same commit** as the change. At release time the section
   is renamed to `## [vX.Y.Z] - DATE` and a fresh `[Unreleased]` goes on top.
2. Run the full release gate: `./gradlew build :shared:test :kotlin-sdk:test
   :app:assembleRelease` (+ the on-device `AppModuleResolutionTest` when DI
   changed).
3. `python3 scripts/version_manager.py bump {patch|minor|major} --git` —
   rewrites `versionName`, stages the changelog, commits
   `chore(release): bump version to X.Y.Z`, tags `vX.Y.Z`.
   Bump `versionCode` by hand in the same release commit (noted in
   `version_manager.toml`).
4. Local stop point — publishing is a separate, explicit `--push`.


## The phased delivery model

The app is delivered in verifiable slices (full history in
[STATUS.md](../STATUS.md) and the changelog):

1. **Phases 0–9** — scaffold, Kotlin SDK, sync engine, Health Connect,
   bridge read paths, WorkManager, onboarding, UI, polish.
2. **UI redesign R1–R8** — design system, bottom-nav, Home/Records/Profile,
   onboarding, Simple mode, a11y.
3. **v2** — documents fix, security hardening (R8, SQLCipher), app-lock,
   ViewModel refactor, Today (now merged into Home), Records mutations,
   notification channels + inbox, 13 Health Connect types, pull sync,
   native clinical records, medication reminders, Simple/Advanced mode,
   Baseline Profile.
4. **v3 (offline-first + viz)** — connection-scoped caches + staleness UX,
   stats/trend charts, local alerts, Glance widgets, wellness overview,
   polish & a11y sweep. Shipped as **v1.2.0**.

Each phase has a concrete test gate: `./gradlew build` **+**
`:shared:test :kotlin-sdk:test` green before commit (composite builds are
not wired into the root `build` task). **One phase per opencode session.**
See [architecture.md](./architecture.md) for the full module map.
Implementation plans live in the repo's gitignored `dev/` directory.

### Adding a new screen (Phase D pattern)

1. Add `FooViewModel(client, ...) : ViewModel()` in `ui/FooViewModel.kt` with
   `val state: StateFlow<FooUiState>` + a `companion object { fun factory(...) =
   viewModelFactory { initializer { ... } } }`.
2. Add `FooScreen(state: FooUiState, onAction1: ..., ...)` — pure state + lambdas.
3. Add `FooRoute(client: BridgeClient, ...)` — thin shim: `viewModel(factory=...)`
   + `collectAsStateWithLifecycle()` + one-liner delegates.
4. Add a `HATab` entry (if top-level) or a `HARoutes` route (if detail) + wire
   in `AppRoot.kt`'s `NavHost`.
5. Run `:app:ktlintFormat` before commit.

### Adding a Health Connect type

Add the enum entry to `HcType` + the manifest permission + the
`HealthConnectSource.readType` branch + the `HealthConnectPermissions` mapping +
the `HcIcon` icon. The Settings toggle + Home/Insights/Today pick it up automatically.

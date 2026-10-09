# Health Assistant — Android App

[![CI](https://github.com/health-assistant-io/health-assistant-android/actions/workflows/ci.yml/badge.svg)](https://github.com/health-assistant-io/health-assistant-android/actions/workflows/ci.yml)
[![gitleaks](https://github.com/health-assistant-io/health-assistant-android/actions/workflows/gitleaks.yml/badge.svg)](https://github.com/health-assistant-io/health-assistant-android/actions/workflows/gitleaks.yml)
[![Release](https://img.shields.io/github/v/release/health-assistant-io/health-assistant-android?display_name=tag&sort=semver)](https://github.com/health-assistant-io/health-assistant-android/releases)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A first-class, **offline-first** companion app for a self-hosted
[Health Assistant](https://github.com/health-assistant-io/health-assistant) instance.
It reads on-device health data (Android **Health Connect**), pushes it through
the **Health Assistant Bridge** integration, and lets you view biomarkers,
examinations, and documents — all through a single, patient-scoped connection
identity. Open source (Apache-2.0), privacy-first, no third-party cloud.

> **Status: Beta — in active development.** The connection, background-sync,
> examinations, and native biomarker-chart flows are implemented and verified
> on-device; rich web surfaces (knowledge graph, analytics, AI assistant) open
> in the browser via Custom Tabs. See *Project status* below.

---

## How it connects

The app treats **one Bridge integration instance as its single connection
identity** (the same model a Home Assistant companion app uses for its webhook):
one base URL, one instance UUID, one optional HMAC secret — bound to **one
patient** on the server. The app never holds the user's login token; the blast
radius of a leaked credential is one patient, scoped to the bridge's paths.

Everything — metric push *and* reads *and* examination/document management —
flows through the Bridge's two-way API proxy. Onboard by entering the instance
details (or scanning a QR / tapping **Open in app** from the server's bridge
screen); the app probes `GET /status`, stores the credential in
`EncryptedSharedPreferences` (Keystore-backed), and enters the app.

See the bridge contract:
[`core/integrations/health_assistant_bridge/docs/`](../core/integrations/health_assistant_bridge/docs/).

---

## Architecture

> **Full technical reference:** [`docs/dev/architecture.md`](docs/dev/architecture.md) —
> module map, sync engine, security model, bridge contract, Health Connect types,
> notification system, the Kotlin SDK, testing layers.

```
Health-Assistant/
├── core/                                      # the backend + bridge integration + Kotlin SDK
│   └── integrations/health_assistant_bridge/kotlin-sdk/   # io.healthassistant:kotlin-sdk (HMAC client, v0.4.0)
└── app/                                       # ← this repo (sibling of core/)
    ├── shared/                                # KMP shared core (pure Kotlin, JVM-tested)
    │   ├── sync/                              #   outbox + state machine + SyncCoordinator + HealthSyncPipeline
    │   ├── healthconnect/                     #   HcType (13 types) + RawSample + HealthConnectMapper
    │   ├── source/                            #   HealthDataSource plugin contract + ManualEntrySource
    │   ├── onboarding/                        #   QR / deep-link / manual credential parsers
    │   └── data/                              #   typed bridge reads + DocumentSummary + cache interface
    └── android/                               # Gradle composite root (Jetpack Compose app)
        └── app/                               #   UI, Health Connect, WorkManager, secure storage
            ├── ui/                            #     4-tab bottom nav: Home / Today / Records / Profile
            │   ├── HomeVM + RecordsVM + ExamDetailVM + TodayVM + InboxVM  (Phase D ViewModel pattern)
            │   ├── AppLockRoute + AppLockManager   (Phase B.4: BiometricPrompt + PIN + 60s grace)
            │   ├── DocumentOpener              (Phase A: FileProvider + remembered viewer per MIME)
            │   └── theme/                      #     brand teal palette, typography, shapes
            ├── source/                        #     HealthConnectSource (13 HC types) + SourceRegistry
            ├── data/                          #     CredentialStore, AppLockManager, Room cache, SqliteOutboxStore
            ├── work/                          #     SyncWorker + scheduler + notifications
            └── web/                           #     WebAppLauncher (Custom Tabs hand-off to the PWA)
```

**The heart is an outbox-based sync engine** (`shared/sync`): every local
event is persisted to a SQLite outbox *and* enqueued atomically; a
`SyncCoordinator` drains it in bounded 1000-record batches, groups `/sync`
records into one payload, signs each request (HMAC), retries with
full-jitter backoff, and dead-letters permanent failures.

**Health Connect → outbox → bridge** (`source/` + `HealthSyncPipeline`): a
pluggable `HealthDataSource` reads on-device data (13 HC types). The
`HealthSyncPipeline` maps each reading to a bridge `ClientRecord` and
enqueues a `/sync` item; the `SyncWorker` runs a read-then-drain **loop**
(chained passes, live progress), cursors resume from the last read.

**5 native tabs + detail routes:** Home (dashboard), **Today** (daily
summary: vitals + meds + exams + inbox), Records › Biomarkers (native Vico charts),
Records (exam list + FAB → new exam / add reading), Profile (connection +
sync settings + app-lock + about). Detail routes: exam detail (native doc
list + upload + delete + re-extract + remembered viewer), inbox, sync.

**App-lock (Phase B.4):** BiometricPrompt (face/fingerprint) + 4-digit PIN
fallback, 60s grace window, `ProcessLifecycleOwner` observer.

**Security:** R8 on in release (7.5 MB APK), `allowBackup=false`,
`network_security_config.xml` (cleartext denied), app-lock gate on the
whole UI, credentials in EncryptedSharedPreferences.

> Developer deep-dive on the UI: [`docs/dev/ui-patterns.md`](docs/dev/ui-patterns.md) — design system,
> navigation model, screen/route pattern, i18n, UI tests.

---

## Install

Grab the APK from the
[releases page](https://github.com/health-assistant-io/health-assistant-android/releases) —
[`health-assistant-android-latest.apk`](https://github.com/health-assistant-io/health-assistant-android/releases/latest/download/health-assistant-android-latest.apk)
always points at the newest one. Sideload it (enable *Install unknown apps*
for your browser/file manager), then follow the
[getting-started guide](docs/user/getting-started.md) to connect your
instance. Android 9 (API 28) or newer.

## Build & run

### Prerequisites
- **Android Studio** (latest stable) with the Android SDK — the project uses
  AGP 9.3.1 / Gradle 9.5 / Kotlin 2.3.20. On Linux, enable **KVM** for the emulator.
- A **JDK** — the Gradle daemon runs on JDK 25 (`/usr/lib/jvm/java-25-openjdk-amd64`);
  set `JAVA_HOME` accordingly.
- A reachable **Health Assistant** instance with a Bridge integration instance
  bound to a patient (configure it in the web UI).

### First build
```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk
cd app/android
./gradlew build                 # compile + unit tests + ktlint + Android Lint (all modules)
```

### Run on a device/emulator
```bash
./gradlew :app:installDebug     # install the debug APK on a connected device
adb shell am start -n io.healthassistant.android/.MainActivity
```
Open the app, enter your server URL + instance ID (+ API secret if set), and
tap **Connect**.

> **MIUI / Xiaomi devices:** enable **Install via USB** and **USB debugging
> (Security settings)** in Developer Options, or installs are blocked with
> `INSTALL_FAILED_USER_RESTRICTED`. Pin one device with `ANDROID_SERIAL` when
> running `connectedDebugAndroidTest` (a device on both USB + wireless shows as
> two serials).

### Baseline Profile (cold-start optimization)

The release APK ships a **Baseline Profile** (`assets/dexopt/baseline.prof`,
~19k rules covering cold start → Home, Home scroll, and the
Records → Biomarkers → graph detail journey) plus a **startup profile** used
for dex layout. Side-loaded installs AOT-compile it on first run via
`androidx.profileinstaller` — no Play Store needed. Regenerate after
meaningful startup/UI changes:

```bash
./gradlew :app:generateReleaseBaselineProfile   # needs a rooted device, an
                                                # emulator, or any API 33+ device
```

Generation requires a **rooted device, an emulator, or API 33+** (the POCO
test phone is API 12 / API 31 non-rooted and cannot run it — see
[Troubleshooting](docs/dev/troubleshooting.md#baseline-profile-generation)). The
`android/baselineprofile/` module holds the generator; see
`docs/dev/troubleshooting.md` for the full local-regeneration recipe (emulator +
mock bridge + `adb reverse`).

---

## Testing

Four layers; only the instrumented one needs a device.

| Layer | Scope | Command |
|---|---|---|
| JVM unit | `shared` (sync engine, mapper, onboarding parsers), `kotlin-sdk` (HMAC parity) | `./gradlew :shared:test :kotlin-sdk:test` |
| JVM unit | `:app` | `./gradlew :app:testDebugUnitTest` |
| Instrumented | Compose UI, Health Connect reads | `./gradlew :app:connectedDebugAndroidTest` |
| Lint | ktlint (mandatory) + Android Lint | `./gradlew :app:ktlintCheck :app:lint` |

The headline correctness gate is the **HMAC parity test** — it asserts the
Kotlin `signRequest` produces byte-identical signatures to the Python
`sign_request` across ASCII / empty / non-ASCII / GET golden vectors, so a
regression on either side is caught instantly.

---

## Security model

- **Single credential, patient-scoped.** The app holds `base_url` +
  `integration_id` + optional `api_secret` — never the user's login token. The
  Bridge instance is bound to one patient, and every backend read path filters
  by `integration.patient_id` (the resolved actor carries the owner's role, so
  patient isolation is an explicit per-path filter).
- **At rest.** Credentials are stored in `EncryptedSharedPreferences`
  (AES-GCM values, AES256-SIV keys, Keystore-backed master key).
- **In transit.** HMAC-SHA256 over `METHOD\n<path>\n<timestamp>\n<raw_body>`
  with a ±5 min replay window on every mutating/reading path; `GET /status` is
  the sole unsigned probe. Enforce HTTPS off-LAN (HTTP only for an explicit
  LAN allowlist). The `api_secret` is long-lived — treat the onboarding QR /
  revealed text like a password.
- **Idempotency.** Examinations and document uploads dedup on the client id, so
  a retry after a network blip is a no-op, not a duplicate.

---

## Project status

| Area | State |
|---|---|
| Scaffold (KMP `shared` + Compose `:app`, Koin, ktlint) | ✅ |
| Kotlin Bridge SDK + HMAC parity | ✅ |
| Offline sync engine (outbox + state machine + coordinator) | ✅ |
| Health Connect → LOINC mapper | ✅ |
| Onboarding (QR scan + paste-code + manual) | ✅ |
| Backend bridge read/management paths + `/status` HMAC-exempt fix | ✅ |
| WorkManager background sync + persistent SQLite outbox | ✅ |
| Phase 8 polish — dynamic color, offline-aware status, sync notifications, multi-connection switcher | ✅ |
| Health Connect source adapter + pluggable `HealthDataSource` | ✅ |
| Sync settings UI (source toggle, per-type w/ HC permission gate, frequency, history window) | ✅ |
| Full pipeline (`HealthSyncPipeline` → outbox → bridge `/sync` → backend FHIR/telemetry) | ✅ |
| Sync efficiency — drain loop + chained passes, read-before-drain, 1000-record batches | ✅ |
| **UI redesign (R1–R5)** — design system, bottom-nav shell, Home dashboard, plain-language Sync, native charts, Records | ✅ |
| **Biomarker-read alignment** — catalog-driven Home + Insights (telemetry-aware reads; HC types + instance-only lab biomarkers), reference-range context | ✅ |
| R6 Profile — account/settings/accessibility/about (sync settings embedded, UiPreferences a11y, About) | ✅ |
| R7 Onboarding — guided welcome (3 slides, once) + restyled connect + post-connect Health Connect step | ✅ |
| R8 Polish — motion, splash, launcher, Simple mode, a11y sweep | ✅ |
| **Insights dropdown + dashboard editing** — searchable biomarker dropdown w/ last-value rows; Home view styles (Grid/List/Simple) + edit dialog (show/hide, reorder) | ✅ |
| **M8 on-device observation cache** — Room + reactive reads (offline charts/cards instant; write-through from bridge + HC) | ✅ |
| **v2 / Phase A — Documents fix** — native ExaminationDetail + DocumentPreview (Coil); FileProvider + `Intent.ACTION_VIEW` + `createChooser`; bridge `GET /documents/{id}/content` + `preview` (in `core/`); client-side 25 MiB upload pre-check | ✅ |
| **v2 / Phase B essentials — Security hardening** — `allowBackup=false`; R8 + resource shrinking in release (**APK 87 MB → 7.5 MB**); `network_security_config.xml` (cleartext denied); predictive-back opt-in | ✅ |
| **v2 / Phase B.4 — App-lock** — BiometricPrompt (face/fingerprint) + 4-digit PIN fallback, 60s background grace window, `ProcessLifecycleOwner` observer, Profile › Privacy toggle + PIN setup | ✅ |
| **v2 / Phase D — ViewModel + UiState refactor** — Home / Records / ExaminationDetail moved from `LaunchedEffect + remember` to `ViewModel + StateFlow<UiState>` (survives process death, testable, the canonical Android pattern) | ✅ |
| **v2 / Phase E — Today screen** — daily summary aggregating vitals + medications + recent exams + inbox preview in parallel; 5th bottom-nav tab | ✅ |
| **v2 / Phase I (partial) — Notification channels + Inbox** — 6 Android notification channels (medication, examination, clinical_alert, anomaly, general + sync); native Inbox screen (`GET /notifications/inbox`, mark-read, mark-all-read); Today's Inbox section → tappable → full Inbox detail route | ✅ |
| **v2 / Phase F — Records overhaul** — exam-level + per-document overflow menus (Delete exam with cascade; Delete document; Re-extract via OCR/NLP pipeline) with confirmation dialogs; simplified cards (fully clickable, no inline buttons); upload moved to the detail page; remembered last-chosen viewer per MIME type (skips the system chooser on subsequent opens) | ✅ |
| **v2 / Phase J — Expanded Health Connect types** — 8 new types added (BP systolic + diastolic, blood glucose, body temperature, respiration rate, height, distance, calories burned); 13 total (was 5); each gets its own HC permission + icon + mapper branch | ✅ |
| **v2 / Phase G — Two-way incremental pull** — pure-Kotlin crash-safe `PullSyncCoordinator` (fetch `GET /changes` → apply delta → advance cursor only on success); `PullSyncWorker` (30-min periodic + on-demand pull-to-refresh); DataStore cursor + `lastPullEpochMs` signal that auto-refreshes Today/Home when the server/PWA mutates clinical records | ✅ |
| **v2 / Phase H — Native clinical records under Records** — the Records tab is a hub of five uniform rows (Examinations, Medications, Allergies, Vaccines, Clinical events), each opening its native list screen; Medications + Allergies support add/delete; the exam list + its FAB (new exam / add reading) moved to an Examinations detail route; Home allergies safety card (always visible, both modes). Screens auto-refresh via the Phase G pull signal | ✅ |
| **v2 / Phase I.6 — Medication reminders** — daily "Time to take X" notification per active medication (user-set hour, `ha_medication` channel, Snooze + Mark as taken); time-bound WorkManager worker with a crash-safe one-fire-per-day guard + 6h catch-up; offline fallback | ✅ |
| **v2 / Phase K — Lint + a11y cleanup** — `./gradlew build` is GREEN (78 lint errors cleared: complete Greek `values-el` key coverage + AppLockScreen resource-read hoist + missing-permission suppression); Compose a11y checks clean | ✅ |
| **v2 / Phase C — SQLCipher at rest** — both on-device DBs (observation cache + outbox) consolidated into one SQLCipher-encrypted Room DB; the plaintext `SqliteOutboxStore` migrated to `RoomOutboxStore` (one-shot, row-preserving); key from a Keystore-backed `EncryptedSharedPreferences` master key | ✅ |
| **v2 / Phase I.3/I.4 — UnifiedPush native push** — `MobilePushReceiver` (UnifiedPush connector) + `PushRegistration` on active connection; `onNewEndpoint` → `register-device`, `onMessage` → notification on the matching channel, `onUnregistered` → DELETE. Per-install stable device id. (Needs a distributor app for delivery; FCM is a separate flavor.) | ✅ |
| **v2 / Phase L — Release readiness (partial)** — version 1.0 → 1.1.0; **StrictMode in debug** (network-on-main crashes, disk-writes logged, VM-leak detection); release build green. Baseline Profile generation + JsBridge runBlocking audit remain | ✅ |
| **v2 / K-simple-mode — App-wide Simple/Advanced mode** — `UiPreferences.mode` (DataStore): SIMPLE defaults on fresh install (existing installs keep ADVANCED), picked in the first-run wizard + re-changeable in Profile instantly; SIMPLE forces the large-print Home cards, hides the dashboard editor, the Records doctors directory and the advanced Profile/Inbox surfaces (3-tab shell unchanged). See `docs/dev/ui-patterns.md` §8 | ✅ |
| **v2 / Bridge Phases 6–8 (in `core/`)** — clinical-record CRUD; notification inbox + preferences + biomarker-threshold triggers; native mobile push (`MobilePushTarget` model + UnifiedPush/FCM dispatch + `register-device`); Kotlin SDK bumped to 0.4.0 | ✅ |
| **v3 / Offline-first read cache M0–M9** — SSOT repositories for every read domain (observations, biomarker catalog, exams, documents, clinical records, inbox) backed by reactive Room caches; offline screens show saved data instead of empty/error states; document byte cache with 100 MiB LRU; `/changes` delta hydration + 24h re-snapshot; **M9**: every cache row scoped by `connection_id` (multi-connection switching can never leak another patient's rows) + the `cache_meta` staleness table written atomically with each refresh | ✅ |
| **v3 / M4 — Home-screen widgets (Glance 1.2)** — Latest vitals (2x2, newest MetricCard-style rows), Single metric ring (2x1, target biomarker picked at placement from the cached catalog), Live heart rate (4x2, current value + last-hour sparkline with reference band); all read the ACTIVE connection's Room cache only (no network — they refresh on the sync cadence via the 30-min `WidgetRefresher`, rate-capped to one pass/min); colors map the `ui/theme` tokens through a day/night GlanceTheme; widget taps deep-link into the biomarker detail screen; cache→widget-UI mapping is a pure JVM-tested mapper | ✅ |

---

## Working with this repo (agents + contributors)

- **`app/android/AGENTS.md`** — the orientation opencode reads first every
  session (module map, the bridge wire contract, the exact `./gradlew`
  commands, the `JAVA_HOME` requirement). **`app/android/CONVENTIONS.md`** —
  style, packages, version-catalog rules.
- **`docs/dev/ui-patterns.md`** — the UI architecture deep-dive (design system, navigation,
  screen/route pattern, i18n, instrumented-test setup).
- **One phase per session**, green `./gradlew build` gate before every
  commit. Implementation plans live in each repo's gitignored `dev/`
  directory (internal working notes — never referenced from published docs).
- `app/` is its own git repo (separate from `core/`). App code commits here;
  Kotlin-SDK and backend changes commit in `core/`.
- Community: [contributing](docs/dev/development.md) ·
  [code of conduct](CODE_OF_CONDUCT.md) · [security](SECURITY.md) ·
  [support](SUPPORT.md).

## Docs

The manual lives in [`docs/`](docs/), split by audience — start at
[`docs/README.md`](docs/README.md):

- **[User guide](docs/user/README.md)** — getting started, the full
  [feature catalog](docs/user/features.md), offline behavior & your data,
  troubleshooting.
- **[Developer docs](docs/dev/README.md)** —
  [architecture](docs/dev/architecture.md),
  [development workflow](docs/dev/development.md),
  [UI patterns](docs/dev/ui-patterns.md),
  [dev troubleshooting](docs/dev/troubleshooting.md) (MIUI adb workflow,
  Baseline Profile regeneration, R8 keep rules, the verification driver).
- **[STATUS](docs/STATUS.md)** — what exists, current phase, open items.
  Releases: [CHANGELOG.md](CHANGELOG.md), tagged `v*` via
  `scripts/version_manager.py`.
- **`app/android/AGENTS.md`** + **`CONVENTIONS.md`** — the orientation
  opencode reads first every session.
- **`core/docs/MOBILE_SYNC.md`** — public-facing mobile-sync architecture
  overview (in the `core/` repo).

Implementation plans and internal working notes live in the gitignored
`dev/` directory — they are not part of the published docs.

## License

Apache-2.0 — see the root [LICENSE](../core/LICENSE).

# AGENTS.md — Health Assistant Android app (`app/`)

> Read this first in every opencode session working in `app/android` or `app/shared`.
> Full architecture lives in [`docs/dev/architecture.md`](../docs/dev/architecture.md).
> UI patterns in [`docs/dev/ui-patterns.md`](../docs/dev/ui-patterns.md).

## What this is

An offline-first Android companion app for a self-hosted Health Assistant
instance. It reads on-device health data (Android **Health Connect**, 13
types), pushes it through the **Health Assistant Bridge** integration,
and lets the user view biomarkers, examinations, documents, medications,
and notifications — all through a single, patient-scoped connection
identity. Open source (Apache-2.0), privacy-first, no third-party cloud.

**Two-repo model:** `app/` (this repo) + `core/` (sibling) are separate git
repos. App code commits here; the Kotlin SDK + bridge backend commit in
`core/`. The parent `Health-Assistant/` is NOT a git repo.

## Module map (v2)

```
app/shared/  (KMP kotlin-jvm; group io.healthassistant)
├── sync/          Outbox, SyncCoordinator, HealthSyncPipeline, BridgeSyncSender
├── healthconnect/ HcType (13 types) + RawSample + HealthConnectMapper
├── source/        HealthDataSource interface + ManualEntrySource
├── onboarding/    ConnectionCredential + QR/deep-link/manual parsers
└── data/          BridgeReads (typed readers) + BridgeUploads + DocumentSummary + cache

app/android/app/  (Jetpack Compose, io.healthassistant.android)
├── HAApplication         Koin + periodic SyncWorker + 6 notification channels + AppLockManager
├── MainActivity          FragmentActivity (for BiometricPrompt) + AppLock gate + ProcessLifecycleOwner
├── data/                 CredentialStore, SqliteOutboxStore (legacy fallback), RoomOutboxStore
│                         (Phase C — encrypted Room), ConnectionRepository,
│                         DocumentOpener (FileProvider + remembered viewer),
│                         AppLockManager (biometric + PIN + 60s grace),
│                         PullSyncRepository (Phase G — /changes cursor + lastPullEpochMs signal),
│                         MedicationReminderRepository (Phase I.6 — prefs + day-guard + med cache)
├── source/               HealthConnectSource (13 HC types) + SourceRegistry├── settings/             SyncSettings + DataStore + SyncSettingsScreen + HealthConnectPermissions
├── monitoring/           SyncMonitorRepository + SyncScreen
├── work/                 SyncWorker (push: HC → bridge) + PullSyncWorker (Phase G pull: /changes)
│                         + MedicationReminderWorker (Phase I.6 — daily med reminder, time-bound,
│                           snooze + mark-as-taken via MedicationReminderReceiver)
│                         + MobilePushReceiver (Phase I — UnifiedPush) + PushRegistration
│                         + SyncScheduler (syncNow/pullNow + periodic) + SyncNotifications
├── ui/                   AppRoot (NavHost + 5-tab bottom bar) + screens (see below)
│   ├── HomeRoute/Screen + HomeViewModel          Dashboard cards (StateFlow<HomeUiState>)
│   ├── TodayRoute/Screen + TodayViewModel         Daily summary (vitals + meds + exams + inbox)
│   ├── BiomarkersRoute/Screen + BiomarkerDetailRoute  Biomarkers card list (Records) + the graph
│   │                                              drill-down (Vico chart, range chips)
│   ├── RecordsRoute/Screen                        Records hub — five uniform rows (Examinations /
│   │                                              Medications / Allergies / Vaccines / Clinical events)
│   ├── ExaminationsRoute/Screen + RecordsViewModel  Exam list (clickable cards) + FAB (new exam + add reading)
│   ├── ExaminationDetailRoute/Screen + VM         Native doc list + upload + delete + re-extract + remembered viewer
│   ├── DocumentPreviewRoute                       Inline Coil image preview
│   ├── InboxRoute/Screen + InboxViewModel         Notification inbox (mark-read, mark-all-read)
│   ├── MedicationsRoute/Screen + VM               Meds list + add + delete (Phase H, from Records)
│   ├── AllergiesRoute/Screen + VM                 Allergies list + add + delete (Phase H, from Records)
│   ├── VaccinesRoute/Screen + VM                  Vaccines list (Phase H, from Records)
│   ├── ClinicalEventsRoute/Screen + VM            Clinical events list (Phase H, from Records)
│   ├── AppLockRoute/Screen                        BiometricPrompt + 4-digit PIN pad
│   ├── AppLockSection                             Profile › Privacy app-lock toggle + PIN setup
│   ├── ProfileRoute/Screen                        Connection + sync settings + a11y + app-lock + about
│   ├── OnboardingScreen                           QR + paste-code + manual
│   └── navigation/                                HATab (5 tabs) + HARoutes (detail routes)
└── web/                  WebAppLauncher (Custom Tabs hand-off to the PWA — assistant + web dashboard)
```

## Screen / Route pattern (Phase D)

Every destination is **three** pieces:

1. **`FooScreen`** (pure): takes `FooUiState` + lambdas, no coroutines, no collection.
2. **`FooViewModel`** (lifecycle-aware): owns `StateFlow<FooUiState>`, all bridge reads,
   mutations, side-effects. Built via `viewModel(factory = FooViewModel.factory(client, ...))`.
   Survives process death.
3. **`FooRoute`** (thin shim): `viewModel(factory=...)` + `collectAsStateWithLifecycle()` +
   one-liner delegates. ~25-50 lines.

Migrated: all destinations — Home, Today, Records hub, Biomarkers list,
Biomarker graph detail, Examinations, ExaminationDetail, Inbox, Medications,
Allergies, Vaccines, Clinical events, Profile, Sync, Onboarding (completed
2026-08-16; the Insights tab was removed — biomarkers live under Records). The
`rememberSyncSettingsController` in ProfileRoute is intentionally still
composable-owned (HC permission launcher).

## Records: FAB + dialogs

RecordsScreen has a **FloatingActionButton** at the bottom-right. Tapping it opens a
dropdown with "New examination" + "Add reading":
- "New examination" → AlertDialog (date picker + notes) → `POST /examinations`
- "Add reading" → ModalBottomSheet (searchable biomarker picker → value entry) →
  `ManualEntrySource.submit` + `SyncScheduler.syncNow`

No inline "Add a reading" card — the FAB is the single entry point for creating items.

## Documents: native rendering + remembered viewer

ExaminationDetailScreen renders documents natively (Phase A):
- Images → inline Coil preview (DocumentPreviewRoute)
- PDF/other → `DocumentOpener.launch()` → FileProvider + `Intent.ACTION_VIEW`
- The user's **last-chosen viewer is remembered per MIME type** (SharedPreferences) so
  subsequent opens skip the system chooser entirely (instant)

## App-lock (Phase B.4)

`AppLockManager.isLocked: StateFlow<Boolean>` gates the whole UI. When locked,
`AppLockRoute` substitutes for `AppRoot`. BiometricPrompt (face/fingerprint) is the
primary auth; a 4-digit PIN is the fallback. 60s grace window via `ProcessLifecycleOwner`.

## The bridge wire contract

Authoritative: `core/integrations/health_assistant_bridge/docs/api-reference.md`.
Key facts:
- Base: `{base_url}/api/v1/integrations/health_assistant_bridge/api/{integration_id}/{path}`
- **`GET /status` is NEVER signed.** Every other path is HMAC-gated when `api_secret` is set.
- HMAC canonical: `<METHOD>\n<path>\n<timestamp>\n<raw_body>` → hex HMAC-SHA256.
- Patient scoping: every patient-scoped path filters by `integration.patient_id`.

**Paths (v2 — bridge Phase 1-8 shipped):**
```
GET    /status                         (unsigned)
POST   /map, POST /sync                (push)
GET    /observations/latest            GET /observations  GET /biomarkers
GET    /examinations                   POST /examinations
GET    /examinations/{id}              DELETE /examinations/{id}
GET    /examinations/{id}/documents    POST /examinations/{id}/documents
GET    /examinations/{id}/status       GET /examinations/{id}/logs
GET    /documents                      GET /documents/{id}
GET    /documents/{id}/content         GET /documents/{id}/preview
DELETE /documents/{id}                 POST /documents/{id}/extract
GET    /documents/{id}/extract/status
POST/PUT/DELETE /medications           POST/PUT/DELETE /allergies
POST/PUT/DELETE /vaccines              POST/PUT/DELETE /clinical-events (+ occurrences)
POST/PUT/DELETE /doctors
GET    /notifications/inbox            GET /notifications/unread-count
PATCH  /notifications/{id}/read|dismiss  POST /notifications/read-all
GET    /notifications/preferences      PUT /notifications/preferences/{kind_id}
GET/POST/DELETE /notifications/triggers
POST   /notifications/register-device  DELETE /notifications/register-device/{id}  ← Phase I push
GET    /devices                         ← Phase I push ("where am I signed in")
GET    /changes?since=&types=&limit=   (unified delta)
```

**Clinical-record create gotcha (Phase H):** the Pydantic create schemas use
**UPPERCASE enum values** — `status: "ACTIVE"` (not `"active"`), allergy
`clinical_status: "ACTIVE"`, `criticality: "HIGH" | "LOW" | "UNABLE_TO_ASSESS"`
(see `core/backend/app/models/enums.py`). Build create bodies via
`io.healthassistant.shared.data.ClinicalRecordBodies` (JVM-tested); the allergy
builder normalizes + drops unrecognized severities so a request never 422s.
Display names: `io.healthassistant.shared.data.ClinicalRecordDisplays`.

## Kotlin SDK (0.4.0)

`core/integrations/health_assistant_bridge/kotlin-sdk/` — `BridgeClient` with typed
readers (`getObservationsLatest`, `getMedications`, `getNotificationInbox`, etc.) +
binary download (`requestBytes`, `getDocumentContent`) + mutation wrappers
(`createMedication`, `deleteExam` via `requestText("DELETE", ...)`) + the generic
`request(method, path, body)` for anything not yet wrapped. HMAC parity-tested.

## Health Connect: 13 types (Phase J)

```
HcType.HEART_RATE          8867-4  loinc    bpm       HeartRateRecord
HcType.STEPS               55423-8 loinc    count     StepsRecord
HcType.WEIGHT              29463-7 loinc    kg        WeightRecord
HcType.OXYGEN_SATURATION   59408-5 loinc    %         OxygenSaturationRecord
HcType.SLEEP_DURATION      custom  custom   min       SleepSessionRecord
HcType.BLOOD_PRESSURE_SYS  8480-6  loinc    mmHg      BloodPressureRecord.systolic
HcType.BLOOD_PRESSURE_DIA  8462-4  loinc    mmHg      BloodPressureRecord.diastolic
HcType.BLOOD_GLUCOSE       2339-0  loinc    mmol/L    BloodGlucoseRecord
HcType.BODY_TEMPERATURE    8310-5  loinc    °C        BodyTemperatureRecord
HcType.RESPIRATION_RATE    9279-1  loinc    br/min    RespiratoryRateRecord
HcType.HEIGHT              8302-2  loinc    m         HeightRecord
HcType.DISTANCE            custom  custom   m         DistanceRecord
HcType.CALORIES            custom  custom   kcal      TotalCaloriesBurnedRecord
```

Adding a new HC type: add the enum entry + manifest permission + HealthConnectSource
read branch + HealthConnectPermissions mapping + HcIcon icon. The Settings toggle +
Home/Insights/Today pick it up automatically.

## Build & test (opencode shell — CRITICAL)

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64   # JDK 25
export ANDROID_HOME=/home/ilias/Android/Sdk
cd app/android
./gradlew build                          # compile + app unit tests + ktlint + Lint
./gradlew :shared:test :kotlin-sdk:test  # composite builds are NOT wired into root `build` — run explicitly
# The FULL gate is BOTH lines above. Koin DI linkage is runtime-only: the
# on-device AppModuleResolutionTest (androidTest, no activity launch — MIUI-safe)
# is the net that catches binding misses; run it when touching di/AppModule.kt.
./gradlew :app:testDebugUnitTest         # JVM unit tests (fast loop)
./gradlew :shared:test :kotlin-sdk:test  # shared core + SDK
./gradlew :app:ktlintFormat              # auto-format — RUN BEFORE committing
./gradlew :app:assembleDebug             # debug APK
./gradlew :app:assembleRelease           # release APK (R8 + resource shrinking, 7.5 MB)
./gradlew :app:installDebug              # install on a connected device
```

**Locale coverage (Phase K):** every string in `values/strings.xml` has a key
in `values-el/strings.xml` (English placeholders pending translation) — missing
keys fail `./gradlew build` as `MissingTranslation` lint errors. When adding a
string, add the key to **both** files. Compose screens read resources via
`stringResource(...)` (hoisted to composable scope), never `LocalContext.current.getString(...)`
in a lambda — the latter fails lint (`LocalContextGetResourceValueCall`).

Toolchain: AGP 9.3.1 · Gradle 9.5 · Kotlin 2.3.20 · minSdk 28 · targetSdk 37.
Release build has R8 ON (keep rules in `proguard-rules.pro`). `buildConfig = true`
(so `BuildConfig.DEBUG` is reachable — StrictMode in `HAApplication` is debug-only).

## DI + conventions

- **Koin 4.2.x** (`di/AppModule.kt`); `koinInject()` in Compose.
- **ktlint mandatory** (14.2.0) — run `ktlintFormat` before every commit.
- **No comments** unless requested; KDoc on public API only.
- **One phase per session**, test gate green before commit.
- **R8 keep rules**: `proguard-rules.pro` covers kotlinx.serialization, Ktor, Koin,
  Room, Health Connect, Coil, BiometricPrompt.

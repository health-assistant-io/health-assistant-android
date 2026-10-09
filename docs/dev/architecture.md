# Architecture

> The technical reference for the Health Assistant Android companion app.
> For the UI patterns deep-dive, see [UI.md](./UI.md). For the build/test
> commands + per-session orientation, see [`android/AGENTS.md`](../android/AGENTS.md).

## 1. Overview

An **offline-first** Android companion app for a self-hosted [Health
Assistant](https://github.com/health-assistant-io/health-assistant) instance.
It reads on-device health data via Android **Health Connect** (13 types),
pushes it through the **Health Assistant Bridge** integration, and lets the
user view biomarkers, examinations, documents, medications, and
notifications — entirely native, without opening the PWA.

**Key properties:**
- **Single connection identity.** One bridge integration instance = one
  patient. The app holds `base_url + integration_id + api_secret` (never
  the user's login token). Multi-patient = multi-connection (switcher).
- **Offline-first.** Every local event is persisted to a SQLite outbox +
  enqueued atomically. The `SyncCoordinator` drains in bounded batches,
  signs each request (HMAC-SHA256), retries with full-jitter backoff, and
  dead-letters permanent failures.
- **Privacy-first.** No third-party cloud. UnifiedPush (self-hostable) is
  the default push transport. Credentials at rest in
  `EncryptedSharedPreferences` (Keystore-backed). R8 + resource shrinking
  in release (7.5 MB APK). App-lock (biometric + PIN) gates the whole UI.
- **Native-first.** Native Compose owns all daily flows (Home, Records,
  Inbox, Profile) including the biomarker charts (Vico: time axis, dots,
  reference-range band). Rich web surfaces (knowledge graph, analytics,
  AI assistant) open in the browser via Custom Tabs (`web/WebAppLauncher`)
  — the in-app asset WebView was removed with the deprecated "Advanced
  charts" screen.

## 2. Two-repo model

```
Health-Assistant/                         # parent (NOT a git repo)
├── core/   # git: backend + integrations + frontend + Kotlin SDK
│   ├── backend/                          # FastAPI + SQLAlchemy + Celery
│   ├── frontend/                         # React/Vite PWA
│   ├── integrations/health_assistant_bridge/
│   │   ├── provider.py                   # the bridge read/management/mutation paths
│   │   ├── kotlin-sdk/                   # io.healthassistant:kotlin-sdk:0.4.0
│   │   ├── docs/                         # the wire contract (api-reference.md, etc.)
│   │   └── manifest.json                 # advertises sdks.kotlin = 0.4.0
│   └── dev/plans/                        # gitignored working plans (bridge phases)
└── app/    # git: THIS repo (the Android app)
    ├── shared/                           # KMP shared core (pure Kotlin, JVM-tested)
    ├── android/                          # Gradle composite root (:app + includeBuild)
    ├── docs/                             # this file + UI.md + CONTRIBUTING.md
    └── dev/plans/                        # gitignored working plans (mobile-app-v2)
```

- App code (`app/android`, `app/shared`) commits in `app/`.
- Kotlin SDK + bridge backend + bridge frontend commit in `core/`.
- Composite builds: `app/android/settings.gradle.kts` includes both
  `includeBuild("../shared")` + `includeBuild("../../core/.../kotlin-sdk")`.
  A local edit in either is picked up by the next `./gradlew build` — no
  publish step.

## 3. Module structure

### `app/shared/` (KMP shared core — pure Kotlin, JVM-tested)

| Package | Responsibility |
|---|---|
| `sync/` | Outbox + state machine (`PENDING → IN_FLIGHT → SYNCED | DEAD_LETTER`), `SyncCoordinator` (1000-record grouped batches, full-jitter backoff, dead-letter), `BridgeSyncSender`, `HealthSyncPipeline` (source.read → mapper → enqueue) |
| `healthconnect/` | `HcType` enum (13 types with LOINC codes), `RawSample`, `HealthConnectMapper` (HcType → `ClientRecord`) |
| `source/` | `HealthDataSource` plugin contract + `ManualEntrySource` (queues readings for the next sync) |
| `onboarding/` | `ConnectionCredential` + QR / deep-link / manual parsers |
| `data/` | `BridgeReads` (typed readers: examinations, observations, biomarkers, documents), `BridgeUploads`, `DocumentSummary`, `ExaminationSummary`, `HomeDashboardBuilder`, `DashboardLayout`, `ObservationCache` interface |
| `data/cache/` | Cache contracts + rows: `ObservationCache`, `BiomarkerCache`, `ExaminationCache`, `DocumentCache`, `ClinicalRecordCache`, `NotificationCache`, `CacheMetaStore` (staleness) — connection-scoped, Room-backed reactive caches |
| `alerts/` | `AlertRule` + `AlertOp` + `Breach` + the pure evaluator (time-window evidence + cooldown semantics) and `outboxItemToPoint` (push-hook mapping) — M5 local threshold alerts |

### `app/android/app/` (Jetpack Compose)

| Package | Responsibility |
|---|---|
| `HAApplication` | Koin startup, periodic `SyncWorker`, 7 notification channels, `AppLockManager` init |
| `MainActivity` | `FragmentActivity` (for BiometricPrompt), AppLock gate (`isLocked` StateFlow), `ProcessLifecycleOwner` observer for the 60s grace window |
| `data/` | `CredentialStore` (EncryptedSharedPreferences), `RoomOutboxStore` (Phase C, encrypted Room — replaces the plaintext `SqliteOutboxStore`), `ConnectionRepository`, `DocumentOpener` (FileProvider + remembered viewer), `AppLockManager` (biometric + PIN + SHA-256 hashed PIN in EncryptedSharedPreferences), `DatabaseKeyProvider`/`DatabaseMigrator` (Phase C SQLCipher key + one-shot migration), `cache/` (Room DB + DAO) |
| `source/` | `HealthConnectSource` (13 HC record types → `RawSample`), `SourceRegistry` |
| `settings/` | `SyncSettings` + DataStore, `SyncSettingsScreen`, `HealthConnectPermissions`, `UiPreferences` (a11y) |
| `alerts/` | `AlertRulesRepository` (DataStore-backed rules), `AlertEngine` (cache subscription + `onSyncedItems` evaluation, pure Kotlin), `AlertEngineHost` (per-active-connection engine lifecycle), `AlertNotifications` + `AndroidAlertNotifier` + `AlertPhrases` (`ha_alert` channel, plain-language breach text) — M5 |
| `monitoring/` | `SyncMonitorRepository` (StateFlow), `SyncScreen` (plain-language) |
| `work/` | `SyncWorker` (read-then-drain loop, chained passes, live progress), `SyncScheduler`, `SyncNotifications` |
| `ui/` | AppRoot (NavHost + 5-tab bottom bar), all screens/routes/ViewModels (see §5), `theme/`, `components/`, `navigation/` |
| `widget/` | M4 Glance home-screen widgets — Latest vitals (2x2), Single metric ring (2x1) + its configuration activity, Live heart rate (4x2, canvas sparkline); `WidgetDataRepository` (cache-only reads of the ACTIVE connection), `WidgetStateMapper` (pure JVM-tested cache→UI mapping), `WidgetRefresher` (30-min periodic, 1/min-capped re-render), token-mapped `WidgetTheme` |
| `web/` | `WebAppLauncher` + `webAppUrl` (Custom Tabs hand-off to the PWA — assistant + web dashboard) |

## 4. The offline sync engine

The heart of the app. Every local event is persisted locally AND enqueued
in the outbox atomically. The engine drains it in bounded batches.

### State machine

```
PENDING ──▶ IN_FLIGHT ──▶ SYNCED        (success → delete row)
   ▲           │
   │           └──▶ PENDING             (transient → full-jitter backoff, attempt++)
   │           └──▶ DEAD_LETTER         (permanent → surface to user)
   └─ (requeue by user action)
```

### Two lanes

- **DEFAULT** (small JSON): `/sync` records, `/examinations` creates.
  Batched (1000 records merged into one `SyncPayload`).
- **LARGE** (document uploads): drained one-at-a-time from a content
  reference (local file path), NOT the bytes — so retries don't re-load
  megabytes into memory.

### The drain loop

`SyncWorker.doWork()` runs a read-then-drain **loop** (~5 min):
1. Read from Health Connect (bounded by the walking-window cursor).
2. Map via `HealthConnectMapper`.
3. Enqueue `/sync` items into the outbox.
4. Drain the outbox (DEFAULT + LARGE lanes).
5. If items remain, chain another pass via `SyncScheduler.syncContinue`
   (`ExistingWorkPolicy.APPEND_OR_REPLACE` — never cancels the in-flight run).
6. Live progress reported each batch.

### Health Connect read pipeline

```
Health Connect ──▶ HealthConnectSource ──▶ HealthSyncPipeline ──▶ OutboxStore
                  (walking-window read)    (map RawSample→ClientRecord)        │
                                                                                ▼
                          SyncCoordinator.drain() → BridgeSyncSender → POST /sync
                          (1000-record grouped batches, looped + chained)      │
                                                                                ▼
                                   Backend: FHIR Observation + telemetry split (is_telemetry)
```

### Two-way sync (Phase G — shipped)

The bridge's `GET /changes?since=<cursor>` returns a unified delta across
all data types (medications, allergies, vaccines, clinical events, documents,
examinations). The `PullSyncWorker` (30-min periodic + on-demand pull-to-refresh)
calls it via the SDK's `getChangesRaw`, hands the result to the pure-Kotlin
`PullSyncCoordinator` (`app/shared/sync/`) which **applies the delta before
advancing the cursor** — a crash mid-apply leaves the cursor untouched so the
next pass re-fetches the same window (idempotent). The DataStore-backed
`PullSyncRepository` persists the cursor and emits a `lastPullEpochMs` signal
that `HomeViewModel` collects (`drop(1)`) to re-fetch and
reflect PWA-side edits automatically. Push (HC → bridge) and pull
(server → device) are now both live; the push `SyncWorker` runs on its own
15-min cadence + `syncNow`, the pull `PullSyncWorker` on 30-min + `pullNow`
("Sync now" fires both). Conflict policy: server wins for clinical writes.

### Offline-first read cache (M0–M9 — connection-scoped SSOT)

Every read domain (observations, biomarker catalog, examinations, documents,
medications, allergies, vaccines, clinical events, notifications) is served
by a Single-Source-of-Truth repository: the UI observes a reactive Room
`Flow` (instant, offline, survives process death) and a `refresh()` writes
successful bridge fetches back into the cache. A failed or offline refresh
leaves the cache untouched — the user keeps the saved snapshot instead of an
empty/error screen.

**Connection scoping (M9):** every cache row carries `connection_id` (the
bridge integration id) as part of its primary key and indices, and every DAO
query filters by it. The Room cache implementations are bound to one
connection at construction (`RoomCaches(db, connectionId)` — built next to
the per-connection `BridgeClient` in the Routes and in `PullSyncWorker`), so
switching patients swaps the whole cache scope and no query can cross the
patient boundary. The v8→v9 Room migration rebuilds each table with the
scoped primary key and backfills existing rows with the active connection's
id (a pre-M9 install only ever cached the active connection).
`fallbackToDestructiveMigration` stays off; the migration is gated by a
`MigrationTestHelper` test against the exported schemas (`app/schemas/`,
`exportSchema = true`).

**Staleness (`cache_meta`, M9):** one row per (connection, domain) records
`last_success_at` (epoch ms), `last_error` (null when the last attempt
succeeded), and `row_count`. Repositories write the success row **in the same
Room transaction** as the cache upsert (the `*Synced` write methods) and
record failures standalone via `CacheMetaStore`. The staleness chip and the
Settings "Data & storage" screen read these rows back.

**Staleness UX (M8):** the shared `StaleChip` renders the domain's row —
"Updated X ago" while fresh, "Showing saved data · offline" after a failed
refresh with no network, and a clickable "Couldn't refresh — tap to retry"
once online (the screens' existing reload action). Home's plain offline
banner is replaced by the saved-data banner whenever a cached snapshot backs
the screen; an empty cache offline keeps the plain banner. Profile →
**Data & storage** shows per-domain row counts + last refresh, "Clear cached
data" (every repository's `clear()` for the active connection), and the
document byte-cache size with "Clear downloaded documents".

**Document bytes (M4):** document content/preview bytes live as files under
`filesDir/ha_docs/` (one per document id, atomic tmp+rename writes) with a
100 MiB LRU cap enforced daily by `DocumentCacheEvictor`. The byte store is
connection-agnostic (a document id addresses one file), so eviction
bookkeeping clears manifest columns across all connections
(`RoomDocumentManifestCache`); metadata rows themselves stay scoped.

## 5. Screens + navigation

### Bottom-nav tabs (5)

| Tab | Screen | VM | Loads from |
|---|---|---|---|
| Home | `HomeScreen` | `HomeViewModel` | `combine()` over 7 flows: monitor + bridge reads + dashboard prefs + edit state |
| **Today** (Phase E) | `TodayScreen` | `TodayViewModel` | 4 parallel bridge reads: latest vitals + medications + exams + inbox preview |
| Records | `RecordsScreen` | — | Hub of six record-type rows (Biomarkers / Examinations / Medications / Allergies / Vaccines / Clinical events) → detail routes |
| Profile | `ProfileScreen` | `ProfileViewModel` | Connection + sync settings + a11y + app-lock + about |

### Detail routes (no bottom bar)

| Route | Screen | VM | Purpose |
|---|---|---|---|
| `examinations` | `ExaminationsScreen` | `RecordsViewModel` | Exam list + FAB dropdown (new exam + add reading) |
| `exam_detail/{id}` | `ExaminationDetailScreen` | `ExaminationDetailViewModel` | Native doc list + upload + delete + re-extract + remembered viewer |
| `doc_preview/{id}` | `DocumentPreviewRoute` | — | Inline Coil image preview |
| `inbox` | `InboxScreen` | `InboxViewModel` | Notification inbox (mark-read, mark-all-read) |
| `sync` | `SyncScreen` | `SyncViewModel` | Plain-language sync status + failed-readings retry |
| `medications` | `MedicationsScreen` | `MedicationsViewModel` | Meds list + add + delete |
| `allergies` | `AllergiesScreen` | `AllergiesViewModel` | Allergies list + add + delete |
| `vaccines` | `VaccinesScreen` | `VaccinesViewModel` | Vaccinations list |
| `events` | `ClinicalEventsScreen` | `ClinicalEventsViewModel` | Clinical events list |
| `biomarkers` | `BiomarkersScreen` | `BiomarkersViewModel` | Card list of every biomarker (cached catalog + latest, offline-first); the Overview entry row (M6, ADVANCED only) pins atop it |
| `biomarker_detail?code=` | `BiomarkerDetailScreen` | `BiomarkerDetailViewModel` | Vico graph drill-down (range chips, reference band, stats row, trend chip) |
| `overview` | `OverviewScreen` | `OverviewViewModel` | Wellness overview (M6): up to 3 picked biomarkers normalized per-series (`shared/data/Normalization.kt`) onto one timeline; selection persists in `DashboardPrefsRepository` |
| `alert_rules?code=` | `AlertRulesScreen` | `AlertRulesViewModel` | M5 alert rules list + the plain-language rule builder (ADVANCED-only Profile entry; the optional code arg prefills the builder from the biomarker detail's "Set an alert") |

### Native clinical records (Phase H)

All record types live under the **Records tab**, which is now a hub of five
uniform rows — Examinations, Medications, Allergies, Vaccines, Clinical events —
each deep-linking to its native list screen (detail routes; the bottom bar is
at the 5-tab Material 3 ceiling). The exam list (cards + extraction-status
chips + the FAB dropdown for "New examination" / "Add reading") moved to the
`examinations` detail route. Medications + Allergies are full CRUD (list / add
dialog / delete confirm); Vaccines + Clinical events are list-only for v2 (the
bridge CRUD paths exist, add/edit is a follow-up). Every screen observes the
Phase G `PullSyncRepository.lastPullEpochMs` signal (`drop(1)`) so a record
added on the PWA refreshes the list automatically. The Home allergies safety
card (H.3) renders the active allergies above the metric cards in both modes.
Create bodies are built by `shared/data/ClinicalRecordBodies` (uppercase enum
values — see `android/AGENTS.md`); display names via
`shared/data/ClinicalRecordDisplays`.

### Medication reminders (Phase I.6)

Profile › Notifications has a toggle + hour picker (default 09:00). The
`MedicationReminderScheduler` arms a **one-time, time-bound** `MedicationReminderWorker`
at the next reminder hour (not a polling periodic worker — one wake per day +
the worker re-arms itself, and WorkManager persists the schedule across
reboots). The worker fetches active medications (offline fallback to the cached
names in `MedicationReminderRepository`) and asks the pure, JVM-tested
`MedicationReminderPlanner` whether to fire: reminder hour ± a 6h catch-up
window (so a Doze-delayed run still fires) AND the persisted `lastNotifiedDay`
guard ≠ today (at most one fire per calendar day — advanced only after a
successful post). Each active medication gets a notification on the
high-importance `ha_medication` channel with **Snooze 15 min** (re-arms a
snooze worker) + **Mark as taken** (dismiss) actions, delivered to the
manifest-registered `MedicationReminderReceiver`. Deviation from plan I.6: the
bridge `frequency` field is free-text/null, so v2 is a daily reminder rather
than per-dose 15-min-before.

### App-lock gate (Phase B.4)

When `AppLockManager.isLocked == true`, `MainActivity` substitutes
`AppLockRoute` for `AppRoot` — the navigation state behind the lock isn't
observable. BiometricPrompt auto-fires when biometrics are enrolled; a
4-digit PIN pad is the fallback. The 60s grace window is driven by
`ProcessLifecycleOwner` (`ON_STOP → onBackgrounded`, `ON_START → onForeground`).

## 6. Security model

### At rest
- **Credentials** in `EncryptedSharedPreferences` (AES-GCM values,
  AES256-SIV keys, Keystore-backed master key).
- **App-lock PIN** SHA-256 hashed (10k rounds + per-install salt) in
  `EncryptedSharedPreferences`. The PIN itself is never stored.
- **On-device databases** (Phase C): the observation cache + the offline outbox
  both live in one **SQLCipher-encrypted Room DB** (`ha_observations.db`,
  `net.zetetic:android-database-sqlcipher` via `net.sqlcipher.database.SupportFactory`).
  The passphrase is 32 random bytes generated once + sealed in
  `EncryptedSharedPreferences` (Keystore-backed master key — see
  `DatabaseKeyProvider`). The plaintext `SqliteOutboxStore` was migrated to
  `RoomOutboxStore` via a one-shot, row-preserving `DatabaseMigrator`.
- **`allowBackup=false`** — health data never backs up to Google Drive.

### In transit
- **HMAC-SHA256** over `METHOD\n<path>\n<timestamp>\n<raw_body>` on every
  mutating/reading path. `GET /status` is the sole unsigned probe.
- **±5 min replay window** on the timestamp.
- **HTTPS required off-LAN** (`network_security_config.xml` denies
  cleartext). TOFU (trust-on-first-use) for self-signed certs on LAN —
  planned (the config trusts the user CA store as a prerequisite).

### App-lock
- **BiometricPrompt** (face/fingerprint) via `androidx.biometric:1.1.0`.
- **4-digit PIN fallback** when no biometric enrolled.
- **60s grace** — backgrounding for <60s doesn't re-prompt (quick tab
  switches). Backgrounding >60s re-locks.
- **ProcessLifecycleOwner** observer feeds `onBackgrounded` / `onForeground`
  app-wide (not per-activity).

### Release build
- **R8 + resource shrinking ON** (release APK: 7.5 MB, down from 87 MB debug).
- **Keep rules** (`proguard-rules.pro`) for kotlinx.serialization, Ktor,
  Koin, Room, Health Connect, Coil, BiometricPrompt.
- **Predictive back** (`enableOnBackInvokedCallback="true"`).

## 7. Notification system (Phase I — partial)

### Channels (7)

| Channel | Importance | Purpose |
|---|---|---|
| `ha_sync` | LOW | Sync status (dead-letter, success) |
| `ha_medication` | HIGH | Medication reminders |
| `ha_examination` | DEFAULT | Exam results / status changes |
| `ha_clinical_alert` | HIGH | Important clinical notifications |
| `ha_anomaly` | HIGH | Biomarker out-of-range alerts |
| `ha_general` | DEFAULT | System announcements |
| `ha_alert` | HIGH | Local threshold-rule breaches (M5) |

Each channel is user-tunable in the system notification settings.

### Local alerts (M5 — shipped)

Threshold rules evaluated **on-device**, no server round-trips. The pure
evaluator lives in `shared/alerts/AlertRule.kt`: a rule carries a comparison
(`GT/LT/GE/LE/OUT_OF_RANGE`) plus a threshold (or range), a `timeWindowSec`,
and a `cooldownSec`. A windowed breach ("above 120 **for 5 minutes**")
requires the condition to hold for EVERY reading inside the window anchored
on the new reading's own time AND evidence spanning the full window — a
lone fresh reading is not "for 5 minutes". The cooldown suppresses
re-firing; its `lastFiredEpochMs` stamp is persisted with the rule
(DataStore `ha_alert_rules` via `AlertRulesRepository`), so suppression
survives process death.

The Android `AlertEngine` (pure Kotlin, JVM-tested with fake flows) has two
best-effort trigger paths: a subscription to the connection-scoped
observation cache (a one-time baseline emission records existing rows
without alerting; every genuinely new latest point is evaluated against its
cached series) and the push pipeline's existing
`SyncCoordinator.Config.onSyncedItems` hook (freshly pushed `/sync` records
are decoded and evaluated), so alerts fire both in the foreground and from
background syncs. `AlertEngineHost` (Koin) owns the per-active-connection
engine lifecycle — AppRoot starts/stops it on connection changes, and the
`SyncWorker` builds one-shot engines for its connection. Breaches post on
the `ha_alert` channel as plain language ("Heart rate is above 120 for 5
minutes. Latest reading: 135 bpm."), one stable notification id per rule so
a re-fire replaces instead of stacking, with a notification-style double
haptic fired alongside (`alertFireHaptic` in `ui/components/Haptics.kt`;
`VIBRATE` is declared).

Rules are managed in Profile › **Alerts** (ADVANCED only — SIMPLE hides the
row) and from the biomarker detail's **Set an alert** action, which opens
the same builder prefilled with that biomarker. The builder speaks plain
language ("Alert me when Heart rate is above 120 for 5 minutes"); op and
threshold stay internal.

### Inbox (native)

`InboxViewModel` reads from `GET /notifications/inbox` (bridge Phase 7,
owner-scoped — not patient-scoped). Per-item mark-read, mark-all-read.
Reachable from Today's Inbox section → full detail route.

### Push (pending — Phase I remaining)

The bridge Phase 8 backend is shipped (`MobilePushTarget` model +
`mobile_push_service` + `deliver_notification` dispatch). The mobile-side
`UnifiedPushReceiver` + registration is pending:
- **UnifiedPush** (self-hostable, default) — the user chooses a distributor
  (ntfy, Gotify, etc.); the app registers the endpoint with the bridge via
  `POST /notifications/register-device`.
- **FCM** (optional, build flavor) — for users who prefer Google services.

## 8. The Kotlin SDK (io.healthassistant:kotlin-sdk:0.4.0)

Lives in `core/integrations/health_assistant_bridge/kotlin-sdk/`. Three
source files:

| File | Contents |
|---|---|
| `BridgeClient.kt` | The client: `getStatus` (unsigned), `requestMapping`, `syncData`, typed readers (`getObservationsLatest`, `getMedications`, `getExaminations`, `getNotificationInbox`, `listDocuments`, etc.), mutation wrappers (`createMedication`, `deleteNotificationTrigger`), binary (`requestBytes`, `getDocumentContent`), generic (`request`, `requestText`, `statusOf`). HMAC-signed when secret set. Full-jitter retry (3 attempts, 8s cap). |
| `Models.kt` | All `@Serializable` types: `ReadEnvelope<T>`, `BridgeStatus`, `ClientRecord`, `SyncPayload`, `ObservationPoint`, `BiomarkerSummary`, `ExaminationSummary`, `DocumentSummary`, `Medication`, `Allergy`, `Vaccine`, `ClinicalEvent`, `Doctor`, `NotificationItem`, `NotificationKind`, `NotificationTrigger`, `MobilePushTarget`, `DeviceRegistration`, + ack types. |
| `Signing.kt` | HMAC-SHA256 canonical sign. Parity-tested against the Python SDK (golden vectors). |

The SDK stays on `kotlin("jvm")` — KMP conversion (for iOS) is deferred
until iOS becomes real (per-platform crypto/time/charset cost).

## 9. Health Connect: 13 types (Phase J)

| HcType | LOINC | Unit | HC Record |
|---|---|---|---|
| HEART_RATE | 8867-4 | bpm | HeartRateRecord |
| STEPS | 55423-8 | count | StepsRecord |
| WEIGHT | 29463-7 | kg | WeightRecord |
| OXYGEN_SATURATION | 59408-5 | % | OxygenSaturationRecord |
| SLEEP_DURATION | custom | min | SleepSessionRecord |
| BLOOD_PRESSURE_SYS | 8480-6 | mmHg | BloodPressureRecord.systolic |
| BLOOD_PRESSURE_DIA | 8462-4 | mmHg | BloodPressureRecord.diastolic |
| BLOOD_GLUCOSE | 2339-0 | mmol/L | BloodGlucoseRecord |
| BODY_TEMPERATURE | 8310-5 | °C | BodyTemperatureRecord |
| RESPIRATION_RATE | 9279-1 | breaths/min | RespiratoryRateRecord |
| HEIGHT | 8302-2 | m | HeightRecord |
| DISTANCE | custom | m | DistanceRecord |
| CALORIES | custom | kcal | TotalCaloriesBurnedRecord |

Proprietary aggregates (Distance, Calories) use the `custom` coding system
to prevent collisions with clinical LOINC codes.

## 10. Testing

Four layers; only the bottom needs a device.

| Layer | Scope | Command |
|---|---|---|
| JVM unit | `shared` (sync engine, mapper, onboarding), `kotlin-sdk` (HMAC parity) | `./gradlew :shared:test :kotlin-sdk:test` |
| JVM unit | `:app` (formatUploadStatus, VM logic) | `./gradlew :app:testDebugUnitTest` |
| Instrumented | Compose UI, HC reads, Room | `./gradlew :app:connectedDebugAndroidTest` |
| Backend | Bridge paths (DB-backed, cross-patient isolation) | `cd core/backend && pytest tests/test_bridge_*.py` |

Headline gate: **HMAC parity test** (`SigningParityTest.kt`) — byte-identical
signatures to the Python SDK across ASCII / empty / non-ASCII / GET golden
vectors.

## 11. The bridge wire contract (summary)

Authoritative: `core/integrations/health_assistant_bridge/docs/`.

- **Base path:** `{base_url}/api/v1/integrations/health_assistant_bridge/api/{integration_id}/{path}`
- **Auth:** `GET /status` is never signed. Every other path is HMAC-gated
  when `api_secret` is set. HMAC canonical:
  `<METHOD>\n<path>\n<timestamp>\n<raw_body>` → hex HMAC-SHA256 in
  `X-Api-Signature`, epoch seconds in `X-Api-Timestamp`, ±300s skew.
- **Patient scoping:** every patient-scoped path filters by
  `integration.patient_id`. Notifications are owner-scoped
  (`integration.user_id`), not patient-scoped.
- **Rate limit:** per-IP 120/60s + per-integration 60/60s.
- **Upload cap:** 25 MiB (`MAX_UPLOAD_BYTES`).
- **Idempotency:** client-supplied `id`/`client_request_id` → `external_id`;
  backend dedups on `(tenant, patient, integration_id, external_id)`.

## See also

- [`android/AGENTS.md`](../android/AGENTS.md) — per-session agent orientation.
- [`ui-patterns.md`](./ui-patterns.md) — the Compose UI architecture deep-dive.
- [`development.md`](./development.md) — dev setup, phased delivery, testing.
- [`troubleshooting.md`](./troubleshooting.md) — MIUI issues, JDK, HMAC 401.
- [`core/integrations/health_assistant_bridge/docs/`](../../core/integrations/health_assistant_bridge/docs/) — the bridge wire contract.
- [`core/docs/MOBILE_SYNC.md`](../../core/docs/MOBILE_SYNC.md) — public-facing mobile-sync overview.

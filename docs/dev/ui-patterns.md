# Health Assistant Android — UI Architecture

How the Jetpack Compose UI is built: the design system, the navigation model,
the screen/route pattern, the reusable components, the i18n rules, and how the
instrumented UI tests are set up. Read this before adding or changing any screen.

> Status: reflects the **complete R1–R8 redesign** (design system, bottom-nav
> shell, Home dashboard, plain-language Sync, native Insights charts, Records,
> Profile with embedded sync settings + accessibility/about, guided onboarding
> with a first-run welcome + restyled connect + post-connect Health Connect
> step, and R8 polish — motion, haptics, branded splash + launcher, Simple
> mode, friendly notifications, a11y) **plus the biomarker-read alignment**
> (Home/Insights driven by the full biomarker catalog `GET /biomarkers` +
> telemetry-aware `GET /observations` — so Health Connect heart rate/steps/SpO₂
> and instance-only lab biomarkers both render). See
> the (internal, gitignored) `dev/plans/UI-REDESIGN-PLAN.md`.

---

## 1. Design system (`ui/theme/`)

Every screen draws from tokens in `ui/theme/` — no screen owns its own colors,
fonts, shapes, or spacing.

| File | Provides |
|---|---|
| `Color.kt` | Brand teal palette + light/dark/AMOLED/high-contrast scheme pieces + health-semantic colors (good/watch/alert, sync states) |
| `Type.kt` | The type scale — larger body defaults (17 sp) than Material's for elderly/low-vision legibility |
| `Shape.kt` | Card/chip shape scale |
| `Spacing.kt` | `xs/sm/md/lg/xl` spacing tokens + `MaterialTheme.spacing` accessor |
| `Theme.kt` | `HATheme` (dark/AMOLED/high-contrast/reduce-motion flags), `LocalReduceMotion`, `LocalHighContrast`, `LocalDarkTheme`, `HAHealthColors` |

Rules:

- **No magic `dp`/`Color`/font literals in screens.** Use
  `MaterialTheme.spacing.*`, `MaterialTheme.colorScheme.*`, and the typography
  tokens. (Only `ui/components` and data-driven spots may use small layout
  constants.)
- **No hard-coded user-facing strings.** Every literal goes in
  `res/values/strings.xml` (Greek mirror in `res/values-el/`) and is read via
  `stringResource(...)`. Developer jargon is banned — see the glossary in
  the (internal, gitignored) `dev/plans/UI-REDESIGN-PLAN.md` §5.2 ("outbox", "dead-letter",
  "cursor", "biomarker", "HMAC" are never user-facing).
- **Dark theme is first-class.** All new screens must read correctly in dark +
  AMOLED modes.

## 2. Navigation (`ui/navigation/` + `ui/AppRoot.kt`)

Jetpack Navigation (`androidx.navigation:navigation-compose`) hosted by
`AppRoot`:

- **Four top-level tabs** (`HATab`): Home, Today, Records, Profile — rendered
  in the persistent bottom bar (`HABottomBar`). Tab content is Scaffold-less
  (it sits inside the nav `Scaffold`); only detail screens bring their own
  `Scaffold` + `TopAppBar` + back arrow.
- **Detail routes** (`HARoutes`): `sync`, `charts` — full-Scaffold screens
  reached from a tab; the bottom bar is hidden there and the system back
  pops to the tab. (R6 folded sync **settings** into the Profile tab as an
  expandable section — no standalone `settings` route anymore.)
- **Query-arg routes:** the biomarker graph detail takes a required code arg
  (`biomarker_detail?code={code}`) — Home metric cards + Biomarkers cards
  deep-link straight into the chart. `HATab.matches(route)` tolerates a query
  suffix. Deleted with the Insights tab:
  the bottom bar highlights correctly.

### The navigation gotchas (learned the hard way)

- **Tab switches use `popUpTo(startDestination) + launchSingleTop` only.** Do
  **not** add `saveState`/`restoreState` — with Navigation 2.9.x, `restoreState`
  on the **start** destination plus an arg-carrying entry (e.g. the card-opened
  Insights route) restores stale state and the Home tab stops working (you get
  stuck on Insights). Keep tab navigation stateless and predictable.
- **Deep-link navigations** (card → `biomarker_detail?code=X`) should also
  `popUpTo(startDestination)` so arg-carrying entries never stack on the back
  stack.
- The active connection's `BridgeClient` is built once in `AppRoot` from the
  active credential and passed to the stateful routes; screens stay pure.

## 3. Screen / Route pattern

Every destination is **three** pieces (Phase D, 2026-08-12):

- **`FooScreen` (pure):** takes data + lambdas, collects nothing, owns no
  coroutines — Compose-testable with a fake state object. Lives in
  `ui/FooScreen.kt`.
- **`FooViewModel` (stateful, Android-lifecycle-aware):** owns the screen's
  `StateFlow<FooUiState>`, all `viewModelScope` coroutines, and every
  side-effect (bridge reads, prefs writes, file I/O). Constructed by the
  Route via `viewModel(factory = FooViewModel.factory(...))` so the
  per-connection `BridgeClient` flows in (the rest of the deps are
  Koin-resolved at the call site). **Survives process death** (the OS
  killing the app to reclaim memory mid-edit no longer drops the user's
  state — the whole point of the Phase D refactor).
- **`FooRoute` (thin Compose shim):** builds the VM via its factory +
  collects its single state flow via `collectAsStateWithLifecycle()` +
  wires the screen's lambdas to one-liner VM delegates. ~50 lines max.

> **Status:** all destinations are migrated (Phase D + the 2026-08-16
> completion pass: Home / Records / ExaminationDetail / Today / Inbox /
> Medications / Allergies / Vaccines / Clinical events /
> Profile / Sync / Onboarding). Every new screen **must** use the ViewModel
> pattern from the start.

Current destinations and their data sources:

| Destination | Loads from | Notes |
|---|---|---|
| Home | `HomeViewModel` → `SyncMonitorRepository.monitor` + bridge `GET /observations/latest` + `GET /biomarkers` | Metric cards show the **server's** latest-per-biomarker (merged FHIR + telemetry — heart rate/steps/SpO₂ come back from the telemetry store) with catalog names/units/reference ranges; merged with local HC readings, newer wins. View styles (Grid / List / Simple) + the dashboard editor live in the **header status dropdown** (v1.3): status ("Up to date · Updated X ago" / syncing / offline / needs attention), Sync now, Edit dashboard, Layout picker, connection switching. An **AI button** beside it opens the web assistant; the header carries no server URL (that's Profile). Cache write-through (M8) keeps charts/cards instant offline. |
| Biomarkers (Records) | `BiomarkersViewModel` → cached catalog + cached latest-per-biomarker (offline-first) | Card list of all biomarkers (icon + name + last value + unit + relative time + trend arrow — Δ vs the previous reading via the cache's previous-per-biomarker query; most-recent first; "Show all" reveals never-measured ones; search). Card tap → the graph detail. |
| `biomarker_detail?code=` | `BiomarkerDetailViewModel` → cached observation series + background refresh | Vico chart drill-down: time-based x-axis with date labels, a dot per record, a translucent reference-range band, a Min/Max/Average/Last stats row over the selected range (`shared/data/Stats.kt`), and a "Latest reading" card (big tinted value + unit + relative time + trend chip: direction icon + % change vs the previous reading, hidden when there is no previous). Categorical/state biomarkers (`value_string`, no numeric) render a newest-first state-change timeline (consecutive same-state runs collapsed) instead of a coerced chart. Range chips 1W/1M/3M/1Y; an empty initial window auto-widens to 1Y once (until the user picks a range). Reached from the Biomarkers list + Home metric cards. **Set an alert** (M5) under the latest card opens the rule builder prefilled with this biomarker (`alert_rules?code=`). |
| `overview` | `OverviewViewModel` → cached observation series per picked code + cached catalog + background refresh | The wellness overview (M6): up to 3 user-picked biomarkers (selected chips + a searchable bottom-sheet picker over the cached catalog, Records-"Add reading"-style), each series normalized per-series by `shared/data/Normalization.kt` (min-max 0–1 or z-score, switchable) onto one now-anchored timeline — unlike units (kg, bpm, h) compare as shape. Legend swatch per series; the reference-range band + label render only for a single-series selection with a known range. Selection persists in `DashboardPrefsRepository` (`overview_codes`; default = the 3 most recently active biomarkers); StaleChip reflects the observations domain. Reached from the Overview row pinned atop the Biomarkers list (ADVANCED only). |
| Records | `RecordsViewModel` → bridge `GET /examinations`; uploads via `BridgeUploads.uploadDocument` with a 25 MiB client-side pre-check (Phase A.5) | Status chips from `extraction_status`; document upload; generalized manual entry; "Open full record" → **native** `ExaminationDetailRoute` (Phase A — was the WebView) with the document list + per-doc open (images inline via Coil; PDFs via FileProvider + `Intent.ACTION_VIEW` + `createChooser`) + "Open in browser" → the server's full PWA record in the external browser |
| Today (Phase E) | `TodayViewModel` → bridge `GET /observations/latest` + `GET /medications` + `GET /examinations` + `GET /notifications/inbox` + `GET /notifications/unread-count` | 5th bottom-nav tab. Daily summary: vitals cards (LazyRow), medications, recent exams, inbox preview. Four bridge reads in parallel; each individually error-tolerant. Uses SDK 0.4.0 typed readers. |
| ExaminationDetail | `ExaminationDetailViewModel` → bridge `GET /examinations/{id}` + `GET /examinations/{id}/documents` (+ lazy `GET /documents/{id}/preview` thumbnails) | Native list (Phase A). "Open in browser" hands off to the PWA record via `WebAppLauncher` (Custom Tabs). |
| Inbox (Phase I) | `InboxViewModel` → bridge `GET /notifications/inbox` + `PATCH /notifications/{id}/read` + `POST /notifications/read-all` | Detail route from Today's Inbox section. Per-item mark-read, "Mark all read", empty state. Owner-scoped (not patient-scoped). |
| Profile | `ProfileViewModel` → `UiPreferencesRepository` + `DashboardPrefsRepository` + `MedicationReminderRepository` | Connection card (server + id, Switch/Disconnect), **Simple / Advanced mode pick** (K-simple-mode), one row per detail page (Sync settings, Server notifications, Notifications, Accessibility, Privacy & data — app-lock, Phase B.4: BiometricPrompt + 4-digit PIN + 60s grace — About). Rows with no callback hide; SIMPLE drops the advanced rows (see §8). | 
| `alert_rules?code=` (M5) | `AlertRulesViewModel` → `AlertRulesRepository` (DataStore) + the offline-first biomarker catalog | Alert rules list (one plain-language sentence per rule, enable switch, remove with confirm) + the rule builder bottom sheet: searchable biomarker picker (catalog + HcType fallbacks) → direction chips (Above / Below / At or above / At or below / Outside a range), value field(s) with unit, duration presets (Right away / 5–30 min / 1 hour), and a live sentence preview — "Alert me when Heart rate is above 120 for 5 minutes". Entry points: Profile › Alerts (ADVANCED) + the biomarker detail's "Set an alert" (prefilled `code`). |
| Sync (detail) | `SyncViewModel` → `SyncMonitorRepository.monitor` | Plain-language failed-readings list, progress, clear-pending. HC availability probe + WorkManager trigger injected as lambdas. |
| Onboarding | `OnboardingViewModel` → shared `Onboarding` parsers (QR / paste-code / manual validation) | Pure screen + sealed `OnboardingError`; leads with the K.4 Simple/Advanced mode pick (`AppModeSection`, persisted on tap); the host (AppRoot) launches the QR scanner and owns the probe + credential-store flow. Form state survives process death. |
| App-lock gate | `AppLockManager.isLocked: StateFlow<Boolean>` | Full-screen `AppLockRoute` substitutes for `AppRoot` while locked (Phase B.4). `MainActivity` gates on it; `ProcessLifecycleOwner` feeds the 60s grace window app-wide. |

## 4. Reusable components (`ui/components/`)

| Component | Purpose |
|---|---|
| `MetricCard` | Health-metric card for any `BiomarkerReading` (icon, name, value, unit, time-ago, reference-range chip); optional `onClick` → deep-link; the value animates on change via `AnimatedValueText` |
| `HcIcon` / `biomarkerIcon` | Semantic icon per `HcType` (heart, steps, scale, air, moon) + generic science icon for instance-only biomarkers |
| `InsightsChart` | Vico `CartesianChartHost` line chart for `TimePoint(timeMs, value)` series: time-based x-axis (date labels), a dot per record, optional reference-range band (`HorizontalBox`), 300 ms entry draw-in (off under reduce-motion), a tap haptic when the marker engages, and a TalkBack sentence built from the loaded series |
| `OverviewChart` | Multi-series Vico line chart for normalized `OverviewSeries` lists: one now-anchored time axis, per-series theme-role palette (shared with the legend via `overviewSeriesColor`), marker tooltips inverted to real units through the fitted `SeriesScale`, optional normalized reference band; same entry animation/haptic/a11y treatment as `InsightsChart` |
| `AnimatedValueText` | M9 number transition: short slide+fade to a changed value, instant swap under reduce-motion |
| `Skeleton` (shimmer) | M9 loading placeholders — `ChartSkeleton` / `DetailSkeleton` / `ListSkeleton` — a tokened `surfaceVariant`→`surface` sweep that freezes under reduce-motion |
| `ChartBandColor` | Adaptive reference-band fill: alpha steps up from the 10 % baseline (capped at 24 %) until the band is perceptibly distinct from the surface, so Material You recoloring can't wash it out |
| `Haptics` | `rememberActionHaptic` / `rememberHaptic(type)` for Compose callbacks; `alertFireHaptic(context)` for the non-Compose alert notifier |
| `ManualEntryCard` | Generalized manual reading entry — type selector + value field |

Add shared visuals here, not in screens.

## 5. Data flow

- **Home cards** read the **server's** latest values per biomarker (`GET /observations/latest`) **and** the biomarker catalog (`GET /biomarkers`) via `HomeRoute`, built into `BiomarkerReading`s by `shared/data/HomeDashboard.kt`. This is source-agnostic: Health Connect types (heart rate, steps, SpO₂ — which the bridge now merges back from the telemetry store) and instance-only lab biomarkers (glucose, cholesterol, OCR'd values) render as identical cards. Local Health Connect readings remain the offline fallback; the merge keeps the newer timestamp.
- **Biomarkers** (Records › Biomarkers) merges the cached catalog with the cached latest snapshot for the card list; the graph detail loads the time series for the routed biomarker + `ChartRange` (1W/1M/3M/1Y → ISO `since`/`until` in `shared/data/ChartRange.kt`). The reference range from the catalog/observation is shown under the chips. The **Overview** (M6) loads one cached window per picked code and normalizes each series through `shared/data/Normalization.kt` before plotting; the pick persists in `DashboardPrefsRepository` and defaults to the 3 most recently active biomarkers.
- **Records** lists examinations from the bridge; uploads flow through the
  unchanged `BridgeUploads.uploadDocument` (idempotent on the client id).
  "Open full record" navigates to `exam_detail?examId=…` (fully native since
  Phase A). The "Open in browser" action hands off to the server's PWA record
  through `WebAppLauncher` (Chrome Custom Tabs, `ACTION_VIEW` fallback) — the
  app never holds the user's web JWT.
- The offline sync engine (`shared/sync`), Health Connect adapter, and the
  `SyncWorker` are unchanged by the redesign — the UI sits on top.

## 6. i18n

- All strings live in `res/values/strings.xml`; `res/values-el/strings.xml`
  mirrors the keys (English placeholders pending Greek translation).
- Format strings use positional args (`%1$s`/`%1$d`). For strings used inside
  coroutines/callbacks, resolve the template with `stringResource` at
  composition time and `.format(...)` at use time (avoids the
  `LocalContextGetResourceValueCall` lint error).

## 7. Instrumented UI tests

- Use **`createComposeRule()`** + `composableRule.setContent { FooScreen(fake) }`.
  `createAndroidComposeRule<MainActivity>()` fails ("already set content") and is
  not used.
- `app/build.gradle.kts` has `debugImplementation(ui-test-manifest)` to register
  the host activity in the debug manifest.
- Tests live in `src/androidTest/` next to the screen (e.g.
  `HomeScreenTest`, `BiomarkersScreenTest`, `RecordsScreenTest`, `SyncScreenTest`).
- On MIUI/POCO, `connectedDebugAndroidTest` needs Developer Options →
  "Background activity launch" enabled (see TROUBLESHOOTING.md).

## 8. Simple / Advanced mode (progressive disclosure)

One app, two levels of disclosure — the v2 plan's core design principle
(K-simple-mode). `UiPreferences.mode` (`settings/UiPreferences.kt`, closed
enum `UiMode { SIMPLE, ADVANCED }`, DataStore `ha_ui_prefs`) is picked in the
first-run wizard ("How do you want to use the app?") and re-changeable any
time from Profile — instantly, no restart (the DataStore flow re-emits and
the shell recomposes). The shared `ui/AppModeSection.kt` renders the pick in
both places.

**Default rule (conservative for existing installs):** a stored `mode` key
wins; a completely empty store (fresh install) starts SIMPLE; an install that
ever saved any ui pref without a mode stays ADVANCED — existing users keep
the full surface unchanged.

**What SIMPLE changes** (bottom bar stays the 3 tabs Home / Records / Profile):

| Surface | SIMPLE | ADVANCED |
|---|---|---|
| Home cards | large-print single-column style forced (`effectiveHomeViewStyle`; the stored dashboard pref is untouched) + the view-style menu and the dashboard edit dialog are hidden | Grid / List / Simple + edit dialog |
| Records | Biomarkers + the five clinical rows; the Doctors directory row hides | all rows |
| Biomarkers list | the wellness-overview entry row (M6) hides | the Overview row pins atop the list |
| Profile | connection card, the mode pick, Privacy & data (app-lock), About | + Open web dashboard, Readings & sync, Sync settings, Server notifications, Notifications (device), Accessibility, Alerts (M5) |
| Inbox | the settings gear (server prefs/triggers) hides | full |

The gating is done by passing `simpleMode` / null callbacks from `AppRoot`
into the Routes; the pure screens already hide anything whose callback is
absent, so SIMPLE stays testable with `createComposeRule()` + fake state
(`SimpleModeGatingTest`).

**K.5 typography audit:** the R1 large-print `HATypography` (body 16–17 sp,
titles 20 sp+) is applied **app-wide** in `HATheme` — unconditionally, so
SIMPLE inherits it everywhere. On top of that, SIMPLE forces the Home cards
into the R8 large `MetricCard` variant (28 sp icons, `headlineMedium` values)
via the effective view style; the SIMPLE view-style variant is the same code
path the dashboard's own "Simple" toggle uses. Touch targets: option rows and
list rows are ≥48 dp; primary buttons are 56 dp.

## 9. Polish & motion (M9)

The closing polish sweep of the monitoring-viz track — bounded, all
reduce-motion aware (`LocalReduceMotion`, the same token that drives the nav
transitions):

- **Animated numbers** — `AnimatedValueText` (slide+fade, 200 ms) behind the
  Home `MetricCard` values and the biomarker detail's Latest reading card;
  instant swap when reduce-motion is on.
- **Chart entry animation** — `InsightsChart`/`OverviewChart` draw in via
  Vico's entry animation at 300 ms (`animateIn = false` + `animationSpec =
  null` under reduce-motion).
- **Skeletons, not spinners** — `ui/components/Skeleton.kt` renders shimmer
  placeholders shaped like the loaded layout (chart block + stats row +
  latest card on the detail; a chart block on the overview; card rows on the
  biomarkers list). The sweep is a `surfaceVariant`→`surface` gradient and
  freezes to a plain tint under reduce-motion.
- **Haptics** — threshold-breach alerts fire a notification-style double
  tick from `AndroidAlertNotifier` (`alertFireHaptic`; `VIBRATE` declared);
  charts tick when a touch engages the marker (observed on the pointer
  Initial pass, so Vico's gestures are untouched); pull-to-refresh already
  haptics through Home's `onSyncNow` → `rememberActionHaptic`.
- **Pull-to-refresh label** — Home's sync row prefers the observations
  cache's "Updated X ago" (the StaleChip source, `cache_meta`) so the label
  advances on every pull refresh, not only push syncs; the biomarkers list
  shows the same stamp via its staleness chip.
- **Dynamic color** — the chart reference-range band alpha adapts
  (`ChartBandColor.referenceBandColor`) from a 10 % baseline up to a 24 %
  cap until the band is perceptibly distinct from the surface, verified by
  `ChartBandColorTest` over the branded schemes plus synthesized Material
  You-like tonal palettes (error/tertiary kept at ≥ 3:1).
- **TalkBack** — every chart exposes a `contentDescription` built from the
  loaded series ("Heart rate over last week, ranging from 58 to 142,
  currently 72 bpm"); the reduction is the pure, JVM-tested
  `shared/data/ChartSummary.kt`. Every widget row carries a Glance
  `semantics { contentDescription }` composed from its already-localized
  pieces. The font-scale gate (2.0× layout test) covers the biomarker
  detail screen and Home; the on-device TalkBack walkthrough is centralized.

## See also

- the (internal, gitignored) `dev/plans/UI-REDESIGN-PLAN.md` — the redesign plan: principles, information architecture, glossary, R-phase checklist.
- [development.md](./development.md) — dev setup, adding features, commit conventions.
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) — MIUI installs, connected-test hangs, Compose-test setup.
- [README.md](../README.md) — the repo front door.
- `../core/docs/MOBILE_SYNC.md` — the offline sync engine the UI renders.

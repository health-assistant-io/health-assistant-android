# Project Status — Health Assistant Android app

## Current phase: v1.2.0 — shipped (2026-10-09)

The offline-first companion app is feature-complete for daily use: native
reads of every clinical domain, charts, widgets, local alerts, Simple/
Advanced modes, and full offline operation. Next candidates live in the
"Open items" table below.

## Module status

| Module | Status | Notes |
|---|---|---|
| Onboarding + connection (QR/paste/manual, multi-connection) | ✅ stable | TOFU for self-signed certs |
| Sync engine (outbox → bridge `/sync`, `/changes` pull, dead-letter mgmt) | ✅ stable | SQLCipher-encrypted at rest |
| Offline cache (all domains, connection-scoped, `cache_meta` staleness) | ✅ stable | Room v9; atomic staleness writes |
| Health Connect source (13 types) | ✅ stable | Per-type permissions + toggles |
| Home / Records / Profile shell (3 tabs, ViewModels) | ✅ stable | All destinations migrated |
| Biomarker charts + stats + trend + state timelines | ✅ stable | Vico; pinch-zoom raw windows |
| Examinations + documents (native detail, upload, re-extract) | ✅ stable | Remembered viewer per MIME |
| Clinical records (meds/allergies/vaccines/events + doctors) | ✅ stable | CRUD via SDK readers |
| Inbox + server notification prefs/triggers + medication reminders | ✅ stable | UnifiedPush-ready |
| Simple/Advanced mode | ✅ stable | Default SIMPLE on fresh installs |
| Local alerts (M5) | ✅ stable | On-device evaluator; `ha_alert` channel |
| Widgets (M4) | ✅ stable | Glance 1.2; cache-only reads |
| Wellness overview (M6) | ✅ stable | ≤3 series, min-max/z-score |
| App lock (biometric/PIN) | ✅ stable | 60s grace |
| Baseline Profile + release build (R8) | ✅ stable | Regeneration runbook in dev docs |
| Polish & a11y (M9) | ✅ stable | Reduce-motion aware; TalkBack fallbacks; 2× font gate |

## Changelog

Kept in the root [CHANGELOG.md](../CHANGELOG.md) (Keep-a-Changelog format;
`[Unreleased]` is the live scope of the next release).

## Open items

| Item | Status | Notes |
|---|---|---|
| Greek translations for accumulated `values-el` placeholders | pending | English placeholders are lint-legal but untranslated |
| M3 leftovers (live HR tile on Home, today-vs-typical) | deferred | Superseded by the Home merge; revisit with user feedback |
| M7 LTTB downsampling | deferred | Only if 1-year ranges feel slow (pinch-zoom windowing covers most cases) |
| Remote + CI + published APK artifacts | missing | Repo is local-only today; needs remote, release workflow, real keystore |
| R7 roadmap (calendar view, AI bridging, analytics, anatomy explorer) | backlog | Calendar feasible app-side; the rest need `core/` work |
| TalkBack full walkthrough on-device | pending | Chart/widget fallbacks shipped (M9); the walkthrough remains manual |

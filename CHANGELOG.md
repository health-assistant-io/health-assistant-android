# Changelog — Health Assistant Android app

All notable user-visible changes to the Android companion app. Format follows
[Keep a Changelog](https://keepachangelog.com/); versions follow semver and are
tagged `vX.Y.Z` via `scripts/version_manager.py`.

## [Unreleased]

### Added

- **About page** (Profile › About) — the family `AboutPanel` pattern as a native page: app identity + version, description, Contact & Connect links (website, GitHub, tap-to-copy email), creator, tech-stack chips, the medical disclaimer, open-source licenses, and copyright — in English and Greek.

- **In-app language picker (Profile › Language)** — System default / English / Ελληνικά, applied instantly (platform `LocaleManager` on Android 13+, `AppCompatDelegate` below). `MainActivity` is now `AppCompatActivity` with `launchMode="singleTop"` — which also fixes widget taps on an already-open app silently dropping their navigation.
- **Full Greek translation** — the app is now fully localized: 435 strings translated, with the remaining entries intentionally English (brand names, URLs, format examples). Health Assistant also supports **per-app languages** (Android 13+): set its language independently of the phone in Settings → Apps → Health Assistant → Language, and adding a new locale is a documented, gate-enforced process (`docs/dev` + android/AGENTS.md).

### Added

- **Appearance presets (Profile › Appearance)** — three color themes: **Aurora** (new default — one periwinkle-indigo family, cool neutral surfaces), **Classic teal** (the original scheme), and **Material You** (wallpaper colors on Android 12+). AMOLED and high-contrast layer on top of any preset; a tap applies instantly.

### Changed

- **Profile redesign** — the gray card containers are gone: settings render as transparent groups with inset hairline dividers between rows (the modern settings-list look), matching the mode + appearance sections and the flattened connection block. The **Simple/Advanced** and **Appearance** selectors are now single compact rows (current value as the subtitle) that open dropdown menus — the fully-expanded mode picker remains in the onboarding wizard.
- **Home header rework** — the server URL is gone from the header (it lives in Profile), the "Ask the assistant" card became a compact **AI button** in the header, and a new **status dropdown** (next to the AI button) gathers the sync status ("Up to date · Updated X ago" / syncing / offline / needs attention), a Sync-now action, **Edit dashboard**, the **Layout style** picker (Grid/List/Simple), and connection switching — replacing the separate status row, view-style menu, and edit button.

### Added

- **Open-source readiness**: `LICENSE` (Apache-2.0 — the README always claimed it; the file now exists), `CODE_OF_CONDUCT.md`, `SECURITY.md`, `SUPPORT.md`, issue templates + PR checklist, Dependabot (gradle + actions, toolchain pins excluded), and `.gitleaks.toml` (full-history scan clean on CI's gitleaks 8.30.1; the two hits are committed onboarding test fixtures, allowlisted with rationale).
- **GitHub Actions**: `ci.yml` (full gate — build + composite suites; clones the public core repo for the bridge-SDK composite build), `release.yml` (v* tag → tag/version guard → signed-when-configured release APK attached with a stable-name alias), `gitleaks.yml` (push/PR/weekly, full history).
- CI-configurable signing: release builds sign with `-Pkeystore*` project properties when present (repo secrets in CI), falling back to debug signing; the SDK composite path is overridable via `HA_KOTLIN_SDK_DIR`.
- README: CI/gitleaks/release/license badges + an Install section with the stable APK link.

### Changed

- **Docs restructured to the family audience-split tree** — `docs/user/` (new user guide: getting started, feature catalog, offline & your data, troubleshooting) and `docs/dev/` (architecture, development workflow incl. the release runbook, UI patterns, dev troubleshooting), plus `docs/README.md`, `docs/STATUS.md`, and a machine-readable `docs/docs-tree.json` nav source of truth. The UI-redesign plan moved out of the tracked tree into the internal (gitignored) `dev/` directory.

## [v1.2.0] - 2026-10-09

### Added

- **Offline-first, connection-scoped caches** — every domain (observations, biomarkers, examinations, documents, medications, allergies, vaccines, clinical events, notifications) is cached per connection (Room v9 with a `cache_meta` staleness table), so switching patients can never show another connection's rows and cached data serves instantly offline.
- **Staleness UX** — a shared "Updated X ago" chip on every cached screen, "Showing saved data · offline" banners, and a new Profile › **Data & storage** page (per-domain row counts, last refresh, clear cached data, downloaded-documents size + clear).
- **Simple / Advanced mode** — a first-run "How do you want to use the app?" pick plus an instant Profile switch; SIMPLE keeps the essentials (Home, Records basics, connection, app-lock) with large-print cards, ADVANCED reveals the full surface.
- **Biomarker detail depth** — Min/Max/Average/Last stats row over the selected range, real Δ-vs-previous trend chips (list + detail), and a state-change timeline for categorical biomarkers instead of a coerced chart.
- **Local health alerts** — plain-language rule builder ("Alert me when Heart rate is above 120 for 5 minutes"), window + cooldown evaluator, `ha_alert` notification channel, Profile › Alerts, and "Set an alert" straight from a biomarker's detail screen.
- **Home-screen widgets** — Latest vitals (2×2), a configurable single-metric ring (2×1), and a live heart-rate widget (4×2) with sparkline; battery-capped refresh straight from the offline cache.
- **Wellness overview** — Records › Biomarkers › Overview: up to 3 biomarkers normalized (min-max or z-score) onto one timeline for correlation, with a searchable picker and persisted selection.
- **Chart upgrades** — time-based axes, reference-range bands, tap-to-inspect markers, and pinch-zoom that expands dense telemetry with raw-window fetch + adaptive ticks.
- **Polish & accessibility** — animated value transitions, chart entry animation (both honoring reduce-motion), shimmer skeleton loading, haptics (alert fire, chart tap, pull-to-refresh), adaptive dynamic-color range bands, TalkBack fallbacks for every chart and widget row, and a 2× font-scale layout gate.
- **Sync dead-letter management** — bounded dead-letter previews, revive-all, and 429-aware release.
- Debug-build-only on-device verification driver (broadcast-driven; documented in `docs/TROUBLESHOOTING.md`).

### Changed

- **Native charts replace the WebView** — the "Advanced charts" WebView is gone; the native Vico chart handles everything.
- **Three-tab shell** — Today merged into Home (one check-in surface) and Insights dissolved: biomarkers live under Records.
- **Profile hub → detail pages**; rich content now renders natively (biomarker About, document extracted text, patient notes + AI impressions on exams, full notification detail, server notification preferences/triggers, doctors directory); PWA hand-off via Chrome Custom Tabs.
- **Baseline Profile ships in the APK** — faster cold starts (regeneration runbook in `docs/TROUBLESHOOTING.md`).

### Fixed

- Chart range windows showed the **oldest** slice — now newest-first, now-anchored.
- The offline banner never cleared after connectivity recovery.
- Profile › Sync section crash (nested scrolling under infinite height).
- Two release-build (R8) crashes: SQLCipher JNI resolution and the ML Kit code scanner.
- Crash-loop on launch from a missing Koin interface binding (`AlertEngine.AlertNotifier`), plus an on-device DI resolution test so the class is caught in the gate.

## [v1.1.0] - 2026-08-13

Phase L release readiness: version bump, debug StrictMode, release build green
with R8 + resource shrinking. (Historical note: 1.1.0 was never tagged — this
changelog starts at v1.2.0, the first tag-driven release.)

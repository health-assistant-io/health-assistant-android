<div align="center">

<img src="android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="120" height="120" alt="Health Assistant logo">

# Health Assistant — Android
### Self-hosted, privacy-first health records — on Android

[![Release](https://img.shields.io/github/v/release/health-assistant-io/health-assistant-android?include_prereleases)](https://github.com/health-assistant-io/health-assistant-android/releases)
[![Status](https://img.shields.io/badge/status-beta-yellow.svg)](docs/STATUS.md)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%209%2B-lightgrey.svg)](#quick-start)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat&logo=kotlin)](https://kotlinlang.org/)

  <p>
    <small>Part of</small><br>
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://neuronection.com/logos/neuronection-dark.svg">
      <img src="https://neuronection.com/logos/neuronection.svg" height="30" alt="">
    </picture>&nbsp;&nbsp;&nbsp;<picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://neuronection.com/logos/neuronection-wordmark-dark.svg">
      <img src="https://neuronection.com/logos/neuronection-wordmark.svg" height="30" alt="Neuronection — one ecosystem, four guides">
    </picture>
  </p>

**Website**: [health-assistant.io](https://health-assistant.io) · **Repository**: [health-assistant-io/health-assistant-android](https://github.com/health-assistant-io/health-assistant-android)

</div>

> Your readings live on your phone and your own server — nothing passes
> through any third-party cloud, and the app never holds your server login.

---

## Table of contents

- [What is Health Assistant for Android?](#what-is-health-assistant-for-android)
- [What's different](#whats-different)
- [Features](#features)
- [Private by design](#private-by-design)
- [Quick start](#quick-start)
  - [Install from the latest release](#install-from-the-latest-release)
  - [Run from source (developers)](#run-from-source-developers)
  - [First steps](#first-steps)
- [Architecture at a glance](#architecture-at-a-glance)
- [Documentation](#documentation)
- [Tech stack](#tech-stack)
- [Scope & limitations](#scope--limitations)
- [Status & roadmap](#status--roadmap)
- [Community & support](#community--support)
- [Contributing](#contributing)
- [Security](#security)
- [License](#license)

---

## What is Health Assistant for Android?

The offline-first companion app for a self-hosted
[Health Assistant](https://github.com/health-assistant-io/health-assistant)
instance: it reads your on-device health data (Android **Health Connect**),
syncs it to your server through the **bridge** integration, and gives you
native biomarker charts, records, alerts, and home-screen widgets — no
browser required.

Under the hood every screen you've opened once keeps working offline, served
from an encrypted on-device cache that is scoped per connection. It is
**beta** software, built for self-hosters first.

## What's different

- **Offline-first, honestly.** Cached screens show their age ("Updated
  2 min ago", "Showing saved data · offline") instead of pretending to be
  fresh — and keep working in airplane mode.
- **One patient-scoped connection, no login.** The app never holds your
  server credentials; it pairs with one bridge instance via QR, and a leaked
  pairing affects exactly one patient's bridge paths.
- **Charts with clinical context.** Reference-range bands, trend deltas,
  stats over any window, multi-biomarker correlation — native, not a wrapped
  web page.
- **Alerts that stay yours.** Threshold rules ("alert me when heart rate is
  above 120 for 5 minutes") evaluate entirely on-device and notify locally.
- **Two altitudes, one app.** Simple mode for the essentials with large
  print; Advanced for everything — switchable instantly, chosen at onboarding.

## Features

- **Home** — latest readings as cards (in-range coloring, trend arrows),
  today's medications/exams/inbox in one scroll, quiet sync status.
- **Records** — biomarkers (catalog + charts + wellness overview),
  examinations with documents (inline image preview, PDF hand-off,
  re-extract, upload), medications, allergies, vaccines, clinical events,
  doctors directory.
- **Biomarker detail** — time-axis chart with reference band, tap-to-inspect
  markers, pinch-zoom into dense telemetry, Min/Max/Avg/Last stats, Δ vs
  previous, state-change timelines for categorical data.
- **Wellness overview** — up to 3 biomarkers normalized onto one timeline to
  spot correlations.
- **Local alerts** — plain-language rule builder, on-device evaluation with
  window + cooldown semantics.
- **Home-screen widgets** — latest vitals stack, single-metric ring, live
  heart rate with sparkline.
- **Inbox & notifications** — full notification bodies, server-side
  preference/trigger management, medication reminders, UnifiedPush support.
- **Offline & data** — SQLCipher-encrypted cache per connection, document
  byte cache with LRU cap, per-domain clear actions.
- **Simple / Advanced** app-wide modes; app lock (biometric or PIN);
  English + Greek.

## Private by design

The app talks to exactly one place: **your instance**, over HTTPS with
per-request HMAC signatures. Health Connect permissions gate what is read;
the connection credential lives in Keystore-backed encrypted storage; the
databases are SQLCipher-encrypted with `allowBackup=false`. Rich text
rendered from the server goes through an allowlist-based native renderer —
never a WebView. Threshold alerts evaluate on-device. The only other network
touch is opening the web dashboard in a Chrome Custom Tab when *you* tap
through.

## Quick start

### Install from the latest release

Download the APK (Android 9+):

| File | Link |
|---|---|
| `health-assistant-android-latest.apk` | [download](https://github.com/health-assistant-io/health-assistant-android/releases/latest/download/health-assistant-android-latest.apk) |

Sideload it (enable *Install unknown apps* for your browser/file manager),
then pair with your instance — see [First steps](#first-steps).

### Run from source (developers)

Prerequisites: JDK 25, Android SDK, a checkout of the sibling
[health-assistant](https://github.com/health-assistant-io/health-assistant)
repo (the bridge Kotlin SDK builds as a composite).

```bash
git clone https://github.com/health-assistant-io/health-assistant-android.git
git clone https://github.com/health-assistant-io/health-assistant.git ../core
cd health-assistant-android/android
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

The full gate and workflows: [docs/dev/development.md](docs/dev/development.md).

### First steps

1. On your instance's web UI, open the bridge integration screen and show
   the **connect QR**.
2. In the app: scan the QR (or paste the code, or enter the details).
3. Pick **Simple** or **Advanced** — switchable later in Profile.
4. Grant the Health Connect types you want to sync.

## Architecture at a glance

```
Health Connect (13 types) ─┐
                           ├─▶ sync engine (outbox, HMAC) ─▶ Bridge API ─▶ core/
Manual entry ──────────────┘        ▲                             (FHIR + telemetry)
                                    │ /changes pull
Room caches (SQLCipher, per-connection) ─▶ Compose UI (3 tabs)
                                    │                    ├─ alerts (on-device)
Document byte cache (LRU 100 MiB) ──┘                    └─ widgets (Glance)
```

- **KMP `shared/`** — pure-Kotlin sync core, mappers, repositories (JVM-tested).
- **`:app`** — Jetpack Compose, ViewModels, Room, WorkManager, Koin.
- **Bridge SDK** — included from the sibling core repo (composite build).

Details: [docs/dev/architecture.md](docs/dev/architecture.md).

## Documentation

| Document | Contents |
|---|---|
| [docs/user/](docs/user/README.md) | User guide — getting started, feature catalog, offline & your data, troubleshooting |
| [docs/dev/](docs/dev/README.md) | Architecture, development workflow, UI patterns, dev troubleshooting |
| [docs/STATUS.md](docs/STATUS.md) | What exists, current phase, open items |
| [CHANGELOG.md](CHANGELOG.md) | Notable changes per version |

## Tech stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose, Material 3, Glance (widgets), Vico (charts) |
| Core | Kotlin 2.3, KMP shared module, Coroutines/Flow |
| Data | Room + SQLCipher, DataStore, WorkManager, Health Connect |
| Networking | Ktor (bridge SDK), HMAC-SHA256 request signing |

## Scope & limitations

- Requires a self-hosted Health Assistant instance (the server is a separate
  repo) — there is no hosted service.
- Push notifications need a UnifiedPush distributor installed; without one,
  the app polls on its sync schedule.
- Greek translations are partially machine-pending (English placeholders in
  some strings).
- Rich editing stays in the web app; the phone app is read-mostly by design.

## Status & roadmap

**Beta** — feature-complete for daily use; current state and near-term items
in [docs/STATUS.md](docs/STATUS.md).

## Community & support

Questions: [Discord](https://discord.com/invite/SZCXNTwv) ·
Bugs & feature requests: [Issues](https://github.com/health-assistant-io/health-assistant-android/issues) ·
Support Health Assistant: [Buy Me a Coffee](https://buymeacoffee.com/healthassistant)

## Contributing

See [docs/dev/development.md](docs/dev/development.md) (dev setup, the
phase-delivery model, commit and release conventions). Issues and PRs
welcome — for bug reports include app version, Android version + device,
and what you expected vs. what happened.

## Security

Found a vulnerability? Do not open a public issue — see
[SECURITY.md](SECURITY.md) for the private disclosure process.

<!-- NEURONECTION:ECOSYSTEM:START -->
---

<div align="center">

### Part of the Neuronection family

**Health Assistant for Android** is one of four connected, open-source (Apache-2.0) AI assistants
for life's big decisions — structured data instead of text dumps, AI that explains
its reasoning, and you in control of your information.

<table>
  <tr>
    <td width="50%" align="center" valign="top">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="https://neuronection.com/logos/health-light.svg">
        <img src="https://neuronection.com/logos/health.svg" height="34" alt="Health Assistant">
      </picture>
      <br>
      <a href="https://neuronection.com/en/health/"><strong>Health Assistant</strong></a>
      <br><sub>Self-hosted, privacy-first health records — lab results, biomarkers, documents and AI-powered insights into your own data.</sub>
      <br><sub><a href="https://github.com/health-assistant-io/health-assistant">GitHub</a> · <a href="https://health-assistant.io">health-assistant.io</a></sub>
    </td>
    <td width="50%" align="center" valign="top">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="https://neuronection.com/logos/career-light.svg">
        <img src="https://neuronection.com/logos/career.svg" height="34" alt="Career Assistant">
      </picture>
      <br>
      <a href="https://neuronection.com/en/career/"><strong>Career Assistant</strong></a>
      <br><sub>A mapped universe of jobs — family tree + relation graph, AI match scoring and university pathways, built for students deciding their future.</sub>
      <br><sub><a href="https://github.com/neuronection/career-assistant">GitHub</a> · <a href="https://github.com/neuronection/career-assistant/tree/main/docs">Docs</a></sub>
    </td>
  </tr>
  <tr>
    <td width="50%" align="center" valign="top">
      <img src="https://neuronection.com/logos/study.svg" height="34" alt="Study Assistant">
      <br>
      <a href="https://neuronection.com/en/study/"><strong>Study Assistant</strong></a>
      <br><sub>A local-first study workbench, in browser or on desktop — AI-powered course library, handwriting, chat and practice; math-first, subject-agnostic.</sub>
      <br><sub><a href="https://github.com/neuronection/study-assistant">GitHub</a> · <a href="https://github.com/neuronection/study-assistant/tree/main/docs">Docs</a></sub>
    </td>
    <td width="50%" align="center" valign="top">
      <img src="https://neuronection.com/logos/desktop.svg" height="34" alt="Desktop Assistant">
      <br>
      <a href="https://neuronection.com/en/desktop/"><strong>Desktop Assistant</strong></a>
      <br><sub>A system-tray AI launcher for Windows, Linux and macOS — global hotkey, streaming chat, voice input, attachments; local-only history.</sub>
      <br><sub><a href="https://github.com/neuronection/desktop-assistant">GitHub</a> · <a href="https://github.com/neuronection/desktop-assistant/tree/main/docs">Docs</a></sub>
    </td>
  </tr>
</table>

Created and maintained by [Ilias Chatzopoulos](https://github.com/constLiakos)
· [LinkedIn](https://www.linkedin.com/in/ilias-chatzopoulos-aabb22163/)
· [info@health-assistant.io](mailto:info@health-assistant.io) · [info@neuronection.com](mailto:info@neuronection.com)

[neuronection.com](https://neuronection.com) — one ecosystem, four guides
· [♥ Support development](https://buymeacoffee.com/neuronection) · star what you use

</div>
<!-- NEURONECTION:ECOSYSTEM:END -->

## License

Apache-2.0 — see [LICENSE](LICENSE).

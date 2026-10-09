**Identity role: device client (identity-auth §9 machine/device class).**
This app authenticates to `core/` exclusively through the integrations
HMAC bridge (`X-Api-Signature`, per-integration secret, one-patient
blast radius). It never holds a user token, has no accounts, no
profiles, and no MFA surface.

# AGENTS.md — Health Assistant Android app (`app/`)

This repo is the **Android companion app** (Jetpack Compose + KMP shared core). It
is a **sibling of `core/`** — the backend + bridge integration + Kotlin SDK repo.
The parent `Health-Assistant/` is **not** a git repo; `core/` and `app/` are two
separate git repos that must be checked out side by side.

> The detailed, per-session agent orientation lives in
> [`android/AGENTS.md`](android/AGENTS.md) — read that for the module map, the
> bridge wire contract, Health Connect mapping, and the build/test gate. This
> file is the workspace-root orientation only.

## Two-repo model (critical)

```
Health-Assistant/                         # parent (NOT a git repo)
├── core/   # git: backend + integrations/ + frontend/ + the Kotlin SDK
│   └── integrations/health_assistant_bridge/kotlin-sdk/   # the SDK (consumed via includeBuild)
└── app/    # git: THIS repo — the Android app
    ├── shared/    # KMP shared core (sync engine, mapper, onboarding, reads)
    └── android/   # Gradle composite root: :app (Compose) + includeBuild("../shared") + kotlin-sdk
```

**Which commits where:** app code (`android/`, `shared/`) → this repo (`app/`);
the Kotlin SDK, the bridge backend paths, and the bridge frontend → `core/`. A
change spanning both (e.g. a kotlin-sdk API the app calls) is **two commits** —
one in each repo — kept in the same working session.

## Skills (shared with `core/` via symlink)

`.opencode/skills` is a **symlink to `../core/.opencode/skills`** — a single
source of truth, so every skill is available here with no duplication or drift.
Load the matching skill before a task:

| Task | Skill |
|---|---|
| Android app / KMP / Kotlin SDK / bridge read paths | `mobile` |
| The bridge integration / Integrations SDK / webhooks | `integrations` |
| FHIR / biomarkers / telemetry / taxonomy (the data the app reads) | `clinical-data` |
| Backend endpoints/services the bridge paths live in | `backend` |
| Frontend (the bridge QR / connect-URL card) | `frontend` |
| Version bump / release / changelog | `versioning` |
| 30,000-foot project orientation | `project-overview` |

**Path convention in skills:** skill paths are expressed relative to the
`Health-Assistant/` parent. From this `app/` workspace, prefix `core/...` paths
with `../` (e.g. `../core/integrations/health_assistant_bridge/kotlin-sdk/`).
`app/...` paths resolve directly from here.

## Build & test (opencode shell — CRITICAL)

opencode's bash shell is non-interactive and does NOT source `~/.bashrc`, so
`JAVA_HOME` / `ANDROID_HOME` are unset. Export them before any `./gradlew`:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64   # JDK 25 (host openjdk-21 is corrupted)
export ANDROID_HOME=/home/ilias/Android/Sdk
cd android
./gradlew build                    # full gate (compile + unit tests + ktlint + Android Lint)
./gradlew :app:testDebugUnitTest   # JVM unit tests (fast loop)
./gradlew :shared:test             # KMP shared core
./gradlew :app:ktlintFormat        # auto-format — RUN BEFORE committing
./gradlew :app:assembleDebug       # build the debug APK
./gradlew :app:installDebug        # install on a connected device
```

Toolchain (locked, do not change without re-validation): AGP 9.3.1 · Gradle 9.5 ·
Kotlin 2.3.20 · daemon JDK 25 · minSdk 28 · compileSdk/targetSdk 37 · Koin 4.2.x.

## See also

- [`android/AGENTS.md`](android/AGENTS.md) + `android/CONVENTIONS.md` — the full
  per-session agent orientation + conventions.
- [`../core/AGENTS.md`](../core/AGENTS.md) — the cross-cutting backend/frontend
  conventions (tenant isolation, JSONB `flag_modified`, no comments, CHANGELOG).
- [`README.md`](README.md) — the app's architecture / build / security front door.
- `../core/integrations/health_assistant_bridge/docs/` — the bridge wire contract.

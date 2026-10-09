# Developer docs

How the app is built, tested, verified on hardware, and released.

| Page | What it covers |
|---|---|
| [Architecture](architecture.md) | Module map, two-repo model, sync engine, Health Connect pipeline, offline cache, alerts/widgets subsystems, security model, SDK, testing layers |
| [Development](development.md) | Dev setup, phased delivery model, testing policy, adding features, commit + release conventions |
| [UI patterns](ui-patterns.md) | The Compose UI architecture: design system, navigation, Screen/Route pattern, i18n, UI tests, Simple/Advanced mode |
| [Troubleshooting](troubleshooting.md) | Build/device/test issues: the working MIUI adb workflow, lint rules, Baseline Profile regeneration, R8 keep rules, the MIUI-proof verification driver |

Working notes, implementation plans, and audits live in the **gitignored
repo-root `dev/` directory** (internal only — never referenced from tracked
docs or code).

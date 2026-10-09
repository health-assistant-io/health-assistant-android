# Security Policy

## Supported versions

Only the latest release (and `main`) receive security fixes.

## Reporting a vulnerability

**Do not open a public issue for security problems.**

Please report privately via [GitHub security advisories](https://github.com/health-assistant-io/health-assistant-android/security/advisories/new)
— or email [info@neuronection.com](mailto:info@neuronection.com). Include a
description, reproduction steps, and impact. You will get an acknowledgement
within a few days; we'll coordinate a fix + disclosure timeline with you.

## Scope notes

This app is a client for self-hosted [Health Assistant](https://github.com/health-assistant-io/health-assistant)
instances. Server-side vulnerabilities belong in the core repository's
security policy — reports about the bridge/API belong there, not here.

## What we already do

- Full-history secret scanning (gitleaks) on every push, PR, and weekly.
- No secrets in the repo: credentials are entered at runtime, stored in
  Keystore-backed encrypted storage; local runtime artifacts are gitignored.
- `allowBackup=false`, no cleartext traffic, R8/ProGuard on release builds.
- The AI trust boundary: model output is treated as untrusted input; the app
  renders HTML/Markdown through an allowlist-based native renderer, never a
  WebView or `dangerouslySetInnerHTML`-style path.

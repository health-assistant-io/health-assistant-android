# Feature catalog

Everything the app ships, as built. Screens are grouped the way you reach
them: the three tabs (Home, Records, Profile) plus detail screens and system
surfaces.

## Home (the daily check-in)

- **Metric cards** — your latest reading per biomarker (value, unit,
  time-ago, in-range coloring, trend arrow vs the previous reading). Tap any
  card to open its chart.
- **Header** — a compact **AI button** (opens the web assistant in a Chrome
  Custom Tab) beside a **status dropdown**: sync status ("Up to date ·
  Updated X ago", syncing, offline, or needs attention), **Sync now**,
  **Edit dashboard** (which cards show and in what order — Advanced mode),
  the **Layout** picker (Grid / List / Simple), and connection switching.
- **Today's sections** — medications due, recent examinations, and an inbox
  preview with unread count, all in one scroll.

## Appearance

Profile › **Appearance** picks the color theme: **Aurora** (the default — a
modern periwinkle-indigo palette), **Classic teal** (the original), or
**Material You** (your wallpaper's colors, Android 12+). Applied instantly,
no restart. AMOLED-black and high-contrast variants stay under Profile ›
Accessibility and work with every theme.

## Records

Five rows, one hub:

- **Biomarkers** — every biomarker on your instance (searchable; cards show
  the last value + trend). **Overview** pins at the top: pick up to 3
  biomarkers and see them normalized on one timeline to spot correlations.
- **Examinations** — exam list with status chips (pending/processed/needs
  review), full detail with notes, patient notes, AI impressions, and
  documents: images preview inline, PDFs open in your viewer, re-extract,
  upload, delete. The **+** button creates a new examination or adds a
  manual reading.
- **Medications / Allergies / Vaccines / Clinical events** — native lists
  with add/delete (medications, allergies), occurrence logging for events,
  and detail sheets.
- **Doctors** (Advanced) — your instance's doctors directory.

## The biomarker chart (detail screen)

- Time-axis line chart with your **reference-range band**, a dot per
  reading, and **tap-to-inspect markers**.
- **Pinch to zoom** into dense telemetry — the app fetches the raw window.
- **Stats row** — Min / Max / Average / Last over the selected range
  (1 week / month / 3 months / year).
- **Trend chip** — % change vs your previous reading.
- Categorical biomarkers (e.g. sleep stages) render a **state-change
  timeline** instead of a fake line chart.
- **"Updated X ago"** chip — when this data was last refreshed; becomes
  "Showing saved data · offline" or "tap to retry" when appropriate.

## Alerts (Advanced)

Local, private threshold alerts — nothing leaves the phone:

- Plain-language builder: *"Alert me when Heart rate is above 120 for
  5 minutes."*
- Rules evaluate on every synced reading; breaches notify on the dedicated
  **Health alerts** channel with cooldown so you're not spammed.
- Manage in Profile → Alerts, or start one straight from a biomarker's
  chart (**Set an alert**).

## Home-screen widgets

Three widgets, all reading your saved (offline) data:

- **Latest vitals** (2×2) — newest readings stacked as rows; tap opens the
  chart.
- **Single metric ring** (2×1) — one biomarker as a value-in-range ring;
  you pick the biomarker when placing it.
- **Live heart rate** (4×2) — current HR, last-hour sparkline, in-range
  status.

## Inbox & notifications

- Native **inbox** — full notification bodies, mark read/dismiss, mark-all.
- **Server notification preferences + biomarker triggers** (Advanced) —
  manage the rules your instance notifies you about.
- **Medication reminders** — a daily "Time to take X" notification per
  medication (hour you choose), with Snooze and Mark-as-taken.
- Push delivery via UnifiedPush when a distributor app is installed.

## Simple vs Advanced

One app, two altitudes. **Simple** keeps Home, records, connection, app
lock, and About — large-print cards, no management clutter. **Advanced**
adds the doctors directory, server notification rules, dashboard editing,
alerts, and Data & storage. Switch instantly in Profile; chosen during
onboarding.

## Profile

Connection card (switch between saved connections, disconnect), the mode
pick, **Readings & sync** (per-source results, sync now, dead-letter
management), **Sync settings** (Health Connect types, frequency, history
window), **Data & storage** (Advanced — cached-data sizes, clear actions),
**Privacy** (app lock: biometric or PIN, 60s grace), Accessibility, and
About.

## Offline behavior

Everything you've opened once keeps working offline — see
[Offline & your data](offline-and-data.md) for the full story.

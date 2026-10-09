# Getting started

From a fresh install to your data on screen, in about two minutes.

## 1. Install

Sideload the release APK (or build a debug one — see the
[dev docs](../dev/development.md)). The app needs Android 9 (API 28) or
newer. Health Connect is preinstalled on Android 14+; on earlier versions
the app will offer to install it.

## 2. Connect your instance

The app talks to your Health Assistant instance through its **bridge
integration** — one connection identity, bound to one patient, no account
login needed.

1. On your instance's web UI, open the bridge integration screen and show
   the **connect QR**.
2. In the app, tap **Scan QR code** and point the camera. (You can also
   paste the connect code, or enter base URL + integration id + secret by
   hand.)
3. The app probes the server, stores the credential encrypted on-device,
   and connects. That's the only login you'll ever do.

If the QR scans but connecting fails, see
[troubleshooting](troubleshooting.md#the-qr-scans-but-the-app-cant-connect) —
it's almost always a URL the phone can't reach.

## 3. Pick Simple or Advanced

The first screen after connecting asks **how you want to use the app**:

- **Simple (just the essentials)** — big, calm screens: your daily readings,
  records, connection. Advanced management surfaces stay hidden.
- **Advanced (all features)** — everything: doctors directory, server
  notification rules, dashboard editing, alerts, data & storage.

This is a preference, not a lock-in: switch anytime in **Profile → How do
you want to use the app?**, instantly, no restart.

## 4. Grant Health Connect permissions

Next, the app asks to read from **Health Connect** (heart rate, steps,
weight, blood pressure, blood glucose, temperature, SpO₂, sleep, and more —
13 types). Grant the ones you want; each type can also be toggled later in
**Profile → Sync settings → Health Connect types**.

Nothing is read without your say-so, and nothing leaves your instance —
readings sync directly from the phone to *your* server, encrypted in
transit, HMAC-signed per request.

## 5. Watch it flow

- **Home** shows your latest readings as cards, a quiet sync status row, and
  today's sections (medications, recent exams, inbox).
- Open **Profile → Readings & sync → Sync now** for an immediate full
  push+pull.
- Pull-to-refresh on any list also syncs.

From here on, sync happens in the background on the cadence you set
(Profile → Sync settings → Sync frequency), and everything you've opened
once keeps working [offline](offline-and-data.md).

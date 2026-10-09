# Offline & your data

## What works offline

Everything you've opened at least once:

- Home cards and today's sections
- The full Records area — biomarkers, charts, examinations, documents
  (including previously opened document files), medications, allergies,
  vaccines, clinical events
- The inbox
- Widgets (they read the same saved data)

Screens show **"Showing saved data · offline"** instead of pretending to be
fresh, and every cached screen carries an **"Updated X ago"** chip so you
always know how old the data is. New writes (a manual reading, a medication
edit) queue in an on-device outbox and drain automatically when you're back
online.

## Where it lives

- All saved clinical data sits in one **SQLCipher-encrypted** database on
  the phone; its key is held in the Android Keystore.
- Your connection credential is stored in
  Keystore-backed encrypted preferences.
- Downloaded document files live in the app's private storage, capped at
  100 MiB (oldest evicted first).
- The app has `allowBackup=false` — nothing is included in Android cloud
  backups or device-to-device migration.

## Multi-connection safety

You can save more than one connection (e.g., two family members' instances)
and switch in Profile. Saved data is **scoped per connection**: switching
never shows another connection's rows, and clearing one connection's data
leaves the others untouched.

## Clearing data

**Profile → Data & storage** (Advanced mode):

- **Clear cached data** — empties every domain's saved rows for the active
  connection (they re-download next time you're online).
- **Clear downloaded documents** — removes cached document files (metadata
  stays; files re-fetch on open).

Both ask for confirmation first. Disconnecting a connection in Profile also
offers to remove its data.

## Privacy in one paragraph

Readings originate on your device (Health Connect or manual entry), sync
directly to *your* self-hosted instance over HTTPS with per-request HMAC
signing, and never touch any third-party cloud. Alerts evaluate entirely
on-device. The only outbound traffic is to your instance (and, when you tap
through, the web dashboard in a Chrome Custom Tab).

## App lock

Optional, in Profile → Privacy: fingerprint/face or a 4-digit PIN, with a
60-second grace window when you briefly switch apps.

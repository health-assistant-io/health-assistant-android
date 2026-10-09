# Troubleshooting — Health Assistant app

The fixes for the problems users most often hit. Developer-oriented build,
test, and device-automation issues live in
[the dev troubleshooting page](../dev/troubleshooting.md).

## The QR scans but the app can't connect

**Cause:** the QR encodes a base URL the phone can't reach (e.g., `localhost`,
a LAN IP on a different network, or HTTPS with a self-signed cert the app
doesn't trust).

**Fix:**
1. Check the base URL resolves from the phone's browser.
2. For self-signed certs on LAN: the app uses trust-on-first-use (TOFU) —
   accept the fingerprint on first connect.
3. Set the per-instance **Connect URL** in the bridge config flow (or the
   `mobile.client_base_url` system setting) to a URL the phone can reach.

## Sync fails with "Couldn't refresh — tap to retry"

**Cause:** the app is offline, the server is unreachable, or the stored
credential was revoked (e.g., the integration was regenerated on the server).

**Fix:**
1. The chip reflects the *last* refresh: tap it to retry once you're back
   online, or pull-to-refresh on any list.
2. If it persists while the browser can reach the instance, reconnect:
   Profile → disconnect, then scan a fresh QR (the integration secret may
   have changed).
3. Health-source errors (a red badge on the sync screen) are per-source:
   Profile → Readings & sync shows each source's last result.

## Readings aren't reaching the server

**Cause:** the Health Connect type toggles are off, Health Connect
permissions were revoked, or sync is paused by the sync-frequency setting.

**Fix:**
1. Profile → **Sync settings** → *Health Connect types* — make sure the types
   you expect are enabled ("N types enabled").
2. Tap the source row → grant the Health Connect permissions it requests.
3. Check Profile → **Readings & sync** → the per-source "Last sync" line;
   **Sync now** forces a full push + pull.

## App opens to a blank screen or crashes immediately

**Fix:**
1. Update to the latest release — crash fixes ship fast.
2. Settings → Apps → Health Assistant → **Clear data** (removes the saved
   connection; you'll re-scan the QR), then relaunch.
3. Still crashing? Enable USB debugging and grab
   `adb logcat -b crash -d` — report it with the stack trace.

## The web dashboard opens but looks wrong / blank

The app opens the web dashboard in Chrome Custom Tabs — the browser renders
it, so most page issues are browser-side (cache, old Chrome, no connection).
Try the same URL in the phone's browser directly; if it fails there too,
it's the instance, not the app.

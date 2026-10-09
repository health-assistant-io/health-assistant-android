# Troubleshooting (development)

Build, test, device-automation, and release issues. End-user problems
(connection, sync, crash-on-open) live in
[user/troubleshooting.md](../user/troubleshooting.md).

## Building, installing & verifying on a device (the working adb workflow)

This is the exact flow that works on the POCO X3 (M2007J20CG). Follow it as-is
so a fresh session doesn't rediscover the MIUI quirks.

### 1. Build gate (always first)
```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64   # JDK 25 (host openjdk-21 is corrupted)
export ANDROID_HOME=$HOME/Android/Sdk
cd android && ./gradlew build    # compile + unit tests + ktlint + Android Lint
```

### 2. Install
```bash
./gradlew :app:installDebug
```
If it fails with `INSTALL_FAILED_USER_RESTRICTED`, either re-enable MIUI's
Developer Options **Install via USB** + **USB debugging (Security settings)**
(MIUI reverts them after reboots/test runs), or **bypass gradle's install
session entirely** — a direct streamed `adb install` is often allowed even when
the toggle flipped:
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```
(`-r` = replace; a fresh install of a *removed* package can still be blocked —
re-enable the toggle in that case.)

### 3. Launch cleanly
NavController restores the last destination across process restarts, so
`am start` alone may reopen the wrong tab. Always force-stop first:
```bash
adb shell am force-stop io.healthassistant.android
adb shell am start -n io.healthassistant.android/.MainActivity
```

### 4. Verify the UI (the model can't read screenshots)
Use the uiautomator semantics tree, not `screencap`:
```bash
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml /tmp/ui.xml
rg -o 'text="[^"]*"' /tmp/ui.xml | sort -u        # what text rendered
```
To find **tappable** elements: Compose *text* nodes often report
`bounds="[0,0][0,0]"` — the real hit targets are the **`clickable="true"`
ancestors**. Parse them (e.g. with python3), compute the center, then tap:
```bash
# python3: for node in tree.iter('node'): if node.get('clickable')=='true': print(bounds)
adb shell input tap <cx> <cy>
```
The 4-tab nav bar on the POCO (1080×2400): items sit at y≈2222, x ranges
Home `0–253`, Insights `275–528`, Records `550–804`, Profile `826–1080`
(centers 126/401/677/953). The **selected** tab is not reported as a clickable
node — don't be surprised it's missing from the list.

### 5. Instrumented / Compose UI tests
```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.healthassistant.android.ui.InsightsScreenTest
```
Gotchas in order of how often they bite:
- **The main app disappears.** A failed `connectedDebugAndroidTest` run can
  leave `io.healthassistant.android` uninstalled → `am instrument` then reports
  `INSTRUMENTATION_FAILED` (target missing). Reinstall the main app first
  (`installDebug` or `adb install -r`), then run the test.
- **MIUI blocks the test-APK split install** (`INSTALL_FAILED_USER_RESTRICTED`).
  Install the test APK directly, then instrument it:
  ```bash
  adb install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w -r -e class io.healthassistant.android.ui.InsightsScreenTest \
    io.healthassistant.android.test/androidx.test.runner.AndroidJUnitRunner
  ```
- **The run hangs on the lockscreen/notification shade.** Wake + dismiss the
  keyguard before instrumenting:
  ```bash
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  ```
- **Even then it may hang** on this POCO: instrumented activity launches need
  Developer Options → **Background activity launch** (MIUI-optimization). If it
  still hangs, **fall back to `installDebug` + uiautomator** (§4) — that is the
  reliable verification path for this device. The JVM tests
  (`:app:testDebugUnitTest`, `:shared:test`) and the build gate run fine.

### 6. Instrumented tests always run the same credential flow
Compose tests use `createComposeRule()` + `setContent` (never
`createAndroidComposeRule<MainActivity>` — see below).

## `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`

**Cause:** MIUI (Xiaomi/POCO/Redmi) blocks adb installs by default.
**Fix:** Developer Options → enable **Install via USB** + **USB debugging
(Security settings)**. MIUI reverts these after reboots/security events —
re-enable if installs suddenly fail again. A confirmation dialog may also pop up
on the phone screen — tap **Install**.

## Gradle daemon JVM crash: `ShenandoahEvacReserve… outside range`

**Cause:** the host's `openjdk-21` install is corrupted (`java -version` fails
with a Shenandoah GC error). JDK 25 is unaffected.
**Fix:** force the Gradle launcher to JDK 25:
```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
```
(Also set in `AGENTS.md` + `.opencode/opencode.json`.)

## `connectedDebugAndroidTest` hangs or fails

**Cause:** MIUI blocks the test-APK install (the `*.test` package), or blocks
the test from launching background activities, or the dual USB+wireless serials
confuse the runner.
**Fix:**
1. Pin one device: `export ANDROID_SERIAL=<serial>`.
2. Enable Developer Options → **Install via USB** + **USB debugging (Security
   settings)**, and **Background activity launch** (or the MIUI-optimization
   toggle) — MIUI reverts these after reboots.
3. If it hangs, the test APK install is waiting on a MIUI prompt → tap Install on
   the phone, or fall back to verifying via `installDebug` + `uiautomator dump`.
4. Alternative: run the tests directly against the already-installed test APK:
   `adb shell am instrument -w -e class <test_class> io.healthassistant.android.test/androidx.test.runner.AndroidJUnitRunner`.

## HTTP 401 on `/sync` or `/map` (HMAC signature rejected)

**Cause:** the client signed a different path/body form than the server verifies,
or the `api_secret` doesn't match.
**Fix:**
1. Run `./gradlew :kotlin-sdk:test` — the `SigningParityTest` catches most
   signing regressions (it asserts byte-for-byte parity with the Python
   `sign_request`).
2. Confirm the server's `api_secret` matches what the app stored (re-scan the QR
   or re-enter the credential).
3. Check that `/status` is NOT signed (it's the unsigned connectivity probe —
   signing it causes a 401). The Kotlin `getStatus()` never signs; if you added
   a custom call, don't sign `/status`.

## Compose UI test fails with "already set content" / "Intent resolved to different process"

**Cause:** two different broken setups:
1. `createAndroidComposeRule<MainActivity>()` + `setContent { … }` fails with
   **"has already set content"** — `MainActivity.onCreate` already calls
   `setContent`, so the rule's second `setContent` throws.
2. `createComposeRule()` without the host activity registered in the **app**
   (debug) manifest fails with **"resolved to different process"**.

**Fix:**
1. Use **`createComposeRule()`** (NOT `createAndroidComposeRule<MainActivity>()`),
   and render the screen in isolation with `composable.setContent { … }` + a fake
   state object — don't rely on AppRoot's nav state (it depends on the
   credential store).
2. Make sure `app/build.gradle.kts` has
   `debugImplementation(libs.androidx.compose.ui.test.manifest)` — that registers
   the host `ComponentActivity` in the app's debug manifest so `createComposeRule`
   can launch it. (`androidTestImplementation` alone is not enough.)

## Baseline Profile generation

The `:baselineprofile` module (`androidx.benchmark:benchmark-macro-junit4` +
the `androidx.baselineprofile` Gradle plugin 1.5.0) generates the app's
Baseline Profile. The committed profile lives in
`android/app/src/release/generated/baselineProfiles/` (`baseline-prof.txt` +
`startup-prof.txt`) and is packaged into the release APK as
`assets/dexopt/baseline.prof` (+ `.profm`); `androidx.profileinstaller`
compiles it on first run for side-loaded installs.

### Regenerating (the working local recipe)

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk

# 1. Boot the AVD headless (any rooted emulator or API 33+ device works;
#    the checked-in Pixel_10 AVD uses the android-37.1 ps16k image).
mkdir -p /tmp/opencode/avd && cp -r ~/.android/avd/Pixel_10.avd /tmp/opencode/avd/ \
  && cp ~/.android/avd/Pixel_10.ini /tmp/opencode/avd/
sed -i 's/disk.dataPartition.size=10G/disk.dataPartition.size=4G/' /tmp/opencode/avd/Pixel_10.avd/config.ini
ANDROID_AVD_HOME=/tmp/opencode/avd $ANDROID_HOME/emulator/emulator -avd Pixel_10 \
  -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim -no-snapshot &
adb wait-for-device && adb -s emulator-5554 shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'

# 2. Start the mock bridge + tunnel it into the emulator. Guest apps CANNOT
#    reach the 10.0.2.2 host alias on the ps16k image (connect times out) —
#    use adb reverse, which the generator's deep link expects.
python3 dev/tools/mock_bridge_server.py &          # serves http://0.0.0.0:8443
adb -s emulator-5554 reverse tcp:8443 tcp:8443

# 3. Generate — installs the nonMinifiedRelease app, onboards it against the
#    mock via the healthassistant://connect deep link, drives the CUJs, dumps
#    and merges the profile into app/src/release/generated/baselineProfiles/.
ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateReleaseBaselineProfile
```

Notes:
- **First launch of each install** pops a system "This app isn't 16 KB
  compatible" dialog on the ps16k image — the generator detects and dismisses
  it ("Don't Show Again", then OK) before driving the journeys.
- The rule runs several iterations (~5–8 min total). Both tests
  (`appJourneys`, `startupProfile`) must pass; the startup profile only
  contains the `startupProfile` collection (`includeInStartupProfile = true`).
- Commit the regenerated `baseline-prof.txt` + `startup-prof.txt` together.

### The POCO test phone (API 31, MIUI) cannot generate

`BaselineProfileRule.collect` hard-fails on it:

```
java.lang.IllegalArgumentException: Baseline Profile collection requires
API 33+, or a rooted device running API 28 or higher and rooted adb session
(via `adb root`).
```

This is a macrobenchmark library gate — MIUI 14 backported the `pm
dump-profiles` command (it writes a class-list dump to
`/data/misc/profman/<pkg>-primary.prof.txt`, readable by the `shell` group),
but the dump is class-level only (no methods, no H/S/P flags), so it is not a
usable substitute. Use the emulator recipe above.

### MIUI gotchas hit during generation attempts

- `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` on the
  `*.baselineprofile` test APK — the Developer Options **Install via USB** +
  **USB debugging (Security settings)** toggles had flipped off again (MIUI
  reverts them); a failed `connectedAndroidTest`-style run also **uninstalls
  the target app** (and its data) — reinstall from the pulled APK
  (`adb install -r <pulled base.apk>`) and re-onboard.
- `am start -W` reports `Status: ok` but MIUI keeps the launcher in front —
  the known background-activity-launch restriction (§5); tap the icon
  manually.

### Release build crashes on "Scan QR code": NPE in mlkit_code_scanner

**Cause:** tapping "Scan QR code" crashed the minified release build —
`NullPointerException: getClass() on null` inside
`com.google.android.gms.internal.mlkit_code_scanner.zzny.<init>`. The scanner
boots through the Firebase-component runtime
(`MlKitContext.get(SharedPrefManager.class)`), which returned null under R8.
**Fix:** keep the whole ML Kit surface in `proguard-rules.pro`
(`com.google.mlkit.**`, `gms.internal.mlkit_common.**`,
`gms.internal.mlkit_code_scanner.**`). Narrower keeps (only
`mlkit.vision.*` + `mlkit_code_scanner`) were NOT enough — the missing
component registration lives in `mlkit.common` / `mlkit_common`.

### Release build crashes at startup: SIGABRT in libsqlcipher JNI_OnLoad

**Cause:** the minified release build crashed on first run —
`Fatal signal 6 (SIGABRT)` in `libsqlcipher.so` `JNI_OnLoad`
(`sqlcipher::register_android_database_SQLiteCompiledSql` → JNI `FindClass`
→ abort). SQLCipher's JNI layer resolves `net.sqlcipher.*` classes **by
name**, and R8 had renamed them; the proguard-rules.pro had no SQLCipher
section. This shipped unnoticed because release APKs were unsigned (never
installable/runnable) until the L.1 debug-keystore signing.
**Fix:** `-keep class net.sqlcipher.** { *; }` + `-keep class
net.zetetic.** { *; }` in `proguard-rules.pro`. Any new JNI-resolving native
lib needs the same treatment.

## App launches to a blank screen / crashes immediately

**Cause:** usually a missing import, a ktlint violation that broke the build, or
an unhandled exception in `AppRoot` (e.g., the credential store is corrupt).
**Fix:**
1. `adb logcat -s AndroidRuntime:E` — the stack trace points to the failure.
2. Clear the app data: `adb shell pm clear io.healthassistant.android` → relaunch.
3. Rebuild: `./gradlew :app:assembleDebug` (ensure ktlintFormat ran).

## MIUI device: adb input injection / instrumented UI tests blocked

**Symptom (POCO M2007J20CG, MIUI 14):** `adb shell input tap` fails with
`SecurityException: Injecting to another application requires INJECT_EVENTS
permission`; `am instrument` hangs at the first UI test; even
`UiAutomation.injectInputEvent` from an instrumentation is denied. The MIUI
toggle that would fix it ("USB debugging (Security settings)",
`persist.security.adbinput`) requires a Mi account and may silently revert.
Implicit `am broadcast -a <action>` is also deferred
("Background execution not allowed: receiving Intent ...").

**Fix — drive the app through its own code via the debug broadcast driver
(`debug/DebugDriver.kt` + `debug/DebugDriverReceiver.kt`, registered only in
the debug manifest):**

```bash
B="am broadcast --receiver-foreground \
  -a io.healthassistant.DEBUG_DRIVE \
  -n io.healthassistant.android/.debug.DebugDriverReceiver"
adb shell $B --es cmd nav --es dest profile      # tabs: home|records|profile, or any raw route
adb shell $B --es cmd nav --es dest "biomarker_detail?code=8867-4"
adb shell $B --es cmd back
adb shell $B --es cmd mode --es arg simple       # or advanced
adb shell $B --es cmd sync                       # SyncScheduler syncNow + pullNow
adb shell $B --es cmd reading --es code 8867-4 --es value 72 [--el daysAgo 3]
adb shell $B --es cmd hctypes --es arg all       # enable every HC type toggle
```

Notes: the explicit `-n` component AND `--receiver-foreground` are both
required on MIUI (implicit action-only broadcasts get dropped); `am` on this
device has no `--ed` (double) option — pass numbers as strings. Verify screen
state read-only with `adb shell uiautomator dump` + `grep text=`.
Airplane-mode flows: `adb shell cmd connectivity airplane-mode enable|disable`.

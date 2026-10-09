package io.healthassistant.android.baselineprofile

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/** Generates the app Baseline Profile for the critical user journeys of plan
 * item L.1: cold start to Home, scrolling the Home dashboard, and the Records
 * → Biomarkers → biomarker graph detail drill-down. On a fresh install the
 * generator self-onboards against a local mock bridge through the
 * `healthassistant://connect` deep link (see dev/tools/mock_bridge_server.py);
 * on an already connected device the journeys run directly. Journeys run
 * before the startup collection so the startup profile reflects a pure cold
 * start on an onboarded app. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun appJourneys() =
        rule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = false,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle(TIMEOUT_MS)
            dismissCompatWarning(device)
            ensureOnboarded(device)
            scrollJourneyHome(device)
            openBiomarkerDetail(device)
        }

    @Test
    fun startupProfile() =
        rule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle(TIMEOUT_MS)
            dismissCompatWarning(device)
            ensureOnboarded(device)
            device.wait(Until.hasObject(By.text(HOME_SECTION_TODAY)), TIMEOUT_MS)
            device.waitForIdle(TIMEOUT_MS)
        }

    private fun dismissCompatWarning(device: UiDevice) {
        if (!device.wait(Until.hasObject(By.text(COMPAT_TITLE)), COMPAT_TIMEOUT_MS)) return
        repeat(COMPAT_RETRIES) {
            if (!device.hasObject(By.text(COMPAT_TITLE))) return
            if (device.hasObject(By.text(COMPAT_DONT_SHOW))) {
                tapPlain(device, COMPAT_DONT_SHOW)
                device.waitForIdle(SHORT_TIMEOUT_MS)
            }
            tapPlain(device, COMPAT_OK)
            device.wait(Until.gone(By.text(COMPAT_TITLE)), SHORT_TIMEOUT_MS)
        }
    }

    private fun ensureOnboarded(device: UiDevice) {
        val needsOnboarding =
            device.hasObject(By.text(ONBOARDING_HEADING)) || device.hasObject(By.text(WELCOME_SKIP))
        if (!needsOnboarding) return
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(DEEP_LINK)).apply {
                setPackage(PACKAGE_NAME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        InstrumentationRegistry.getInstrumentation().targetContext.startActivity(intent)
        if (!device.wait(Until.hasObject(By.text(CONNECT_BUTTON)), TIMEOUT_MS)) return
        if (!tapText(device, CONNECT_BUTTON)) return
        if (device.wait(Until.hasObject(By.text(HC_LATER)), TIMEOUT_MS)) {
            tapText(device, HC_LATER)
        }
        device.wait(Until.hasObject(By.text(TAB_RECORDS)), ONBOARD_TIMEOUT_MS)
        device.waitForIdle(TIMEOUT_MS)
    }

    private fun scrollJourneyHome(device: UiDevice) {
        repeat(2) { fling(device, up = true) }
        fling(device, up = false)
    }

    private fun openBiomarkerDetail(device: UiDevice) {
        if (!tapText(device, TAB_RECORDS)) return
        device.waitForIdle(TIMEOUT_MS)
        if (!tapText(device, ROW_BIOMARKERS)) return
        if (!device.wait(Until.hasObject(By.textStartsWith(SEARCH_HINT_PREFIX)), TIMEOUT_MS)) return
        if (!tapFirstBiomarkerCard(device)) {
            if (!tapTextContains(device, SHOW_ALL_PREFIX)) return
            device.waitForIdle(TIMEOUT_MS)
            if (!tapFirstBiomarkerCard(device)) return
        }
        if (!device.wait(Until.hasObject(By.text(RANGE_1W)), TIMEOUT_MS)) return
        tapText(device, RANGE_3M)
        device.waitForIdle(TIMEOUT_MS)
        tapText(device, RANGE_1W)
        fling(device, up = true)
        device.pressBack()
        device.waitForIdle(TIMEOUT_MS)
    }

    private fun fling(
        device: UiDevice,
        up: Boolean,
    ) {
        val w = device.displayWidth
        val h = device.displayHeight
        if (up) {
            device.swipe(w / 2, h * 3 / 4, w / 2, h / 5, 20)
        } else {
            device.swipe(w / 2, h / 2, w / 2, h * 4 / 5, 20)
        }
    }

    private fun tapText(
        device: UiDevice,
        text: String,
    ): Boolean = tapTextInClickable(device, By.text(text))

    private fun tapTextContains(
        device: UiDevice,
        text: String,
    ): Boolean = tapTextInClickable(device, By.textContains(text))

    private fun tapTextInClickable(
        device: UiDevice,
        selector: BySelector,
    ): Boolean {
        val obj =
            device.findObjects(selector).firstOrNull { candidate ->
                !candidate.visibleBounds.isEmpty && isInClickableAncestor(candidate)
            } ?: return false
        obj.click()
        return true
    }

    private fun tapPlain(
        device: UiDevice,
        text: String,
    ): Boolean {
        val obj = device.findObject(By.text(text)) ?: return false
        if (obj.visibleBounds.isEmpty) return false
        obj.click()
        return true
    }

    private fun isInClickableAncestor(node: UiObject2): Boolean =
        generateSequence(node) { it.parent }.take(ANCESTRY_DEPTH).any { it.isClickable }

    private fun tapFirstBiomarkerCard(device: UiDevice): Boolean {
        val bodyTop = device.displayHeight / 8
        val card =
            device
                .findObjects(By.textContains(""))
                .filter { obj ->
                    val t = obj.text.orEmpty()
                    t.isNotBlank() &&
                        obj.visibleBounds.top > bodyTop &&
                        !obj.visibleBounds.isEmpty &&
                        !isBiomarkersScreenChrome(t)
                }.minByOrNull { it.visibleBounds.top }
                ?: return false
        card.click()
        return true
    }

    private fun isBiomarkersScreenChrome(text: String): Boolean =
        text == ROW_BIOMARKERS ||
            text.startsWith(SEARCH_HINT_PREFIX) ||
            text.startsWith(SHOW_ALL_PREFIX) ||
            text.endsWith(COUNT_SUFFIX) ||
            text.startsWith(EMPTY_NO_READINGS) ||
            text.startsWith(EMPTY_NO_CATALOG)
}

private const val PACKAGE_NAME = "io.healthassistant.android"
private const val ONBOARDING_HEADING = "Connect to Health Assistant"
private const val WELCOME_SKIP = "Skip"
private const val CONNECT_BUTTON = "Connect"
private const val HC_LATER = "Not now"
private const val TAB_RECORDS = "Records"
private const val HOME_SECTION_TODAY = "Today"
private const val ROW_BIOMARKERS = "Biomarkers"
private const val SEARCH_HINT_PREFIX = "Search biomarkers"
private const val SHOW_ALL_PREFIX = "Show all ("
private const val COUNT_SUFFIX = " shown"
private const val EMPTY_NO_READINGS = "No readings yet"
private const val EMPTY_NO_CATALOG = "No biomarkers in your catalog"
private const val RANGE_1W = "Last week"
private const val RANGE_3M = "Last 3 months"
private const val DEEP_LINK =
    "healthassistant://connect?base_url=http://127.0.0.1:8443" +
        "&integration_id=11111111-2222-4333-8444-555555555555" +
        "&api_secret=local-baseline-profile-secret"
private const val TIMEOUT_MS = 10_000L
private const val SHORT_TIMEOUT_MS = 2_000L
private const val COMPAT_TIMEOUT_MS = 10_000L
private const val COMPAT_RETRIES = 3
private const val ONBOARD_TIMEOUT_MS = 45_000L
private const val ANCESTRY_DEPTH = 8
private const val COMPAT_TITLE = "Android App Compatibility"
private const val COMPAT_DONT_SHOW = "Don't Show Again"
private const val COMPAT_OK = "OK"

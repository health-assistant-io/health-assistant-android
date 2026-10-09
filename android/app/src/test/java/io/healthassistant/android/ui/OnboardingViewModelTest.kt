package io.healthassistant.android.ui

import io.healthassistant.android.settings.UiMode
import io.healthassistant.shared.onboarding.ConnectionCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase D migration — OnboardingViewModel is fully JVM-pure (validation via
 * the shared `Onboarding` parsers; connect is an injected callback), so the
 * three onboarding tiers are covered end-to-end here: QR/paste-code parsing,
 * deep-link parsing, manual validation, and the scan-failure path.
 */
class OnboardingViewModelTest {
    private val uuid = "123e4567-e89b-42d3-a456-426614174000"
    private val secret = "0123456789abcdef"

    private fun vm(prefilled: ConnectionCredential? = null): Pair<OnboardingViewModel, MutableList<ConnectionCredential>> {
        val connects = mutableListOf<ConnectionCredential>()
        return OnboardingViewModel(prefilled, connect = { connects.add(it) }) to connects
    }

    @Test
    fun `valid pasted code connects with the parsed credential`() {
        val (vm, connects) = vm()
        vm.onCodeChange("https://ha.example.com|$uuid|$secret")
        vm.submitCode("https://ha.example.com|$uuid|$secret")
        assertEquals(1, connects.size)
        assertEquals("https://ha.example.com", connects[0].baseUrl)
        assertEquals(uuid, connects[0].integrationId)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `garbage code surfaces InvalidCode and does not connect`() {
        val (vm, connects) = vm()
        vm.onCodeChange("not a connection code")
        vm.submitCode("not a connection code")
        assertTrue(connects.isEmpty())
        assertEquals(OnboardingError.InvalidCode, vm.state.value.error)
    }

    @Test
    fun `deep link payload parses through submitCode`() {
        val (vm, connects) = vm()
        vm.onScanResult("https://ha.example.com/connect?base_url=https%3A%2F%2Fha.example.com&integration_id=$uuid")
        assertEquals(1, connects.size)
        assertEquals(uuid, connects[0].integrationId)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `scan failure carries the scanner detail`() {
        val (vm, connects) = vm()
        vm.onScanFailed("camera busy")
        assertTrue(connects.isEmpty())
        val err = vm.state.value.error
        assertTrue(err is OnboardingError.ScanFailed && err.detail == "camera busy")
    }

    @Test
    fun `valid manual fields connect and clear the error`() {
        val (vm, connects) = vm()
        vm.onManualBaseChange("http://192.168.1.10:8000")
        vm.onManualIdChange(uuid)
        vm.onManualSecretChange(secret)
        vm.submitManual()
        assertEquals(1, connects.size)
        assertEquals("http://192.168.1.10:8000", connects[0].baseUrl)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `invalid manual id surfaces ManualInvalid`() {
        val (vm, connects) = vm()
        vm.onManualBaseChange("https://ha.example.com")
        vm.onManualIdChange("not-a-uuid")
        vm.submitManual()
        assertTrue(connects.isEmpty())
        assertEquals(OnboardingError.ManualInvalid, vm.state.value.error)
    }

    @Test
    fun `short api secret is rejected`() {
        val (vm, connects) = vm()
        vm.onManualBaseChange("https://ha.example.com")
        vm.onManualIdChange(uuid)
        vm.onManualSecretChange("short")
        vm.submitManual()
        assertTrue(connects.isEmpty())
        assertEquals(OnboardingError.ManualInvalid, vm.state.value.error)
    }

    @Test
    fun `prefilled credential seeds the code and manual fields`() {
        val (vm, _) = vm(ConnectionCredential("https://ha.example.com", uuid, secret))
        assertEquals("https://ha.example.com|$uuid|$secret", vm.state.value.code)
        assertEquals("https://ha.example.com", vm.state.value.manualBase)
        assertEquals(uuid, vm.state.value.manualId)
        assertEquals(secret, vm.state.value.manualSecret)
    }

    @Test
    fun `toggleManual flips the advanced section`() {
        val (vm, _) = vm()
        assertTrue(!vm.state.value.showManual)
        vm.toggleManual()
        assertTrue(vm.state.value.showManual)
    }

    @Test
    fun `mode pick defaults to simple`() {
        val (vm, _) = vm()

        assertEquals(UiMode.SIMPLE, vm.state.value.mode)
    }

    @Test
    fun `stored mode seeds the pick`() {
        val vm = OnboardingViewModel(null, {}, initialMode = UiMode.ADVANCED)

        assertEquals(UiMode.ADVANCED, vm.state.value.mode)
    }

    @Test
    fun `mode pick updates state and persists on tap`() {
        val persisted = mutableListOf<UiMode>()
        val vm = OnboardingViewModel(null, {}, UiMode.SIMPLE, persisted::add)

        vm.onModeChange(UiMode.ADVANCED)

        assertEquals(UiMode.ADVANCED, vm.state.value.mode)
        assertEquals(listOf(UiMode.ADVANCED), persisted)
    }
}

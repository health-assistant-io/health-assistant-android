package io.healthassistant.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase D — focused tests on the parts of the new ViewModel layer that are
 * pure logic (no device, no DataStore, no BridgeClient needed).
 *
 * What's covered:
 * - [RecordsUiState] sealed-interface shape — the Loaded/Error/Loading
 *   branches the screen matches on.
 * - [UploadResult] → human-readable string mapping (the upload status line
 *   the user sees), via the top-level [formatUploadStatus].
 *
 * What's NOT covered here (needs more infrastructure):
 * - The full RecordsViewModel reload + upload flow — needs a fake
 *   ManualEntrySource + BridgeClient. Tracked as a follow-up once mockk
 *   lands or the bridge-client surface gets an interface extraction. The
 *   on-device verification covers correctness in the meantime.
 * - HomeViewModel + ExaminationDetailViewModel end-to-end — same reason.
 */
class RecordsViewModelLogicTest {
    @Test
    fun `RecordsUiState Loaded carries the exam list`() {
        val s: RecordsUiState = RecordsUiState.Loaded(emptyList())
        assertEquals(0, (s as RecordsUiState.Loaded).exams.size)
    }

    @Test
    fun `RecordsUiState Error preserves the message`() {
        val s: RecordsUiState = RecordsUiState.Error("Couldn't load records.")
        assertEquals("Couldn't load records.", (s as RecordsUiState.Error).message)
    }

    @Test
    fun `formatUploadStatus None returns null`() {
        assertNull(formatUploadStatus(UploadResult.None, "L", "U %s", "F %s", "B"))
    }

    @Test
    fun `formatUploadStatus Uploading returns loading string verbatim`() {
        assertEquals("L", formatUploadStatus(UploadResult.Uploading, "L", "U %s", "F %s", "B"))
    }

    @Test
    fun `formatUploadStatus Uploaded substitutes the filename`() {
        val r =
            formatUploadStatus(
                UploadResult.Uploaded("report.pdf"),
                "L",
                "Uploaded %s",
                "F %s",
                "B",
            )
        assertEquals("Uploaded report.pdf", r)
    }

    @Test
    fun `formatUploadStatus Failed substitutes the message`() {
        val r =
            formatUploadStatus(
                UploadResult.Failed("network timeout"),
                "L",
                "U %s",
                "Failed: %s",
                "B",
            )
        assertEquals("Failed: network timeout", r)
    }

    @Test
    fun `formatUploadStatus TooLarge returns the cap message verbatim`() {
        assertEquals("Too large.", formatUploadStatus(UploadResult.TooLarge, "L", "U %s", "F %s", "Too large."))
    }
}

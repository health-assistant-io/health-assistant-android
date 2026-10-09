package io.healthassistant.shared.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalEventDetailTest {
    private val raw =
        """
        {
          "id": "ev1",
          "patient_id": "p1",
          "tenant_id": "t1",
          "type_id": "ty1",
          "type_details": {"id": "ty1", "name": "Migraine", "slug": "migraine",
                           "icon": "neurology", "color": "#7C3AED",
                           "description": "Recurring headache disorder", "phases": []},
          "status": "active",
          "title": "Chronic migraine",
          "description": "Episodes with **aura**; worse with stress.",
          "onset_date": "2026-01-15",
          "resolved_date": null,
          "occurrences": [
            {"id": "o1", "date": null, "intensity": 8, "notes": "Left side, 4h",
             "occurred_at": "2026-08-01T14:00:00", "title": null, "severity": "severe",
             "anatomy_id": null, "metadata": null},
            {"id": "o2", "occurred_at": "2026-08-10T09:30:00", "intensity": 5,
             "notes": null, "severity": "moderate"}
          ],
          "event_metadata": {"kind": "journey"},
          "examinations": [],
          "observations": [],
          "anatomy_links": []
        }
        """.trimIndent()

    @Test
    fun `parses the bridge detail shape`() {
        val d = parseClinicalEventDetail(raw)
        assertNotNull(d)
        assertEquals("ev1", d!!.id)
        assertEquals("Chronic migraine", d.title)
        assertEquals("active", d.status)
        assertEquals("2026-01-15", d.onsetDate)
        assertEquals("Migraine", d.typeDetails?.name)
        assertEquals("#7C3AED", d.typeDetails?.color)
        assertEquals(2, d.occurrences.size)
        assertEquals("2026-08-01T14:00:00", d.occurrences[0].occurredAt)
        assertEquals(8, d.occurrences[0].intensity)
        assertEquals("severe", d.occurrences[0].severity)
        assertEquals("moderate", d.occurrences[1].severity)
        assertTrue(d.description!!.contains("**aura**"))
    }

    @Test
    fun `returns null on garbage`() {
        assertNull(parseClinicalEventDetail("not json"))
        assertNull(parseClinicalEventDetail("{\"nope\": 1}"))
    }
}

package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.DocumentSummary
import io.healthassistant.shared.data.ExaminationSummary

/**
 * Network read abstractions for examinations + documents, isolating
 * [ExaminationRepository] / [DocumentRepository] from the SDK's ktor-dependent
 * `BridgeClient` so both stay pure-Kotlin + JVM-testable. Each method performs
 * exactly one bridge read and throws on a non-2xx response.
 */
interface ExaminationGateway {
    /** `GET /examinations` — the exam list snapshot (≤ 200). */
    suspend fun list(limit: Int = 50): List<ExaminationSummary>

    /** `GET /examinations/{id}` — the detail projection (fills diagnoses +
     *  impressions). */
    suspend fun detail(id: String): ExaminationSummary
}

interface DocumentGateway {
    /** `GET /examinations/{examId}/documents` — the exam's document snapshot. */
    suspend fun listForExam(examId: String): List<DocumentSummary>

    /** `GET /documents` — the patient-wide document snapshot (the full
     *  re-snapshot counterpart; offline-first M7). */
    suspend fun listAll(limit: Int = 500): List<DocumentSummary>
}

package io.healthassistant.android.data.repository

import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.BridgeReads
import io.healthassistant.shared.data.DocumentSummary
import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.repository.DocumentGateway
import io.healthassistant.shared.data.repository.ExaminationGateway

/**
 * Android record gateways — delegate to [BridgeReads] against the
 * per-connection [BridgeClient]. Constructed per active connection alongside
 * the repositories that wrap them.
 */
class BridgeExaminationGateway(
    private val client: BridgeClient,
) : ExaminationGateway {
    override suspend fun list(limit: Int): List<ExaminationSummary> = BridgeReads.listExaminations(client, limit)

    override suspend fun detail(id: String): ExaminationSummary = BridgeReads.examinationDetail(client, id)
}

class BridgeDocumentGateway(
    private val client: BridgeClient,
) : DocumentGateway {
    override suspend fun listForExam(examId: String): List<DocumentSummary> = BridgeReads.listDocumentsForExam(client, examId)

    override suspend fun listAll(limit: Int): List<DocumentSummary> = BridgeReads.listDocuments(client, limit = limit)
}

class BridgeDocumentByteGateway(
    private val client: BridgeClient,
) : io.healthassistant.shared.data.repository.DocumentByteGateway {
    override suspend fun content(docId: String): ByteArray = BridgeReads.getDocumentBytes(client, docId)

    override suspend fun preview(
        docId: String,
        page: Int?,
    ): ByteArray = BridgeReads.getDocumentPreview(client, docId, page)
}

class BridgeClinicalRecordGateway(
    private val client: BridgeClient,
) : io.healthassistant.shared.data.repository.ClinicalRecordGateway {
    override suspend fun medications(limit: Int): List<io.healthassistant.bridge.Medication> = client.getMedications(limit = limit).data

    override suspend fun allergies(limit: Int): List<io.healthassistant.bridge.Allergy> =
        client.getAllergies(active = null, limit = limit).data

    override suspend fun vaccines(limit: Int): List<io.healthassistant.bridge.Vaccine> = client.getVaccines(limit = limit).data

    override suspend fun clinicalEvents(limit: Int): List<io.healthassistant.bridge.ClinicalEvent> =
        client.getClinicalEvents(limit = limit).data
}

class BridgeNotificationGateway(
    private val client: BridgeClient,
) : io.healthassistant.shared.data.repository.NotificationGateway {
    override suspend fun inbox(limit: Int): List<io.healthassistant.bridge.NotificationItem> =
        client.getNotificationInbox(limit = limit).data

    override suspend fun markRead(recipientId: String): Boolean {
        client.markNotificationRead(recipientId)
        return true
    }

    override suspend fun markDismissed(recipientId: String): Boolean {
        client.markNotificationDismissed(recipientId)
        return true
    }

    override suspend fun markAllRead(): Int = client.markAllNotificationsRead().markedRead

    override suspend fun unreadCount(): Int = client.getUnreadNotificationCount()
}

package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.DocumentSummary
import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.cache.DocumentCache
import io.healthassistant.shared.data.cache.ExaminationCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the offline-first invariants of [ExaminationRepository] and
 * [DocumentRepository] (M3): cache-first observe with no gateway call, refresh
 * upserts + reconciles removals, offline / failed refreshes leave the saved
 * snapshot intact.
 */
class RecordRepositoriesTest {
    @Test
    fun `observeAll emits cached exams with no gateway call`() =
        runTest {
            val cache = FakeExaminationCache()
            cache.storeAll(listOf(exam("e1", "2026-08-01")))
            val gateway = RecordingExamGateway()
            val repo = ExaminationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val exams = repo.observeAll().first()

            assertEquals(listOf("e1"), exams.map { it.id })
            assertEquals("gateway must not be called for a plain observe", 0, gateway.listCalls)
        }

    @Test
    fun `refresh upserts and reconciles removals`() =
        runTest {
            val cache = FakeExaminationCache()
            cache.storeAll(
                listOf(
                    exam("e1", "2026-08-01"),
                    exam("e2", "2026-07-01"),
                ),
            )
            val gateway = RecordingExamGateway(list = listOf(exam("e1", "2026-08-01")))
            val repo = ExaminationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val outcome = repo.refresh()

            assertEquals(RefreshOutcome.REFRESHED, outcome)
            val ids = repo.observeAll().first().map { it.id }
            assertEquals("an exam deleted server-side must disappear locally", listOf("e1"), ids)
        }

    @Test
    fun `refresh offline keeps the saved list and returns OFFLINE`() =
        runTest {
            val cache = FakeExaminationCache()
            cache.storeAll(listOf(exam("e1", "2026-08-01")))
            val gateway = RecordingExamGateway()
            val repo = ExaminationRepository(cache, gateway, ConnectivityProvider { false }, FakeCacheMetaStore())

            assertEquals(RefreshOutcome.OFFLINE, repo.refresh())
            assertEquals("offline must skip the network entirely", 0, gateway.listCalls)
            assertEquals(1, repo.observeAll().first().size)
        }

    @Test
    fun `failed refresh keeps the saved list`() =
        runTest {
            val cache = FakeExaminationCache()
            cache.storeAll(listOf(exam("e1", "2026-08-01")))
            val gateway = RecordingExamGateway(listThrows = IllegalStateException("503"))
            val repo = ExaminationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            assertEquals(RefreshOutcome.FAILED, repo.refresh())
            assertEquals(1, repo.observeAll().first().size)
        }

    @Test
    fun `refreshDetail fills the detail projection on the same row`() =
        runTest {
            val cache = FakeExaminationCache()
            cache.storeAll(listOf(ExaminationSummary(id = "e1", extractionStatus = "pending")))
            val gateway =
                RecordingExamGateway(
                    detail = ExaminationSummary(id = "e1", extractionStatus = "processed", diagnoses = listOf("Hypertension")),
                )
            val repo = ExaminationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val outcome = repo.refreshDetail("e1")

            assertEquals(RefreshOutcome.REFRESHED, outcome)
            val loaded = repo.observeById("e1").first()
            assertEquals("processed", loaded?.extractionStatus)
            assertEquals(listOf("Hypertension"), loaded?.diagnoses)
        }

    @Test
    fun `onDeleted removes the row instantly`() =
        runTest {
            val cache = FakeExaminationCache()
            cache.storeAll(listOf(exam("e1", "2026-08-01")))
            val repo = ExaminationRepository(cache, RecordingExamGateway(), ConnectivityProvider { true }, FakeCacheMetaStore())

            repo.onDeleted("e1")

            assertNull(repo.observeById("e1").first())
        }

    @Test
    fun `documents refresh upserts and reconciles per exam`() =
        runTest {
            val cache = FakeDocumentCache()
            cache.storeAll(
                listOf(
                    doc("d1", "exam-1"),
                    doc("d2", "exam-1"),
                    doc("d9", "exam-2"),
                ),
            )
            val gateway = RecordingDocGateway(docs = listOf(doc("d2", "exam-1")))
            val repo = DocumentRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val outcome = repo.refreshForExam("exam-1")

            assertEquals(RefreshOutcome.REFRESHED, outcome)
            assertEquals(listOf("d2"), repo.observeForExam("exam-1").first().map { it.id })
            assertEquals(
                "another exam's documents must be untouched by the reconcile",
                listOf("d9"),
                repo.observeForExam("exam-2").first().map { it.id },
            )
        }

    @Test
    fun `documents offline refresh keeps the saved list`() =
        runTest {
            val cache = FakeDocumentCache()
            cache.storeAll(listOf(doc("d1", "exam-1")))
            val gateway = RecordingDocGateway()
            val repo = DocumentRepository(cache, gateway, ConnectivityProvider { false }, FakeCacheMetaStore())

            assertEquals(RefreshOutcome.OFFLINE, repo.refreshForExam("exam-1"))
            assertEquals(1, repo.observeForExam("exam-1").first().size)
        }

    private fun exam(
        id: String,
        date: String,
    ): ExaminationSummary = ExaminationSummary(id = id, examinationDate = date)

    private fun doc(
        id: String,
        examId: String,
    ): DocumentSummary = DocumentSummary(id = id, filename = "$id.pdf", examinationId = examId)

    private class RecordingExamGateway(
        private val list: List<ExaminationSummary> = emptyList(),
        private val detail: ExaminationSummary = ExaminationSummary(id = "x"),
        private val listThrows: Throwable? = null,
    ) : ExaminationGateway {
        var listCalls = 0
            private set

        override suspend fun list(limit: Int): List<ExaminationSummary> {
            listCalls++
            listThrows?.let { throw it }
            return list
        }

        override suspend fun detail(id: String): ExaminationSummary = detail
    }

    private class RecordingDocGateway(
        private val docs: List<DocumentSummary> = emptyList(),
    ) : DocumentGateway {
        override suspend fun listForExam(examId: String): List<DocumentSummary> = docs

        override suspend fun listAll(limit: Int): List<DocumentSummary> = docs
    }

    /** In-memory [ExaminationCache] mirroring the Room semantics (upsert by
     *  id, reconcile, date-desc observe). */
    private class FakeExaminationCache : ExaminationCache {
        private val rows = MutableStateFlow<List<ExaminationSummary>>(emptyList())

        override suspend fun storeAll(exams: List<ExaminationSummary>) {
            val byId = rows.value.associateBy { it.id }.toMutableMap()
            exams.forEach { byId[it.id] = it }
            rows.value = byId.values.sortedByDescending { it.examinationDate ?: "" }
        }

        override suspend fun storeAllSynced(
            exams: List<ExaminationSummary>,
            meta: io.healthassistant.shared.data.cache.CacheRefreshMeta,
        ) = storeAll(exams)

        override suspend fun reconcile(ids: List<String>) {
            rows.value = rows.value.filter { it.id in ids }
        }

        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }

        override fun observeAll(): Flow<List<ExaminationSummary>> = rows.asStateFlow()

        override fun observeById(id: String): Flow<ExaminationSummary?> =
            rows.map { list -> list.firstOrNull { it.id == id } }

        override suspend fun applyDeltas(rows: List<io.healthassistant.shared.data.cache.ExaminationDeltaRow>) {
            val existing = this.rows.value.associateBy { it.id }
            storeAll(rows.map { io.healthassistant.shared.data.cache.DeltaRows.merge(existing[it.id], it) })
        }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }

    /** In-memory [DocumentCache] mirroring the Room semantics (upsert by id,
     *  per-exam reconcile, created-desc observe). */
    private class FakeDocumentCache : DocumentCache {
        private val rows = MutableStateFlow<List<DocumentSummary>>(emptyList())

        override suspend fun storeAll(docs: List<DocumentSummary>) {
            val byId = rows.value.associateBy { it.id }.toMutableMap()
            docs.forEach { byId[it.id] = it }
            rows.value = byId.values.sortedByDescending { it.createdAt ?: "" }
        }

        override suspend fun storeAllSynced(
            docs: List<DocumentSummary>,
            meta: io.healthassistant.shared.data.cache.CacheRefreshMeta,
        ) = storeAll(docs)

        override suspend fun reconcileForExam(
            examId: String,
            ids: List<String>,
        ) {
            rows.value = rows.value.filter { it.examinationId != examId || it.id in ids }
        }

        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }

        override fun observeForExam(examId: String): Flow<List<DocumentSummary>> =
            rows.map { list -> list.filter { it.examinationId == examId } }

        override suspend fun setLocalManifest(
            docId: String,
            localPath: String,
            cachedAtEpochMs: Long,
        ) = Unit

        override suspend fun clearLocalManifest(docId: String) = Unit

        override suspend fun applyDeltas(rows: List<io.healthassistant.shared.data.cache.DocumentDeltaRow>) {
            val existing = this.rows.value.associateBy { it.id }
            storeAll(
                rows.map { d ->
                    val merged =
                        io.healthassistant.shared.data.cache.DeltaRows.merge(existing[d.id]?.toRow(), d)
                    DocumentSummary(
                        id = merged.id, filename = merged.filename, status = merged.status,
                        progress = merged.progress, externalId = merged.externalId,
                        createdAt = merged.createdAt, contentType = merged.contentType,
                        fileSize = merged.fileSize, examinationId = merged.examinationId,
                    )
                },
            )
        }

        override suspend fun reconcileAll(ids: List<String>) {
            this.rows.value = this.rows.value.filter { it.id in ids }
        }

        private fun DocumentSummary.toRow() =
            io.healthassistant.shared.data.cache.CachedDocumentRow(
                id = id, filename = filename, status = status, progress = progress,
                externalId = externalId, createdAt = createdAt, contentType = contentType,
                fileSize = fileSize, examinationId = examinationId, localPath = null, localCachedAt = null,
            )

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }
}

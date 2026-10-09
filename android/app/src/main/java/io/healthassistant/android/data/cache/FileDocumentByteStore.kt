package io.healthassistant.android.data.cache

import android.content.Context
import io.healthassistant.shared.data.cache.DocumentByteStore
import java.io.File

/**
 * File-based [DocumentByteStore] (offline-first M4). Stores one file per
 * document under `filesDir/ha_docs/` — private storage, survives cache-dir
 * cleanup, excluded from system backups by the app's backup rules. Writes are
 * atomic (tmp + rename) so a killed process never leaves a torn file; LRU
 * order is the file's last-modified time, refreshed on every write.
 *
 * The filename is the document id (a server UUID) with an `.bin` suffix —
 * never user input, so no traversal risk; [open] consumers read via
 * FileProvider which needs a recognizable name, so the caller copies/renames
 * when handing off to a viewer.
 */
class FileDocumentByteStore(
    context: Context,
) : DocumentByteStore {
    private val dir: File = File(context.applicationContext.filesDir, DIR_NAME)

    override fun read(docId: String): ByteArray? {
        val file = fileFor(docId)
        if (!file.exists()) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    override fun write(
        docId: String,
        bytes: ByteArray,
    ) {
        dir.mkdirs()
        val tmp = File(dir, "${fileFor(docId).name}$TMP_SUFFIX")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(fileFor(docId))) {
                fileFor(docId).delete()
                tmp.renameTo(fileFor(docId))
            }
            fileFor(docId).setLastModified(System.currentTimeMillis())
        }
    }

    override fun delete(docId: String) {
        fileFor(docId).delete()
    }

    override fun totalBytes(): Long = listFiles().sumOf { it.length() }

    override fun evictLRU(capBytes: Long): List<String> {
        val evicted = mutableListOf<String>()
        var total = totalBytes()
        val byAge = listFiles().sortedBy { it.lastModified() }
        for (file in byAge) {
            if (total <= capBytes) break
            total -= file.length()
            if (file.delete()) evicted += docIdOf(file)
        }
        return evicted
    }

    override fun clear() {
        listFiles().forEach { it.delete() }
    }

    /** The backing file for [docId] — for FileProvider-based viewer launch. */
    fun fileFor(docId: String): File = File(dir, "${sanitize(docId)}.bin")

    private fun listFiles(): List<File> = dir.listFiles()?.toList() ?: emptyList()

    private fun docIdOf(file: File): String = file.name.removeSuffix(".bin")

    private fun sanitize(id: String): String = id.map { c -> if (c.isLetterOrDigit() || c in "-_") c else '_' }.joinToString("").take(80)

    private companion object {
        const val DIR_NAME = "ha_docs"
        const val TMP_SUFFIX = ".tmp"
    }
}

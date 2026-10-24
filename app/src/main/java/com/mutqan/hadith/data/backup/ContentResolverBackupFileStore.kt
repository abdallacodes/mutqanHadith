package com.mutqan.hadith.data.backup

import android.content.ContentResolver
import android.net.Uri
import com.mutqan.hadith.domain.backup.BackupException
import com.mutqan.hadith.domain.backup.BackupFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * Reads and writes the documents the user picked with the system file picker (Storage Access
 * Framework): the app never needs a storage permission, only the one-off grant the picker gives.
 */
class ContentResolverBackupFileStore(
    private val resolver: ContentResolver,
    private val maxBytes: Long = MAX_BACKUP_BYTES,
) : BackupFileStore {

    override suspend fun write(target: String, text: String) = withContext(Dispatchers.IO) {
        // "wt" truncates: plain "w" may leave the tail of a longer old file behind on some providers.
        val out = resolver.openOutputStream(Uri.parse(target), "wt") ?: throw FileNotFoundException(target)
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun read(source: String): String = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(Uri.parse(source)) ?: throw FileNotFoundException(source)
        input.use {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = it.read(chunk)
                if (read < 0) break
                if (buffer.size() + read > maxBytes) throw BackupException.TooLarge(maxBytes)
                buffer.write(chunk, 0, read)
            }
            buffer.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        /** A backup is a few hundred KB even after years of study; anything past this is not one. */
        const val MAX_BACKUP_BYTES = 32L * 1024 * 1024
        private const val CHUNK_BYTES = 64 * 1024
    }
}

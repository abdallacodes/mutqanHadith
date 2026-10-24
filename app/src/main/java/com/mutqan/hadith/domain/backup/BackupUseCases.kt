package com.mutqan.hadith.domain.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A backup ready to be written to a file. */
data class ExportedBackup(
    val json: String,
    val masteryCount: Int,
    val eventCount: Int,
)

/** Serializes the user's mastery states and narrator review events into a versioned JSON backup. */
class ExportUserDataUseCase(
    private val backup: BackupRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend operator fun invoke(): ExportedBackup {
        val data = backup.readUserData()
        return ExportedBackup(BackupCodec.encode(data, now()), data.mastery.size, data.events.size)
    }

    /** `mutqan-hadith-backup-2026-10-24.json`, for the file picker's name field. */
    fun suggestedFileName(): String {
        val day = DateTimeFormatter.ISO_LOCAL_DATE.withLocale(Locale.ROOT).format(Instant.ofEpochMilli(now()).atZone(zone()))
        return "$FILE_PREFIX-$day.json"
    }

    companion object {
        const val FILE_PREFIX = "mutqan-hadith-backup"
    }
}

/** A backup that was read and checked and can be restored; nothing has been changed yet. */
data class PreparedBackup(val exportedAt: Long, val data: UserData) {
    val masteryCount: Int get() = data.mastery.size
    val eventCount: Int get() = data.events.size
}

data class ImportResult(val masteryCount: Int, val eventCount: Int)

/**
 * Restores a backup over the user's data. It is split in two so a screen can show what the file
 * holds and ask before anything is replaced: [prepare] validates, [apply] replaces atomically.
 */
class ImportUserDataUseCase(private val backup: BackupRepository) {

    /** @throws BackupException when the text is not a backup, is damaged, or refers to content this app lacks. */
    suspend fun prepare(json: String): PreparedBackup {
        val parsed = BackupCodec.decode(json)
        requireKnownContent(parsed.data)
        return PreparedBackup(parsed.exportedAt, parsed.data)
    }

    /** Replaces the user's data with the backup's in a single transaction; on failure nothing changes. */
    suspend fun apply(prepared: PreparedBackup): ImportResult {
        requireKnownContent(prepared.data)
        backup.replaceUserData(prepared.data)
        return ImportResult(prepared.masteryCount, prepared.eventCount)
    }

    suspend operator fun invoke(json: String): ImportResult = apply(prepare(json))

    private suspend fun requireKnownContent(data: UserData) {
        val library = backup.libraryIds()
        val hadithIds = data.mastery.map { it.hadithId } + data.events.map { it.hadithId }
        val missingHadiths = hadithIds.toSet().count { it !in library.hadithIds }
        val missingNarrators = data.events.map { it.narratorId }.toSet().count { it !in library.narratorIds }
        if (missingHadiths > 0 || missingNarrators > 0) throw BackupException.UnknownContent(missingHadiths, missingNarrators)
    }
}

/** Clears all review data, taking the app back to the pristine seeded library. */
class ResetProgressUseCase(private val backup: BackupRepository) {
    suspend operator fun invoke() = backup.clearUserData()
}

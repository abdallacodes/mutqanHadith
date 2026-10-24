package com.mutqan.hadith.domain.backup

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity

/**
 * Everything the user has produced: mastery state per Hadith and the narrator review history.
 * The library (Hadiths, narrators, chains) ships with the app and is not part of a backup.
 */
data class UserData(
    val mastery: List<MasteryRecordEntity>,
    val events: List<NarratorReviewEventEntity>,
) {
    companion object {
        val Empty = UserData(emptyList(), emptyList())
    }
}

/** Ids of the library this install ships with, to check that a backup only refers to known content. */
data class LibraryIds(val hadithIds: Set<Long>, val narratorIds: Set<Long>)

/** Why a backup could not be read or restored. The messages are for logs; screens show their own text. */
sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The file is empty, or JSON that is not one of our backups. */
    class NotABackup(detail: String) : BackupException("Not a Mutqan backup: $detail")

    /** It is one of our backups but damaged, or edited into something invalid. */
    class Corrupt(detail: String, cause: Throwable? = null) : BackupException("Corrupt backup: $detail", cause)

    /** Written by a newer version of the app than this one. */
    class UnsupportedVersion(val found: Int, val supported: Int) :
        BackupException("Backup format $found is newer than the supported $supported")

    /** The backup refers to Hadiths or narrators this version of the library does not have. */
    class UnknownContent(val missingHadiths: Int, val missingNarrators: Int) :
        BackupException("Backup refers to $missingHadiths unknown hadith(s) and $missingNarrators unknown narrator(s)")

    class TooLarge(val limitBytes: Long) : BackupException("Backup file is larger than $limitBytes bytes")
}

interface BackupRepository {
    /** A consistent snapshot of the user's data. */
    suspend fun readUserData(): UserData

    suspend fun libraryIds(): LibraryIds

    /** Replaces all user data with [data] in one transaction: either all of it lands or none. */
    suspend fun replaceUserData(data: UserData)

    /** Deletes all user data, leaving the library untouched, in one transaction. */
    suspend fun clearUserData()
}

/** Where backup text goes to and comes from: a document the user picked with the system file picker. */
interface BackupFileStore {
    /** Writes [text] to the document identified by [target], replacing what was there. */
    suspend fun write(target: String, text: String)

    /** Reads the document identified by [source]; throws [BackupException.TooLarge] for an absurdly big file. */
    suspend fun read(source: String): String
}

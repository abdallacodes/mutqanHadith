package com.mutqan.hadith.data.repository

import androidx.room.withTransaction
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.domain.backup.BackupRepository
import com.mutqan.hadith.domain.backup.LibraryIds
import com.mutqan.hadith.domain.backup.UserData

/**
 * User data lives in two tables, `mastery_record` and `narrator_review_event`; the library tables
 * are the app's own content and are never touched by a restore or a reset.
 */
class RoomBackupRepository(private val db: MutqanDatabase) : BackupRepository {

    override suspend fun readUserData(): UserData = db.withTransaction {
        UserData(db.masteryDao().getAll(), db.reviewHistoryDao().getAll())
    }

    override suspend fun libraryIds(): LibraryIds = db.withTransaction {
        LibraryIds(db.hadithDao().getAllIds().toSet(), db.narratorDao().getAllIds().toSet())
    }

    override suspend fun replaceUserData(data: UserData) {
        db.withTransaction {
            deleteUserData()
            db.masteryDao().upsertAll(data.mastery)
            db.reviewHistoryDao().insertAll(data.events)
        }
    }

    override suspend fun clearUserData() {
        db.withTransaction { deleteUserData() }
    }

    private suspend fun deleteUserData() {
        db.reviewHistoryDao().deleteAll()
        db.masteryDao().deleteAll()
    }
}

package com.mutqan.hadith.data.repository

import androidx.room.withTransaction
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.NarratorOutcomeCount
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository

class RoomHadithRepository(private val db: MutqanDatabase) : HadithRepository {
    override suspend fun getWithSanad(id: Long): HadithWithFullSanad? =
        db.hadithDao().getHadithWithSanad(id)

    override suspend fun getWithSanad(ids: Collection<Long>): List<HadithWithFullSanad> =
        if (ids.isEmpty()) emptyList() else db.hadithDao().getWithSanadByIds(ids)
}

class RoomReviewRepository(private val db: MutqanDatabase) : ReviewRepository {
    override suspend fun getMastery(hadithId: Long): MasteryRecordEntity? =
        db.masteryDao().getByHadithId(hadithId)

    override suspend fun getDueMastery(now: Long): List<MasteryRecordEntity> =
        db.masteryDao().getDue(now)

    override suspend fun saveReview(record: MasteryRecordEntity, narratorEvents: List<NarratorReviewEventEntity>) {
        db.withTransaction {
            db.masteryDao().upsert(record)
            if (narratorEvents.isNotEmpty()) db.reviewHistoryDao().insertAll(narratorEvents)
        }
    }

    override suspend fun getNarratorOutcomeCounts(hadithId: Long): List<NarratorOutcomeCount> =
        db.reviewHistoryDao().getOutcomeCounts(hadithId)
}

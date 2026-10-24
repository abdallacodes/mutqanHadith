package com.mutqan.hadith.data.repository

import androidx.room.withTransaction
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.HadithOutcomeCount
import com.mutqan.hadith.domain.model.NarratorOutcomeCount
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.NarratorRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import kotlinx.coroutines.flow.Flow

class RoomHadithRepository(private val db: MutqanDatabase) : HadithRepository {
    override suspend fun getWithSanad(id: Long): HadithWithFullSanad? =
        db.hadithDao().getHadithWithSanad(id)

    override suspend fun getWithSanad(ids: Collection<Long>): List<HadithWithFullSanad> =
        if (ids.isEmpty()) emptyList() else db.hadithDao().getWithSanadByIds(ids)

    override suspend fun getUnseen(): List<HadithWithFullSanad> = db.hadithDao().getUnseenWithSanad()

    override fun observeAll(): Flow<List<HadithWithFullSanad>> = db.hadithDao().observeAllWithSanad()

    override suspend fun getAll(): List<HadithWithFullSanad> = db.hadithDao().getAllWithSanad()

    override suspend fun getThroughNarrator(narratorId: Long): List<HadithWithFullSanad> =
        db.hadithDao().getHadithsByNarrator(narratorId)
}

class RoomNarratorRepository(private val db: MutqanDatabase) : NarratorRepository {
    override suspend fun getAll(): List<NarratorEntity> = db.narratorDao().getAll()
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

    override suspend fun logNarratorEvents(narratorEvents: List<NarratorReviewEventEntity>) {
        if (narratorEvents.isNotEmpty()) db.reviewHistoryDao().insertAll(narratorEvents)
    }

    override suspend fun getAllMastery(): List<MasteryRecordEntity> = db.masteryDao().getAll()

    override fun observeAllMastery(): Flow<List<MasteryRecordEntity>> = db.masteryDao().observeAll()

    override suspend fun getNarratorOutcomeCounts(hadithId: Long): List<NarratorOutcomeCount> =
        db.reviewHistoryDao().getOutcomeCounts(hadithId)

    override suspend fun getNarratorOutcomeTotals(): List<NarratorOutcomeCount> =
        db.reviewHistoryDao().getOutcomeTotals()

    override suspend fun getOutcomeCountsByHadith(narratorId: Long): List<HadithOutcomeCount> =
        db.reviewHistoryDao().getOutcomeCountsByHadith(narratorId)

    override suspend fun getReviewTimestamps(): List<Long> = db.reviewHistoryDao().getReviewTimestamps()
}

package com.mutqan.hadith.data.repository

import androidx.room.withTransaction
import com.mutqan.hadith.data.dao.searchInGroups
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.CollectionEntity
import com.mutqan.hadith.data.entity.HadithCollectionCrossRef
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.relation.HadithPlacement
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.CollectionSummary
import com.mutqan.hadith.domain.model.HadithOutcomeCount
import com.mutqan.hadith.domain.model.NarratorOutcomeCount
import com.mutqan.hadith.domain.repository.CollectionRepository
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.NarratorRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    override fun observeGroups(): Flow<List<CatalogGroupEntity>> = db.hadithDao().observeGroups()

    override fun observePlacements(): Flow<List<HadithPlacement>> = db.hadithDao().observePlacements()

    override fun getHadithsInGroup(groupId: Long): Flow<List<HadithEntity>> = db.hadithDao().getHadithsInGroup(groupId)

    override fun searchInGroups(groupIds: Collection<Long>, query: String): Flow<List<HadithEntity>> =
        db.hadithDao().searchInGroups(groupIds, query)
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

class RoomCollectionRepository(private val db: MutqanDatabase) : CollectionRepository {
    private val dao get() = db.collectionDao()

    override fun observeAll(): Flow<List<CollectionSummary>> = dao.observeSummaries()

    override fun observe(id: Long): Flow<CollectionEntity?> = dao.observe(id)

    override fun observeHadiths(collectionId: Long): Flow<List<HadithEntity>> = dao.observeHadiths(collectionId)

    override fun observeCollectionIdsOf(hadithId: Long): Flow<Set<Long>> = dao.observeCollectionIdsOf(hadithId).map { it.toSet() }

    override fun observeCollectedHadithIds(): Flow<Set<Long>> = dao.observeCollectedHadithIds().map { it.toSet() }

    override suspend fun getHadithIds(collectionId: Long): List<Long> = dao.getHadithIds(collectionId)

    override suspend fun isTitleTaken(title: String, exceptId: Long): Boolean = dao.countTitled(title, exceptId) > 0

    override suspend fun create(title: String, accent: Int, createdAt: Long, firstHadithId: Long?): Long =
        dao.insertWith(CollectionEntity(title = title, createdAt = createdAt, accent = accent), firstHadithId)

    override suspend fun update(id: Long, title: String, accent: Int) = dao.update(id, title, accent)

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun add(collectionId: Long, hadithId: Long, addedAt: Long) {
        dao.addReference(HadithCollectionCrossRef(collectionId, hadithId, addedAt))
    }

    override suspend fun remove(collectionId: Long, hadithId: Long) = dao.removeReference(collectionId, hadithId)
}

package com.mutqan.hadith.domain.repository

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.HadithOutcomeCount
import com.mutqan.hadith.domain.model.NarratorOutcomeCount
import kotlinx.coroutines.flow.Flow

interface HadithRepository {
    suspend fun getWithSanad(id: Long): HadithWithFullSanad?

    suspend fun getWithSanad(ids: Collection<Long>): List<HadithWithFullSanad>

    /** Hadiths with no mastery record yet, in curriculum order. */
    suspend fun getUnseen(): List<HadithWithFullSanad>

    fun observeAll(): Flow<List<HadithWithFullSanad>>

    suspend fun getAll(): List<HadithWithFullSanad>

    /** Every Hadith whose chain passes through [narratorId], at any position, in curriculum order. */
    suspend fun getThroughNarrator(narratorId: Long): List<HadithWithFullSanad>
}

interface NarratorRepository {
    suspend fun getAll(): List<NarratorEntity>
}

interface ReviewRepository {
    suspend fun getMastery(hadithId: Long): MasteryRecordEntity?

    /** Mastery records whose `nextReviewDue` is at or before [now]. */
    suspend fun getDueMastery(now: Long): List<MasteryRecordEntity>

    /** Persists the new mastery state and the per-narrator outcomes of the same review atomically. */
    suspend fun saveReview(record: MasteryRecordEntity, narratorEvents: List<NarratorReviewEventEntity>)

    /** Records narrator outcomes without touching the Hadith's mastery state. */
    suspend fun logNarratorEvents(narratorEvents: List<NarratorReviewEventEntity>)

    suspend fun getAllMastery(): List<MasteryRecordEntity>

    fun observeAllMastery(): Flow<List<MasteryRecordEntity>>

    suspend fun getNarratorOutcomeCounts(hadithId: Long): List<NarratorOutcomeCount>

    /** Per-narrator tallies over every Hadith and review. */
    suspend fun getNarratorOutcomeTotals(): List<NarratorOutcomeCount>

    /** One narrator's tally in each Hadith they appear in. */
    suspend fun getOutcomeCountsByHadith(narratorId: Long): List<HadithOutcomeCount>

    /** The moments reviews were recorded, in epoch millis; each review is listed once. */
    suspend fun getReviewTimestamps(): List<Long>
}

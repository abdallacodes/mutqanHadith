package com.mutqan.hadith.domain.repository

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.NarratorOutcomeCount

interface HadithRepository {
    suspend fun getWithSanad(id: Long): HadithWithFullSanad?

    suspend fun getWithSanad(ids: Collection<Long>): List<HadithWithFullSanad>
}

interface ReviewRepository {
    suspend fun getMastery(hadithId: Long): MasteryRecordEntity?

    /** Mastery records whose `nextReviewDue` is at or before [now]. */
    suspend fun getDueMastery(now: Long): List<MasteryRecordEntity>

    /** Persists the new mastery state and the per-narrator outcomes of the same review atomically. */
    suspend fun saveReview(record: MasteryRecordEntity, narratorEvents: List<NarratorReviewEventEntity>)

    suspend fun getNarratorOutcomeCounts(hadithId: Long): List<NarratorOutcomeCount>
}

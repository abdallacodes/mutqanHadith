package com.mutqan.hadith.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.model.HadithOutcomeCount
import com.mutqan.hadith.domain.model.NarratorOutcomeCount

@Dao
interface ReviewHistoryDao {
    @Insert
    suspend fun insertAll(events: List<NarratorReviewEventEntity>)

    @Query("SELECT * FROM narrator_review_event ORDER BY id")
    suspend fun getAll(): List<NarratorReviewEventEntity>

    @Query("DELETE FROM narrator_review_event")
    suspend fun deleteAll()

    /** When reviews happened: one timestamp per review (its events share it), for the study streak. */
    @Query("SELECT DISTINCT reviewedAt FROM narrator_review_event")
    suspend fun getReviewTimestamps(): List<Long>

    @Query(
        "SELECT narratorId, " +
            "SUM(CASE WHEN succeeded = 0 THEN 1 ELSE 0 END) AS errorCount, " +
            "SUM(CASE WHEN succeeded = 1 THEN 1 ELSE 0 END) AS successCount " +
            "FROM narrator_review_event WHERE hadithId = :hadithId GROUP BY narratorId"
    )
    suspend fun getOutcomeCounts(hadithId: Long): List<NarratorOutcomeCount>

    /** Every narrator's recall tally over the whole library, for the narrator graph. */
    @Query(
        "SELECT narratorId, " +
            "SUM(CASE WHEN succeeded = 0 THEN 1 ELSE 0 END) AS errorCount, " +
            "SUM(CASE WHEN succeeded = 1 THEN 1 ELSE 0 END) AS successCount " +
            "FROM narrator_review_event GROUP BY narratorId"
    )
    suspend fun getOutcomeTotals(): List<NarratorOutcomeCount>

    /** One narrator's tally in each Hadith they appear in. */
    @Query(
        "SELECT hadithId, " +
            "SUM(CASE WHEN succeeded = 0 THEN 1 ELSE 0 END) AS errorCount, " +
            "SUM(CASE WHEN succeeded = 1 THEN 1 ELSE 0 END) AS successCount " +
            "FROM narrator_review_event WHERE narratorId = :narratorId GROUP BY hadithId"
    )
    suspend fun getOutcomeCountsByHadith(narratorId: Long): List<HadithOutcomeCount>
}

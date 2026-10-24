package com.mutqan.hadith.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.model.NarratorOutcomeCount

@Dao
interface ReviewHistoryDao {
    @Insert
    suspend fun insertAll(events: List<NarratorReviewEventEntity>)

    @Query(
        "SELECT narratorId, " +
            "SUM(CASE WHEN succeeded = 0 THEN 1 ELSE 0 END) AS errorCount, " +
            "SUM(CASE WHEN succeeded = 1 THEN 1 ELSE 0 END) AS successCount " +
            "FROM narrator_review_event WHERE hadithId = :hadithId GROUP BY narratorId"
    )
    suspend fun getOutcomeCounts(hadithId: Long): List<NarratorOutcomeCount>
}

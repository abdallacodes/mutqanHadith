package com.mutqan.hadith.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MasteryDao {
    @Upsert
    suspend fun upsert(record: MasteryRecordEntity)

    @Query("SELECT * FROM mastery_record WHERE hadithId = :hadithId")
    suspend fun getByHadithId(hadithId: Long): MasteryRecordEntity?

    @Query("SELECT * FROM mastery_record WHERE hadithId = :hadithId")
    fun observeByHadithId(hadithId: Long): Flow<MasteryRecordEntity?>

    /** Records whose next review falls at or before [now], most overdue first. */
    @Query("SELECT * FROM mastery_record WHERE nextReviewDue <= :now ORDER BY nextReviewDue")
    suspend fun getDue(now: Long): List<MasteryRecordEntity>

    @Query("DELETE FROM mastery_record WHERE hadithId = :hadithId")
    suspend fun delete(hadithId: Long)
}

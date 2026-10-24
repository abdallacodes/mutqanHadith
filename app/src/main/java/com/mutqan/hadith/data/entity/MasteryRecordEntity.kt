package com.mutqan.hadith.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dual-decay memory state: the Sanad and the Matn decay independently, so each keeps its own
 * stability and its own last-review timestamp (null = never reviewed).
 * [nextReviewDue] is the earlier of the two components' due times.
 */
@Entity(
    tableName = "mastery_record",
    foreignKeys = [
        ForeignKey(
            entity = HadithEntity::class,
            parentColumns = ["id"],
            childColumns = ["hadithId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("nextReviewDue")],
)
data class MasteryRecordEntity(
    @PrimaryKey val hadithId: Long,
    val matnStability: Float,
    val sanadStability: Float,
    val matnLastReviewedAt: Long?,
    val sanadLastReviewedAt: Long?,
    val nextReviewDue: Long,
    val repetitionCount: Int,
)

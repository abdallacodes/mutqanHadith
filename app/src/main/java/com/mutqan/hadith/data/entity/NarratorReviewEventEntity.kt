package com.mutqan.hadith.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Whether one narrator of a Hadith's chain was recalled correctly in one review. */
@Entity(
    tableName = "narrator_review_event",
    foreignKeys = [
        ForeignKey(
            entity = HadithEntity::class,
            parentColumns = ["id"],
            childColumns = ["hadithId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = NarratorEntity::class,
            parentColumns = ["id"],
            childColumns = ["narratorId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("hadithId", "narratorId"), Index("narratorId")],
)
data class NarratorReviewEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hadithId: Long,
    val narratorId: Long,
    val reviewedAt: Long,
    val succeeded: Boolean,
)

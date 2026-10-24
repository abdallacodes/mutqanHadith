package com.mutqan.hadith.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One link of a Hadith's chain. [orderIndex] 0 is the narrator closest to the collector
 * (his direct teacher); the highest index is the Sahabi.
 *
 * [transmissionTerm] is the performance term (صيغة الأداء) by which the next link down the
 * chain (or the collector, for index 0) received the report from this narrator.
 */
@Entity(
    tableName = "sanad_edge",
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
    indices = [
        Index(value = ["hadithId", "orderIndex"], unique = true),
        Index("narratorId"),
    ],
)
data class SanadEdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hadithId: Long,
    val narratorId: Long,
    val orderIndex: Int,
    val transmissionTerm: String,
)

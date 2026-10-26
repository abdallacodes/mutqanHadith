package com.mutqan.hadith.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A personal, thematic grouping of hadiths: for a study circle, a sermon, a set of adhkār. It is the user's own and
 * has nothing to do with the curriculum levels or with spaced repetition.
 */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    /** Which of the app's accent tones colors the collection; see `CollectionAccents`. 0 is the first. */
    @ColumnInfo(defaultValue = "0") val accent: Int = 0,
)

/**
 * "This hadith is in this collection". Both sides cascade: deleting a collection removes only its references, never
 * the hadiths (the foreign key points from here to them, not the other way round), and a hadith that left the
 * library would leave its collections with it.
 */
@Entity(
    tableName = "hadith_collection_cross_ref",
    primaryKeys = ["collectionId", "hadithId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = HadithEntity::class,
            parentColumns = ["id"],
            childColumns = ["hadithId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("hadithId")],
)
data class HadithCollectionCrossRef(
    val collectionId: Long,
    val hadithId: Long,
    /** When the hadith joined the collection: the collection lists its hadiths in this order. */
    val addedAt: Long,
)

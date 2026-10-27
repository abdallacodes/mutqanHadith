package com.mutqan.hadith.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "narrator")
data class NarratorEntity(
    @PrimaryKey val id: Long,
    val primaryName: String,
    val fullName: String,
    val tier: String,
    val isPivot: Boolean = false,
    /**
     * When the cloud catalog last wrote this row (the document's `updatedAt`, epoch millis); 0 for a row that only the
     * bundled catalog has written. From the first cloud write on the cloud owns the row and a bundled catalog never
     * overwrites it (see `SeedLoader.sync`).
     */
    @ColumnInfo(defaultValue = "0") val catalogUpdatedAt: Long = 0,
    /**
     * The row this one is the same person as, written by an admin in the cloud catalog; null for a row nobody has said
     * is a duplicate. It changes nothing stored: the app only groups the two rows as one narrator when it reads them
     * (see `NarratorDirectory`), so it can be set and cleared without touching anyone's progress.
     */
    val sameAsId: Long? = null,
)

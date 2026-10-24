package com.mutqan.hadith.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "narrator")
data class NarratorEntity(
    @PrimaryKey val id: Long,
    val primaryName: String,
    val fullName: String,
    val tier: String,
    val isPivot: Boolean = false,
)

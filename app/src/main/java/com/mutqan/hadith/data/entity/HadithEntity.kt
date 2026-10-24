package com.mutqan.hadith.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hadith")
data class HadithEntity(
    @PrimaryKey val id: Long,
    val matnWithTashkeel: String,
    /** [com.mutqan.hadith.data.text.ArabicNormalizer] output of [matnWithTashkeel], used for search. */
    val matnClean: String,
    val sourceBook: String,
    val hadithNumber: Int,
    val chapter: String,
    val grade: String,
    val collector: String,
)

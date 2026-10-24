package com.mutqan.hadith.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "hadith", indices = [Index("level")])
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
    /** Who else reports the hadith, as the books word it: «متفق عليه», «رواه مسلم», «رواه البخاري». */
    @ColumnInfo(defaultValue = "''") val takhrij: String,
    /** The Companion the hadith is anchored to: the first layer of memorization, and the whole chain at level 1. */
    @ColumnInfo(defaultValue = "''") val sahabi: String,
    /** Which stage of the curriculum the hadith belongs to; see [com.mutqan.hadith.domain.model.HadithLevel]. */
    @ColumnInfo(defaultValue = "3") val level: Int = 3,
)

package com.mutqan.hadith.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "hadith", indices = [Index("level"), Index("groupId")])
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
    /**
     * Retired: the app no longer has levels, and the catalog writes 1 here. The column stays because SQLite cannot drop
     * one without rebuilding the table; nothing reads it.
     */
    @ColumnInfo(defaultValue = "3") val level: Int = 3,
    /** The book or section this hadith is filed under (`catalog_group.id`); null until the catalog has placed it. */
    val groupId: Long? = null,
    /** Where the hadith stands in the whole catalog, ascending: the order it is taught in. See `CurriculumOrder`. */
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
    /**
     * The book whose narration the chain is read from («صحيح مسلم», «سنن الترمذي»), shown with the chain; blank when the
     * chain is the Companion alone because no narration of the hadith could be followed to him.
     */
    @ColumnInfo(defaultValue = "''") val sanadSource: String = "",
)

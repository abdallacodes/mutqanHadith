package com.mutqan.hadith.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A book of the catalog, or a section of one (a chapter, a door): the shelves the library is browsed by. A group with no
 * [parentId] is a book; a group inside another is a section of it, to any depth. A hadith sits in exactly one group, the
 * deepest one that names it (`hadith.groupId`), and a group may hold both sections and hadiths of its own.
 *
 * Groups belong to the bundled catalog, like the hadiths: they are written and refreshed by `SeedLoader`, never by the
 * user, and nothing the user owns refers to them. So no foreign key ties a hadith to its group; the catalog may move a
 * hadith between groups, or drop a group, without a cascade ever reaching the user's progress.
 */
@Entity(tableName = "catalog_group", indices = [Index("parentId")])
data class CatalogGroupEntity(
    @PrimaryKey val id: Long,
    /** The group this one is a section of; null for a book. */
    val parentId: Long?,
    val title: String,
    /** The title in English; blank when the catalog has none, and the app then shows [title]. */
    val titleEn: String,
    /** Where this group stands among its siblings, ascending. */
    val position: Int,
    /**
     * When the cloud catalog last wrote this row (the document's `updatedAt`, epoch millis); 0 for a row that only the
     * bundled catalog has written. From the first cloud write on the cloud owns the row and a bundled catalog never
     * overwrites it (see `SeedLoader.sync`).
     */
    @ColumnInfo(defaultValue = "0") val catalogUpdatedAt: Long = 0,
)

package com.mutqan.hadith.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Which version of the bundled hadith catalog this database has absorbed (a single row, [id] 1).
 * It lets a new app version add or correct hadiths in an existing database without touching
 * the user's progress; see [com.mutqan.hadith.data.seed.SeedLoader.CATALOG_VERSION].
 */
@Entity(tableName = "catalog_state")
data class CatalogStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val version: Int,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

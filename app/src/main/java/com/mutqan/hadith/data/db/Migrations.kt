package com.mutqan.hadith.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real migrations keep the user's progress. Version 4 is what shipped in v1.0.0, so everything from it
 * on is migrated; the earlier versions only ever existed on development builds and are rebuilt instead
 * (see [MutqanDatabase.builder]).
 */
object Migrations {
    /**
     * Levels: every existing hadith is a level 3 (Sahih al-Bukhari) hadith. The new `takhrij` and `sahabi`
     * columns start empty and are filled, together with the level 1 hadiths, when the catalog is synced
     * on the next open (see `SeedLoader.sync`), because the catalog has no version recorded yet.
     */
    val V4_TO_V5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE hadith ADD COLUMN takhrij TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE hadith ADD COLUMN sahabi TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE hadith ADD COLUMN level INTEGER NOT NULL DEFAULT 3")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_hadith_level ON hadith (level)")
            db.execSQL("CREATE TABLE IF NOT EXISTS catalog_state (id INTEGER NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(id))")
        }
    }

    /**
     * Personal collections: two new tables and nothing else. No existing table is altered, so the progress in
     * `mastery_record` and `narrator_review_event` and the library itself are untouched by construction.
     */
    val V5_TO_V6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `accent` INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `hadith_collection_cross_ref` (`collectionId` INTEGER NOT NULL, " +
                    "`hadithId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`collectionId`, `hadithId`), " +
                    "FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`hadithId`) REFERENCES `hadith`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_hadith_collection_cross_ref_hadithId` ON `hadith_collection_cross_ref` (`hadithId`)")
        }
    }

    val ALL = arrayOf(V4_TO_V5, V5_TO_V6)
}

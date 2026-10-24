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

    val ALL = arrayOf(V4_TO_V5)
}

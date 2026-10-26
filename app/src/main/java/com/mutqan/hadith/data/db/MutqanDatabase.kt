package com.mutqan.hadith.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mutqan.hadith.data.dao.CollectionDao
import com.mutqan.hadith.data.dao.HadithDao
import com.mutqan.hadith.data.dao.MasteryDao
import com.mutqan.hadith.data.dao.NarratorDao
import com.mutqan.hadith.data.dao.ReviewHistoryDao
import com.mutqan.hadith.data.entity.CatalogStateEntity
import com.mutqan.hadith.data.entity.CollectionEntity
import com.mutqan.hadith.data.entity.HadithCollectionCrossRef
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity
import com.mutqan.hadith.data.seed.SeedLoader

@Database(
    entities = [
        NarratorEntity::class,
        HadithEntity::class,
        SanadEdgeEntity::class,
        NarratorReviewEventEntity::class,
        MasteryRecordEntity::class,
        CatalogStateEntity::class,
        CollectionEntity::class,
        HadithCollectionCrossRef::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class MutqanDatabase : RoomDatabase() {
    abstract fun hadithDao(): HadithDao
    abstract fun narratorDao(): NarratorDao
    abstract fun masteryDao(): MasteryDao
    abstract fun reviewHistoryDao(): ReviewHistoryDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        private const val NAME = "mutqan_hadith.db"

        @Volatile
        private var instance: MutqanDatabase? = null

        fun getInstance(context: Context): MutqanDatabase =
            instance ?: synchronized(this) {
                instance ?: builder(context.applicationContext, NAME).build().also { instance = it }
            }

        /** A null [name] builds an in-memory database. */
        fun builder(context: Context, name: String?): Builder<MutqanDatabase> {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, MutqanDatabase::class.java)
            } else {
                Room.databaseBuilder(context, MutqanDatabase::class.java, name)
            }
            return builder
                .addMigrations(*Migrations.ALL)
                // Versions 1-3 only ever existed on development builds: rebuild and reseed them. From
                // version 4 (v1.0.0) on, a schema change needs a real migration so progress survives.
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
                .addCallback(PrepopulateCallback(context))
        }
    }

    /**
     * Keeps the catalog current. A database with no Hadiths is seeded: on first creation, after a
     * destructive migration recreated the tables, or if an earlier seed was interrupted. One that holds
     * an older catalog than the app bundles is synced, adding and refreshing hadiths without touching
     * the user's progress. Runs on every open but costs two cheap queries once the catalog is current.
     */
    private class PrepopulateCallback(private val context: Context) : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            if (db.isReadOnly) return
            val empty = SeedLoader.isEmpty(db)
            if (!empty && SeedLoader.appliedVersion(db) >= SeedLoader.CATALOG_VERSION) return
            db.beginTransaction()
            try {
                val catalog = SeedLoader.load(context)
                if (empty) SeedLoader.write(db, catalog) else SeedLoader.sync(db, catalog)
                SeedLoader.markApplied(db, SeedLoader.CATALOG_VERSION)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}

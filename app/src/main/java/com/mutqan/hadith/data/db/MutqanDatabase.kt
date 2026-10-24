package com.mutqan.hadith.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mutqan.hadith.data.dao.HadithDao
import com.mutqan.hadith.data.dao.MasteryDao
import com.mutqan.hadith.data.dao.NarratorDao
import com.mutqan.hadith.data.dao.ReviewHistoryDao
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
    ],
    version = 3,
    exportSchema = true,
)
abstract class MutqanDatabase : RoomDatabase() {
    abstract fun hadithDao(): HadithDao
    abstract fun narratorDao(): NarratorDao
    abstract fun masteryDao(): MasteryDao
    abstract fun reviewHistoryDao(): ReviewHistoryDao

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
                // Dev only: replace with real migrations once the schema ships.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(PrepopulateCallback(context))
        }
    }

    private class PrepopulateCallback(private val context: Context) : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            SeedLoader.write(db, SeedLoader.load(context))
        }
    }
}

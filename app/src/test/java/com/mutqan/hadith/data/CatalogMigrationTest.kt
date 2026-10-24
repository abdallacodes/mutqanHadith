package com.mutqan.hadith.data

import android.content.ContentValues
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.seed.SeedLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A device that shipped v1.0.0 holds a version 4 database with the user's progress in it. Updating the app must
 * give it the levels and the new catalog without losing that progress, so the old schema is rebuilt here from the
 * schema Room exported for it, and then opened with the production database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seed = SeedLoader.load(context)
    private val progress = MasteryRecordEntity(1, 12.5f, 3.25f, matnLastReviewedAt = 1_700_000_000_000L, sanadLastReviewedAt = 1_700_000_100_000L, nextReviewDue = 1_700_500_000_000L, repetitionCount = 4)

    /** The exact version 4 schema: tables, indices and Room's own bookkeeping, as exported when v4 shipped. */
    private fun createVersion4(name: String) {
        val schema = JSONObject(File("schemas/com.mutqan.hadith.data.db.MutqanDatabase/4.json").readText(Charsets.UTF_8)).getJSONObject("database")
        assertEquals(4, schema.getInt("version"))
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val statements = mutableListOf<String>()
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                statements += entity.getString("createSql").replace("\${TABLE_NAME}", table)
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    statements += indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) statements += setup.getString(i)
            statements.forEach(db::execSQL)

            // What v1.0.0 seeded: ten Bukhari hadiths and their chains, with none of the new columns.
            seed.narrators.filter { it.id <= 39 }.forEach {
                db.insert("narrator", null, ContentValues().apply {
                    put("id", it.id); put("primaryName", it.primaryName); put("fullName", it.fullName); put("tier", it.tier); put("isPivot", if (it.isPivot) 1 else 0)
                })
            }
            seed.hadiths.filter { it.id <= 10 }.forEach {
                db.insert("hadith", null, ContentValues().apply {
                    put("id", it.id); put("matnWithTashkeel", it.matnWithTashkeel); put("matnClean", it.matnClean); put("sourceBook", it.sourceBook)
                    put("hadithNumber", it.hadithNumber); put("chapter", it.chapter); put("grade", it.grade); put("collector", it.collector)
                })
            }
            seed.edges.filter { it.hadithId <= 10 }.forEach {
                db.insert("sanad_edge", null, ContentValues().apply {
                    put("hadithId", it.hadithId); put("narratorId", it.narratorId); put("orderIndex", it.orderIndex); put("transmissionTerm", it.transmissionTerm)
                })
            }

            // The user's progress.
            db.insert("mastery_record", null, ContentValues().apply {
                put("hadithId", progress.hadithId); put("matnStability", progress.matnStability); put("sanadStability", progress.sanadStability)
                put("matnLastReviewedAt", progress.matnLastReviewedAt); put("sanadLastReviewedAt", progress.sanadLastReviewedAt)
                put("nextReviewDue", progress.nextReviewDue); put("repetitionCount", progress.repetitionCount)
            })
            listOf(6L to true, 5L to false, 4L to true).forEach { (narrator, ok) ->
                db.insert("narrator_review_event", null, ContentValues().apply {
                    put("hadithId", 1); put("narratorId", narrator); put("reviewedAt", 1_700_000_100_000L); put("succeeded", if (ok) 1 else 0)
                })
            }
            db.version = 4
        }
    }

    private fun open(name: String) = MutqanDatabase.builder(context, name).allowMainThreadQueries().build()

    private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun updatingFromV4KeepsTheUsersProgress() = runBlocking {
        createVersion4("shipped.db")

        val db = open("shipped.db")
        val raw = db.openHelper.writableDatabase

        assertEquals(progress, db.masteryDao().getByHadithId(1))
        assertEquals(1, raw.count("SELECT COUNT(*) FROM mastery_record"))
        assertEquals(3, raw.count("SELECT COUNT(*) FROM narrator_review_event WHERE hadithId = 1"))
        db.close()
    }

    @Test
    fun updatingFromV4MakesTheExistingHadithsLevelThreeWithTheirTakhrijAndSahabi() = runBlocking {
        createVersion4("shipped.db")

        val db = open("shipped.db")

        val old = db.hadithDao().getAllWithSanad().filter { it.hadith.id <= 10 }
        assertEquals(10, old.size)
        assertTrue(old.all { it.hadith.level == 3 })
        assertTrue(old.all { it.hadith.takhrij == "متفق عليه" })
        assertTrue(old.all { it.hadith.sahabi == it.sanad.last().narrator.primaryName })
        // Their chains are exactly as they were.
        val seededChains = seed.edges.filter { it.hadithId <= 10 }.groupBy { it.hadithId }
        old.forEach { h -> assertEquals(seededChains.getValue(h.hadith.id).map { it.narratorId }, h.sanad.map { it.narrator.id }) }
        db.close()
    }

    @Test
    fun updatingFromV4AddsTheFoundationHadithsAndTheirNarrator() = runBlocking {
        createVersion4("shipped.db")

        val db = open("shipped.db")

        assertEquals(13, db.hadithDao().count())
        assertEquals(listOf(11L, 12L, 13L), db.hadithDao().getAllIds().filter { it > 10 })
        val teaching = db.hadithDao().getHadithWithSanad(13)!!
        assertEquals(1, teaching.hadith.level)
        assertEquals(listOf("تميم الداري"), teaching.sanad.map { it.narrator.primaryName })
        assertNotNull(db.narratorDao().getAll().firstOrNull { it.id == 40L })
        // Never reviewed, so they are waiting to be learned.
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L), db.hadithDao().getUnseenWithSanad().map { it.hadith.id })
        db.close()
    }

    @Test
    fun theCatalogVersionIsRecordedAndNothingChangesWhenTheDatabaseReopens() = runBlocking {
        createVersion4("shipped.db")
        val first = open("shipped.db")
        assertEquals(SeedLoader.CATALOG_VERSION, SeedLoader.appliedVersion(first.openHelper.writableDatabase))
        val before = first.hadithDao().getAllWithSanad()
        first.close()

        val second = open("shipped.db")

        assertEquals(before, second.hadithDao().getAllWithSanad())
        assertEquals(progress, second.masteryDao().getByHadithId(1))
        assertEquals(3, second.openHelper.writableDatabase.count("SELECT COUNT(*) FROM narrator_review_event"))
        second.close()
    }

    @Test
    fun aStaleCatalogIsBroughtUpToDateWithoutTouchingProgress() = runBlocking {
        val db = open("stale.db")
        db.masteryDao().upsert(progress)
        val raw = db.openHelper.writableDatabase
        // An older catalog: a hadith missing, another worded differently, and no record of having been synced.
        raw.execSQL("DELETE FROM hadith WHERE id = 13")
        raw.execSQL("UPDATE hadith SET takhrij = 'قديم', level = 2 WHERE id = 1")
        raw.execSQL("DELETE FROM catalog_state")
        db.close()

        val reopened = open("stale.db")

        assertEquals(13, reopened.hadithDao().count())
        val refreshed = reopened.hadithDao().getHadithWithSanad(1)!!
        assertEquals("متفق عليه", refreshed.hadith.takhrij)
        assertEquals(3, refreshed.hadith.level)
        assertEquals(6, refreshed.sanad.size)
        assertEquals(1, reopened.hadithDao().getHadithWithSanad(13)!!.sanad.size)
        // Updating hadith 1 in place must not cascade into the progress that points at it.
        assertEquals(progress, reopened.masteryDao().getByHadithId(1))
        reopened.close()
    }

    @Test
    fun aCurrentCatalogIsNotSyncedAgain() = runBlocking {
        val db = open("current.db")
        db.openHelper.writableDatabase.execSQL("UPDATE hadith SET takhrij = 'edited' WHERE id = 1")
        db.close()

        val reopened = open("current.db")

        assertEquals("edited", reopened.hadithDao().getHadithWithSanad(1)!!.hadith.takhrij)
        reopened.close()
    }

    @Test
    fun theUnshippedVersionsAreStillRebuiltFromScratch() = runBlocking {
        context.openOrCreateDatabase("old-dev.db", Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL("CREATE TABLE hadith (id INTEGER PRIMARY KEY, junk TEXT)")
            legacy.version = 3
        }

        val db = open("old-dev.db")

        assertEquals(13, db.hadithDao().count())
        assertEquals(SeedLoader.CATALOG_VERSION, SeedLoader.appliedVersion(db.openHelper.writableDatabase))
        db.close()
    }
}

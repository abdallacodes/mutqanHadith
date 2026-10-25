package com.mutqan.hadith.data

import android.content.ContentValues
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.seed.SeedLoader
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A device that shipped v1.0.0 holds a version 4 database with the user's progress in it. Updating the app must
 * give it the levels and the audited catalog without losing that progress, so the old schema is rebuilt here from
 * the schema Room exported for it, filled with the catalog v1.0.0 shipped (a frozen fixture, since the bundled
 * seed has been replaced), and then opened with the production database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seed = SeedLoader.load(context)
    private val shipped = SeedLoader.parse(
        javaClass.classLoader!!.getResourceAsStream("shipped_v1_catalog.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() },
    )
    /** The catalog v1.1.0 shipped (catalog version 2: the Forty Hadith and the Bukhari selection, no Level 2), frozen. */
    private val shippedV110 = SeedLoader.parse(
        javaClass.classLoader!!.getResourceAsStream("shipped_v110_catalog.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() },
    )
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
            shipped.narrators.forEach {
                db.insert("narrator", null, ContentValues().apply {
                    put("id", it.id); put("primaryName", it.primaryName); put("fullName", it.fullName); put("tier", it.tier); put("isPivot", if (it.isPivot) 1 else 0)
                })
            }
            shipped.hadiths.forEach {
                db.insert("hadith", null, ContentValues().apply {
                    put("id", it.id); put("matnWithTashkeel", it.matnWithTashkeel); put("matnClean", it.matnClean); put("sourceBook", it.sourceBook)
                    put("hadithNumber", it.hadithNumber); put("chapter", it.chapter); put("grade", it.grade); put("collector", it.collector)
                })
            }
            shipped.edges.forEach {
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
            val chainOfOne = shipped.edges.filter { it.hadithId == 1L }.sortedBy { it.orderIndex }.map { it.narratorId }
            chainOfOne.take(3).forEachIndexed { i, narrator ->
                db.insert("narrator_review_event", null, ContentValues().apply {
                    put("hadithId", 1); put("narratorId", narrator); put("reviewedAt", 1_700_000_100_000L); put("succeeded", if (i == 1) 0 else 1)
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

        assertEquals(progress, db.masteryDao().getByHadithId(1))
        assertEquals(1, db.openHelper.writableDatabase.count("SELECT COUNT(*) FROM mastery_record"))
        db.close()
    }

    @Test
    fun updatingFromV4ReplacesTheChainsAndTheNarratorHistoryRecordedAgainstThem() = runBlocking {
        createVersion4("shipped.db")
        val chainBefore = shipped.edges.filter { it.hadithId == 1L }.sortedBy { it.orderIndex }.map { it.narratorId }

        val db = open("shipped.db")
        val raw = db.openHelper.writableDatabase

        val hadith = db.hadithDao().getHadithWithSanad(1)!!
        val chain = seed.edges.filter { it.hadithId == 1L }.sortedBy { it.orderIndex }
        assertEquals(chain.map { it.narratorId }, hadith.sanad.map { it.narrator.id })
        assertEquals(chain.map { it.transmissionTerm }, hadith.sanad.map { it.transmissionTerm })
        assertNotEquals(chainBefore, hadith.sanad.map { it.narrator.id })
        // Three events had been recorded against narrators of the old chain; they describe nobody now.
        assertEquals(0, raw.count("SELECT COUNT(*) FROM narrator_review_event"))
        // No narrator of the hand-written catalog survives unless the audited one has it too.
        assertEquals(seed.narrators.map { it.id }.toSet(), db.narratorDao().getAll().map { it.id }.toSet())
        assertEquals(seed.narrators.map { it.primaryName }.toSet(), db.narratorDao().getAll().map { it.primaryName }.toSet())
        db.close()
    }

    @Test
    fun updatingFromV4MakesTheExistingHadithsLevelThreeWithTheirAuditedTextAndTakhrij() = runBlocking {
        createVersion4("shipped.db")

        val db = open("shipped.db")

        val old = db.hadithDao().getAllWithSanad().filter { it.hadith.id <= 10 }
        assertEquals(10, old.size)
        assertTrue(old.all { it.hadith.level == 3 })
        assertEquals(seed.hadiths.filter { it.id <= 10 }, old.map { it.hadith })
        assertTrue(old.all { it.hadith.takhrij.isNotBlank() })
        assertTrue(old.all { it.hadith.sahabi == it.sanad.last().narrator.primaryName })
        db.close()
    }

    @Test
    fun updatingFromV4AddsTheRestOfTheCatalogAsUnseen() = runBlocking {
        createVersion4("shipped.db")

        val db = open("shipped.db")

        assertEquals(seed.hadiths.size, db.hadithDao().count())
        assertEquals(seed.hadiths.map { it.id }.sorted(), db.hadithDao().getAllIds().sorted())
        val arbain = db.hadithDao().getHadithWithSanad(13)!!
        assertEquals(1, arbain.hadith.level)
        assertEquals(seed.hadiths.single { it.id == 13L }.sahabi, arbain.sanad.single().narrator.primaryName)
        // Never reviewed, so they are waiting to be learned, level by level and by the number in the book; hadith 1 has progress.
        val curriculum = compareBy<com.mutqan.hadith.data.entity.HadithEntity>({ it.level }, { it.hadithNumber }, { it.id })
        assertEquals(seed.hadiths.filter { it.id != 1L }.sortedWith(curriculum).map { it.id }, db.hadithDao().getUnseenWithSanad().map { it.hadith.id })
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

        assertEquals(seed.hadiths.size, reopened.hadithDao().count())
        val refreshed = reopened.hadithDao().getHadithWithSanad(1)!!
        assertEquals(seed.hadiths.single { it.id == 1L }.takhrij, refreshed.hadith.takhrij)
        assertEquals(3, refreshed.hadith.level)
        assertEquals(seed.edges.count { it.hadithId == 1L }, refreshed.sanad.size)
        assertEquals(1, reopened.hadithDao().getHadithWithSanad(13)!!.sanad.size)
        // Updating hadith 1 in place must not cascade into the progress that points at it.
        assertEquals(progress, reopened.masteryDao().getByHadithId(1))
        reopened.close()
    }

    @Test
    fun updatingFromCatalogTwoAddsLevelTwoAndLeavesTheUsersProgressAlone() = runBlocking {
        // A device on v1.1.0: the current schema holding exactly the 52 hadiths of catalog 2, with progress on top.
        val device = open("v110.db")
        val raw = device.openHelper.writableDatabase
        listOf("narrator_review_event", "mastery_record", "sanad_edge", "hadith", "narrator").forEach { raw.execSQL("DELETE FROM $it") }
        SeedLoader.write(raw, shippedV110)
        SeedLoader.markApplied(raw, 2)
        val progressOnAnArbain = progress.copy(hadithId = 13)
        device.masteryDao().upsert(progress)
        device.masteryDao().upsert(progressOnAnArbain)
        val narrator = shippedV110.edges.first { it.hadithId == 1L }.narratorId
        raw.execSQL("INSERT INTO narrator_review_event (hadithId, narratorId, reviewedAt, succeeded) VALUES (1, $narrator, 1700000000000, 1)")
        assertEquals(52, device.hadithDao().count())
        val earlier = device.hadithDao().getAllWithSanad()
        device.close()

        val updated = open("v110.db")

        assertEquals(SeedLoader.CATALOG_VERSION, SeedLoader.appliedVersion(updated.openHelper.writableDatabase))
        assertEquals(seed.hadiths.size, updated.hadithDao().count())
        assertEquals(seed.narrators.size, updated.narratorDao().getAll().size)
        // The 52 hadiths, their chains and their narrators are exactly as they were; nothing was replaced or renumbered.
        assertEquals(earlier, updated.hadithDao().getAllWithSanad().filter { it.hadith.level != 2 })
        // Level 2 arrives whole, each hadith with its chain, waiting to be learned.
        val levelTwo = seed.hadiths.filter { it.level == 2 }
        assertTrue(levelTwo.isNotEmpty())
        assertEquals(levelTwo.map { it.id }.toSet(), updated.hadithDao().getHadithsByLevel(2).first().map { it.id }.toSet())
        levelTwo.forEach { expected ->
            val stored = updated.hadithDao().getHadithWithSanad(expected.id)!!
            assertEquals(expected, stored.hadith)
            assertEquals(seed.edges.filter { it.hadithId == expected.id }.sortedBy { it.orderIndex }.map { it.narratorId }, stored.sanad.map { it.narrator.id })
        }
        assertTrue(updated.hadithDao().getUnseenWithSanad().map { it.hadith.id }.containsAll(levelTwo.map { it.id }))
        // And the user's progress is untouched.
        assertEquals(progress, updated.masteryDao().getByHadithId(1))
        assertEquals(progressOnAnArbain, updated.masteryDao().getByHadithId(13))
        assertEquals(2, updated.openHelper.writableDatabase.count("SELECT COUNT(*) FROM mastery_record"))
        assertEquals(1, updated.openHelper.writableDatabase.count("SELECT COUNT(*) FROM narrator_review_event WHERE hadithId = 1 AND narratorId = $narrator"))
        updated.close()
    }

    @Test
    fun narratorHistoryOnAChainTheCatalogDidNotChangeIsKept() = runBlocking {
        val db = open("stale.db")
        val raw = db.openHelper.writableDatabase
        val narrator = seed.edges.first { it.hadithId == 1L }.narratorId
        raw.execSQL("INSERT INTO narrator_review_event (hadithId, narratorId, reviewedAt, succeeded) VALUES (1, $narrator, 1700000000000, 1)")
        raw.execSQL("DELETE FROM catalog_state")
        db.close()

        val reopened = open("stale.db")

        assertEquals(1, reopened.openHelper.writableDatabase.count("SELECT COUNT(*) FROM narrator_review_event WHERE hadithId = 1 AND narratorId = $narrator"))
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

        assertEquals(seed.hadiths.size, db.hadithDao().count())
        assertEquals(SeedLoader.CATALOG_VERSION, SeedLoader.appliedVersion(db.openHelper.writableDatabase))
        db.close()
    }
}

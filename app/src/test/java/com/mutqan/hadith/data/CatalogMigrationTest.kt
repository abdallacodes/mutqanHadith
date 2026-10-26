package com.mutqan.hadith.data

import android.content.ContentValues
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.data.repository.RoomCollectionRepository
import com.mutqan.hadith.data.seed.SeedLoader
import com.mutqan.hadith.domain.model.CurriculumOrder
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A device that shipped v1.0.0 holds a version 4 database with the user's progress in it. Updating the app must
 * bring it to the current catalog: the audited Forty Hadith, with the Bukhari selection and 'Umdat al-Ahkam retired
 * together with the progress on them, and the progress on the Forty Hadith untouched. The old schema is rebuilt here
 * from the schema Room exported for it, filled with the catalog v1.0.0 shipped (a frozen fixture, since the bundled
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

    /** The hadiths as a catalog older than the groups wrote them: filed nowhere, taught in no particular position. */
    private fun List<HadithWithFullSanad>.unplaced() = map { it.copy(hadith = it.hadith.copy(groupId = null, position = 0)) }



    @Test
    fun updatingFromV4RetiresTheBukhariHadithsTogetherWithTheirProgress() = runBlocking {
        createVersion4("shipped.db")

        val db = open("shipped.db")
        val raw = db.openHelper.writableDatabase

        // v1.0.0 held ten Bukhari hadiths and nothing else; the catalog is now the Forty Hadith alone.
        assertEquals(seed.hadiths.map { it.id }.sorted(), db.hadithDao().getAllIds().sorted())
        assertEquals(0, raw.count("SELECT COUNT(*) FROM hadith WHERE id <= 10"))
        assertEquals(0, raw.count("SELECT COUNT(*) FROM mastery_record"))
        assertEquals(0, raw.count("SELECT COUNT(*) FROM narrator_review_event"))
        assertEquals(0, raw.count("SELECT COUNT(*) FROM sanad_edge WHERE hadithId <= 10"))
        // No narrator of the old catalog survives unless the Forty Hadith has it too.
        assertEquals(seed.narrators.map { it.id }.toSet(), db.narratorDao().getAll().map { it.id }.toSet())
        assertEquals(seed.narrators.map { it.primaryName }.toSet(), db.narratorDao().getAll().map { it.primaryName }.toSet())
        db.close()
    }

    @Test
    fun updatingFromV4GivesTheFortyHadithWithTheirAuditedTextTakhrijAndChainAsUnseen() = runBlocking {
        createVersion4("shipped.db")

        val db = open("shipped.db")

        val all = db.hadithDao().getAllWithSanad()
        assertEquals(seed.hadiths, all.map { it.hadith }.sortedBy { it.id })
        assertTrue(all.all { it.hadith.level == 1 && it.hadith.takhrij.isNotBlank() })
        assertTrue(all.all { it.hadith.sahabi == it.sanad.last().narrator.primaryName })
        assertEquals(seed.edges.size, all.sumOf { it.sanad.size })
        val arbain = db.hadithDao().getHadithWithSanad(13)!!
        assertEquals(seed.hadiths.single { it.id == 13L }.sahabi, arbain.sanad.single().narrator.primaryName)
        // Never reviewed, so they are waiting to be learned by the number in the book.
        assertEquals(seed.hadiths.sortedWith(CurriculumOrder).map { it.id }, db.hadithDao().getUnseenWithSanad().map { it.hadith.id })
        // And they are on the shelf: one book, holding all of them.
        assertEquals(seed.groups, db.hadithDao().observeGroups().first())
        assertTrue(all.all { it.hadith.groupId == 1L })
        db.close()
    }

    @Test
    fun theCatalogVersionIsRecordedAndNothingChangesWhenTheDatabaseReopens() = runBlocking {
        createVersion4("shipped.db")
        val first = open("shipped.db")
        assertEquals(SeedLoader.CATALOG_VERSION, SeedLoader.appliedVersion(first.openHelper.writableDatabase))
        first.masteryDao().upsert(progress.copy(hadithId = 13))
        val before = first.hadithDao().getAllWithSanad()
        first.close()

        val second = open("shipped.db")

        assertEquals(before, second.hadithDao().getAllWithSanad())
        assertEquals(progress.copy(hadithId = 13), second.masteryDao().getByHadithId(13))
        second.close()
    }

    @Test
    fun aStaleCatalogIsBroughtUpToDateWithoutTouchingProgress() = runBlocking {
        val db = open("stale.db")
        db.masteryDao().upsert(progress.copy(hadithId = 11))
        val raw = db.openHelper.writableDatabase
        // An older catalog: a hadith missing, another worded differently, and no record of having been synced.
        raw.execSQL("DELETE FROM hadith WHERE id = 13")
        raw.execSQL("UPDATE hadith SET takhrij = 'قديم', level = 2, groupId = NULL, position = 0 WHERE id = 11")
        raw.execSQL("DELETE FROM catalog_group")
        raw.execSQL("DELETE FROM catalog_state")
        db.close()

        val reopened = open("stale.db")

        assertEquals(seed.hadiths.size, reopened.hadithDao().count())
        val refreshed = reopened.hadithDao().getHadithWithSanad(11)!!
        assertEquals(seed.hadiths.single { it.id == 11L }.takhrij, refreshed.hadith.takhrij)
        assertEquals(1, refreshed.hadith.level)
        assertEquals(seed.hadiths.single { it.id == 11L }.let { it.groupId to it.position }, refreshed.hadith.let { it.groupId to it.position })
        assertEquals(seed.groups, reopened.hadithDao().observeGroups().first())
        assertEquals(seed.edges.count { it.hadithId == 11L }, refreshed.sanad.size)
        assertEquals(1, reopened.hadithDao().getHadithWithSanad(13)!!.sanad.size)
        // Updating hadith 11 in place must not cascade into the progress that points at it.
        assertEquals(progress.copy(hadithId = 11), reopened.masteryDao().getByHadithId(11))
        reopened.close()
    }

    @Test
    fun updatingFromCatalogThreeRetiresBukhariAndUmdatAndKeepsEverythingOfTheFortyHadith() = runBlocking {
        // A device on v1.4.x: the current schema holding the Forty Hadith, the Bukhari selection and a hadith of 'Umdat al-Ahkam
        // (level 2, ids from 1000), each with progress, narrator history and a place in one of the user's collections.
        val device = open("v14.db")
        val raw = device.openHelper.writableDatabase
        listOf("narrator_review_event", "mastery_record", "sanad_edge", "hadith", "narrator").forEach { raw.execSQL("DELETE FROM $it") }
        SeedLoader.write(raw, shippedV110)
        val umdat = com.mutqan.hadith.data.entity.HadithEntity(
            1005, "الْبَيِّنَةُ عَلَى الْمُدَّعِي", "البينه على المدعي", "عمدة الأحكام", 5, "كتاب", "صحيح", "عبد الغني المقدسي",
            takhrij = "رواه البخاري", sahabi = "أبو هريرة", level = 2,
        )
        val abuHurayra = shippedV110.narrators.first { it.primaryName == "أبو هريرة" }.id
        device.hadithDao().insertWithSanad(
            umdat,
            listOf(com.mutqan.hadith.data.entity.SanadEdgeEntity(hadithId = 1005, narratorId = abuHurayra, orderIndex = 0, transmissionTerm = "عن")),
        )
        SeedLoader.markApplied(raw, 3)

        val progressOnAnArbain = progress.copy(hadithId = 13)
        listOf(progress, progressOnAnArbain, progress.copy(hadithId = 1005)).forEach { device.masteryDao().upsert(it) }
        val bukhariNarrator = shippedV110.edges.first { it.hadithId == 1L }.narratorId
        val arbainNarrator = shippedV110.edges.first { it.hadithId == 13L }.narratorId
        raw.execSQL("INSERT INTO narrator_review_event (hadithId, narratorId, reviewedAt, succeeded) VALUES (1, $bukhariNarrator, 1700000000000, 1)")
        raw.execSQL("INSERT INTO narrator_review_event (hadithId, narratorId, reviewedAt, succeeded) VALUES (13, $arbainNarrator, 1700000000000, 0)")
        raw.execSQL("INSERT INTO collections (id, title, createdAt, accent) VALUES (1, 'Mine', 1, 0)")
        listOf(1, 13, 1005).forEach { raw.execSQL("INSERT INTO hadith_collection_cross_ref (collectionId, hadithId, addedAt) VALUES (1, $it, ${it + 10})") }
        assertEquals(shippedV110.hadiths.size + 1, device.hadithDao().count())
        val arbainBefore = device.hadithDao().getAllWithSanad().filter { it.hadith.level == 1 }.unplaced()
        device.close()

        val updated = open("v14.db")
        val after = updated.openHelper.writableDatabase

        assertEquals(SeedLoader.CATALOG_VERSION, SeedLoader.appliedVersion(after))
        // The Forty Hadith, their chains and their narrators are exactly as they were; nothing was replaced or renumbered.
        // The catalog has only now filed them under their book.
        assertEquals(arbainBefore, updated.hadithDao().getAllWithSanad().unplaced())
        assertTrue(updated.hadithDao().getAllWithSanad().all { it.hadith.groupId == 1L && it.hadith.position > 0 })
        assertEquals(seed.groups, updated.hadithDao().observeGroups().first())
        assertEquals(seed.hadiths.map { it.id }.sorted(), updated.hadithDao().getAllIds().sorted())
        assertEquals(seed.narrators.map { it.id }.toSet(), updated.narratorDao().getAll().map { it.id }.toSet())
        // Progress, narrator history and collection entries on the retired hadiths went with them; the others are untouched.
        assertEquals(listOf(progressOnAnArbain), updated.masteryDao().getAll())
        assertEquals(1, after.count("SELECT COUNT(*) FROM narrator_review_event"))
        assertEquals(1, after.count("SELECT COUNT(*) FROM narrator_review_event WHERE hadithId = 13 AND narratorId = $arbainNarrator"))
        assertEquals(0, after.count("SELECT COUNT(*) FROM sanad_edge WHERE hadithId NOT IN (SELECT id FROM hadith)"))
        // The collection itself stays, holding what is left of it.
        assertEquals(1, after.count("SELECT COUNT(*) FROM collections"))
        assertEquals(listOf(13L), RoomCollectionRepository(updated).getHadithIds(1))
        updated.close()
    }

    @Test
    fun onceTheCatalogIsAtTheRetirementNothingIsRetiredAgain() = runBlocking {
        // A later catalog may carry hadiths of any book; one that holds more or fewer than the database must not delete anything.
        val db = open("later.db")
        val raw = db.openHelper.writableDatabase
        db.masteryDao().upsert(progress.copy(hadithId = 11))
        val umdat = com.mutqan.hadith.data.entity.HadithEntity(
            1005, "الْبَيِّنَةُ عَلَى الْمُدَّعِي", "البينه على المدعي", "عمدة الأحكام", 5, "كتاب", "صحيح", "عبد الغني المقدسي",
            takhrij = "رواه البخاري", sahabi = "أبو هريرة", level = 2,
        )
        db.hadithDao().insertHadith(umdat)
        db.masteryDao().upsert(progress.copy(hadithId = 1005))

        raw.beginTransaction()
        try {
            SeedLoader.sync(raw, seed, appliedVersion = SeedLoader.CATALOG_VERSION)
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
        }

        assertEquals(1, raw.count("SELECT COUNT(*) FROM hadith WHERE id = 1005"))
        assertEquals(2, raw.count("SELECT COUNT(*) FROM mastery_record"))
        db.close()
    }

    @Test
    fun narratorHistoryOnAChainTheCatalogDidNotChangeIsKept() = runBlocking {
        val db = open("stale.db")
        val raw = db.openHelper.writableDatabase
        val narrator = seed.edges.first { it.hadithId == 11L }.narratorId
        raw.execSQL("INSERT INTO narrator_review_event (hadithId, narratorId, reviewedAt, succeeded) VALUES (11, $narrator, 1700000000000, 1)")
        raw.execSQL("DELETE FROM catalog_state")
        db.close()

        val reopened = open("stale.db")

        assertEquals(1, reopened.openHelper.writableDatabase.count("SELECT COUNT(*) FROM narrator_review_event WHERE hadithId = 11 AND narratorId = $narrator"))
        reopened.close()
    }

    @Test
    fun aCurrentCatalogIsNotSyncedAgain() = runBlocking {
        val db = open("current.db")
        db.openHelper.writableDatabase.execSQL("UPDATE hadith SET takhrij = 'edited' WHERE id = 11")
        db.close()

        val reopened = open("current.db")

        assertEquals("edited", reopened.hadithDao().getHadithWithSanad(11)!!.hadith.takhrij)
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

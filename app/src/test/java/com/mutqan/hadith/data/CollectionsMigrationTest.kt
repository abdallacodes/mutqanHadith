package com.mutqan.hadith.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.CollectionEntity
import com.mutqan.hadith.data.entity.HadithCollectionCrossRef
import com.mutqan.hadith.data.seed.SeedLoader
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A device on v1.4.x holds a version 5 database with the user's study history in it. Updating to the version with
 * personal collections must add the two new tables and change nothing else. The old schema is rebuilt here from the
 * schema Room exported for it, filled with the bundled catalog and a study history, and opened with the production
 * database; every row of every table that existed before is compared with what it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionsMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seed = SeedLoader.load(context)

    /** Five hadiths of the three levels that the user has studied; ids of the catalog are not consecutive. */
    private val studied = seed.hadiths.map { it.id }.sorted().let { listOf(it[0], it[6], it[59], it[199], it.last()) }
    private val unstudied = seed.hadiths.map { it.id }.sorted()[300]

    /** The tables of version 5, i.e. every one of the app's tables before collections. */
    private val versionFiveTables = listOf("narrator", "hadith", "sanad_edge", "narrator_review_event", "mastery_record", "catalog_state")

    private fun schema(version: Int): JSONObject =
        JSONObject(File("schemas/com.mutqan.hadith.data.db.MutqanDatabase/$version.json").readText(Charsets.UTF_8)).getJSONObject("database")

    /** Version 5 exactly as shipped (tables, indices, Room's bookkeeping), with the catalog and a study history in it. */
    private fun createVersion5(name: String) {
        val schema = schema(5)
        assertEquals(5, schema.getInt("version"))
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    db.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))

            seed.narrators.forEach {
                db.insert("narrator", null, ContentValues().apply {
                    put("id", it.id); put("primaryName", it.primaryName); put("fullName", it.fullName); put("tier", it.tier)
                    put("isPivot", if (it.isPivot) 1 else 0)
                })
            }
            seed.hadiths.forEach {
                db.insert("hadith", null, ContentValues().apply {
                    put("id", it.id); put("matnWithTashkeel", it.matnWithTashkeel); put("matnClean", it.matnClean)
                    put("sourceBook", it.sourceBook); put("hadithNumber", it.hadithNumber); put("chapter", it.chapter)
                    put("grade", it.grade); put("collector", it.collector); put("takhrij", it.takhrij); put("sahabi", it.sahabi)
                    put("level", it.level)
                })
            }
            seed.edges.forEach {
                db.insert("sanad_edge", null, ContentValues().apply {
                    put("hadithId", it.hadithId); put("narratorId", it.narratorId); put("orderIndex", it.orderIndex)
                    put("transmissionTerm", it.transmissionTerm)
                })
            }
            db.insert("catalog_state", null, ContentValues().apply { put("id", 1); put("version", SeedLoader.CATALOG_VERSION) })

            // The user's study history: hadiths of all three levels in different states, and what was recalled of their chains.
            val history = listOf(
                Triple(studied[0], 12.5f to 3.25f, 4), Triple(studied[1], 40.0f to 9.5f, 9), Triple(studied[2], 0.5f to 0.25f, 1),
                Triple(studied[3], 2.0f to 0.0f, 2), Triple(studied[4], 21.75f to 6.5f, 6),
            )
            history.forEach { (hadithId, stability, repetitions) ->
                db.insert("mastery_record", null, ContentValues().apply {
                    put("hadithId", hadithId); put("matnStability", stability.first); put("sanadStability", stability.second)
                    put("matnLastReviewedAt", 1_700_000_000_000L + hadithId); put("sanadLastReviewedAt", 1_700_000_100_000L + hadithId)
                    put("nextReviewDue", 1_700_500_000_000L + hadithId); put("repetitionCount", repetitions)
                })
            }
            seed.edges.filter { it.hadithId == studied[0] || it.hadithId == studied[1] }.forEachIndexed { i, edge ->
                db.insert("narrator_review_event", null, ContentValues().apply {
                    put("hadithId", edge.hadithId); put("narratorId", edge.narratorId)
                    put("reviewedAt", 1_700_000_200_000L + i); put("succeeded", if (i % 3 == 0) 0 else 1)
                })
            }
            db.version = 5
        }
    }

    private fun open(name: String) = MutqanDatabase.builder(context, name).allowMainThreadQueries().build()

    private fun Cursor.rows(): List<List<String?>> = use {
        buildList { while (it.moveToNext()) add(List(it.columnCount) { c -> if (it.isNull(c)) null else it.getString(c) }) }
    }

    private fun dump(query: (String) -> Cursor): Map<String, List<List<String?>>> =
        versionFiveTables.associateWith { table -> query("SELECT * FROM $table ORDER BY rowid").rows() }

    private fun rawDump(name: String): Map<String, List<List<String?>>> =
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { raw -> dump { raw.rawQuery(it, null) } }

    @Test
    fun theFixtureIsAVersionFiveDatabaseHoldingAStudyHistory() {
        createVersion5("v5.db")

        val before = rawDump("v5.db")

        assertEquals(397, before.getValue("hadith").size)
        assertEquals(5, before.getValue("mastery_record").size)
        assertTrue(before.getValue("narrator_review_event").isNotEmpty())
        assertEquals(seed.edges.size, before.getValue("sanad_edge").size)
        assertEquals(listOf(listOf("1", SeedLoader.CATALOG_VERSION.toString())), before.getValue("catalog_state"))
    }

    @Test
    fun migratingToVersionSixKeepsEveryRowOfEveryExistingTableExactlyAsItWas() {
        createVersion5("v5.db")
        val before = rawDump("v5.db")

        val db = open("v5.db")
        val after = dump { db.openHelper.writableDatabase.query(it) }

        assertEquals("the migrated database is version 6", 6, db.openHelper.writableDatabase.version)
        versionFiveTables.forEach { table -> assertEquals("rows of $table", before.getValue(table), after.getValue(table)) }
        db.close()
    }

    @Test
    fun theUsersMasteryRecordsAndNarratorHistoryReadBackThroughTheDaosAfterMigrating() = runBlocking {
        createVersion5("v5.db")

        val db = open("v5.db")

        val mastery = db.masteryDao().getAll().associateBy { it.hadithId }
        assertEquals(studied.toSet(), mastery.keys)
        assertEquals(12.5f, mastery.getValue(studied[0]).matnStability, 0f)
        assertEquals(3.25f, mastery.getValue(studied[0]).sanadStability, 0f)
        assertEquals(9, mastery.getValue(studied[1]).repetitionCount)
        assertEquals(1_700_500_000_000L + studied[2], mastery.getValue(studied[2]).nextReviewDue)
        assertTrue(db.reviewHistoryDao().getAll().isNotEmpty())
        db.close()
    }

    @Test
    fun theNewTablesStartEmptyAndHaveTheDeclaredShape() {
        createVersion5("v5.db")

        val db = open("v5.db")
        val raw = db.openHelper.writableDatabase

        assertEquals(0, raw.query("SELECT COUNT(*) FROM collections").rows().single().single()?.toInt())
        assertEquals(0, raw.query("SELECT COUNT(*) FROM hadith_collection_cross_ref").rows().single().single()?.toInt())
        // Both sides of a reference cascade; that is what lets deleting a collection clean up after itself.
        val keys = raw.query("PRAGMA foreign_key_list(hadith_collection_cross_ref)").rows()
        assertEquals(setOf("collections", "hadith"), keys.map { it[2] }.toSet())
        assertTrue(keys.all { it[6] == "CASCADE" })
        val indices = raw.query("PRAGMA index_list(hadith_collection_cross_ref)").rows().map { it[1] }
        assertTrue("index_hadith_collection_cross_ref_hadithId" in indices)
        db.close()
    }

    @Test
    fun collectionsWorkOnAMigratedDatabaseAndLeaveTheStudyHistoryAlone() = runBlocking {
        createVersion5("v5.db")
        val before = rawDump("v5.db")

        val db = open("v5.db")
        val dao = db.collectionDao()
        val first = dao.insertWith(CollectionEntity(title = "Adhkar", createdAt = 10), firstHadithId = studied[0])
        dao.addReference(HadithCollectionCrossRef(first, studied[1], addedAt = 11))
        dao.addReference(HadithCollectionCrossRef(first, unstudied, addedAt = 12))

        val summary = dao.observeSummaries().first().single()
        assertEquals(3, summary.hadithCount)
        assertEquals(listOf(studied[0], studied[1], unstudied), dao.getHadithIds(first))

        dao.delete(first)

        assertTrue(dao.observeSummaries().first().isEmpty())
        assertTrue(dao.getHadithIds(first).isEmpty())
        // Filing hadiths into a collection and deleting it again left every old table exactly as it was.
        val after = dump { db.openHelper.writableDatabase.query(it) }
        versionFiveTables.forEach { table -> assertEquals("rows of $table", before.getValue(table), after.getValue(table)) }
        assertFalse(after.getValue("hadith").isEmpty())
        db.close()
    }
}

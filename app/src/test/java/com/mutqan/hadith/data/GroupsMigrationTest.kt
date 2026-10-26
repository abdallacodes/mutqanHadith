package com.mutqan.hadith.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.repository.RoomCollectionRepository
import com.mutqan.hadith.data.seed.SeedLoader
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
 * A device that holds a version 6 database (the Forty Hadith, the user's study history and a collection) is updated to
 * the version that files the hadiths in groups. The migration adds one table and two columns and nothing else, and the
 * catalog sync that follows on the same open places every hadith in its book. The old schema is rebuilt from the schema
 * Room exported for it, and every row that existed before is compared with what it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupsMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seed = SeedLoader.load(context)

    private val studied = seed.hadiths.map { it.id }.sorted().let { listOf(it[0], it[10], it[20], it.last()) }

    /** Every table version 6 had, with the columns it had: those of `hadith` are the ones before groupId and position. */
    private val versionSixTables = linkedMapOf(
        "narrator" to "*",
        "hadith" to "id, matnWithTashkeel, matnClean, sourceBook, hadithNumber, chapter, grade, collector, takhrij, sahabi, level",
        "sanad_edge" to "*",
        "narrator_review_event" to "*",
        "mastery_record" to "*",
        "collections" to "*",
        "hadith_collection_cross_ref" to "*",
    )

    /** The catalog version of a database that has the Forty Hadith and no groups. */
    private val catalogBeforeGroups = 4

    private fun createVersion6(name: String) {
        val schema = JSONObject(File("schemas/com.mutqan.hadith.data.db.MutqanDatabase/6.json").readText(Charsets.UTF_8)).getJSONObject("database")
        assertEquals(6, schema.getInt("version"))
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
            db.insert("catalog_state", null, ContentValues().apply { put("id", 1); put("version", catalogBeforeGroups) })

            studied.forEachIndexed { i, hadithId ->
                db.insert("mastery_record", null, ContentValues().apply {
                    put("hadithId", hadithId); put("matnStability", 3.5f * (i + 1)); put("sanadStability", 1.25f * (i + 1))
                    put("matnLastReviewedAt", 1_700_000_000_000L + hadithId); put("sanadLastReviewedAt", 1_700_000_100_000L + hadithId)
                    put("nextReviewDue", 1_700_500_000_000L + hadithId); put("repetitionCount", i + 1)
                })
            }
            seed.edges.filter { it.hadithId == studied[0] }.forEachIndexed { i, edge ->
                db.insert("narrator_review_event", null, ContentValues().apply {
                    put("hadithId", edge.hadithId); put("narratorId", edge.narratorId)
                    put("reviewedAt", 1_700_000_200_000L + i); put("succeeded", if (i % 2 == 0) 0 else 1)
                })
            }
            db.insert("collections", null, ContentValues().apply { put("id", 1); put("title", "Adhkar"); put("createdAt", 10L); put("accent", 2) })
            studied.take(3).forEachIndexed { i, hadithId ->
                db.insert("hadith_collection_cross_ref", null, ContentValues().apply {
                    put("collectionId", 1); put("hadithId", hadithId); put("addedAt", 20L + i)
                })
            }
            db.version = 6
        }
    }

    private fun open(name: String) = MutqanDatabase.builder(context, name).allowMainThreadQueries().build()

    private fun Cursor.rows(): List<List<String?>> = use {
        buildList { while (it.moveToNext()) add(List(it.columnCount) { c -> if (it.isNull(c)) null else it.getString(c) }) }
    }

    private fun dump(query: (String) -> Cursor): Map<String, List<List<String?>>> =
        versionSixTables.mapValues { (table, columns) -> query("SELECT $columns FROM $table ORDER BY rowid").rows() }

    @Test
    fun theFixtureIsAVersionSixDatabaseWithNoGroupsInIt() {
        createVersion6("v6.db")

        context.openOrCreateDatabase("v6.db", Context.MODE_PRIVATE, null).use { raw ->
            assertEquals(6, raw.version)
            assertEquals(emptyList<String>(), raw.rawQuery("PRAGMA table_info(hadith)", null).rows().map { it[1]!! }.filter { it == "groupId" || it == "position" })
            assertEquals(0, raw.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'catalog_group'", null).rows().single().single()!!.toInt())
            assertEquals(seed.hadiths.size, dump { raw.rawQuery(it, null) }.getValue("hadith").size)
        }
    }

    @Test
    fun migratingKeepsEveryRowThatExistedExactlyAsItWas() {
        createVersion6("v6.db")
        val before = context.openOrCreateDatabase("v6.db", Context.MODE_PRIVATE, null).use { raw -> dump { raw.rawQuery(it, null) } }

        val db = open("v6.db")
        val after = dump { db.openHelper.writableDatabase.query(it) }

        assertEquals(8, db.openHelper.writableDatabase.version)
        versionSixTables.keys.forEach { table -> assertEquals("rows of $table", before.getValue(table), after.getValue(table)) }
        db.close()
    }

    @Test
    fun theUpdateFilesEveryHadithInItsBookAndGivesItsPosition() = runBlocking {
        createVersion6("v6.db")

        val db = open("v6.db")

        assertEquals(SeedLoader.CATALOG_VERSION, SeedLoader.appliedVersion(db.openHelper.writableDatabase))
        assertEquals(seed.groups, db.hadithDao().observeGroups().first())
        val placed = db.hadithDao().getAllWithSanad().map { it.hadith }
        assertEquals(seed.hadiths, placed.sortedBy { it.id })
        assertTrue(placed.all { it.groupId == 1L && it.position == it.hadithNumber })
        db.close()
    }

    @Test
    fun theProgressAndTheCollectionAreUntouchedAndReadBackThroughTheDaos() = runBlocking {
        createVersion6("v6.db")

        val db = open("v6.db")

        val mastery = db.masteryDao().getAll().associateBy { it.hadithId }
        assertEquals(studied.toSet(), mastery.keys)
        assertEquals(3.5f, mastery.getValue(studied[0]).matnStability, 0f)
        assertEquals(1.25f * 4, mastery.getValue(studied[3]).sanadStability, 0f)
        assertTrue(db.reviewHistoryDao().getAll().isNotEmpty())
        assertEquals(studied.take(3), RoomCollectionRepository(db).getHadithIds(1))
        db.close()
    }

    @Test
    fun theGroupsHaveNoForeignKeyToOrFromAHadithSoNothingCascadesIntoTheUsersProgress() {
        createVersion6("v6.db")

        val db = open("v6.db")
        val raw = db.openHelper.writableDatabase

        assertEquals(emptyList<List<String?>>(), raw.query("PRAGMA foreign_key_list(catalog_group)").rows())
        assertTrue(raw.query("PRAGMA foreign_key_list(hadith)").rows().isEmpty())
        val indices = raw.query("PRAGMA index_list(catalog_group)").rows().map { it[1] } + raw.query("PRAGMA index_list(hadith)").rows().map { it[1] }
        assertTrue("index_catalog_group_parentId" in indices)
        assertTrue("index_hadith_groupId" in indices)
        db.close()
    }

    @Test
    fun reopeningTheMigratedDatabaseChangesNothing() = runBlocking {
        createVersion6("v6.db")
        val first = open("v6.db")
        val hadiths = first.hadithDao().getAllWithSanad()
        val groups = first.hadithDao().observeGroups().first()
        first.close()

        val second = open("v6.db")

        assertEquals(hadiths, second.hadithDao().getAllWithSanad())
        assertEquals(groups, second.hadithDao().observeGroups().first())
        second.close()
    }
}

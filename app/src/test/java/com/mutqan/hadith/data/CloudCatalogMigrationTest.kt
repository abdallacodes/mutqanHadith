package com.mutqan.hadith.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.seed.SeedLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A device that holds a version 8 database (the full catalog 6 and the user's study history) is updated to the version
 * that records when the cloud last wrote a catalog row. The migration adds one column to each of three catalog tables and
 * nothing else, so the learner's progress and the library are untouched. The old schema is rebuilt from the schema Room
 * exported for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudCatalogMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seed = SeedLoader.load(context)
    private val studied = listOf(seed.hadiths[0].id, seed.hadiths[1].id)

    private val userTables = listOf("mastery_record", "narrator_review_event", "collections", "hadith_collection_cross_ref")

    private fun createVersion8(name: String) {
        val schema = JSONObject(File("schemas/com.mutqan.hadith.data.db.MutqanDatabase/8.json").readText(Charsets.UTF_8)).getJSONObject("database")
        assertEquals(8, schema.getInt("version"))
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

            seed.groups.forEach {
                db.insert("catalog_group", null, ContentValues().apply {
                    put("id", it.id); put("parentId", it.parentId); put("title", it.title); put("titleEn", it.titleEn); put("position", it.position)
                })
            }
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
                    put("level", it.level); put("groupId", it.groupId); put("position", it.position); put("sanadSource", it.sanadSource)
                })
            }
            seed.edges.forEach {
                db.insert("sanad_edge", null, ContentValues().apply {
                    put("hadithId", it.hadithId); put("narratorId", it.narratorId); put("orderIndex", it.orderIndex)
                    put("transmissionTerm", it.transmissionTerm)
                })
            }
            db.insert("catalog_state", null, ContentValues().apply { put("id", 1); put("version", SeedLoader.CATALOG_VERSION) })

            studied.forEachIndexed { i, hadithId ->
                db.insert("mastery_record", null, ContentValues().apply {
                    put("hadithId", hadithId); put("matnStability", 3.5f * (i + 1)); put("sanadStability", 1.25f * (i + 1))
                    put("matnLastReviewedAt", 1_700_000_000_000L + hadithId); put("sanadLastReviewedAt", 1_700_000_100_000L + hadithId)
                    put("nextReviewDue", 1_700_500_000_000L + hadithId); put("repetitionCount", i + 1)
                })
                db.insert("narrator_review_event", null, ContentValues().apply {
                    put("hadithId", hadithId); put("narratorId", seed.edges.first { e -> e.hadithId == hadithId }.narratorId)
                    put("reviewedAt", 1_700_000_200_000L + i); put("succeeded", i)
                })
            }
            db.insert("collections", null, ContentValues().apply { put("id", 1); put("title", "Adhkar"); put("createdAt", 10L); put("accent", 2) })
            studied.forEachIndexed { i, hadithId ->
                db.insert("hadith_collection_cross_ref", null, ContentValues().apply {
                    put("collectionId", 1); put("hadithId", hadithId); put("addedAt", 20L + i)
                })
            }
            db.version = 8
        }
    }

    private fun open(name: String) = MutqanDatabase.builder(context, name).allowMainThreadQueries().build()

    private fun Cursor.rows(): List<List<String?>> = use {
        buildList { while (it.moveToNext()) add(List(it.columnCount) { c -> if (it.isNull(c)) null else it.getString(c) }) }
    }

    private fun userRows(query: (String) -> Cursor) = userTables.associateWith { query("SELECT * FROM $it ORDER BY rowid").rows() }

    @Test
    fun migratingAddsTheColumnToEachCatalogTableAndKeepsEveryRowThatWasTheUsers() {
        createVersion8("v8.db")
        val before = context.openOrCreateDatabase("v8.db", Context.MODE_PRIVATE, null).use { raw -> userRows { raw.rawQuery(it, null) } }
        assertTrue(before.getValue("mastery_record").isNotEmpty())

        val db = open("v8.db")
        val raw = db.openHelper.writableDatabase

        assertEquals(9, raw.version)
        assertEquals(before, userRows { raw.query(it) })
        listOf("hadith", "narrator", "catalog_group").forEach { table ->
            val columns = raw.query("PRAGMA table_info($table)").rows().map { it[1]!! }
            assertEquals(table, 1, columns.count { it == "catalogUpdatedAt" })
        }
        db.close()
    }

    @Test
    fun everyExistingCatalogRowStartsAsOneTheCloudHasNeverWritten() = runBlocking {
        createVersion8("v8.db")

        val db = open("v8.db")

        assertEquals(seed.hadiths, db.hadithDao().getAllWithSanad().map { it.hadith }.sortedBy { it.id })
        assertTrue(db.hadithDao().getAllWithSanad().all { it.hadith.catalogUpdatedAt == 0L })
        assertEquals(seed.narrators, db.narratorDao().getAll())
        db.close()
    }

    @Test
    fun reopeningTheMigratedDatabaseChangesNothing() = runBlocking {
        createVersion8("v8.db")
        val first = open("v8.db")
        val hadiths = first.hadithDao().getAllWithSanad()
        first.close()

        val second = open("v8.db")

        assertEquals(hadiths, second.hadithDao().getAllWithSanad())
        second.close()
    }
}

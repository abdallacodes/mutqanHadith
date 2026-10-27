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
 * A device that holds a version 7 database (the Forty Hadith filed in their book, each with its Companion alone as
 * its chain, and the user's study history) is updated to the version that records which book a chain is read from. The
 * migration adds one column and nothing else; the catalog sync that follows on the same open gives the hadiths that
 * have a narration in the six books their full chain. The old schema is rebuilt from the schema Room exported for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChainSourceMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seed = SeedLoader.load(context)

    private val longChains = seed.edges.groupBy { it.hadithId }.filterValues { it.size > 1 }.keys.sorted()
    private val companionAlone = seed.hadiths.map { it.id }.filter { it !in longChains }
    private val companionOf = seed.hadiths.associate { h -> h.id to seed.narrators.single { it.primaryName == h.sahabi } }

    /** One hadith that is about to get a long chain and one that is not, each studied, with a recall on its Companion. */
    private val studied = listOf(longChains.first(), companionAlone.first())

    /** The columns the hadith table had in version 7. */
    private val versionSevenHadithColumns =
        "id, matnWithTashkeel, matnClean, sourceBook, hadithNumber, chapter, grade, collector, takhrij, sahabi, level, groupId, position"

    /** The tables of version 7 that are the user's. */
    private val userTables = listOf("mastery_record", "collections", "hadith_collection_cross_ref")

    /** The catalog version of a database that has the Forty Hadith in groups, each with its Companion alone. */
    private val catalogBeforeChains = 5

    private fun createVersion7(name: String) {
        val schema = JSONObject(File("schemas/com.mutqan.hadith.data.db.MutqanDatabase/7.json").readText(Charsets.UTF_8)).getJSONObject("database")
        assertEquals(7, schema.getInt("version"))
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
            companionOf.values.toSet().forEach {
                db.insert("narrator", null, ContentValues().apply {
                    put("id", it.id); put("primaryName", it.primaryName); put("fullName", it.fullName); put("tier", "صحابي")
                    put("isPivot", 0)
                })
            }
            seed.hadiths.forEach {
                db.insert("hadith", null, ContentValues().apply {
                    put("id", it.id); put("matnWithTashkeel", it.matnWithTashkeel); put("matnClean", it.matnClean)
                    put("sourceBook", it.sourceBook); put("hadithNumber", it.hadithNumber); put("chapter", it.chapter)
                    put("grade", it.grade); put("collector", "النووي"); put("takhrij", it.takhrij); put("sahabi", it.sahabi)
                    put("level", it.level); put("groupId", it.groupId); put("position", it.position)
                })
                db.insert("sanad_edge", null, ContentValues().apply {
                    put("hadithId", it.id); put("narratorId", companionOf.getValue(it.id).id); put("orderIndex", 0)
                    put("transmissionTerm", "عن")
                })
            }
            db.insert("catalog_state", null, ContentValues().apply { put("id", 1); put("version", catalogBeforeChains) })

            studied.forEachIndexed { i, hadithId ->
                db.insert("mastery_record", null, ContentValues().apply {
                    put("hadithId", hadithId); put("matnStability", 3.5f * (i + 1)); put("sanadStability", 1.25f * (i + 1))
                    put("matnLastReviewedAt", 1_700_000_000_000L + hadithId); put("sanadLastReviewedAt", 1_700_000_100_000L + hadithId)
                    put("nextReviewDue", 1_700_500_000_000L + hadithId); put("repetitionCount", i + 1)
                })
                db.insert("narrator_review_event", null, ContentValues().apply {
                    put("hadithId", hadithId); put("narratorId", companionOf.getValue(hadithId).id)
                    put("reviewedAt", 1_700_000_200_000L + i); put("succeeded", i)
                })
            }
            db.insert("collections", null, ContentValues().apply { put("id", 1); put("title", "Adhkar"); put("createdAt", 10L); put("accent", 2) })
            studied.forEachIndexed { i, hadithId ->
                db.insert("hadith_collection_cross_ref", null, ContentValues().apply {
                    put("collectionId", 1); put("hadithId", hadithId); put("addedAt", 20L + i)
                })
            }
            db.version = 7
        }
    }

    private fun open(name: String) = MutqanDatabase.builder(context, name).allowMainThreadQueries().build()

    private fun Cursor.rows(): List<List<String?>> = use {
        buildList { while (it.moveToNext()) add(List(it.columnCount) { c -> if (it.isNull(c)) null else it.getString(c) }) }
    }

    private fun userRows(query: (String) -> Cursor) = userTables.associateWith { query("SELECT * FROM $it ORDER BY rowid").rows() }

    @Test
    fun theFixtureIsAVersionSevenDatabaseWhoseChainsAreTheCompanionAlone() {
        createVersion7("v7.db")

        context.openOrCreateDatabase("v7.db", Context.MODE_PRIVATE, null).use { raw ->
            assertEquals(7, raw.version)
            assertEquals(0, raw.rawQuery("PRAGMA table_info(hadith)", null).rows().count { it[1] == "sanadSource" })
            assertEquals(seed.hadiths.size, raw.rawQuery("SELECT COUNT(*) FROM sanad_edge", null).rows().single().single()!!.toInt())
            assertTrue(longChains.isNotEmpty() && companionAlone.isNotEmpty())
        }
    }

    @Test
    fun migratingAddsOneColumnAndKeepsEveryRowThatWasTheUsers() {
        createVersion7("v7.db")
        val before = context.openOrCreateDatabase("v7.db", Context.MODE_PRIVATE, null).use { raw -> userRows { raw.rawQuery(it, null) } }

        val db = open("v7.db")
        val raw = db.openHelper.writableDatabase

        assertEquals(9, raw.version)
        assertEquals(before, userRows { raw.query(it) })
        val columns = raw.query("PRAGMA table_info(hadith)").rows().map { it[1]!! }
        assertEquals(versionSevenHadithColumns.split(", ") + "sanadSource" + "catalogUpdatedAt", columns)
        db.close()
    }

    @Test
    fun theUpdateGivesTheLongChainsAndNamesTheBookTheyAreReadFrom() = runBlocking {
        createVersion7("v7.db")

        val db = open("v7.db")

        assertEquals(SeedLoader.CATALOG_VERSION, SeedLoader.appliedVersion(db.openHelper.writableDatabase))
        val all = db.hadithDao().getAllWithSanad()
        assertEquals(seed.hadiths, all.map { it.hadith }.sortedBy { it.id })
        all.forEach { h ->
            assertEquals(
                "hadith ${h.hadith.id}",
                seed.edges.filter { it.hadithId == h.hadith.id }.sortedBy { it.orderIndex }.map { it.narratorId to it.transmissionTerm },
                h.sanad.map { it.narrator.id to it.transmissionTerm },
            )
        }
        assertEquals(longChains.toSet(), all.filter { it.hadith.sanadSource.isNotEmpty() }.map { it.hadith.id }.toSet())
        assertEquals(seed.narrators.map { it.id }.toSet(), db.narratorDao().getAll().map { it.id }.toSet())
        db.close()
    }

    @Test
    fun theProgressIsUntouchedAndOnlyTheNarratorHistoryOfAReplacedChainGoes() = runBlocking {
        createVersion7("v7.db")

        val db = open("v7.db")
        val raw = db.openHelper.writableDatabase

        val mastery = db.masteryDao().getAll().associateBy { it.hadithId }
        assertEquals(studied.toSet(), mastery.keys)
        assertEquals(3.5f, mastery.getValue(studied[0]).matnStability, 0f)
        assertEquals(1.25f * 2, mastery.getValue(studied[1]).sanadStability, 0f)
        // What was recalled of a chain the catalog replaced said how well the user knew narrators it no longer has.
        val events = raw.query("SELECT hadithId FROM narrator_review_event").rows().map { it.single()!!.toLong() }
        assertEquals(listOf(studied[1]), events)
        assertEquals(1, raw.query("SELECT COUNT(*) FROM collections").rows().single().single()!!.toInt())
        assertEquals(studied.size, raw.query("SELECT COUNT(*) FROM hadith_collection_cross_ref").rows().single().single()!!.toInt())
        db.close()
    }

    @Test
    fun reopeningTheMigratedDatabaseChangesNothing() = runBlocking {
        createVersion7("v7.db")
        val first = open("v7.db")
        val hadiths = first.hadithDao().getAllWithSanad()
        first.close()

        val second = open("v7.db")

        assertEquals(hadiths, second.hadithDao().getAllWithSanad())
        second.close()
    }
}

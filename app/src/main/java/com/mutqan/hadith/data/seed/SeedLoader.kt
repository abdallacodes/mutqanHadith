package com.mutqan.hadith.data.seed

import android.content.ContentValues
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.CatalogStateEntity
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity
import com.mutqan.hadith.data.text.ArabicNormalizer
import org.json.JSONArray
import org.json.JSONObject

data class SeedData(
    val narrators: List<NarratorEntity>,
    val hadiths: List<HadithEntity>,
    val edges: List<SanadEdgeEntity>,
    val groups: List<CatalogGroupEntity> = emptyList(),
)

/**
 * Parses `assets/seed_hadiths.json` and writes it straight into the database being created,
 * so the seed commits atomically with the schema. An existing database is brought up to date
 * with [sync] instead, which leaves the user's progress alone.
 */
object SeedLoader {
    const val ASSET_NAME = "seed_hadiths.json"

    /**
     * The version of the bundled catalog. Raise it whenever `seed_hadiths.json` changes: a database
     * that has absorbed an older version [sync]s the new one the next time it opens, then records it
     * in `catalog_state`. A schema change is a Room migration instead.
     *
     * 1: the first levelled catalog. 2: every text and chain re-sourced from audited datasets
     * (`tools/import_hadiths.py`), replacing the hand-written one. 3: Level 2, the hadiths of 'Umdat al-Ahkam
     * that were found in Sahih al-Bukhari or Sahih Muslim, added as new rows. 4: the Forty Hadith alone; the
     * Bukhari selection and 'Umdat al-Ahkam were retired (see [RETIRED_IN]). 5: the hadiths are filed in groups (books and
     * their sections) and carry the position they are taught in. 6: the hadiths that have a narration whose isnad can be
     * followed to their Companion carry that full chain, and say which book it is read from.
     */
    const val CATALOG_VERSION = 6

    /**
     * The catalog version that retired the hadiths of Sahih al-Bukhari and 'Umdat al-Ahkam. A database that
     * absorbed an older catalog loses every hadith the catalog no longer has, together with its progress, its
     * narrator history and its place in the user's collections, once, when it is synced to this version or later.
     * A later catalog that carries such hadiths again simply adds them, as any new hadith.
     */
    private const val RETIRED_IN = 4

    fun load(context: Context): SeedData =
        parse(context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() })

    fun parse(json: String): SeedData {
        val root = JSONObject(json)
        val narrators = root.getJSONArray("narrators").let { arr ->
            List(arr.length()) { i ->
                arr.getJSONObject(i).run {
                    NarratorEntity(
                        id = getLong("id"),
                        primaryName = getString("primaryName"),
                        fullName = getString("fullName"),
                        tier = getString("tier"),
                        isPivot = getBoolean("isPivot"),
                    )
                }
            }
        }
        // A catalog written before groups existed (catalog 4 and older) has none, and files its hadiths nowhere.
        val groups = (root.optJSONArray("groups") ?: JSONArray()).let { arr ->
            List(arr.length()) { i ->
                arr.getJSONObject(i).run {
                    CatalogGroupEntity(
                        id = getLong("id"),
                        parentId = if (isNull("parentId")) null else getLong("parentId"),
                        title = getString("title"),
                        titleEn = getString("titleEn"),
                        position = getInt("position"),
                    )
                }
            }
        }
        val hadiths = mutableListOf<HadithEntity>()
        val edges = mutableListOf<SanadEdgeEntity>()
        val hadithArr = root.getJSONArray("hadiths")
        for (i in 0 until hadithArr.length()) {
            val h = hadithArr.getJSONObject(i)
            val id = h.getLong("id")
            val matn = h.getString("matnWithTashkeel")
            hadiths += HadithEntity(
                id = id,
                matnWithTashkeel = matn,
                matnClean = ArabicNormalizer.normalize(matn),
                sourceBook = h.getString("sourceBook"),
                hadithNumber = h.getInt("hadithNumber"),
                chapter = h.getString("chapter"),
                grade = h.getString("grade"),
                collector = h.getString("collector"),
                takhrij = h.getString("takhrij"),
                sahabi = h.getString("sahabi"),
                level = h.getInt("level"),
                groupId = getLongOrNull(h, "groupId"),
                position = h.optInt("position", 0),
                sanadSource = h.optString("sanadSource", ""),
            )
            val sanad = h.getJSONArray("sanad")
            for (j in 0 until sanad.length()) {
                val link = sanad.getJSONObject(j)
                edges += SanadEdgeEntity(
                    hadithId = id,
                    narratorId = link.getLong("narratorId"),
                    orderIndex = j,
                    transmissionTerm = link.getString("transmissionTerm"),
                )
            }
        }
        return SeedData(narrators, hadiths, edges, groups)
    }

    private fun getLongOrNull(json: JSONObject, name: String): Long? = if (json.isNull(name)) null else json.getLong(name)

    fun isEmpty(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT COUNT(*) FROM hadith").use { it.moveToFirst() && it.getInt(0) == 0 }

    /** The catalog version this database has absorbed; 0 when it has not recorded one. */
    fun appliedVersion(db: SupportSQLiteDatabase): Int =
        db.query("SELECT version FROM catalog_state WHERE id = ${CatalogStateEntity.SINGLETON_ID}").use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun markApplied(db: SupportSQLiteDatabase, version: Int) {
        db.execSQL(
            "INSERT OR REPLACE INTO catalog_state (id, version) VALUES (?, ?)",
            arrayOf(CatalogStateEntity.SINGLETON_ID, version),
        )
    }

    /** Inserts in FK order; call inside a transaction so a partial seed never survives. */
    fun write(db: SupportSQLiteDatabase, data: SeedData) {
        data.groups.forEach { db.insert("catalog_group", CONFLICT_REPLACE, groupValues(it)) }
        data.narrators.forEach { db.insert("narrator", CONFLICT_REPLACE, narratorValues(it)) }
        data.hadiths.forEach { db.insert("hadith", CONFLICT_REPLACE, hadithValues(it)) }
        data.edges.forEach { db.insert("sanad_edge", CONFLICT_REPLACE, edgeValues(it)) }
    }

    /**
     * Brings a database that already holds a catalog up to [data]: adds the narrators and hadiths it
     * lacks and refreshes the ones it has. Existing rows are updated in place, never replaced, because
     * deleting a hadith would cascade into the user's mastery records, which this never touches.
     *
     * The chain of a hadith that is already there is replaced when the catalog words it differently,
     * and so is the narrator-level history recorded against the old chain: it says how well the user
     * knew narrators the hadith no longer has, and narrator ids may now mean someone else. Narrators
     * that nothing refers to any more are removed. A database whose [appliedVersion] predates [RETIRED_IN] first
     * drops the hadiths the catalog no longer carries. The groups are rewritten as a whole: nothing the user owns refers
     * to one. Call inside a transaction.
     *
     * A row the cloud catalog has written (`catalogUpdatedAt` above 0) belongs to the cloud from then on: this leaves it,
     * its chain and its place alone, so that an app update never undoes an admin's edit. Rows the cloud has not touched
     * are refreshed as before.
     */
    fun sync(db: SupportSQLiteDatabase, data: SeedData, appliedVersion: Int) {
        if (appliedVersion < RETIRED_IN) retireMissing(db, data)
        db.execSQL("DELETE FROM catalog_group WHERE catalogUpdatedAt = 0")
        val cloudGroups = ids(db, "catalog_group")
        data.groups.filter { it.id !in cloudGroups }.forEach { db.insert("catalog_group", CONFLICT_ABORT, groupValues(it)) }
        val knownHadiths = ids(db, "hadith")
        val cloudHadiths = cloudOwned(db, "hadith")
        val cloudNarrators = cloudOwned(db, "narrator")
        val storedChains = chains(db)
        val newChains = data.edges.groupBy { it.hadithId }.mapValues { (_, edges) ->
            edges.sortedBy { it.orderIndex }.map { it.narratorId to it.transmissionTerm }
        }
        val replaced = data.hadiths.map { it.id }
            .filter { it in knownHadiths && it !in cloudHadiths && storedChains[it].orEmpty() != newChains[it].orEmpty() }
            .toSet()
        // The old chains go first, so that no narrator row is rewritten under an edge that still means someone else.
        replaced.forEach {
            db.delete("narrator_review_event", "hadithId = ?", arrayOf(it))
            db.delete("sanad_edge", "hadithId = ?", arrayOf(it))
        }

        val knownNarrators = ids(db, "narrator")
        data.narrators.forEach {
            if (it.id in cloudNarrators) {
                // The cloud owns this narrator.
            } else if (it.id in knownNarrators) {
                db.update("narrator", CONFLICT_NONE, narratorValues(it), "id = ?", arrayOf(it.id))
            } else {
                db.insert("narrator", CONFLICT_ABORT, narratorValues(it))
            }
        }

        data.hadiths.forEach {
            if (it.id in cloudHadiths) {
                // The cloud owns this hadith.
            } else if (it.id in knownHadiths) {
                db.update("hadith", CONFLICT_NONE, hadithValues(it), "id = ?", arrayOf(it.id))
            } else {
                db.insert("hadith", CONFLICT_ABORT, hadithValues(it))
            }
        }
        data.edges.filter { it.hadithId !in cloudHadiths && (it.hadithId !in knownHadiths || it.hadithId in replaced) }.forEach {
            db.insert("sanad_edge", CONFLICT_ABORT, edgeValues(it))
        }

        val wanted = data.narrators.joinToString(",") { it.id.toString() }
        db.execSQL(
            "DELETE FROM narrator WHERE id NOT IN ($wanted) " +
                "AND catalogUpdatedAt = 0 " +
                "AND id NOT IN (SELECT narratorId FROM sanad_edge) " +
                "AND id NOT IN (SELECT narratorId FROM narrator_review_event)"
        )
    }

    /**
     * Deletes the hadiths [data] does not carry, children first and explicitly, so that it does not depend on the
     * connection enforcing the cascades. Narrators that were only theirs are removed by [sync] afterwards.
     */
    private fun retireMissing(db: SupportSQLiteDatabase, data: SeedData) {
        val kept = data.hadiths.joinToString(",") { it.id.toString() }
        listOf("mastery_record", "narrator_review_event", "hadith_collection_cross_ref", "sanad_edge").forEach {
            db.execSQL("DELETE FROM $it WHERE hadithId NOT IN ($kept)")
        }
        db.execSQL("DELETE FROM hadith WHERE id NOT IN ($kept)")
    }

    /** Each stored chain as (narrator, term) pairs in order, from the collector's side. */
    private fun chains(db: SupportSQLiteDatabase): Map<Long, List<Pair<Long, String>>> =
        db.query("SELECT hadithId, narratorId, transmissionTerm FROM sanad_edge ORDER BY hadithId, orderIndex").use { cursor ->
            val chains = mutableMapOf<Long, MutableList<Pair<Long, String>>>()
            while (cursor.moveToNext()) {
                chains.getOrPut(cursor.getLong(0)) { mutableListOf() } += cursor.getLong(1) to cursor.getString(2)
            }
            chains
        }

    /** The ids of the rows of [table] the cloud catalog has written. */
    private fun cloudOwned(db: SupportSQLiteDatabase, table: String): Set<Long> =
        db.query("SELECT id FROM $table WHERE catalogUpdatedAt > 0").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

    private fun ids(db: SupportSQLiteDatabase, table: String): Set<Long> =
        db.query("SELECT id FROM $table").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

    private fun narratorValues(n: NarratorEntity) = ContentValues().apply {
        put("id", n.id)
        put("primaryName", n.primaryName)
        put("fullName", n.fullName)
        put("tier", n.tier)
        put("isPivot", if (n.isPivot) 1 else 0)
    }

    private fun hadithValues(h: HadithEntity) = ContentValues().apply {
        put("id", h.id)
        put("matnWithTashkeel", h.matnWithTashkeel)
        put("matnClean", h.matnClean)
        put("sourceBook", h.sourceBook)
        put("hadithNumber", h.hadithNumber)
        put("chapter", h.chapter)
        put("grade", h.grade)
        put("collector", h.collector)
        put("takhrij", h.takhrij)
        put("sahabi", h.sahabi)
        put("level", h.level)
        if (h.groupId != null) put("groupId", h.groupId) else putNull("groupId")
        put("position", h.position)
        put("sanadSource", h.sanadSource)
    }

    private fun groupValues(g: CatalogGroupEntity) = ContentValues().apply {
        put("id", g.id)
        if (g.parentId != null) put("parentId", g.parentId) else putNull("parentId")
        put("title", g.title)
        put("titleEn", g.titleEn)
        put("position", g.position)
    }

    private fun edgeValues(e: SanadEdgeEntity) = ContentValues().apply {
        put("hadithId", e.hadithId)
        put("narratorId", e.narratorId)
        put("orderIndex", e.orderIndex)
        put("transmissionTerm", e.transmissionTerm)
    }

    private const val CONFLICT_NONE = android.database.sqlite.SQLiteDatabase.CONFLICT_NONE
    private const val CONFLICT_ABORT = android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT
    private const val CONFLICT_REPLACE = android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
}

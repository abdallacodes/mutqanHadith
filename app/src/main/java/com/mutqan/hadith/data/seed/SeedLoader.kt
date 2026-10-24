package com.mutqan.hadith.data.seed

import android.content.ContentValues
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity
import com.mutqan.hadith.data.text.ArabicNormalizer
import org.json.JSONObject

data class SeedData(
    val narrators: List<NarratorEntity>,
    val hadiths: List<HadithEntity>,
    val edges: List<SanadEdgeEntity>,
)

/**
 * Parses `assets/seed_hadiths.json` and writes it straight into the database being created,
 * so the seed commits atomically with the schema.
 */
object SeedLoader {
    const val ASSET_NAME = "seed_hadiths.json"

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
                chapter = h.getString("chapter"),
                grade = h.getString("grade"),
                collector = h.getString("collector"),
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
        return SeedData(narrators, hadiths, edges)
    }

    /** Inserts in FK order; call inside the open transaction of `RoomDatabase.Callback.onCreate`. */
    fun write(db: SupportSQLiteDatabase, data: SeedData) {
        data.narrators.forEach {
            db.insert("narrator", CONFLICT_REPLACE, ContentValues().apply {
                put("id", it.id)
                put("primaryName", it.primaryName)
                put("fullName", it.fullName)
                put("tier", it.tier)
                put("isPivot", if (it.isPivot) 1 else 0)
            })
        }
        data.hadiths.forEach {
            db.insert("hadith", CONFLICT_REPLACE, ContentValues().apply {
                put("id", it.id)
                put("matnWithTashkeel", it.matnWithTashkeel)
                put("matnClean", it.matnClean)
                put("sourceBook", it.sourceBook)
                put("chapter", it.chapter)
                put("grade", it.grade)
                put("collector", it.collector)
            })
        }
        data.edges.forEach {
            db.insert("sanad_edge", CONFLICT_REPLACE, ContentValues().apply {
                put("hadithId", it.hadithId)
                put("narratorId", it.narratorId)
                put("orderIndex", it.orderIndex)
                put("transmissionTerm", it.transmissionTerm)
            })
        }
    }

    private const val CONFLICT_REPLACE = android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
}

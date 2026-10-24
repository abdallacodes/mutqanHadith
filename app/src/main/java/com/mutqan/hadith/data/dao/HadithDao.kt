package com.mutqan.hadith.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.data.text.ArabicNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Dao
interface HadithDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHadith(hadith: HadithEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSanadEdges(edges: List<SanadEdgeEntity>)

    @Transaction
    suspend fun insertWithSanad(hadith: HadithEntity, edges: List<SanadEdgeEntity>) {
        insertHadith(hadith)
        insertSanadEdges(edges)
    }

    @Query("SELECT COUNT(*) FROM hadith")
    suspend fun count(): Int

    @Query("SELECT id FROM hadith")
    suspend fun getAllIds(): List<Long>

    @Transaction
    @Query("SELECT * FROM hadith WHERE id = :id")
    suspend fun getHadithWithSanad(id: Long): HadithWithFullSanad?

    @Transaction
    @Query("SELECT * FROM hadith WHERE id = :id")
    fun observeHadithWithSanad(id: Long): Flow<HadithWithFullSanad?>

    @Transaction
    @Query("SELECT * FROM hadith WHERE id IN (:ids) ORDER BY id")
    suspend fun getWithSanadByIds(ids: Collection<Long>): List<HadithWithFullSanad>

    @Transaction
    @Query("SELECT * FROM hadith ORDER BY id")
    suspend fun getAllWithSanad(): List<HadithWithFullSanad>

    @Transaction
    @Query("SELECT * FROM hadith ORDER BY id")
    fun observeAllWithSanad(): Flow<List<HadithWithFullSanad>>

    /** Hadiths that were never reviewed (no mastery record), in curriculum order. */
    @Transaction
    @Query(
        "SELECT * FROM hadith h WHERE NOT EXISTS " +
            "(SELECT 1 FROM mastery_record m WHERE m.hadithId = h.id) ORDER BY h.id"
    )
    suspend fun getUnseenWithSanad(): List<HadithWithFullSanad>

    /** [pattern] must already be normalized and LIKE-escaped (`\` is the escape character); prefer [search]. */
    @Transaction
    @Query("SELECT * FROM hadith WHERE matnClean LIKE '%' || :pattern || '%' ESCAPE '\\' ORDER BY id")
    suspend fun searchByCleanText(pattern: String): List<HadithWithFullSanad>

    /** The hadiths of one curriculum [level], in curriculum order; emits again when the library changes. */
    @Query("SELECT * FROM hadith WHERE level = :level ORDER BY id")
    fun getHadithsByLevel(level: Int): Flow<List<HadithEntity>>

    @Query("SELECT COUNT(*) FROM hadith WHERE level = :level")
    fun countHadithsByLevel(level: Int): Flow<Int>

    /** [pattern] must already be normalized and LIKE-escaped (`\` is the escape character); prefer [searchInLevel]. */
    @Query("SELECT * FROM hadith WHERE level = :level AND matnClean LIKE '%' || :pattern || '%' ESCAPE '\\' ORDER BY id")
    fun searchInLevelByCleanText(level: Int, pattern: String): Flow<List<HadithEntity>>

    /** Every Hadith whose chain passes through [narratorId], at any position. */
    @Transaction
    @Query(
        "SELECT DISTINCT h.* FROM hadith h " +
            "INNER JOIN sanad_edge e ON e.hadithId = h.id " +
            "WHERE e.narratorId = :narratorId ORDER BY h.id"
    )
    suspend fun getHadithsByNarrator(narratorId: Long): List<HadithWithFullSanad>
}

/** [query] as a LIKE pattern over the clean text, or null when nothing searchable is left after normalizing. */
private fun likePattern(query: String): String? {
    val normalized = ArabicNormalizer.normalize(query)
    if (normalized.isEmpty()) return null
    return normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/** Searches by Arabic text with or without tashkeel. */
suspend fun HadithDao.search(query: String): List<HadithWithFullSanad> =
    likePattern(query)?.let { searchByCleanText(it) } ?: emptyList()

/** [search] within one curriculum [level]; a query with nothing searchable in it matches nothing. */
fun HadithDao.searchInLevel(level: Int, query: String): Flow<List<HadithEntity>> =
    likePattern(query)?.let { searchInLevelByCleanText(level, it) } ?: flowOf(emptyList())

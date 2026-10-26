package com.mutqan.hadith.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mutqan.hadith.data.entity.CollectionEntity
import com.mutqan.hadith.data.entity.HadithCollectionCrossRef
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.domain.model.CollectionSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    /** Joining twice is not an error and keeps the first date: a hadith is in a collection or it is not. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addReference(reference: HadithCollectionCrossRef): Long

    @Transaction
    suspend fun insertWith(collection: CollectionEntity, firstHadithId: Long?): Long {
        val id = insert(collection)
        if (firstHadithId != null) addReference(HadithCollectionCrossRef(id, firstHadithId, collection.createdAt))
        return id
    }

    @Query("UPDATE collections SET title = :title, accent = :accent WHERE id = :id")
    suspend fun update(id: Long, title: String, accent: Int)

    /** Removes the collection and, by cascade, its references; never the hadiths they pointed at. */
    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM hadith_collection_cross_ref WHERE collectionId = :collectionId AND hadithId = :hadithId")
    suspend fun removeReference(collectionId: Long, hadithId: Long)

    /** Newest first. */
    @Query(
        "SELECT c.id AS id, c.title AS title, c.accent AS accent, c.createdAt AS createdAt, COUNT(x.hadithId) AS hadithCount " +
            "FROM collections c LEFT JOIN hadith_collection_cross_ref x ON x.collectionId = c.id " +
            "GROUP BY c.id ORDER BY c.createdAt DESC, c.id DESC"
    )
    fun observeSummaries(): Flow<List<CollectionSummary>>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observe(id: Long): Flow<CollectionEntity?>

    /** The hadiths of a collection in the order they were added. */
    @Query(
        "SELECT h.* FROM hadith h INNER JOIN hadith_collection_cross_ref x ON x.hadithId = h.id " +
            "WHERE x.collectionId = :collectionId ORDER BY x.addedAt, h.id"
    )
    fun observeHadiths(collectionId: Long): Flow<List<HadithEntity>>

    @Query("SELECT hadithId FROM hadith_collection_cross_ref WHERE collectionId = :collectionId ORDER BY addedAt, hadithId")
    suspend fun getHadithIds(collectionId: Long): List<Long>

    /** The collections [hadithId] belongs to. */
    @Query("SELECT collectionId FROM hadith_collection_cross_ref WHERE hadithId = :hadithId")
    fun observeCollectionIdsOf(hadithId: Long): Flow<List<Long>>

    /** Every hadith that is in at least one collection. */
    @Query("SELECT DISTINCT hadithId FROM hadith_collection_cross_ref")
    fun observeCollectedHadithIds(): Flow<List<Long>>

    /** Whether a name is taken by a collection other than [exceptId]; case does not matter. */
    @Query("SELECT COUNT(*) FROM collections WHERE title = :title COLLATE NOCASE AND id != :exceptId")
    suspend fun countTitled(title: String, exceptId: Long): Int
}

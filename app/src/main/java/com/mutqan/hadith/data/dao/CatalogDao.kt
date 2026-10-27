package com.mutqan.hadith.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity

/**
 * What applying the cloud catalog needs. Rows are upserted in place: replacing a hadith would delete it first and the
 * cascade would take the learner's progress with it, so nothing here uses `REPLACE`.
 */
@Dao
interface CatalogDao {
    @Upsert
    suspend fun upsertGroup(group: CatalogGroupEntity)

    @Upsert
    suspend fun upsertNarrator(narrator: NarratorEntity)

    @Upsert
    suspend fun upsertHadith(hadith: HadithEntity)

    @Insert
    suspend fun insertEdges(edges: List<SanadEdgeEntity>)

    @Query("DELETE FROM sanad_edge WHERE hadithId = :hadithId")
    suspend fun deleteEdges(hadithId: Long)

    @Query("SELECT id FROM narrator")
    suspend fun narratorIds(): List<Long>

    @Query("SELECT id FROM hadith")
    suspend fun hadithIds(): List<Long>

    @Query("DELETE FROM catalog_group WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Query("UPDATE hadith SET groupId = NULL WHERE groupId = :groupId")
    suspend fun unfileHadithsOf(groupId: Long)

    /** A deleted hadith takes its progress, its narrator history and its place in the learner's collections with it. */
    @Query("DELETE FROM mastery_record WHERE hadithId = :hadithId")
    suspend fun deleteMastery(hadithId: Long)

    @Query("DELETE FROM narrator_review_event WHERE hadithId = :hadithId")
    suspend fun deleteEvents(hadithId: Long)

    @Query("DELETE FROM hadith_collection_cross_ref WHERE hadithId = :hadithId")
    suspend fun deleteCollectionRefs(hadithId: Long)

    @Query("DELETE FROM hadith WHERE id = :hadithId")
    suspend fun deleteHadith(hadithId: Long)

    /** Removes a narrator nothing refers to; one that a chain or a recall still names is kept. */
    @Query(
        "DELETE FROM narrator WHERE id = :id AND id NOT IN (SELECT narratorId FROM sanad_edge) " +
            "AND id NOT IN (SELECT narratorId FROM narrator_review_event)"
    )
    suspend fun deleteNarratorIfUnused(id: Long)
}

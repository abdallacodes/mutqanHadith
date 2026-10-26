package com.mutqan.hadith.domain.repository

import com.mutqan.hadith.data.entity.CollectionEntity
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.CollectionSummary
import com.mutqan.hadith.domain.model.HadithOutcomeCount
import com.mutqan.hadith.domain.model.NarratorOutcomeCount
import kotlinx.coroutines.flow.Flow

interface HadithRepository {
    suspend fun getWithSanad(id: Long): HadithWithFullSanad?

    suspend fun getWithSanad(ids: Collection<Long>): List<HadithWithFullSanad>

    /** Hadiths with no mastery record yet, in curriculum order. */
    suspend fun getUnseen(): List<HadithWithFullSanad>

    fun observeAll(): Flow<List<HadithWithFullSanad>>

    suspend fun getAll(): List<HadithWithFullSanad>

    /** Every Hadith whose chain passes through [narratorId], at any position, in curriculum order. */
    suspend fun getThroughNarrator(narratorId: Long): List<HadithWithFullSanad>

    /** The hadiths of one curriculum [level] in curriculum order; emits again when the library changes. */
    fun getHadithsByLevel(level: Int): Flow<List<HadithEntity>>

    fun countHadithsByLevel(level: Int): Flow<Int>

    /** The hadiths of [level] whose text contains [query], with or without tashkeel; a query with nothing searchable in it finds none. */
    fun searchInLevel(level: Int, query: String): Flow<List<HadithEntity>>
}

interface NarratorRepository {
    suspend fun getAll(): List<NarratorEntity>
}

/**
 * The user's personal collections of hadiths. A collection only ever refers to hadiths: nothing here creates, changes
 * or removes a hadith, a mastery record or any other study data.
 */
interface CollectionRepository {
    /** Every collection with its size, newest first. */
    fun observeAll(): Flow<List<CollectionSummary>>

    /** One collection; emits null when it does not exist or is deleted. */
    fun observe(id: Long): Flow<CollectionEntity?>

    /** The hadiths of a collection in the order they were added. */
    fun observeHadiths(collectionId: Long): Flow<List<HadithEntity>>

    /** The ids of the collections a hadith is in. */
    fun observeCollectionIdsOf(hadithId: Long): Flow<Set<Long>>

    /** The ids of all hadiths that are in some collection. */
    fun observeCollectedHadithIds(): Flow<Set<Long>>

    /** The ids of a collection's hadiths in the order they were added. */
    suspend fun getHadithIds(collectionId: Long): List<Long>

    /** Whether another collection than [exceptId] already has this name, whatever its case. */
    suspend fun isTitleTaken(title: String, exceptId: Long = 0): Boolean

    /** Creates a collection, together with its first hadith if one is given, or not at all; returns its id. */
    suspend fun create(title: String, accent: Int, createdAt: Long, firstHadithId: Long? = null): Long

    suspend fun update(id: Long, title: String, accent: Int)

    /** Deletes the collection and its references to hadiths; the hadiths themselves stay. */
    suspend fun delete(id: Long)

    /** Puts a hadith in a collection; nothing happens if it is there already. */
    suspend fun add(collectionId: Long, hadithId: Long, addedAt: Long)

    suspend fun remove(collectionId: Long, hadithId: Long)
}

interface ReviewRepository {
    suspend fun getMastery(hadithId: Long): MasteryRecordEntity?

    /** Mastery records whose `nextReviewDue` is at or before [now]. */
    suspend fun getDueMastery(now: Long): List<MasteryRecordEntity>

    /** Persists the new mastery state and the per-narrator outcomes of the same review atomically. */
    suspend fun saveReview(record: MasteryRecordEntity, narratorEvents: List<NarratorReviewEventEntity>)

    /** Records narrator outcomes without touching the Hadith's mastery state. */
    suspend fun logNarratorEvents(narratorEvents: List<NarratorReviewEventEntity>)

    suspend fun getAllMastery(): List<MasteryRecordEntity>

    fun observeAllMastery(): Flow<List<MasteryRecordEntity>>

    suspend fun getNarratorOutcomeCounts(hadithId: Long): List<NarratorOutcomeCount>

    /** Per-narrator tallies over every Hadith and review. */
    suspend fun getNarratorOutcomeTotals(): List<NarratorOutcomeCount>

    /** One narrator's tally in each Hadith they appear in. */
    suspend fun getOutcomeCountsByHadith(narratorId: Long): List<HadithOutcomeCount>

    /** The moments reviews were recorded, in epoch millis; each review is listed once. */
    suspend fun getReviewTimestamps(): List<Long>
}

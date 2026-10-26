package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.CollectionEntity
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.CollectionSummary
import com.mutqan.hadith.domain.repository.CollectionRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** The collections of the app held in memory, behaving as the database does: references to hadiths the library lacks are refused. */
class FakeCollectionRepository(hadiths: List<HadithWithFullSanad> = emptyList()) : CollectionRepository {
    private data class Ref(val collectionId: Long, val hadithId: Long, val addedAt: Long)

    private val known: Map<Long, HadithEntity> = hadiths.associate { it.hadith.id to it.hadith }
    private val collections = MutableStateFlow<List<CollectionEntity>>(emptyList())
    private val refs = MutableStateFlow<List<Ref>>(emptyList())
    private var nextId = 1L

    /** Makes every write fail, like a full disk. */
    var failWrites = false

    /** How many writes were made, to show that a refused request wrote nothing. */
    var writes = 0
        private set

    private fun write() {
        if (failWrites) throw IllegalStateException("disk full")
        writes++
    }

    private fun refsOf(collectionId: Long) =
        refs.value.filter { it.collectionId == collectionId }.sortedWith(compareBy({ it.addedAt }, { it.hadithId }))

    override fun observeAll(): Flow<List<CollectionSummary>> = combine(collections, refs) { all, references ->
        all.sortedWith(compareByDescending<CollectionEntity> { it.createdAt }.thenByDescending { it.id })
            .map { c -> CollectionSummary(c.id, c.title, c.accent, c.createdAt, references.count { it.collectionId == c.id }) }
    }

    override fun observe(id: Long): Flow<CollectionEntity?> = collections.map { list -> list.firstOrNull { it.id == id } }

    override fun observeHadiths(collectionId: Long): Flow<List<HadithEntity>> =
        refs.map { refsOf(collectionId).mapNotNull { known[it.hadithId] } }

    override fun observeCollectionIdsOf(hadithId: Long): Flow<Set<Long>> =
        refs.map { all -> all.filter { it.hadithId == hadithId }.map { it.collectionId }.toSet() }

    override fun observeCollectedHadithIds(): Flow<Set<Long>> = refs.map { all -> all.map { it.hadithId }.toSet() }

    override suspend fun getHadithIds(collectionId: Long): List<Long> = refsOf(collectionId).map { it.hadithId }

    override suspend fun isTitleTaken(title: String, exceptId: Long): Boolean =
        collections.value.any { it.id != exceptId && it.title.equals(title, ignoreCase = true) }

    override suspend fun create(title: String, accent: Int, createdAt: Long, firstHadithId: Long?): Long {
        write()
        require(firstHadithId == null || firstHadithId in known) { "FOREIGN KEY constraint failed" }
        val id = nextId++
        collections.value += CollectionEntity(id, title, createdAt, accent)
        if (firstHadithId != null) refs.value += Ref(id, firstHadithId, createdAt)
        return id
    }

    override suspend fun update(id: Long, title: String, accent: Int) {
        write()
        collections.value = collections.value.map { if (it.id == id) it.copy(title = title, accent = accent) else it }
    }

    override suspend fun delete(id: Long) {
        write()
        collections.value = collections.value.filterNot { it.id == id }
        refs.value = refs.value.filterNot { it.collectionId == id }
    }

    override suspend fun add(collectionId: Long, hadithId: Long, addedAt: Long) {
        write()
        require(hadithId in known) { "FOREIGN KEY constraint failed" }
        if (refs.value.none { it.collectionId == collectionId && it.hadithId == hadithId }) refs.value += Ref(collectionId, hadithId, addedAt)
    }

    override suspend fun remove(collectionId: Long, hadithId: Long) {
        write()
        refs.value = refs.value.filterNot { it.collectionId == collectionId && it.hadithId == hadithId }
    }
}

/**
 * A review store that answers reads from [base] and fails the test the moment anything is written to it: used to show
 * that a flow which must not record a review does not, whatever it is given.
 */
class ReadOnlyReviewRepository(private val base: ReviewRepository) : ReviewRepository by base {
    override suspend fun saveReview(record: MasteryRecordEntity, narratorEvents: List<NarratorReviewEventEntity>) {
        throw AssertionError("a review was recorded for hadith ${record.hadithId}")
    }

    override suspend fun logNarratorEvents(narratorEvents: List<NarratorReviewEventEntity>) {
        throw AssertionError("narrator events were recorded: $narratorEvents")
    }
}

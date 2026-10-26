package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.entity.CollectionEntity
import com.mutqan.hadith.domain.model.CollectionAccent
import com.mutqan.hadith.domain.model.CollectionSaved
import com.mutqan.hadith.domain.model.CollectionTitle
import com.mutqan.hadith.domain.model.TitleProblem
import com.mutqan.hadith.domain.repository.CollectionRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The problem with a collection's [cleaned] name, or null if it will do; [exceptId] is the collection being renamed. */
private suspend fun CollectionRepository.problemWith(cleaned: String, exceptId: Long): TitleProblem? = when {
    !CollectionTitle.isVisible(cleaned) -> TitleProblem.EMPTY
    cleaned.length > CollectionTitle.MAX_LENGTH -> TitleProblem.TOO_LONG
    isTitleTaken(cleaned, exceptId) -> TitleProblem.DUPLICATE
    else -> null
}

/**
 * Makes a new collection. The name is tidied first and refused when it is empty, too long or already used; with
 * [firstHadithId] the collection is born holding that hadith, so a hadith can be filed into a collection that did not
 * exist a moment ago in one step.
 */
class CreateCollectionUseCase(
    private val collections: CollectionRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend operator fun invoke(title: String, accent: Int = 0, firstHadithId: Long? = null): CollectionSaved {
        val cleaned = CollectionTitle.clean(title)
        collections.problemWith(cleaned, exceptId = 0)?.let { return CollectionSaved.Rejected(it) }
        return CollectionSaved.Saved(collections.create(cleaned, CollectionAccent.coerce(accent), now(), firstHadithId))
    }
}

/** Renames a collection and/or changes its accent; the name rules are those of [CreateCollectionUseCase]. */
class RenameCollectionUseCase(private val collections: CollectionRepository) {
    suspend operator fun invoke(id: Long, title: String, accent: Int): CollectionSaved {
        val cleaned = CollectionTitle.clean(title)
        collections.problemWith(cleaned, exceptId = id)?.let { return CollectionSaved.Rejected(it) }
        collections.update(id, cleaned, CollectionAccent.coerce(accent))
        return CollectionSaved.Saved(id)
    }
}

/** Deletes a collection and its list of hadiths. The hadiths and the user's progress on them are not touched. */
class DeleteCollectionUseCase(private val collections: CollectionRepository) {
    suspend operator fun invoke(id: Long) = collections.delete(id)
}

/** Puts a hadith in a collection or takes it out; either is safe to repeat. */
class SetCollectionMembershipUseCase(
    private val collections: CollectionRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend operator fun invoke(collectionId: Long, hadithId: Long, member: Boolean) {
        if (member) collections.add(collectionId, hadithId, now()) else collections.remove(collectionId, hadithId)
    }
}

/** A collection with its hadiths, each with the user's progress on it, in the order they were added. */
data class CollectionDetail(
    val collection: CollectionEntity,
    val hadiths: List<LibraryHadith>,
) {
    val isEmpty: Boolean get() = hadiths.isEmpty()
    val dueCount: Int get() = hadiths.count { it.isDue }
}

/**
 * One collection as the user sees it: its hadiths with their progress. It reads the user's progress and never writes it,
 * and emits null once the collection is gone.
 */
class ObserveCollectionDetailUseCase(
    private val collections: CollectionRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    operator fun invoke(id: Long): Flow<CollectionDetail?> =
        combine(collections.observe(id), collections.observeHadiths(id), reviews.observeAllMastery()) { collection, hadiths, mastery ->
            val at = now()
            val masteryById = mastery.associateBy { it.hadithId }
            collection?.let {
                CollectionDetail(
                    collection = it,
                    hadiths = hadiths.map { hadith ->
                        val record = masteryById[hadith.id]
                        LibraryHadith(hadith, record?.let(scheduler::metrics), isDue = record != null && record.nextReviewDue <= at)
                    },
                )
            }
        }
}

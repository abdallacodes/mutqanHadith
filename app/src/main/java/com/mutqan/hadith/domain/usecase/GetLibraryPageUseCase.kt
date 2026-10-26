package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** How far a hadith has come for the user. */
enum class HadithProgress { NEW, LEARNING, MASTERED }

/** The progress shown for a hadith with these [metrics]; null means it was never taken into study. */
fun hadithProgress(metrics: StabilityMetrics?): HadithProgress = when {
    metrics == null -> HadithProgress.NEW
    metrics.overallMastery >= GetMasteryAnalyticsUseCase.MASTERED_AT -> HadithProgress.MASTERED
    else -> HadithProgress.LEARNING
}

/** A library hadith with the user's mastery of it; [metrics] is null for one that was never reviewed. */
data class LibraryHadith(
    val hadith: HadithEntity,
    val metrics: StabilityMetrics?,
    val isDue: Boolean,
) {
    val progress: HadithProgress get() = hadithProgress(metrics)
}

/**
 * A book or a section as the library lists it. [status] and [dueCount] count every hadith in it, the ones in its own
 * sections included, however deep, so a book shows how far the user has come through all of it.
 */
data class LibraryGroup(
    val group: CatalogGroupEntity,
    val status: LibraryStatus,
    val dueCount: Int,
    /** How many sections it has directly. */
    val sectionCount: Int,
)

/**
 * One page of the library: the group that is open, or the shelf of books when none is.
 *
 * Unless a search is shown, [groups] are the sections of the open group (the books, at the root) and [hadiths] the ones
 * filed directly under it. While a search is shown, [hadiths] are the ones in the open group and in its sections, at any
 * depth, whose text contains [searched], and [groups] is empty. [status] and [dueCount] do not depend on the search.
 */
data class LibraryPage(
    /** From the book down to the open group; empty at the root. */
    val trail: List<CatalogGroupEntity>,
    val groups: List<LibraryGroup>,
    val hadiths: List<LibraryHadith>,
    /** Every hadith in the open group and its sections (at the root, the whole library) split by progress. */
    val status: LibraryStatus,
    val dueCount: Int,
    /** The text [hadiths] were searched for; blank when they are the group's own. */
    val searched: String,
) {
    /** The open group, or null at the root. */
    val open: CatalogGroupEntity? get() = trail.lastOrNull()

    val isSearch: Boolean get() = searched.isNotBlank()
}

/**
 * The library one group at a time, each hadith and each group with the user's mastery attached. It stays current as the
 * catalog or the user's progress changes. A [groupId] that is null, or that names no group, is the root: the books.
 *
 * A non-blank `query` narrows the page to the hadiths of the open group and its sections whose text contains it, with or
 * without tashkeel.
 */
class GetLibraryPageUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(groupId: Long? = null, query: String = ""): Flow<LibraryPage> =
        hadiths.observeGroups().flatMapLatest { groups ->
            val tree = GroupTree(groups)
            val open = groupId?.let(tree::get)
            val listed: Flow<List<HadithEntity>> = when {
                query.isNotBlank() -> hadiths.searchInGroups(tree.subtree(open?.id), query)
                open != null -> hadiths.getHadithsInGroup(open.id)
                else -> flowOf(emptyList())
            }
            combine(listed, hadiths.observePlacements(), reviews.observeAllMastery()) { found, placements, mastery ->
                val at = now()
                val records = mastery.associateBy { it.hadithId }
                val metrics = records.mapValues { (_, record) -> scheduler.metrics(record) }
                fun progressOf(id: Long) = hadithProgress(metrics[id])
                fun isDue(id: Long) = records[id]?.let { it.nextReviewDue <= at } ?: false

                val filed = placements.groupBy({ it.groupId }, { it.hadithId })
                fun idsUnder(id: Long?): List<Long> = tree.subtree(id).flatMap { filed[it].orEmpty() }
                fun statusOf(ids: List<Long>) = LibraryStatus(
                    total = ids.size,
                    unseen = ids.count { progressOf(it) == HadithProgress.NEW },
                    learning = ids.count { progressOf(it) == HadithProgress.LEARNING },
                    mastered = ids.count { progressOf(it) == HadithProgress.MASTERED },
                )

                val here = idsUnder(open?.id)
                LibraryPage(
                    trail = tree.trail(open?.id),
                    groups = if (query.isNotBlank()) {
                        emptyList()
                    } else {
                        tree.sections(open?.id).map { section ->
                            val ids = idsUnder(section.id)
                            LibraryGroup(section, statusOf(ids), ids.count(::isDue), tree.sections(section.id).size)
                        }
                    },
                    hadiths = found.map { LibraryHadith(it, metrics[it.id], isDue(it.id)) },
                    status = statusOf(here),
                    dueCount = here.count(::isDue),
                    searched = query,
                )
            }
        }

    /** The groups as the tree they form, read in memory: there are books and chapters, never thousands. */
    private class GroupTree(groups: List<CatalogGroupEntity>) {
        private val byId = groups.associateBy { it.id }
        private val children = groups.sortedWith(compareBy({ it.position }, { it.id })).groupBy { it.parentId }

        fun get(id: Long): CatalogGroupEntity? = byId[id]

        /** The groups directly under [id], or the books for null, in order. */
        fun sections(id: Long?): List<CatalogGroupEntity> = children[id].orEmpty()

        /** [id] and every group below it; the whole catalog for null. A group that names itself as a parent is not walked twice. */
        fun subtree(id: Long?): List<Long> {
            val seen = linkedSetOf<Long>()
            fun walk(group: Long) {
                if (seen.add(group)) sections(group).forEach { walk(it.id) }
            }
            if (id == null) sections(null).forEach { walk(it.id) } else walk(id)
            return seen.toList()
        }

        /** From the book down to [id]; empty for null. */
        fun trail(id: Long?): List<CatalogGroupEntity> {
            val path = ArrayDeque<CatalogGroupEntity>()
            val seen = mutableSetOf<Long>()
            var at = id?.let(::get)
            while (at != null && seen.add(at.id)) {
                path.addFirst(at)
                at = at.parentId?.let(::get)
            }
            return path.toList()
        }
    }
}

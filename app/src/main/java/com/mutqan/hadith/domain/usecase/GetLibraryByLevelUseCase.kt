package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.domain.model.HadithLevel
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** How far a hadith has come for the user. */
enum class HadithProgress { NEW, LEARNING, MASTERED }

/** A library hadith with the user's mastery of it; [metrics] is null for one that was never reviewed. */
data class LibraryHadith(
    val hadith: HadithEntity,
    val metrics: StabilityMetrics?,
    val isDue: Boolean,
) {
    val progress: HadithProgress
        get() = when {
            metrics == null -> HadithProgress.NEW
            metrics.overallMastery >= GetMasteryAnalyticsUseCase.MASTERED_AT -> HadithProgress.MASTERED
            else -> HadithProgress.LEARNING
        }
}

/**
 * One level of the curriculum. [hadiths] are the ones to show, in curriculum order: all of the level, or
 * those a search matched. [totalInLevel] is the level's full size, whatever the search matched.
 */
data class LibraryLevel(
    val level: HadithLevel,
    val hadiths: List<LibraryHadith>,
    val totalInLevel: Int,
) {
    /** The shown [hadiths] split by progress. */
    val status: LibraryStatus
        get() = LibraryStatus(
            total = hadiths.size,
            unseen = hadiths.count { it.progress == HadithProgress.NEW },
            learning = hadiths.count { it.progress == HadithProgress.LEARNING },
            mastered = hadiths.count { it.progress == HadithProgress.MASTERED },
        )

    val dueCount: Int get() = hadiths.count { it.isDue }
}

/**
 * The library by curriculum level, each hadith with the user's mastery of it attached. Every level is
 * listed, in order, even when it holds nothing yet, so a screen can show all three stages. It stays
 * current as the library or the user's progress changes.
 *
 * A non-blank [query] narrows each level to the hadiths whose text contains it, with or without tashkeel.
 */
class GetLibraryByLevelUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Found(val level: HadithLevel, val hadiths: List<HadithEntity>, val total: Int)

    operator fun invoke(query: String = ""): Flow<List<LibraryLevel>> {
        val perLevel = HadithLevel.entries.map { level ->
            val matching = if (query.isBlank()) hadiths.getHadithsByLevel(level.value) else hadiths.searchInLevel(level.value, query)
            combine(matching, hadiths.countHadithsByLevel(level.value)) { found, total -> Found(level, found, total) }
        }
        return combine(combine(perLevel) { it.toList() }, reviews.observeAllMastery()) { levels, mastery ->
            val at = now()
            val masteryById = mastery.associateBy { it.hadithId }
            levels.map { found ->
                LibraryLevel(
                    level = found.level,
                    hadiths = found.hadiths.map { hadith ->
                        val record = masteryById[hadith.id]
                        LibraryHadith(
                            hadith = hadith,
                            metrics = record?.let(scheduler::metrics),
                            isDue = record != null && record.nextReviewDue <= at,
                        )
                    },
                    totalInLevel = found.total,
                )
            }
        }
    }
}

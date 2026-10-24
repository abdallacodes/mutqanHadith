package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.domain.analytics.StudyStreak
import com.mutqan.hadith.domain.analytics.StudyStreakCalculator
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.NarratorRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import java.time.ZoneId

enum class MemoryComponent { MATN, SANAD }

/**
 * How well one component is held across the Hadiths whose component has been studied at all.
 * @property retention mean probability of recalling it right now, 0..1.
 * @property averageStabilityDays mean stability: how many days recall takes to fall to 90%.
 */
data class ComponentRetention(val retention: Float, val averageStabilityDays: Float)

/** Matn against Sanad; a side is null until at least one Hadith has had that component reviewed. */
data class DualRetention(val matn: ComponentRetention?, val sanad: ComponentRetention?) {
    /** The clearly weaker side, or null when they are level or one is unknown. */
    val weaker: MemoryComponent?
        get() {
            val m = matn?.retention ?: return null
            val s = sanad?.retention ?: return null
            return when {
                m - s >= GAP -> MemoryComponent.SANAD
                s - m >= GAP -> MemoryComponent.MATN
                else -> null
            }
        }

    private companion object {
        /** Retention points apart before one side is called weaker. */
        const val GAP = 0.1f
    }
}

/** The library split by how far each Hadith has come; the three parts add up to [total]. */
data class LibraryStatus(val total: Int, val unseen: Int, val learning: Int, val mastered: Int)

data class WeakNarrator(
    val narratorId: Long,
    val name: String,
    val tier: String,
    val isPivot: Boolean,
    val errorCount: Int,
    val attempts: Int,
) {
    val errorRate: Float get() = if (attempts == 0) 0f else errorCount.toFloat() / attempts
}

data class MasteryAnalytics(
    val streak: StudyStreak,
    val retention: DualRetention,
    val status: LibraryStatus,
    /** Narrators the user misses most, worst first; empty until some narrator has been missed repeatedly enough to tell. */
    val weakestNarrators: List<WeakNarrator>,
) {
    /** False for a fresh install or after a reset, when every figure is still empty. */
    val hasActivity: Boolean get() = status.unseen < status.total || streak.studyDays > 0
}

class GetMasteryAnalyticsUseCase(
    private val hadiths: HadithRepository,
    private val narrators: NarratorRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend operator fun invoke(): MasteryAnalytics {
        val at = now()
        val libraryIds = hadiths.getAll().mapTo(mutableSetOf()) { it.hadith.id }
        val mastery = reviews.getAllMastery().filter { it.hadithId in libraryIds }

        val metrics = mastery.map { it to scheduler.metrics(it) }
        val mastered = metrics.count { (_, m) -> m.overallMastery >= MASTERED_AT }
        val status = LibraryStatus(
            total = libraryIds.size,
            unseen = libraryIds.size - mastery.size,
            learning = mastery.size - mastered,
            mastered = mastered,
        )

        val recall = mastery.map { it to scheduler.retrievability(it, at) }
        val retention = DualRetention(
            matn = componentRetention(
                recall.filter { (r, _) -> r.matnLastReviewedAt != null },
                { (_, p) -> p.matn }, { (r, _) -> r.matnStability },
            ),
            sanad = componentRetention(
                recall.filter { (r, _) -> r.sanadLastReviewedAt != null },
                { (_, p) -> p.sanad }, { (r, _) -> r.sanadStability },
            ),
        )

        return MasteryAnalytics(
            streak = StudyStreakCalculator.compute(reviews.getReviewTimestamps(), at, zone()),
            retention = retention,
            status = status,
            weakestNarrators = weakestNarrators(),
        )
    }

    private fun <T> componentRetention(studied: List<T>, retrievability: (T) -> Float, stability: (T) -> Float): ComponentRetention? =
        if (studied.isEmpty()) {
            null
        } else {
            ComponentRetention(
                retention = studied.map(retrievability).average().toFloat(),
                averageStabilityDays = studied.map(stability).average().toFloat(),
            )
        }

    private suspend fun weakestNarrators(): List<WeakNarrator> {
        val byId = narrators.getAll().associateBy { it.id }
        return reviews.getNarratorOutcomeTotals()
            .mapNotNull { total ->
                val narrator = byId[total.narratorId] ?: return@mapNotNull null
                WeakNarrator(
                    narratorId = narrator.id,
                    name = narrator.primaryName,
                    tier = narrator.tier,
                    isPivot = narrator.isPivot,
                    errorCount = total.errorCount,
                    attempts = total.errorCount + total.successCount,
                )
            }
            .filter { it.errorCount > 0 && it.attempts >= MIN_ATTEMPTS }
            .sortedWith(
                compareByDescending<WeakNarrator> { it.errorRate }
                    .thenByDescending { it.errorCount }
                    .thenByDescending { it.attempts }
                    .thenBy { it.narratorId },
            )
            .take(LEADERBOARD_SIZE)
    }

    companion object {
        /** Overall mastery from which a Hadith counts as mastered: recall holds for weeks on both Matn and Sanad. */
        const val MASTERED_AT = 0.7f

        /** A narrator tested fewer times than this says nothing yet: one miss in one try is not a pattern. */
        const val MIN_ATTEMPTS = 2

        const val LEADERBOARD_SIZE = 5
    }
}

package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.Retrievability
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler

data class DueHadith(
    val hadith: HadithWithFullSanad,
    val mastery: MasteryRecordEntity,
    val metrics: StabilityMetrics,
    val retrievability: Retrievability,
    val overdueMillis: Long,
)

class GetDueHadithsUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Hadiths with `nextReviewDue <= now`, most urgent first: lowest current recall of the weaker
     * component, then lowest stability, then the longest overdue.
     */
    suspend operator fun invoke(limit: Int? = null): List<DueHadith> {
        val at = now()
        val due = reviews.getDueMastery(at)
        val byId = hadiths.getWithSanad(due.map { it.hadithId }).associateBy { it.hadith.id }
        val ranked = due.mapNotNull { record ->
            val hadith = byId[record.hadithId] ?: return@mapNotNull null
            DueHadith(
                hadith = hadith,
                mastery = record,
                metrics = scheduler.metrics(record),
                retrievability = scheduler.retrievability(record, at),
                overdueMillis = at - record.nextReviewDue,
            )
        }.sortedWith(
            compareBy<DueHadith> { it.retrievability.weakest }
                .thenBy { it.metrics.overallMastery }
                .thenByDescending { it.overdueMillis },
        )
        return if (limit == null) ranked else ranked.take(limit)
    }
}

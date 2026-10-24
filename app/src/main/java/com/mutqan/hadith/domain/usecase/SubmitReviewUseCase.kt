package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.model.ReviewTarget
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.ScheduleRequest
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import kotlin.time.Duration

data class ReviewOutcome(
    val record: MasteryRecordEntity,
    val metrics: StabilityMetrics,
    val matnInterval: Duration,
    val sanadInterval: Duration,
) {
    val nextReviewDue: Long get() = record.nextReviewDue
}

class SubmitReviewUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * @param failedNarratorIds narrators the user dropped or misplaced; they drive the weakest-link
     * penalty and the chain heatmap. Ignored unless [target] includes the Sanad.
     * @throws NoSuchElementException if the Hadith does not exist.
     * @throws IllegalArgumentException if a failed narrator is not part of the Hadith's chain.
     */
    suspend operator fun invoke(
        hadithId: Long,
        matnRating: ReviewRating,
        sanadRating: ReviewRating,
        failedNarratorIds: Set<Long> = emptySet(),
        target: ReviewTarget = ReviewTarget.DUAL,
    ): ReviewOutcome {
        val hadith = hadiths.getWithSanad(hadithId)
            ?: throw NoSuchElementException("No Hadith with id $hadithId")
        val chainIds = hadith.edges.map { it.edge.narratorId }.toSet()
        val failed = if (target.includesSanad) failedNarratorIds else emptySet()
        require(chainIds.containsAll(failed)) {
            "Narrators ${failed - chainIds} are not in the chain of Hadith $hadithId"
        }

        val reviewedAt = now()
        val result = scheduler.schedule(
            ScheduleRequest(
                hadithId = hadithId,
                previous = reviews.getMastery(hadithId),
                matnRating = matnRating,
                sanadRating = sanadRating,
                now = reviewedAt,
                target = target,
                brokenLinks = failed.size,
                chainLength = chainIds.size,
            ),
        )

        // Per-narrator outcomes are only known when the user named the failures or recalled the
        // whole chain (GOOD/EASY). A bare HARD/AGAIN says nothing about which link broke.
        val attributable = target.includesSanad && (failed.isNotEmpty() || sanadRating >= ReviewRating.GOOD)
        val events = if (attributable) {
            chainIds.map { NarratorReviewEventEntity(hadithId = hadithId, narratorId = it, reviewedAt = reviewedAt, succeeded = it !in failed) }
        } else {
            emptyList()
        }
        reviews.saveReview(result.record, events)

        return ReviewOutcome(result.record, scheduler.metrics(result.record), result.matnInterval, result.sanadInterval)
    }
}

package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.ReviewRating
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
    /** Where the hadith stood before this review, and where it stands now. */
    val layerBefore: LayerStatus = LayerStatus.Fresh,
    val layerAfter: LayerStatus = LayerStatus.Fresh,
) {
    val nextReviewDue: Long get() = record.nextReviewDue

    /** The layer this review opened, or null if it did not move the hadith up. */
    val unlockedLayer: MemorizationLayer? get() = layerAfter.layer.takeIf { it > layerBefore.layer }
}

class SubmitReviewUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Records a review of the Matn ([matnRating]), the Sanad ([sanadRating]) or both; a null rating
     * means that component was not tested and its state and clock are left untouched.
     *
     * @param failedNarratorIds narrators the user dropped or misplaced; they drive the weakest-link
     * penalty and the chain heatmap. Ignored when [sanadRating] is null.
     * @throws NoSuchElementException if the Hadith does not exist.
     * @throws IllegalArgumentException if both ratings are null, or a failed narrator is not part
     * of the Hadith's chain.
     */
    suspend operator fun invoke(
        hadithId: Long,
        matnRating: ReviewRating?,
        sanadRating: ReviewRating?,
        failedNarratorIds: Set<Long> = emptySet(),
    ): ReviewOutcome {
        require(matnRating != null || sanadRating != null) { "A review must test the Matn, the Sanad, or both" }
        val hadith = hadiths.getWithSanad(hadithId)
            ?: throw NoSuchElementException("No Hadith with id $hadithId")
        val chainIds = hadith.edges.map { it.edge.narratorId }.toSet()
        val failed = if (sanadRating != null) failedNarratorIds else emptySet()
        require(chainIds.containsAll(failed)) {
            "Narrators ${failed - chainIds} are not in the chain of Hadith $hadithId"
        }

        val reviewedAt = now()
        val previous = reviews.getMastery(hadithId)
        val result = scheduler.schedule(
            ScheduleRequest(
                hadithId = hadithId,
                previous = previous,
                matnRating = matnRating,
                sanadRating = sanadRating,
                now = reviewedAt,
                brokenLinks = failed.size,
                chainLength = chainIds.size,
            ),
        )

        // Per-narrator outcomes are only known when the user named the failures or recalled the
        // whole chain (GOOD/EASY). A bare HARD/AGAIN says nothing about which link broke.
        val attributable = sanadRating != null && (failed.isNotEmpty() || sanadRating >= ReviewRating.GOOD)
        val events = if (attributable) {
            chainIds.map { NarratorReviewEventEntity(hadithId = hadithId, narratorId = it, reviewedAt = reviewedAt, succeeded = it !in failed) }
        } else {
            emptyList()
        }
        reviews.saveReview(result.record, events)

        return ReviewOutcome(
            record = result.record,
            metrics = scheduler.metrics(result.record),
            matnInterval = result.matnInterval,
            sanadInterval = result.sanadInterval,
            layerBefore = scheduler.layerStatus(previous),
            layerAfter = scheduler.layerStatus(result.record),
        )
    }
}

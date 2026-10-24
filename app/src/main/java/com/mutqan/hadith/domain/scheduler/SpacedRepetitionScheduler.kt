package com.mutqan.hadith.domain.scheduler

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.model.Retrievability
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.model.ReviewTarget
import com.mutqan.hadith.domain.model.StabilityMetrics
import kotlin.time.Duration

/**
 * @property previous the stored state, or null for a Hadith that was never reviewed.
 * @property matnRating null when the Matn was not tested in this review.
 * @property sanadRating null when the Sanad was not tested in this review.
 * @property brokenLinks narrators in the chain that were dropped or misplaced during this review.
 * @property chainLength number of narrators in the chain; scales the weakest-link penalty.
 */
data class ScheduleRequest(
    val hadithId: Long,
    val previous: MasteryRecordEntity?,
    val matnRating: ReviewRating?,
    val sanadRating: ReviewRating?,
    val now: Long,
    val brokenLinks: Int = 0,
    val chainLength: Int = 0,
) {
    init {
        require(matnRating != null || sanadRating != null) { "A review must test the Matn, the Sanad, or both" }
    }

    val target: ReviewTarget
        get() = when {
            matnRating != null && sanadRating != null -> ReviewTarget.DUAL
            matnRating != null -> ReviewTarget.MATN
            else -> ReviewTarget.SANAD
        }
}

/** [record] is the new state to persist; its `nextReviewDue` is the earlier of the two component dues. */
data class ScheduleResult(
    val record: MasteryRecordEntity,
    val matnInterval: Duration,
    val sanadInterval: Duration,
)

interface SpacedRepetitionScheduler {
    fun schedule(request: ScheduleRequest): ScheduleResult

    /** Recall probability of each component at [now], from the stored state. */
    fun retrievability(record: MasteryRecordEntity, now: Long): Retrievability

    fun metrics(record: MasteryRecordEntity): StabilityMetrics
}

package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.domain.model.Retrievability
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * One hadith with everything the study screen shows besides its text: how far the user has come with it.
 * [metrics], [retention] and [nextReviewDue] are null for a hadith that was never taken into study.
 */
data class HadithStudy(
    val hadith: HadithWithFullSanad,
    val metrics: StabilityMetrics?,
    /** The chance of recalling each component right now. */
    val retention: Retrievability?,
    val nextReviewDue: Long?,
    val isDue: Boolean,
    /** The memorization layer the hadith is in; null for one that was never taken into study. */
    val layer: LayerStatus? = null,
) {
    val isInStudy: Boolean get() = metrics != null

    val progress: HadithProgress get() = hadithProgress(metrics)

    /** How well the Matn is held, in words; null for a hadith whose Matn was never learned. */
    val band: RecallBand? get() = metrics?.let { RecallBand.of(it.matnStability) }
}

/**
 * Follows one hadith and the user's progress with it, so the study screen is current after a review.
 * Emits null while the hadith does not exist.
 */
class GetHadithStudyUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    operator fun invoke(hadithId: Long): Flow<HadithStudy?> =
        combine(reviews.observeAllMastery(), scheduler.layerRules) { records, _ ->
            val hadith = hadiths.getWithSanad(hadithId) ?: return@combine null
            val at = now()
            val record = records.firstOrNull { it.hadithId == hadithId }
            HadithStudy(
                hadith = hadith,
                metrics = record?.let(scheduler::metrics),
                retention = record?.let { scheduler.retrievability(it, at) },
                nextReviewDue = record?.nextReviewDue,
                isDue = record != null && record.nextReviewDue <= at,
                layer = record?.let(scheduler::layerStatus),
            )
        }
}

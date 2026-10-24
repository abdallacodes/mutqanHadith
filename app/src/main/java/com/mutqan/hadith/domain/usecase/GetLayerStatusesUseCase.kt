package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler

/** The memorization layer each of the given hadiths is in; a hadith with no record is [LayerStatus.Fresh]. */
class GetLayerStatusesUseCase(
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
) {
    suspend operator fun invoke(hadithIds: Collection<Long>): Map<Long, LayerStatus> =
        hadithIds.associateWith { scheduler.layerStatus(reviews.getMastery(it)) }
}

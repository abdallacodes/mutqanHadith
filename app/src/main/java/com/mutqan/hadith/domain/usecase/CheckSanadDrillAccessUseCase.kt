package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.drill.DrillAccess
import com.mutqan.hadith.domain.drill.SanadDrill
import com.mutqan.hadith.domain.drill.SanadDrillPolicy
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler

/**
 * Whether [SanadDrill] can be played on a hadith right now. The layer is read from the hadith's record each time, so
 * an advanced student who opens every layer in the settings finds the drills open at once, and one who closes them
 * again finds them shut.
 */
class CheckSanadDrillAccessUseCase(
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
) {
    suspend operator fun invoke(drill: SanadDrill, hadith: HadithWithFullSanad): DrillAccess =
        SanadDrillPolicy.access(drill, scheduler.layerStatus(reviews.getMastery(hadith.hadith.id)), hadith.sanad)
}

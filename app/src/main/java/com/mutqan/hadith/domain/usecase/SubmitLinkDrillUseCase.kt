package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.ScheduleRequest
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler

/** The tested narrator's running tally after the drill answer was recorded. */
data class LinkDrillOutcome(
    val narratorId: Long,
    val correct: Boolean,
    val errorCount: Int,
    val successCount: Int,
) {
    /** Share of this link's recall attempts that succeeded: the link's stability in the ledger. */
    val retentionRate: Float
        get() = (errorCount + successCount).let { if (it == 0) 1f else successCount.toFloat() / it }
}

/**
 * Records the answer to a single-link ("missing narrator") question.
 *
 * Only one narrator was tested, so a correct answer proves nothing about the rest of the chain:
 * it is logged in the narrator ledger but leaves the Hadith's schedule alone. A wrong answer is a
 * real dropped link, so it also hits the Sanad with the weakest-link penalty, provided the Hadith
 * has already been studied (a drill must not make an unseen Hadith look "seen").
 */
class SubmitLinkDrillUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * @throws NoSuchElementException if the Hadith does not exist.
     * @throws IllegalArgumentException if [narratorId] is not in the Hadith's chain.
     */
    suspend operator fun invoke(hadithId: Long, narratorId: Long, correct: Boolean): LinkDrillOutcome {
        val hadith = hadiths.getWithSanad(hadithId) ?: throw NoSuchElementException("No Hadith with id $hadithId")
        val chainIds = hadith.edges.map { it.edge.narratorId }
        require(narratorId in chainIds) { "Narrator $narratorId is not in the chain of Hadith $hadithId" }

        val at = now()
        val event = NarratorReviewEventEntity(hadithId = hadithId, narratorId = narratorId, reviewedAt = at, succeeded = correct)
        val previous = reviews.getMastery(hadithId)
        if (!correct && previous != null) {
            val result = scheduler.schedule(
                ScheduleRequest(
                    hadithId = hadithId,
                    previous = previous,
                    matnRating = null,
                    sanadRating = ReviewRating.HARD,
                    now = at,
                    brokenLinks = 1,
                    chainLength = chainIds.size,
                ),
            )
            reviews.saveReview(result.record, listOf(event))
        } else {
            reviews.logNarratorEvents(listOf(event))
        }

        val tally = reviews.getNarratorOutcomeCounts(hadithId).firstOrNull { it.narratorId == narratorId }
        return LinkDrillOutcome(narratorId, correct, tally?.errorCount ?: 0, tally?.successCount ?: 0)
    }
}

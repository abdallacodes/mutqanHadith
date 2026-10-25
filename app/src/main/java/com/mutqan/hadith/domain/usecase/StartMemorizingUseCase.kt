package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository

/**
 * Takes a hadith into the user's memorization: gives it the mastery record that marks it as "in study".
 *
 * The record starts empty (zero stability, neither component reviewed) and due right away, which is exactly
 * what the scheduler treats as "never learned": the first rating of each component sets its first stability.
 * A hadith that already has a record is left as it is, so asking twice, or asking for one already being
 * reviewed, never resets the user's progress. Only a hadith that is really taken into study counts towards
 * the day's [pacing].
 */
class StartMemorizingUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val pacing: DailyPacing? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * @return the hadith's mastery record, the existing one or the one just created.
     * @throws NoSuchElementException if the hadith does not exist.
     */
    suspend operator fun invoke(hadithId: Long): MasteryRecordEntity {
        reviews.getMastery(hadithId)?.let { return it }
        hadiths.getWithSanad(hadithId) ?: throw NoSuchElementException("No Hadith with id $hadithId")
        val record = MasteryRecordEntity(
            hadithId = hadithId,
            matnStability = 0f,
            sanadStability = 0f,
            matnLastReviewedAt = null,
            sanadLastReviewedAt = null,
            nextReviewDue = now(),
            repetitionCount = 0,
        )
        reviews.saveReview(record, narratorEvents = emptyList())
        pacing?.recordStart()
        return record
    }
}

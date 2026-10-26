package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.domain.model.StartingPoint
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import com.mutqan.hadith.domain.settings.StudyPreferences

/**
 * Lets the learner say where they already stand, so the app starts from there instead of from zero.
 *
 * Placing a hadith writes the same mastery record a review would have left, at the stability of its [RecallBand]; no
 * new kind of data. It is not "taking a hadith into study", so it does not count against the day's pacing. It never
 * touches a hadith that already has a record, so progress earned by reviewing is safe whatever is chosen here, and
 * choosing again later only fills what is still unmarked.
 */
class PlacementUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val preferences: StudyPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Remembers the learner's answer; for a beginner nothing else happens. */
    fun choose(point: StartingPoint) = preferences.setStartingPoint(point)

    /**
     * Marks one hadith as held at [band], unless it already has a record.
     * @return whether it was marked.
     */
    suspend fun place(hadithId: Long, band: RecallBand): Boolean {
        if (reviews.getMastery(hadithId) != null) return false
        hadiths.getWithSanad(hadithId) ?: throw NoSuchElementException("No Hadith with id $hadithId")
        reviews.saveReview(scheduler.place(hadithId, band, now()), narratorEvents = emptyList())
        return true
    }

    /**
     * One answer of the whole-book test: unlike [place] it overwrites, so the test can lower a hadith's status as well as
     * raise it. The Matn is set to [band] (null, "not memorized", counts as [RecallBand.WEAK] for a hadith that has a
     * record and leaves one without a record unmarked); the Sanad's own state is kept.
     */
    suspend fun grade(hadithId: Long, band: RecallBand?) {
        val previous = reviews.getMastery(hadithId)
        val graded = band ?: if (previous != null) RecallBand.WEAK else return
        hadiths.getWithSanad(hadithId) ?: throw NoSuchElementException("No Hadith with id $hadithId")
        reviews.saveReview(scheduler.place(hadithId, graded, now(), previous), narratorEvents = emptyList())
    }

    /** Marks every hadith still without a record as held at [band]; returns how many were marked. */
    suspend fun placeRemaining(band: RecallBand): Int =
        hadiths.getUnseen().count { place(it.hadith.id, band) }

    /** What skipping the self-check does: the starting point's own default band for every unmarked hadith. */
    suspend fun placeRemainingByDefault(point: StartingPoint): Int =
        point.defaultBand?.let { placeRemaining(it) } ?: 0
}

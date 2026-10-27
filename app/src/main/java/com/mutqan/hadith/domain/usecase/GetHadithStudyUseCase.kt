package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.narrator.bioParagraphs
import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.domain.model.Retrievability
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.NarratorRepository
import com.mutqan.hadith.domain.repository.NoNarrators
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What an admin wrote about the Companion of a hadith: [paragraphs] are in reading order, none of them blank. */
data class CompanionBio(val name: String, val paragraphs: List<String>)

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
    /** About the Companion who narrates it; null when nobody has written about him. */
    val companionBio: CompanionBio? = null,
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
    private val narrators: NarratorRepository = NoNarrators(),
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
                companionBio = companionBioOf(hadith, narrators.getAll()),
            )
        }

    /**
     * The Companion is the last link of the chain. What was written about him is read from any row that is the same
     * person (an admin can say two rows are one), the Companion's own row first.
     */
    private fun companionBioOf(hadith: HadithWithFullSanad, all: List<NarratorEntity>): CompanionBio? {
        val companion = hadith.sanad.maxByOrNull { it.orderIndex }?.narrator ?: return null
        val person = NarratorDirectory.of(listOf(companion) + all).canonicalOf(companion.id) ?: return null
        val written = (listOf(companion) + all).firstOrNull { it.id in person.memberIds && it.bio.isNotBlank() }?.bio ?: return null
        val paragraphs = bioParagraphs(written)
        return paragraphs.takeIf { it.isNotEmpty() }?.let { CompanionBio(person.name, it) }
    }

}

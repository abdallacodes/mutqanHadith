package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.domain.model.CurriculumOrder
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** [metrics] is null for a Hadith that was never reviewed (a "new" Hadith). */
data class DashboardItem(
    val hadithId: Long,
    val hadithNumber: Int,
    val sourceBook: String,
    val sahabiName: String,
    val metrics: StabilityMetrics?,
    val isDue: Boolean,
) {
    val isNew: Boolean get() = metrics == null
}

data class StudyDashboard(
    val dueCount: Int,
    val newCount: Int,
    /** Mean overall mastery over the whole library, counting unseen Hadiths as zero. */
    val overallMastery: Float,
    /** Due Hadiths first, then curriculum order. */
    val items: List<DashboardItem>,
    /** The studied Hadith whose Sanad is shakiest, for the Sanad drills; any Hadith if none is studied. */
    val drillSuggestionId: Long?,
)

class ObserveStudyDashboardUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    operator fun invoke(): Flow<StudyDashboard> =
        combine(hadiths.observeAll(), reviews.observeAllMastery()) { library, mastery ->
            val at = now()
            val masteryById = mastery.associateBy { it.hadithId }
            val items = library.sortedWith(compareBy(CurriculumOrder) { it.hadith }).map { h ->
                val record = masteryById[h.hadith.id]
                DashboardItem(
                    hadithId = h.hadith.id,
                    hadithNumber = h.hadith.hadithNumber,
                    sourceBook = h.hadith.sourceBook,
                    sahabiName = h.sanad.lastOrNull()?.narrator?.primaryName.orEmpty(),
                    metrics = record?.let(scheduler::metrics),
                    isDue = record != null && record.nextReviewDue <= at,
                )
            }.sortedBy { !it.isDue }

            StudyDashboard(
                dueCount = items.count { it.isDue },
                newCount = items.count { it.isNew },
                overallMastery = if (items.isEmpty()) 0f else items.map { it.metrics?.overallMastery ?: 0f }.average().toFloat(),
                items = items,
                drillSuggestionId = (items.filter { !it.isNew }.minByOrNull { it.metrics!!.sanadMastery } ?: items.firstOrNull())?.hadithId,
            )
        }
}

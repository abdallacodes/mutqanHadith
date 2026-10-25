package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.analytics.StudyStreak
import com.mutqan.hadith.domain.analytics.StudyStreakCalculator
import com.mutqan.hadith.domain.model.CurriculumOrder
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import java.time.ZoneId
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
    /** The memorization layer; [LayerStatus.Fresh] for a hadith not yet taken into study. */
    val layer: LayerStatus = LayerStatus.Fresh,
) {
    val isNew: Boolean get() = metrics == null
}

/** The first Hadith still unseen in curriculum order: where the memorization carries on. */
data class NextToLearn(
    val hadithId: Long,
    val level: Int,
    val hadithNumber: Int,
    val sourceBook: String,
    val sahabiName: String,
    /** The whole matn, with tashkeel; the screen decides how much of it to show. */
    val matn: String,
)

data class StudyDashboard(
    val dueCount: Int,
    val newCount: Int,
    /** Mean overall mastery over the whole library, counting unseen Hadiths as zero. */
    val overallMastery: Float,
    /** Due Hadiths first, then curriculum order. */
    val items: List<DashboardItem>,
    /** The Hadith whose Sanad is shakiest among those whose Sanad tree is open, for the drills; null while none is. */
    val drillSuggestionId: Long?,
    /** Studied Hadiths not yet mastered; see [GetMasteryAnalyticsUseCase.MASTERED_AT]. */
    val learningCount: Int = 0,
    val masteredCount: Int = 0,
    /** Mean probability of recalling the studied Hadiths right now, 0..1; null until something has been studied. */
    val retention: Float? = null,
    val streak: StudyStreak = StudyStreak.None,
    /** Null once every Hadith in the library has been started. */
    val nextToLearn: NextToLearn? = null,
) {
    val studiedCount: Int get() = learningCount + masteredCount
}

class ObserveStudyDashboardUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
    private val scheduler: SpacedRepetitionScheduler,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
) {
    operator fun invoke(): Flow<StudyDashboard> =
        combine(hadiths.observeAll(), reviews.observeAllMastery(), scheduler.layerRules) { library, mastery, _ ->
            val at = now()
            val masteryById = mastery.associateBy { it.hadithId }
            val inCurriculumOrder = library.sortedWith(compareBy(CurriculumOrder) { it.hadith })
            val items = inCurriculumOrder.map { h ->
                val record = masteryById[h.hadith.id]
                DashboardItem(
                    hadithId = h.hadith.id,
                    hadithNumber = h.hadith.hadithNumber,
                    sourceBook = h.hadith.sourceBook,
                    sahabiName = h.sanad.lastOrNull()?.narrator?.primaryName.orEmpty(),
                    metrics = record?.let(scheduler::metrics),
                    isDue = record != null && record.nextReviewDue <= at,
                    layer = scheduler.layerStatus(record),
                )
            }.sortedBy { !it.isDue }

            val studied = items.filter { !it.isNew }
            val mastered = studied.count { it.metrics!!.overallMastery >= GetMasteryAnalyticsUseCase.MASTERED_AT }
            val next = inCurriculumOrder.firstOrNull { it.hadith.id !in masteryById }
            val recall = inCurriculumOrder.mapNotNull { h -> masteryById[h.hadith.id]?.let { recallNow(it, at) } }

            StudyDashboard(
                dueCount = items.count { it.isDue },
                newCount = items.count { it.isNew },
                overallMastery = if (items.isEmpty()) 0f else items.map { it.metrics?.overallMastery ?: 0f }.average().toFloat(),
                items = items,
                drillSuggestionId = studied.filter { it.layer.layer.opensSanad }.minByOrNull { it.metrics!!.sanadMastery }?.hadithId,
                learningCount = studied.size - mastered,
                masteredCount = mastered,
                retention = if (recall.isEmpty()) null else recall.average().toFloat(),
                streak = StudyStreakCalculator.compute(reviews.getReviewTimestamps(), at, zone()),
                nextToLearn = next?.let {
                    NextToLearn(
                        hadithId = it.hadith.id,
                        level = it.hadith.level,
                        hadithNumber = it.hadith.hadithNumber,
                        sourceBook = it.hadith.sourceBook,
                        sahabiName = it.sanad.lastOrNull()?.narrator?.primaryName.orEmpty(),
                        matn = it.hadith.matnWithTashkeel,
                    )
                },
            )
        }

    /** The mean recall of the components that have been reviewed; a Hadith with neither has no figure. */
    private fun recallNow(record: MasteryRecordEntity, at: Long): Float? {
        val recall = scheduler.retrievability(record, at)
        val parts = listOfNotNull(
            recall.matn.takeIf { record.matnLastReviewedAt != null },
            recall.sanad.takeIf { record.sanadLastReviewedAt != null },
        )
        return if (parts.isEmpty()) null else parts.average().toFloat()
    }
}

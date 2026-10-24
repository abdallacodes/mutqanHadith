package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.domain.model.NarratorChainPerformance
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository

/** One node of the chain. [heat] is the error frequency in 0..1 (0 = never missed or never tested). */
data class NarratorHeat(
    val orderIndex: Int,
    val performance: NarratorChainPerformance,
    val heat: Float,
    val isWeakestLink: Boolean,
)

/** [nodes] follow chain order, collector's teacher first. */
data class ChainHeatmap(
    val hadithId: Long,
    val nodes: List<NarratorHeat>,
) {
    val weakestLink: NarratorHeat? get() = nodes.firstOrNull { it.isWeakestLink }
}

class GetNarratorChainHeatmapUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
) {
    /** @throws NoSuchElementException if the Hadith does not exist. */
    suspend operator fun invoke(hadithId: Long): ChainHeatmap {
        val hadith = hadiths.getWithSanad(hadithId)
            ?: throw NoSuchElementException("No Hadith with id $hadithId")
        val counts = reviews.getNarratorOutcomeCounts(hadithId).associateBy { it.narratorId }

        val nodes = hadith.sanad.map { link ->
            val count = counts[link.narrator.id]
            val errors = count?.errorCount ?: 0
            val successes = count?.successCount ?: 0
            val attempts = errors + successes
            val retention = if (attempts == 0) 1f else successes.toFloat() / attempts
            NarratorHeat(
                orderIndex = link.orderIndex,
                performance = NarratorChainPerformance(
                    narratorId = link.narrator.id,
                    narratorName = link.narrator.primaryName,
                    errorCount = errors,
                    successCount = successes,
                    retentionRate = retention,
                ),
                heat = 1f - retention,
                isWeakestLink = false,
            )
        }

        // Highest error rate wins; more raw errors break ties, then the earlier link.
        val weakest = nodes
            .filter { it.performance.errorCount > 0 }
            .maxWithOrNull(
                compareBy<NarratorHeat> { it.heat }
                    .thenBy { it.performance.errorCount }
                    .thenByDescending { it.orderIndex },
            )
        return ChainHeatmap(hadithId, nodes.map { it.copy(isWeakestLink = it === weakest) })
    }
}

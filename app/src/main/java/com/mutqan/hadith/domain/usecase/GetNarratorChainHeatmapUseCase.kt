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
    val fullName: String,
    val tier: String,
    /** The term by which the previous link (the collector, for the first node) received from this narrator. */
    val transmissionTerm: String,
) {
    val attempts: Int get() = performance.errorCount + performance.successCount

    /** Colour class of the node; see [ChainHealth]. */
    val health: ChainHealth get() = ChainHealth.of(performance.errorCount, performance.successCount, isWeakestLink)
}

/** How safe a link is to rely on, from its recall record. */
enum class ChainHealth {
    /** Never tested, so there is no record to judge by. */
    UNTESTED,

    /** Recalled more than 85% of the time. */
    STRONG,

    /** Recalled 60% to 85% of the time. */
    FAIR,

    /** Recalled under 60% of the time, or the chain's weakest link. */
    FRAGILE;

    companion object {
        const val STRONG_ABOVE_PERCENT = 85
        const val FRAGILE_BELOW_PERCENT = 60

        /** Integer arithmetic keeps the 85% and 60% boundaries exact. */
        fun of(errors: Int, successes: Int, isWeakestLink: Boolean = false): ChainHealth {
            val attempts = errors + successes
            return when {
                isWeakestLink -> FRAGILE
                attempts == 0 -> UNTESTED
                successes * 100 < FRAGILE_BELOW_PERCENT * attempts -> FRAGILE
                successes * 100 > STRONG_ABOVE_PERCENT * attempts -> STRONG
                else -> FAIR
            }
        }
    }
}

/** [nodes] follow chain order, collector's teacher first. */
data class ChainHeatmap(
    val hadithId: Long,
    val nodes: List<NarratorHeat>,
    /** The collector who received the chain's first link, e.g. "البخاري". */
    val collector: String = "",
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
                fullName = link.narrator.fullName,
                tier = link.narrator.tier,
                transmissionTerm = link.transmissionTerm,
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
        return ChainHeatmap(hadithId, nodes.map { it.copy(isWeakestLink = it === weakest) }, hadith.hadith.collector)
    }
}

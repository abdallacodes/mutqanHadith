package com.mutqan.hadith.domain.model

/** Self-assessed recall quality, ordered from worst to best (`ordinal` follows [value]). */
enum class ReviewRating(val value: Int) {
    /** Complete blackout / total failure. */
    AGAIN(1),

    /** Recalled with hesitation or a partial error (a dropped link, mixed order). */
    HARD(2),

    /** Correct recall with normal effort. */
    GOOD(3),

    /** Instant, effortless recall. */
    EASY(4),
}

/** Which component(s) a review session tested. */
enum class ReviewTarget(val includesMatn: Boolean, val includesSanad: Boolean) {
    MATN(includesMatn = true, includesSanad = false),
    SANAD(includesMatn = false, includesSanad = true),
    DUAL(includesMatn = true, includesSanad = true),
}

/**
 * [matnStability] and [sanadStability] are the stored stabilities in days (0 = never learned).
 * [matnMastery] and [sanadMastery] normalize each to 0..1 (Sanad measured on its faster clock);
 * [overallMastery] is their geometric mean, so it collapses when either side is weak.
 */
data class StabilityMetrics(
    val matnStability: Float,
    val sanadStability: Float,
    val overallMastery: Float,
    val matnMastery: Float = 0f,
    val sanadMastery: Float = 0f,
) {
    /** The mastery that counts in [layer]: before the chain opens only the Matn is being learned. */
    fun masteryIn(layer: MemorizationLayer): Float = if (layer.opensSanad) overallMastery else matnMastery
}

/** Probability of recall right now, per component, in 0..1. */
data class Retrievability(val matn: Float, val sanad: Float) {
    /** The weaker component decides how urgent a review is. */
    val weakest: Float get() = minOf(matn, sanad)

    /** The weaker of the components being studied in [layer]: the chain does not count before its layer opens. */
    fun weakestIn(layer: MemorizationLayer): Float = if (layer.opensSanad) weakest else matn
}

/** Per-narrator tally of recall attempts within one chain. */
data class NarratorChainPerformance(
    val narratorId: Long,
    val narratorName: String,
    val errorCount: Int,
    val successCount: Int,
    /** `successCount / (errorCount + successCount)`; 1 when the narrator was never tested. */
    val retentionRate: Float,
)

/** Row shape of the aggregated narrator outcomes query. */
data class NarratorOutcomeCount(
    val narratorId: Long,
    val errorCount: Int,
    val successCount: Int,
)

/** One narrator's tally within one Hadith, across every review of that Hadith. */
data class HadithOutcomeCount(
    val hadithId: Long,
    val errorCount: Int,
    val successCount: Int,
)

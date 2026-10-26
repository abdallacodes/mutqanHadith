package com.mutqan.hadith.domain.model

/**
 * Where the learner says they start in the Forty Hadith. It only decides how the study begins; nothing in the app
 * is locked by it, and it can be changed at any time.
 */
enum class StartingPoint {
    /** Starts from nothing: no hadith is marked, the app behaves as it does for a new learner. */
    BEGINNER,

    /** Memorized them once and is rusty: unless told otherwise each hadith starts as [RecallBand.WEAK]. */
    INTERMEDIATE,

    /** Has them by heart: unless told otherwise each hadith starts as [RecallBand.GOOD], and grows from there. */
    EXPERT;

    /** What an unchecked hadith is marked as when the learner skips the self-check; null when nothing is marked. */
    val defaultBand: RecallBand?
        get() = when (this) {
            BEGINNER -> null
            INTERMEDIATE -> RecallBand.WEAK
            EXPERT -> RecallBand.GOOD
        }

    /** Whether the self-check is offered: a beginner has nothing to grade. */
    val offersCheck: Boolean get() = this != BEGINNER
}

/**
 * How well a memorized hadith is held, in four bands. A band is a way of speaking about the Matn's stability:
 * [placedStabilityDays] is the stability a self-graded hadith starts with, and [of] reads a band back from any
 * stability, whether it was placed or earned by reviews.
 */
enum class RecallBand(val placedStabilityDays: Float, private val fromStabilityDays: Float) {
    WEAK(1f, 0f),
    GOOD(4f, 2.5f),
    VERY_GOOD(12f, 8f),
    EXCELLENT(45f, 25f);

    companion object {
        /** The band of a Matn with [stabilityDays]; null for one that was never learned (no stability yet). */
        fun of(stabilityDays: Float): RecallBand? =
            if (stabilityDays <= 0f) null else entries.last { stabilityDays >= it.fromStabilityDays }
    }
}

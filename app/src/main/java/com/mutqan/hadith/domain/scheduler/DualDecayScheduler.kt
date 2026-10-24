package com.mutqan.hadith.domain.scheduler

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.model.Retrievability
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.model.ReviewTarget
import com.mutqan.hadith.domain.model.StabilityMetrics
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class SchedulerConfig(
    /** Sanad forgets this many times faster than Matn (target range 1.5..2.0). */
    val sanadDecayMultiplier: Double = 1.75,
    /** Recall probability a review is scheduled to land on. */
    val desiredRetention: Double = 0.9,
    /** Stability left after an AGAIN. */
    val lapseFactor: Double = 0.25,
    /** Fraction of Sanad stability kept when every link in the chain was dropped. */
    val brokenLinkMinFactor: Double = 0.3,
    /** Fraction of Sanad stability kept when a single link of a long chain was dropped. */
    val brokenLinkMaxFactor: Double = 0.6,
    val minStabilityDays: Double = 0.2,
    val maxStabilityDays: Double = 365.0,
    /** Stability at which [SpacedRepetitionScheduler.metrics] reports a component as fully mastered. */
    val masteryHorizonDays: Double = 90.0,
) {
    init {
        require(sanadDecayMultiplier >= 1.0) { "Sanad must not decay slower than Matn" }
        require(desiredRetention > 0.0 && desiredRetention < 1.0)
        require(brokenLinkMinFactor <= brokenLinkMaxFactor)
    }
}

/**
 * Forgetting follows the FSRS power curve `R(t) = (1 + F·t/S)^-0.5` with `F = 19/81`, so that
 * `R(S) = 0.9`. Matn and Sanad each carry their own stability `S` (days):
 *
 * - **Decay asymmetry:** for Sanad the clock runs [SchedulerConfig.sanadDecayMultiplier] times
 *   faster, so equal stabilities give a Sanad interval that many times shorter.
 * - **Weakest link:** one dropped narrator caps the Sanad rating at HARD and cuts its stability
 *   to 30..60% of what it was, however well the rest of the chain went.
 * - **Next due** is `min(matnDue, sanadDue)`.
 *
 * Single-target reviews ([com.mutqan.hadith.domain.model.ReviewTarget.MATN] / `SANAD`) update only
 * the tested component. Because a record has one `lastReviewedAt`, it is advanced only by DUAL
 * reviews, which keeps the untested component's elapsed time honest at the cost of slightly
 * overstating the tested component's elapsed time at its next review. The untested component's
 * due date is carried over from the previous record (or "now" if it was never learned).
 */
class DualDecayScheduler(
    private val config: SchedulerConfig = SchedulerConfig(),
) : SpacedRepetitionScheduler {

    override fun schedule(request: ScheduleRequest): ScheduleResult {
        val prev = request.previous
        val now = request.now
        val elapsedDays = if (prev == null) 0.0 else max(0L, now - prev.lastReviewedAt) / DAY_MS
        val prevMatn = prev?.matnStability?.toDouble() ?: 0.0
        val prevSanad = prev?.sanadStability?.toDouble() ?: 0.0

        val matnStability = if (request.target.includesMatn) {
            nextMatnStability(prevMatn, request.matnRating, elapsedDays)
        } else {
            prevMatn
        }
        val sanadStability = if (request.target.includesSanad) {
            nextSanadStability(prevSanad, request.sanadRating, elapsedDays, request.brokenLinks, request.chainLength)
        } else {
            prevSanad
        }

        val matnInterval = intervalFor(matnStability, decay = 1.0)
        val sanadInterval = intervalFor(sanadStability, decay = config.sanadDecayMultiplier)

        val carriedDue = prev?.nextReviewDue ?: now
        val matnDue = if (request.target.includesMatn) now + matnInterval.inWholeMilliseconds else carriedDue
        val sanadDue = if (request.target.includesSanad) now + sanadInterval.inWholeMilliseconds else carriedDue

        val record = MasteryRecordEntity(
            hadithId = request.hadithId,
            matnStability = matnStability.toFloat(),
            sanadStability = sanadStability.toFloat(),
            lastReviewedAt = if (prev == null || request.target == ReviewTarget.DUAL) now else prev.lastReviewedAt,
            nextReviewDue = minOf(matnDue, sanadDue),
            repetitionCount = (prev?.repetitionCount ?: 0) + 1,
        )
        return ScheduleResult(record, matnInterval, sanadInterval)
    }

    override fun retrievability(record: MasteryRecordEntity, now: Long): Retrievability {
        val elapsedDays = max(0L, now - record.lastReviewedAt) / DAY_MS
        return Retrievability(
            matn = recall(elapsedDays, record.matnStability.toDouble()).toFloat(),
            sanad = recall(elapsedDays * config.sanadDecayMultiplier, record.sanadStability.toDouble()).toFloat(),
        )
    }

    override fun metrics(record: MasteryRecordEntity): StabilityMetrics {
        val matn = mastery(record.matnStability.toDouble(), decay = 1.0)
        val sanad = mastery(record.sanadStability.toDouble(), decay = config.sanadDecayMultiplier)
        return StabilityMetrics(
            matnStability = record.matnStability,
            sanadStability = record.sanadStability,
            // Geometric mean: a collapse of either side drags the whole Hadith down.
            overallMastery = sqrt(matn * sanad).toFloat(),
        )
    }

    private fun nextMatnStability(prev: Double, rating: ReviewRating, elapsedDays: Double): Double {
        if (prev <= 0.0) return clamp(initialStability(rating))
        return clamp(
            if (rating == ReviewRating.AGAIN) prev * config.lapseFactor
            else grow(prev, rating, recall(elapsedDays, prev)),
        )
    }

    private fun nextSanadStability(
        prev: Double,
        rating: ReviewRating,
        elapsedDays: Double,
        brokenLinks: Int,
        chainLength: Int,
    ): Double {
        val hasBrokenLink = brokenLinks > 0
        // However smooth the rest of the chain felt, a dropped narrator is at best a HARD recall.
        val effective = if (hasBrokenLink && rating > ReviewRating.HARD) ReviewRating.HARD else rating
        if (prev <= 0.0) return clamp(initialStability(effective))
        return clamp(
            when {
                effective == ReviewRating.AGAIN -> prev * config.lapseFactor
                hasBrokenLink -> prev * brokenLinkFactor(brokenLinks, chainLength)
                else -> grow(prev, effective, recall(elapsedDays * config.sanadDecayMultiplier, prev))
            },
        )
    }

    private fun brokenLinkFactor(brokenLinks: Int, chainLength: Int): Double {
        val ratio = if (chainLength <= 0) 1.0 else (brokenLinks.toDouble() / chainLength).coerceIn(0.0, 1.0)
        val span = config.brokenLinkMaxFactor - config.brokenLinkMinFactor
        return config.brokenLinkMaxFactor - span * ratio
    }

    /** Success growth: bigger for better ratings and for reviews that wait until recall is shakier. */
    private fun grow(stability: Double, rating: ReviewRating, retrievability: Double): Double {
        val spacing = 0.5 + 2.0 * (1.0 - retrievability)
        return stability * (1.0 + gain(rating) * spacing)
    }

    private fun gain(rating: ReviewRating) = when (rating) {
        ReviewRating.AGAIN -> 0.0
        ReviewRating.HARD -> 0.3
        ReviewRating.GOOD -> 1.5
        ReviewRating.EASY -> 2.5
    }

    private fun initialStability(rating: ReviewRating) = when (rating) {
        ReviewRating.AGAIN -> 0.3
        ReviewRating.HARD -> 1.0
        ReviewRating.GOOD -> 2.0
        ReviewRating.EASY -> 4.0
    }

    private fun clamp(stability: Double) = stability.coerceIn(config.minStabilityDays, config.maxStabilityDays)

    /** Days until recall decays to [SchedulerConfig.desiredRetention], with the clock running [decay]× faster. */
    private fun intervalFor(stability: Double, decay: Double): Duration {
        if (stability <= 0.0) return Duration.ZERO
        val days = stability / decay * (config.desiredRetention.pow(-2.0) - 1.0) / FORGETTING_F
        return (days * DAY_MS).roundToLong().milliseconds
    }

    private fun recall(elapsedDays: Double, stability: Double): Double =
        if (stability <= 0.0) 0.0 else (1.0 + FORGETTING_F * elapsedDays / stability).pow(-0.5)

    private fun mastery(stability: Double, decay: Double): Double =
        if (stability <= 0.0) 0.0
        else (ln(1.0 + stability / decay) / ln(1.0 + config.masteryHorizonDays)).coerceIn(0.0, 1.0)

    private companion object {
        const val DAY_MS = 86_400_000.0
        const val FORGETTING_F = 19.0 / 81.0
    }
}

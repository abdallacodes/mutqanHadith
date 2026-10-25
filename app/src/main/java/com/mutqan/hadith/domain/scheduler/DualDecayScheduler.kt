package com.mutqan.hadith.domain.scheduler

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.Retrievability
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.model.StabilityMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** Matn mastery (0..1, from its stability) at which the Sanad tree, layer 2, opens. */
    val sanadTreeUnlockMastery: Double = 0.5,
    /** Sanad mastery (0..1, from its stability) at which the transmission terms, layer 3, open. */
    val termsUnlockMastery: Double = 0.6,
    /** How long after the Sanad tree opens its first study is invited, so a review does not make its hadith due again at once. */
    val sanadIntroDelayDays: Double = 1.0,
) {
    init {
        require(sanadDecayMultiplier >= 1.0) { "Sanad must not decay slower than Matn" }
        require(desiredRetention > 0.0 && desiredRetention < 1.0)
        require(brokenLinkMinFactor <= brokenLinkMaxFactor)
        require(sanadTreeUnlockMastery in 0.0..1.0 && termsUnlockMastery in 0.0..1.0) { "Unlock thresholds are mastery levels, 0..1" }
        require(sanadIntroDelayDays >= 0.0)
    }
}

/**
 * Forgetting follows the FSRS power curve `R(t) = (1 + F·t/S)^-0.5` with `F = 19/81`, so that
 * `R(S) = 0.9`. Matn and Sanad each carry their own stability `S` (days) and their own
 * last-review timestamp:
 *
 * - **Decay asymmetry:** for Sanad the clock runs [SchedulerConfig.sanadDecayMultiplier] times
 *   faster, so equal stabilities give a Sanad interval that many times shorter.
 * - **Weakest link:** one dropped narrator caps the Sanad rating at HARD and cuts its stability
 *   to 30..60% of what it was, however well the rest of the chain went.
 * - **Next due** is `min(matnDue, sanadDue)`, where each component is due at its own last review
 *   plus its own interval, so a Matn-only or Sanad-only review never disturbs the other clock.
 *   A component that was never learned is due immediately.
 * - **Layers:** the Sanad is not studied until the Matn holds (see [layerStatus]), so before that it has no
 *   clock and its due time is left out; once the tree opens, its first study is due after a short delay.
 */
class DualDecayScheduler(
    private val config: SchedulerConfig = SchedulerConfig(),
    /**
     * The "advanced student" choice: when it is on, every hadith in study is on the last layer, whatever its
     * stabilities say. Read each time a layer is decided, so a change takes effect at once.
     */
    private val unlockAllLayers: StateFlow<Boolean> = MutableStateFlow(false),
) : SpacedRepetitionScheduler {

    override val layerRules: Flow<Boolean> get() = unlockAllLayers

    override fun schedule(request: ScheduleRequest): ScheduleResult {
        val prev = request.previous
        val now = request.now
        val prevMatn = prev?.matnStability?.toDouble() ?: 0.0
        val prevSanad = prev?.sanadStability?.toDouble() ?: 0.0

        val matnStability = request.matnRating?.let {
            nextMatnStability(prevMatn, it, elapsedDays(prev?.matnLastReviewedAt, now))
        } ?: prevMatn
        val sanadStability = request.sanadRating?.let {
            nextSanadStability(
                prevSanad, it, elapsedDays(prev?.sanadLastReviewedAt, now), request.brokenLinks, request.chainLength,
            )
        } ?: prevSanad

        val matnLastReviewedAt = if (request.matnRating != null) now else prev?.matnLastReviewedAt
        val sanadLastReviewedAt = if (request.sanadRating != null) now else prev?.sanadLastReviewedAt

        val matnInterval = intervalFor(matnStability, decay = 1.0)
        val sanadInterval = intervalFor(sanadStability, decay = config.sanadDecayMultiplier)
        val layer = layerFor(matnStability, sanadStability, sanadLastReviewedAt).layer
        val matnDue = dueAt(matnLastReviewedAt, matnInterval, matnStability, now)
        val sanadDue = when {
            !layer.opensSanad -> Long.MAX_VALUE
            sanadLastReviewedAt == null -> now + (config.sanadIntroDelayDays * DAY_MS).roundToLong()
            else -> dueAt(sanadLastReviewedAt, sanadInterval, sanadStability, now)
        }

        val record = MasteryRecordEntity(
            hadithId = request.hadithId,
            matnStability = matnStability.toFloat(),
            sanadStability = sanadStability.toFloat(),
            matnLastReviewedAt = matnLastReviewedAt,
            sanadLastReviewedAt = sanadLastReviewedAt,
            nextReviewDue = minOf(matnDue, sanadDue),
            repetitionCount = (prev?.repetitionCount ?: 0) + 1,
        )
        return ScheduleResult(record, matnInterval, sanadInterval)
    }

    override fun retrievability(record: MasteryRecordEntity, now: Long): Retrievability =
        Retrievability(
            matn = recallSince(record.matnLastReviewedAt, now, record.matnStability, decay = 1.0),
            sanad = recallSince(record.sanadLastReviewedAt, now, record.sanadStability, config.sanadDecayMultiplier),
        )

    override fun metrics(record: MasteryRecordEntity): StabilityMetrics {
        val matn = mastery(record.matnStability.toDouble(), decay = 1.0)
        val sanad = mastery(record.sanadStability.toDouble(), decay = config.sanadDecayMultiplier)
        return StabilityMetrics(
            matnStability = record.matnStability,
            sanadStability = record.sanadStability,
            // Geometric mean: a collapse of either side drags the whole Hadith down.
            overallMastery = sqrt(matn * sanad).toFloat(),
            matnMastery = matn.toFloat(),
            sanadMastery = sanad.toFloat(),
        )
    }

    override fun layerStatus(record: MasteryRecordEntity?): LayerStatus =
        if (record == null) LayerStatus.Fresh else layerFor(record.matnStability.toDouble(), record.sanadStability.toDouble(), record.sanadLastReviewedAt)

    /**
     * Layer 2 opens when the Matn is mastered to [SchedulerConfig.sanadTreeUnlockMastery], or as soon as the chain
     * has been studied at all (a hadith whose chain was reviewed before layers existed keeps it). Layer 3 opens
     * from there once the Sanad is mastered to [SchedulerConfig.termsUnlockMastery]. An advanced student who chose to
     * unlock everything is on the last layer from the start.
     */
    private fun layerFor(matnStability: Double, sanadStability: Double, sanadLastReviewedAt: Long?): LayerStatus {
        if (unlockAllLayers.value) return LayerStatus(MemorizationLayer.LAYER_3_TRANSMISSION_TERMS, 1f)
        val matn = mastery(matnStability, decay = 1.0)
        val sanad = mastery(sanadStability, decay = config.sanadDecayMultiplier)
        return when {
            matn < config.sanadTreeUnlockMastery && sanadLastReviewedAt == null ->
                LayerStatus(MemorizationLayer.LAYER_1_CORE, progressTo(matn, config.sanadTreeUnlockMastery))
            sanad < config.termsUnlockMastery ->
                LayerStatus(MemorizationLayer.LAYER_2_SANAD_TREE, progressTo(sanad, config.termsUnlockMastery))
            else -> LayerStatus(MemorizationLayer.LAYER_3_TRANSMISSION_TERMS, 1f)
        }
    }

    private fun progressTo(mastery: Double, threshold: Double): Float =
        if (threshold <= 0.0) 1f else (mastery / threshold).coerceIn(0.0, 1.0).toFloat()

    private fun elapsedDays(lastReviewedAt: Long?, now: Long): Double =
        if (lastReviewedAt == null) 0.0 else max(0L, now - lastReviewedAt) / DAY_MS

    /** A component that was never learned has no clock: it is due right away. */
    private fun dueAt(lastReviewedAt: Long?, interval: Duration, stability: Double, now: Long): Long =
        if (lastReviewedAt == null || stability <= 0.0) now else lastReviewedAt + interval.inWholeMilliseconds

    private fun recallSince(lastReviewedAt: Long?, now: Long, stability: Float, decay: Double): Float =
        if (lastReviewedAt == null) 0f else recall(elapsedDays(lastReviewedAt, now) * decay, stability.toDouble()).toFloat()

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

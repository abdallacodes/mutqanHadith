package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.model.ReviewRating.AGAIN
import com.mutqan.hadith.domain.model.ReviewRating.EASY
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.model.ReviewRating.HARD
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.scheduler.ScheduleRequest
import com.mutqan.hadith.domain.scheduler.ScheduleResult
import com.mutqan.hadith.domain.scheduler.SchedulerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.days

class DualDecaySchedulerTest {

    private val scheduler = DualDecayScheduler()
    private val t0 = 1_700_000_000_000L

    private fun review(
        previous: MasteryRecordEntity?,
        matn: ReviewRating?,
        sanad: ReviewRating?,
        now: Long,
        brokenLinks: Int = 0,
        chainLength: Int = 0,
    ): ScheduleResult = scheduler.schedule(
        ScheduleRequest(1, previous, matn, sanad, now, brokenLinks, chainLength),
    )

    private fun record(
        matn: Float,
        sanad: Float,
        matnAt: Long? = t0,
        sanadAt: Long? = t0,
        due: Long = t0 + DAY_MS,
        count: Int = 3,
    ) = MasteryRecordEntity(1, matn, sanad, matnAt, sanadAt, due, count)

    // -- min(matnDue, sanadDue) ------------------------------------------------------------

    @Test
    fun failingSanadWhileMasteringMatn_schedulesTheShorterSanadInterval() {
        val now = t0 + 10 * DAY_MS
        val result = review(record(10f, 10f), matn = EASY, sanad = AGAIN, now = now)

        assertTrue("matn should be > 7 days, was ${result.matnInterval}", result.matnInterval > 7.days)
        assertTrue("sanad should be < 2 days, was ${result.sanadInterval}", result.sanadInterval < 2.days)
        assertEquals(now + result.sanadInterval.inWholeMilliseconds, result.record.nextReviewDue)
        assertTrue(result.record.nextReviewDue < now + result.matnInterval.inWholeMilliseconds)
    }

    @Test
    fun failingMatnWhileMasteringSanad_schedulesTheShorterMatnInterval() {
        val now = t0 + 10 * DAY_MS
        val result = review(record(10f, 10f), matn = AGAIN, sanad = EASY, now = now)

        assertTrue(result.matnInterval < result.sanadInterval)
        assertEquals(now + result.matnInterval.inWholeMilliseconds, result.record.nextReviewDue)
    }

    // -- progression -----------------------------------------------------------------------

    @Test
    fun consecutiveGoodRatings_growIntervalsAtEachOnTimeReview() {
        var result = review(null, GOOD, GOOD, t0)
        assertEquals(2.days, result.matnInterval)
        val matnIntervals = mutableListOf(result.matnInterval)
        val sanadIntervals = mutableListOf(result.sanadInterval)

        repeat(4) {
            // Reviewed exactly when the Hadith falls due, as the app would prompt.
            result = review(result.record, GOOD, GOOD, result.record.nextReviewDue)
            matnIntervals += result.matnInterval
            sanadIntervals += result.sanadInterval
        }

        matnIntervals.zipWithNext().forEach { (a, b) -> assertTrue("matn $a -> $b", b > a * 1.5) }
        sanadIntervals.zipWithNext().forEach { (a, b) -> assertTrue("sanad $a -> $b", b > a * 1.5) }
        assertEquals(5, result.record.repetitionCount)
    }

    @Test
    fun easyGrowsFasterThanGood_andHardGrowsSlowest() {
        val first = review(null, GOOD, GOOD, t0).record
        val now = first.nextReviewDue
        val hard = review(first, HARD, HARD, now)
        val good = review(first, GOOD, GOOD, now)
        val easy = review(first, EASY, EASY, now)

        assertTrue(hard.matnInterval > 0.days)
        assertTrue(hard.record.matnStability > first.matnStability) // HARD still counts as a success
        assertTrue(good.record.matnStability > hard.record.matnStability)
        assertTrue(easy.record.matnStability > good.record.matnStability)
        assertTrue(easy.sanadInterval > good.sanadInterval && good.sanadInterval > hard.sanadInterval)
    }

    @Test
    fun reviewingLaterEarnsMoreGrowthThanReviewingEarly() {
        val prev = record(10f, 10f)
        val early = review(prev, GOOD, GOOD, t0 + 1 * DAY_MS)
        val late = review(prev, GOOD, GOOD, t0 + 20 * DAY_MS)
        assertTrue(late.record.matnStability > early.record.matnStability)
    }

    // -- AGAIN -----------------------------------------------------------------------------

    @Test
    fun again_penalizesStabilityAndShortensIntervals() {
        val prev = record(8f, 8f)
        val before = review(prev, GOOD, GOOD, t0 + 8 * DAY_MS)
        val result = review(prev, AGAIN, AGAIN, t0 + 8 * DAY_MS)

        assertEquals(2f, result.record.matnStability, 1e-4f) // 8 * lapseFactor 0.25
        assertEquals(2f, result.record.sanadStability, 1e-4f)
        assertTrue(result.matnInterval < before.matnInterval)
        assertTrue(result.sanadInterval < before.sanadInterval)
        assertEquals(4, result.record.repetitionCount)
    }

    @Test
    fun again_onFirstReview_isShorterThanAnySuccess() {
        val again = review(null, AGAIN, AGAIN, t0)
        val hard = review(null, HARD, HARD, t0)
        assertTrue(again.matnInterval < hard.matnInterval)
        assertTrue(again.record.nextReviewDue < hard.record.nextReviewDue)
    }

    @Test
    fun repeatedAgain_neverDropsBelowTheStabilityFloor() {
        var current: MasteryRecordEntity? = null
        var now = t0
        repeat(12) {
            current = review(current, AGAIN, AGAIN, now).record
            now = current!!.nextReviewDue
        }
        val floor = SchedulerConfig().minStabilityDays.toFloat()
        assertEquals(floor, current!!.matnStability, 1e-6f)
        assertEquals(floor, current!!.sanadStability, 1e-6f)
        assertTrue(current!!.nextReviewDue > t0)
    }

    @Test
    fun stabilityIsCappedAtTheConfiguredMaximum() {
        var current: MasteryRecordEntity? = null
        var now = t0
        repeat(30) {
            current = review(current, EASY, EASY, now).record
            now = current!!.nextReviewDue
        }
        assertEquals(365f, current!!.matnStability, 1e-3f)
    }

    // -- decay asymmetry and weakest link --------------------------------------------------

    @Test
    fun sanadDecaysFasterThanMatn_atEqualStability() {
        val fresh = review(null, GOOD, GOOD, t0)
        // Same stability (2.0) but the Sanad clock runs 1.75x faster.
        assertEquals(fresh.matnInterval.inWholeMilliseconds / 1.75, fresh.sanadInterval.inWholeMilliseconds.toDouble(), 2.0)

        val r = scheduler.retrievability(record(10f, 10f), t0 + 5 * DAY_MS)
        assertTrue("sanad ${r.sanad} should be below matn ${r.matn}", r.sanad < r.matn)
        assertEquals(0.9f, scheduler.retrievability(record(10f, 10f), t0 + 10 * DAY_MS).matn, 1e-3f)
    }

    @Test
    fun oneBrokenLink_dropsSanadStabilitySharply_whileMatnStillGrows() {
        val prev = record(10f, 10f)
        val now = t0 + 10 * DAY_MS
        val clean = review(prev, GOOD, GOOD, now)
        val oneBroken = review(prev, GOOD, EASY, now, brokenLinks = 1, chainLength = 6)
        val manyBroken = review(prev, GOOD, GOOD, now, brokenLinks = 3, chainLength = 6)

        assertEquals(5.5f, oneBroken.record.sanadStability, 1e-3f) // 10 * (0.6 - 0.3 * 1/6)
        assertTrue(oneBroken.record.sanadStability < prev.sanadStability)
        assertTrue(clean.record.sanadStability > prev.sanadStability)
        assertTrue(manyBroken.record.sanadStability < oneBroken.record.sanadStability)
        assertTrue(oneBroken.record.matnStability > prev.matnStability)
        // A perfect-feeling EASY can't rescue a chain with a dropped narrator.
        assertTrue(oneBroken.sanadInterval < clean.sanadInterval)
    }

    // -- single-target reviews -------------------------------------------------------------

    @Test
    fun matnOnlyReview_advancesOnlyTheMatnClock() {
        val prev = record(10f, 6f)
        val now = t0 + 10 * DAY_MS
        val result = review(prev, GOOD, null, now)

        assertEquals(6f, result.record.sanadStability, 0f)
        assertEquals(now, result.record.matnLastReviewedAt)
        assertEquals(t0, result.record.sanadLastReviewedAt) // the Sanad's clock must not be reset
        assertTrue(result.record.matnStability > prev.matnStability)
        assertEquals(4, result.record.repetitionCount)
    }

    @Test
    fun matnOnlyReview_leavesTheSanadDueExactlyWhereItWas() {
        val prev = record(10f, 6f)
        val now = t0 + 1 * DAY_MS
        val result = review(prev, GOOD, null, now)

        // Sanad (S = 6, clock 1.75x) fell due at t0 + 6/1.75 days, long before the new Matn interval.
        val sanadDue = t0 + (6.0 / 1.75 * DAY_MS).roundToLong()
        assertEquals(sanadDue, result.record.nextReviewDue)
        assertTrue(result.record.nextReviewDue < now + result.matnInterval.inWholeMilliseconds)
    }

    @Test
    fun sanadOnlyReview_doesNotSkewTheMatnElapsedTime() {
        val prev = record(10f, 10f)
        val later = t0 + 10 * DAY_MS

        // A Sanad drill happens in between; Matn must still be measured from its own last review.
        val afterSanadDrill = review(prev, null, GOOD, later - 1000).record
        val viaDrill = review(afterSanadDrill, GOOD, null, later)
        val direct = review(prev, GOOD, null, later)

        assertEquals(direct.record.matnStability, viaDrill.record.matnStability, 1e-6f)
        assertEquals(later - 1000, afterSanadDrill.sanadLastReviewedAt)
        assertEquals(t0, afterSanadDrill.matnLastReviewedAt)
        assertTrue(
            scheduler.retrievability(afterSanadDrill, later).matn <
                scheduler.retrievability(afterSanadDrill, later).sanad,
        )
    }

    @Test
    fun matnOnlyFirstReview_leavesTheSanadWithoutAClockWhileTheMatnIsBeingLearned() {
        // Layer 1: the chain is not studied yet, so its being unlearned does not make the hadith due.
        val result = review(null, EASY, null, t0)
        assertEquals(0f, result.record.sanadStability, 0f)
        assertEquals(null, result.record.sanadLastReviewedAt)
        assertEquals(t0 + result.matnInterval.inWholeMilliseconds, result.record.nextReviewDue)
        assertEquals(t0, result.record.matnLastReviewedAt)
        assertTrue(result.record.matnStability > 0f)
        assertEquals(0f, scheduler.retrievability(result.record, t0 + DAY_MS).sanad, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aReviewMustTestSomething() {
        review(null, null, null, t0)
    }

    // -- metrics ---------------------------------------------------------------------------

    @Test
    fun overallMastery_collapsesWhenEitherSideIsWeak() {
        val strong = scheduler.metrics(record(60f, 60f)).overallMastery
        val weakSanad = scheduler.metrics(record(60f, 1f)).overallMastery
        val unlearnedSanad = scheduler.metrics(record(60f, 0f)).overallMastery

        assertTrue(strong in 0f..1f)
        assertTrue(weakSanad < strong)
        assertEquals(0f, unlearnedSanad, 0f)
        assertEquals(60f, scheduler.metrics(record(60f, 1f)).matnStability, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sanadMustNotDecaySlowerThanMatn() {
        SchedulerConfig(sanadDecayMultiplier = 0.8)
    }
}

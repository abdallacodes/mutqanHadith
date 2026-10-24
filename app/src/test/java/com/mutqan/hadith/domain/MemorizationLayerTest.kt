package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.MemorizationLayer.LAYER_1_CORE
import com.mutqan.hadith.domain.model.MemorizationLayer.LAYER_2_SANAD_TREE
import com.mutqan.hadith.domain.model.MemorizationLayer.LAYER_3_TRANSMISSION_TERMS
import com.mutqan.hadith.domain.model.Retrievability
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.model.ReviewRating.AGAIN
import com.mutqan.hadith.domain.model.ReviewRating.EASY
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.model.ReviewRating.HARD
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.scheduler.ScheduleRequest
import com.mutqan.hadith.domain.scheduler.ScheduleResult
import com.mutqan.hadith.domain.scheduler.SchedulerConfig
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layered memorization (التدرج بالطبقات): which layer a hadith is in follows from the stabilities the scheduler
 * keeps, never from the clock, and the review schedule leaves the chain out until its layer opens.
 *
 * The thresholds are on the mastery that [DualDecayScheduler.metrics] reports: `ln(1 + S / decay) / ln(91)`.
 * Layer 2 opens at a Matn mastery of 0.5, i.e. a Matn stability of `sqrt(91) - 1` = 8.54 days; layer 3 at a Sanad
 * mastery of 0.6, i.e. a Sanad stability of 1.75 * (91^0.6 - 1) = 24.45 days.
 */
class MemorizationLayerTest {

    private val t0 = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()

    private fun record(
        matn: Float,
        sanad: Float,
        matnAt: Long? = t0,
        sanadAt: Long? = t0,
        due: Long = t0 + DAY_MS,
    ) = MasteryRecordEntity(1, matn, sanad, matnAt, sanadAt, due, 3)

    private fun layerOf(matn: Float, sanad: Float, sanadAt: Long? = t0) = scheduler.layerStatus(record(matn, sanad, sanadAt = sanadAt))

    /** One hadith followed review after review; each review happens exactly when the previous one fell due. */
    private inner class Run(val engine: DualDecayScheduler = scheduler) {
        var record: MasteryRecordEntity? = null
        var now = t0
        val layers = mutableListOf<MemorizationLayer>()

        fun review(matn: ReviewRating?, sanad: ReviewRating? = null, wait: Boolean = true): ScheduleResult {
            val result = engine.schedule(ScheduleRequest(1, record, matn, sanad, now, 0, 4))
            record = result.record
            layers += engine.layerStatus(result.record).layer
            if (wait) now += (if (matn != null) result.matnInterval else result.sanadInterval).inWholeMilliseconds
            return result
        }

        fun repeatMatn(rating: ReviewRating, times: Int) = repeat(times) { review(rating) }
    }

    // -- the layers themselves -------------------------------------------------------------

    @Test
    fun layers_knowWhatEachOneOpens() {
        assertFalse(LAYER_1_CORE.opensSanad)
        assertFalse(LAYER_1_CORE.opensTerms)
        assertTrue(LAYER_2_SANAD_TREE.opensSanad)
        assertFalse(LAYER_2_SANAD_TREE.opensTerms)
        assertTrue(LAYER_3_TRANSMISSION_TERMS.opensSanad)
        assertTrue(LAYER_3_TRANSMISSION_TERMS.opensTerms)
        assertEquals(LAYER_2_SANAD_TREE, LAYER_1_CORE.next)
        assertEquals(LAYER_3_TRANSMISSION_TERMS, LAYER_2_SANAD_TREE.next)
        assertNull(LAYER_3_TRANSMISSION_TERMS.next)
        assertEquals(listOf(1, 2, 3), MemorizationLayer.entries.map { it.number })
    }

    @Test
    fun aHadithWithNoRecordIsFreshOnLayerOne() {
        assertEquals(LayerStatus.Fresh, scheduler.layerStatus(null))
        assertEquals(LAYER_1_CORE, LayerStatus.Fresh.layer)
        assertEquals(0f, LayerStatus.Fresh.progress, 0f)
    }

    @Test
    fun weakestIn_leavesTheChainOutUntilItsLayerOpens() {
        val recall = Retrievability(matn = 0.9f, sanad = 0f)
        assertEquals(0.9f, recall.weakestIn(LAYER_1_CORE), 0f)
        assertEquals(0f, recall.weakestIn(LAYER_2_SANAD_TREE), 0f)
        assertEquals(0f, recall.weakestIn(LAYER_3_TRANSMISSION_TERMS), 0f)
    }

    // -- thresholds from stability ---------------------------------------------------------

    @Test
    fun layerTwo_opensWhenTheMatnStabilityReachesAMasteryOfOneHalf() {
        // Just under sqrt(91) - 1 = 8.54 days, and just over it.
        assertEquals(LAYER_1_CORE, layerOf(matn = 8.5f, sanad = 0f, sanadAt = null).layer)
        assertEquals(LAYER_2_SANAD_TREE, layerOf(matn = 8.6f, sanad = 0f, sanadAt = null).layer)
        // The threshold is the one the metrics report.
        assertEquals(0.5f, scheduler.metrics(record(8.539f, 0f, sanadAt = null)).matnMastery, 1e-3f)
    }

    @Test
    fun layerThree_opensWhenTheSanadStabilityReachesAMasteryOfThreeFifths() {
        // Sanad ages 1.75 times faster, so its mastery 0.6 sits at a stability of 24.45 days.
        assertEquals(LAYER_2_SANAD_TREE, layerOf(matn = 30f, sanad = 24.3f).layer)
        assertEquals(LAYER_3_TRANSMISSION_TERMS, layerOf(matn = 30f, sanad = 24.6f).layer)
        assertEquals(0.6f, scheduler.metrics(record(30f, 24.45f)).sanadMastery, 1e-3f)
    }

    @Test
    fun aStrongSanadDoesNotSkipTheMatn() {
        // A chain studied before the matn was firm (the shipped version asked for both) is still on layer 2, not 3,
        // until its own stability gets there; and a hadith with no chain study at all cannot be past layer 1.
        assertEquals(LAYER_2_SANAD_TREE, layerOf(matn = 1f, sanad = 1f).layer)
        assertEquals(LAYER_1_CORE, layerOf(matn = 1f, sanad = 60f, sanadAt = null).layer)
    }

    @Test
    fun progress_isTheMasteryTowardTheNextThreshold() {
        // First GOOD: stability 2 -> mastery ln(3) / ln(91) = 0.2435, a little under half way to 0.5.
        val first = scheduler.layerStatus(record(2f, 0f, sanadAt = null))
        assertEquals(LAYER_1_CORE, first.layer)
        assertEquals(0.487f, first.progress, 2e-3f)

        // On layer 2 it is the Sanad toward 0.6: stability 12 -> mastery ln(1 + 12/1.75) / ln(91) = 0.457.
        val second = scheduler.layerStatus(record(30f, 12f))
        assertEquals(LAYER_2_SANAD_TREE, second.layer)
        assertEquals(0.762f, second.progress, 2e-3f)

        // Nothing is left to open on the last layer.
        assertEquals(1f, layerOf(60f, 60f).progress, 0f)
    }

    @Test
    fun progress_neverReachesOneBeforeTheLayerOpens() {
        val almost = scheduler.layerStatus(record(8.5f, 0f, sanadAt = null))
        assertEquals(LAYER_1_CORE, almost.layer)
        assertTrue(almost.progress < 1f)
    }

    @Test
    fun theThresholdsAreConfigurable() {
        val eager = DualDecayScheduler(SchedulerConfig(sanadTreeUnlockMastery = 0.2))
        // A first GOOD (stability 2, mastery 0.2435) already clears 0.2.
        assertEquals(LAYER_2_SANAD_TREE, eager.layerStatus(record(2f, 0f, sanadAt = null)).layer)
        assertEquals(LAYER_1_CORE, scheduler.layerStatus(record(2f, 0f, sanadAt = null)).layer)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aThresholdIsAMasteryLevel() {
        SchedulerConfig(termsUnlockMastery = 1.5)
    }

    // -- transitions from ratings ----------------------------------------------------------

    @Test
    fun goodReviewsOnTime_leaveLayerOneOnTheFourthReview() {
        val run = Run()

        run.repeatMatn(GOOD, 4)

        // 2.0 -> 4.1 -> 8.4 days: still just under a mastery of 0.5; the fourth, 17.2 days, is over it.
        assertEquals(listOf(LAYER_1_CORE, LAYER_1_CORE, LAYER_1_CORE, LAYER_2_SANAD_TREE), run.layers)
    }

    @Test
    fun easyReviewsOpenTheSanadTreeSooner() {
        val run = Run()

        run.repeatMatn(EASY, 2)

        // 4.0 -> 11.0 days.
        assertEquals(listOf(LAYER_1_CORE, LAYER_2_SANAD_TREE), run.layers)
    }

    @Test
    fun hardReviewsKeepTheHadithOnLayerOneForLong() {
        val run = Run()

        run.repeatMatn(HARD, 8)

        assertTrue(run.layers.all { it == LAYER_1_CORE })
    }

    @Test
    fun aLapseSetsTheProgressBack() {
        val run = Run()
        run.repeatMatn(GOOD, 3)
        val before = scheduler.layerStatus(run.record).progress

        run.review(AGAIN)

        val after = scheduler.layerStatus(run.record)
        assertEquals(LAYER_1_CORE, after.layer)
        assertTrue("progress $before should fall after an AGAIN, was ${after.progress}", after.progress < before)
    }

    @Test
    fun theProgressOnlyGrowsWithSuccessfulReviews() {
        val run = Run()
        val progress = (1..3).map {
            run.review(GOOD)
            scheduler.layerStatus(run.record).progress
        }

        assertEquals(progress.sorted(), progress)
        assertTrue(progress.first() > 0f && progress.last() < 1f)
    }

    @Test
    fun goodSanadReviewsOpenTheTransmissionTermsOnTheFifthReview() {
        val run = Run()
        run.repeatMatn(EASY, 2) // layer 2
        run.layers.clear()

        repeat(5) { run.review(matn = null, sanad = GOOD) }

        // 2.0 -> 4.1 -> 8.4 -> 17.2 -> 35.3 days against a threshold of 24.45.
        assertEquals(
            listOf(LAYER_2_SANAD_TREE, LAYER_2_SANAD_TREE, LAYER_2_SANAD_TREE, LAYER_2_SANAD_TREE, LAYER_3_TRANSMISSION_TERMS),
            run.layers,
        )
    }

    @Test
    fun aBrokenLinkSlowsTheWayToTheLastLayer() {
        val run = Run()
        run.repeatMatn(EASY, 2)
        repeat(4) {
            val result = scheduler.schedule(ScheduleRequest(1, run.record, null, GOOD, run.now, brokenLinks = 1, chainLength = 4))
            run.record = result.record
            run.now += result.sanadInterval.inWholeMilliseconds
        }

        // A dropped narrator caps every review at HARD and cuts the stability: four of them are nowhere near 24 days.
        assertEquals(LAYER_2_SANAD_TREE, scheduler.layerStatus(run.record).layer)
    }

    @Test
    fun anOpenSanadTreeStaysOpenAfterALapse() {
        val run = Run()
        run.repeatMatn(EASY, 2)
        run.review(matn = null, sanad = GOOD)

        run.review(AGAIN, AGAIN)

        // The matn is back to a stability of 2.75 days, below the threshold that opened the tree, but the chain has
        // been studied and is not taken away again.
        assertTrue(scheduler.metrics(run.record!!).matnMastery < 0.5f)
        assertEquals(LAYER_2_SANAD_TREE, scheduler.layerStatus(run.record).layer)
    }

    @Test
    fun theLayerDoesNotMoveWithTheClock() {
        val settled = record(matn = 12f, sanad = 2f, matnAt = t0, sanadAt = t0)
        val fiveYearsLater = t0 + 5 * 365 * DAY_MS

        // Everything has been forgotten by then; the layer is what the stabilities say and has no clock.
        assertTrue(scheduler.retrievability(settled, fiveYearsLater).matn < 0.2f)
        assertEquals(LAYER_2_SANAD_TREE, scheduler.layerStatus(settled).layer)
    }

    // -- the schedule follows the layer ----------------------------------------------------

    @Test
    fun onLayerOneTheHadithIsDueWithItsMatnAlone() {
        val result = Run().review(GOOD, wait = false)

        val matnDue = t0 + result.matnInterval.inWholeMilliseconds
        assertEquals(matnDue, result.record.nextReviewDue)
        assertTrue(result.record.nextReviewDue > t0 + DAY_MS) // not due again the next day on account of a chain nobody asked for
    }

    @Test
    fun anUnlockDoesNotMakeTheHadithDueAtOnce() {
        val run = Run()
        run.repeatMatn(GOOD, 3)

        val unlocking = run.review(GOOD, wait = false)

        assertEquals(LAYER_2_SANAD_TREE, scheduler.layerStatus(unlocking.record).layer)
        // The chain is invited a day later, not right away, though it was never studied.
        assertEquals(run.now + DAY_MS, unlocking.record.nextReviewDue)
        assertTrue(unlocking.matnInterval.inWholeMilliseconds > DAY_MS)
    }

    @Test
    fun theIntroDelayIsConfigurable() {
        val run = Run(DualDecayScheduler(SchedulerConfig(sanadIntroDelayDays = 3.0)))
        run.review(EASY)

        val unlocking = run.review(EASY, wait = false)

        assertEquals(LAYER_2_SANAD_TREE, run.engine.layerStatus(unlocking.record).layer)
        assertEquals(run.now + 3 * DAY_MS, unlocking.record.nextReviewDue)
    }

    @Test
    fun aSanadReviewOnLayerTwoSchedulesTheChainOnItsOwnClock() {
        val run = Run()
        run.review(EASY)
        run.review(EASY, wait = false) // layer 2, the matn at 11 days; the chain not yet studied

        val result = run.review(matn = null, sanad = GOOD, wait = false)

        val sanadDue = run.now + result.sanadInterval.inWholeMilliseconds
        val matnDue = result.record.matnLastReviewedAt!! + result.matnInterval.inWholeMilliseconds
        assertTrue(sanadDue < matnDue)
        assertEquals(sanadDue, result.record.nextReviewDue)
    }

    // -- the use cases carry the layer -----------------------------------------------------

    private val chain = (1L..4L).map { narrator(it) }
    private val hadiths = FakeHadithRepository(listOf(hadith(1, chain), hadith(2, chain.take(2)), hadith(3, chain.take(3))))
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private var clock = t0

    private val submit = SubmitReviewUseCase(hadiths, reviews, scheduler) { clock }
    private val getDue = GetDueHadithsUseCase(hadiths, reviews, scheduler) { clock }

    @Test
    fun submit_reportsTheLayerOpenedByTheReviewThatOpenedIt() = runBlocking {
        val outcomes = (1..4).map {
            submit(1, GOOD, null).also { outcome -> clock = outcome.nextReviewDue }
        }

        assertEquals(listOf(null, null, null, LAYER_2_SANAD_TREE), outcomes.map { it.unlockedLayer })
        assertEquals(LayerStatus.Fresh, outcomes.first().layerBefore)
        assertEquals(LAYER_1_CORE, outcomes.first().layerAfter.layer)
        assertEquals(LAYER_1_CORE, outcomes.last().layerBefore.layer)
        assertEquals(LAYER_2_SANAD_TREE, outcomes.last().layerAfter.layer)
    }

    @Test
    fun submit_aSanadReviewCanOpenTheLastLayer() = runBlocking {
        reviews.mastery[1] = MasteryRecordEntity(1, 40f, 20f, t0 - 10 * DAY_MS, t0 - 10 * DAY_MS, t0 - DAY_MS, 6)

        val outcome = submit(1, null, EASY)

        assertEquals(LAYER_2_SANAD_TREE, outcome.layerBefore.layer)
        assertEquals(LAYER_3_TRANSMISSION_TERMS, outcome.unlockedLayer)
    }

    @Test
    fun submit_aReviewThatDoesNotMoveTheLayerUnlocksNothing() = runBlocking {
        reviews.mastery[1] = MasteryRecordEntity(1, 60f, 60f, t0 - 10 * DAY_MS, t0 - 10 * DAY_MS, t0 - DAY_MS, 9)

        val outcome = submit(1, GOOD, GOOD)

        assertNull(outcome.unlockedLayer)
        assertEquals(LAYER_3_TRANSMISSION_TERMS, outcome.layerAfter.layer)
    }

    @Test
    fun layerStatuses_answerForEveryRequestedHadith() = runBlocking {
        reviews.mastery[2] = MasteryRecordEntity(2, 30f, 5f, t0, t0, t0 + DAY_MS, 4)
        reviews.mastery[3] = MasteryRecordEntity(3, 3f, 0f, t0, null, t0 + DAY_MS, 1)

        val statuses = GetLayerStatusesUseCase(reviews, scheduler)(listOf(1L, 2L, 3L))

        assertEquals(LayerStatus.Fresh, statuses.getValue(1))
        assertEquals(LAYER_2_SANAD_TREE, statuses.getValue(2).layer)
        assertEquals(LAYER_1_CORE, statuses.getValue(3).layer)
        assertTrue(statuses.getValue(3).progress > 0f)
    }

    @Test
    fun due_ranksEachHadithByTheComponentsOfItsOwnLayer() = runBlocking {
        // Hadith 1 is on layer 1: its matn is at 90% and its never-studied chain is no weakness.
        reviews.mastery[1] = MasteryRecordEntity(1, 2f, 0f, t0 - 2 * DAY_MS, null, t0 - 1, 1)
        // Hadith 2 is on layer 2 with a chain that has faded to about 70%.
        reviews.mastery[2] = MasteryRecordEntity(2, 20f, 2f, t0 - DAY_MS, t0 - 5 * DAY_MS, t0 - 1, 4)

        val due = getDue()

        assertEquals(listOf(2L, 1L), due.map { it.hadith.hadith.id })
        assertEquals(listOf(LAYER_2_SANAD_TREE, LAYER_1_CORE), due.map { it.layer.layer })
    }

    @Test
    fun study_carriesTheLayerOnlyOnceTheHadithIsInStudy() = runBlocking {
        val study = GetHadithStudyUseCase(hadiths, reviews, scheduler) { clock }

        assertNull(study(1).first()!!.layer)

        submit(1, GOOD, null)
        assertEquals(LAYER_1_CORE, study(1).first()!!.layer!!.layer)

        reviews.mastery[1] = MasteryRecordEntity(1, 30f, 30f, t0, t0, t0 + DAY_MS, 6)
        assertEquals(LAYER_3_TRANSMISSION_TERMS, study(1).first()!!.layer!!.layer)
    }

    @Test
    fun dashboard_carriesTheLayerOfEveryItem() = runBlocking {
        reviews.mastery[2] = MasteryRecordEntity(2, 30f, 5f, t0, t0, t0 + DAY_MS, 4)

        val items = ObserveStudyDashboardUseCase(hadiths, reviews, scheduler) { clock }().first().items.associateBy { it.hadithId }

        assertEquals(LayerStatus.Fresh, items.getValue(1).layer)
        assertEquals(LAYER_2_SANAD_TREE, items.getValue(2).layer.layer)
    }
}

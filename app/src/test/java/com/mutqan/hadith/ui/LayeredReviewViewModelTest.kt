package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.MemorizationLayer.LAYER_1_CORE
import com.mutqan.hadith.domain.model.MemorizationLayer.LAYER_2_SANAD_TREE
import com.mutqan.hadith.domain.model.MemorizationLayer.LAYER_3_TRANSMISSION_TERMS
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.model.ReviewRating.EASY
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.review.CardStage
import com.mutqan.hadith.ui.review.LayerUnlock
import com.mutqan.hadith.ui.review.ReviewEvent
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewSession
import com.mutqan.hadith.ui.review.ReviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The review session on the layers: what a card asks for, when a layer opens, and how that is announced. */
@OptIn(ExperimentalCoroutinesApi::class)
class LayeredReviewViewModelTest {

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()

    private val chain = listOf(narrator(1, "الحميدي"), narrator(2, "سفيان"), narrator(3, "يحيى"), narrator(4, "عمر", tier = "صحابي"))
    private val hadiths = FakeHadithRepository((1L..4L).map { hadith(it, chain) })
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A hadith on layer 1 whose matn is close to the mastery that opens the Sanad tree, last seen [daysAgo] days ago. */
    private fun core(id: Long, matn: Float = 8f, daysAgo: Int = 8) {
        reviews.mastery[id] = MasteryRecordEntity(id, matn, 0f, now - daysAgo * DAY_MS, null, now - DAY_MS, 3)
    }

    /** A hadith with an open Sanad tree whose chain is some way from opening the transmission terms. */
    private fun tree(id: Long, sanad: Float = 2f) {
        reviews.mastery[id] = MasteryRecordEntity(id, 20f, sanad, now - DAY_MS, now - 5 * DAY_MS, now - DAY_MS, 4)
    }

    /** A hadith on the last layer. */
    private fun terms(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 60f, 60f, now - DAY_MS, now - DAY_MS, now - 1, 8)
    }

    private fun reviewVm(mode: ReviewMode, targetId: Long? = null) = ReviewViewModel(
        mode, targetId,
        GetDueHadithsUseCase(hadiths, reviews, scheduler) { now },
        GetUnseenHadithsUseCase(hadiths),
        hadiths,
        SubmitReviewUseCase(hadiths, reviews, scheduler) { now },
        GetNarratorChainHeatmapUseCase(hadiths, reviews),
        GetLayerStatusesUseCase(reviews, scheduler),
    )

    private fun ReviewViewModel.session(): ReviewSession = (state.value as UiState.Success).data

    private fun ReviewViewModel.rateAndSubmit(matn: ReviewRating, sanad: ReviewRating? = null) {
        onEvent(ReviewEvent.RateMatn(matn))
        if (sanad != null) onEvent(ReviewEvent.RateSanad(sanad))
        onEvent(ReviewEvent.Submit)
    }

    // -- the cards -------------------------------------------------------------------------

    @Test
    fun cards_carryTheirLayerTheCompanionAndTheTakhrij() = runTest {
        core(1)
        tree(2)
        terms(3)

        val queue = reviewVm(ReviewMode.DUE).session().queue.associateBy { it.hadithId }

        assertEquals(LAYER_1_CORE, queue.getValue(1).activeLayer)
        assertEquals(LAYER_2_SANAD_TREE, queue.getValue(2).activeLayer)
        assertEquals(LAYER_3_TRANSMISSION_TERMS, queue.getValue(3).activeLayer)
        assertEquals("عمر", queue.getValue(1).sahabi)
        assertEquals("متفق عليه", queue.getValue(1).takhrij)
        assertEquals(CardStage.MATN, queue.getValue(1).finalStage)
        assertEquals(CardStage.SANAD, queue.getValue(2).finalStage)
    }

    @Test
    fun aNewHadith_isALayerOneCardThatNeverGoesPastTheMatn() = runTest {
        val vm = reviewVm(ReviewMode.NEW, targetId = 1)

        assertEquals(LAYER_1_CORE, vm.session().current!!.activeLayer)
        repeat(3) { vm.onEvent(ReviewEvent.Reveal) }

        assertEquals(CardStage.MATN, vm.session().stage)
        assertTrue(vm.session().heatmaps.isEmpty())
    }

    @Test
    fun aLayerOneCard_revealsTheMatnAndStopsThere() = runTest {
        core(1)
        val vm = reviewVm(ReviewMode.DUE)
        assertEquals(CardStage.RECALL, vm.session().stage)

        repeat(3) { vm.onEvent(ReviewEvent.Reveal) }

        assertEquals(CardStage.MATN, vm.session().stage)
    }

    @Test
    fun aLayerTwoCard_goesOnToTheChain() = runTest {
        tree(1)
        val vm = reviewVm(ReviewMode.DUE)

        repeat(3) { vm.onEvent(ReviewEvent.Reveal) }

        assertEquals(CardStage.SANAD, vm.session().stage)
    }

    // -- what is rated ---------------------------------------------------------------------

    @Test
    fun aLayerOneCard_isSubmittedOnTheMatnAlone() = runTest {
        val vm = reviewVm(ReviewMode.NEW, targetId = 1)
        assertFalse(vm.session().canSubmit)

        vm.onEvent(ReviewEvent.RateMatn(GOOD))
        assertTrue(vm.session().canSubmit)
        vm.onEvent(ReviewEvent.Submit)

        val record = reviews.mastery.getValue(1L)
        assertEquals(2f, record.matnStability, 1e-6f)
        assertEquals(0f, record.sanadStability, 0f)
        assertNull(record.sanadLastReviewedAt)
        assertTrue(reviews.events.isEmpty()) // no narrator was tested
    }

    @Test
    fun aLayerOneCard_ignoresAStraySanadRating() = runTest {
        val vm = reviewVm(ReviewMode.NEW, targetId = 1)

        vm.rateAndSubmit(GOOD, sanad = EASY)

        val record = reviews.mastery.getValue(1L)
        assertEquals(0f, record.sanadStability, 0f)
        assertNull(record.sanadLastReviewedAt)
    }

    @Test
    fun aLayerTwoCard_needsBothRatings() = runTest {
        tree(1)
        val vm = reviewVm(ReviewMode.DUE)

        vm.onEvent(ReviewEvent.RateMatn(GOOD))
        assertFalse(vm.session().canSubmit)
        vm.onEvent(ReviewEvent.Submit) // refused: nothing is recorded
        assertEquals(4, reviews.mastery.getValue(1L).repetitionCount)

        vm.onEvent(ReviewEvent.RateSanad(GOOD))
        assertTrue(vm.session().canSubmit)
        vm.onEvent(ReviewEvent.Submit)
        assertEquals(5, reviews.mastery.getValue(1L).repetitionCount)
    }

    // -- unlocking -------------------------------------------------------------------------

    @Test
    fun aReviewThatOpensTheSanadTree_announcesIt() = runTest {
        core(1)
        val vm = reviewVm(ReviewMode.DUE)

        vm.rateAndSubmit(EASY)

        val s = vm.session()
        assertTrue(s.isFinished)
        assertEquals(LayerUnlock(hadithId = 1, hadithNumber = 1, layer = LAYER_2_SANAD_TREE), s.unlock)
        assertEquals(listOf(s.unlock), s.unlocks)
    }

    @Test
    fun aReviewThatDoesNotCrossAThreshold_announcesNothing() = runTest {
        core(1, matn = 2f, daysAgo = 2)
        val vm = reviewVm(ReviewMode.DUE)

        vm.rateAndSubmit(GOOD)

        assertNull(vm.session().unlock)
        assertTrue(vm.session().unlocks.isEmpty())
    }

    @Test
    fun theUnlockIsShownOnTheNextCardAndCanBeDismissed() = runTest {
        core(1, matn = 8f, daysAgo = 30) // the most overdue, so first
        core(2, matn = 2f, daysAgo = 2)
        val vm = reviewVm(ReviewMode.DUE)
        assertEquals(listOf(1L, 2L), vm.session().queue.map { it.hadithId })

        vm.rateAndSubmit(EASY)

        assertEquals(2L, vm.session().current!!.hadithId)
        assertEquals(LAYER_2_SANAD_TREE, vm.session().unlock!!.layer)

        vm.onEvent(ReviewEvent.DismissUnlock)
        assertNull(vm.session().unlock)
        assertEquals(1, vm.session().unlocks.size) // the summary still has it

        vm.rateAndSubmit(GOOD)
        assertNull(vm.session().unlock)
        assertEquals(1, vm.session().unlocks.size)
    }

    @Test
    fun aSanadReviewThatOpensTheTransmissionTerms_announcesIt() = runTest {
        reviews.mastery[1] = MasteryRecordEntity(1, 40f, 20f, now - 10 * DAY_MS, now - 10 * DAY_MS, now - DAY_MS, 6)
        val vm = reviewVm(ReviewMode.DUE)
        assertEquals(LAYER_2_SANAD_TREE, vm.session().current!!.activeLayer)

        vm.rateAndSubmit(GOOD, sanad = EASY)

        assertEquals(LAYER_3_TRANSMISSION_TERMS, vm.session().unlock!!.layer)
    }

    // -- layer 3 and the matn view ---------------------------------------------------------

    @Test
    fun theTransmissionTerms_areAskedForBeforeTheyAreShown_andFoldBackOnTheNextCard() = runTest {
        terms(1)
        terms(2)
        val vm = reviewVm(ReviewMode.DUE)
        assertFalse(vm.session().termsRevealed)

        vm.onEvent(ReviewEvent.RevealTerms)
        assertTrue(vm.session().termsRevealed)

        vm.rateAndSubmit(GOOD, sanad = GOOD)
        assertFalse(vm.session().termsRevealed)
    }

    @Test
    fun theTashkeelSetting_isKeptForTheWholeSession() = runTest {
        core(1)
        core(2, matn = 2f, daysAgo = 2)
        val vm = reviewVm(ReviewMode.DUE)
        assertFalse(vm.session().hideTashkeel)

        vm.onEvent(ReviewEvent.ToggleTashkeel)
        assertTrue(vm.session().hideTashkeel)
        vm.rateAndSubmit(GOOD)

        assertTrue(vm.session().hideTashkeel)
        vm.onEvent(ReviewEvent.ToggleTashkeel)
        assertFalse(vm.session().hideTashkeel)
    }
}

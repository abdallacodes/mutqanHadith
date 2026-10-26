package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.MemorizationLayer.LAYER_1_CORE
import com.mutqan.hadith.domain.model.MemorizationLayer.LAYER_3_TRANSMISSION_TERMS
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.review.CardStage
import com.mutqan.hadith.ui.review.HintKind
import com.mutqan.hadith.ui.review.ReviewEvent
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewSession
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.study.HadithStudyUi
import com.mutqan.hadith.ui.study.HadithStudyViewModel
import com.mutqan.hadith.ui.study.MatnScale
import com.mutqan.hadith.ui.study.ReaderSettings
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The retention suite in the ViewModels: due reviews worked through in batches, the hints of a layer 1 card, the
 * daily limit on new hadiths, and the advanced student's way round the layers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetentionSuiteViewModelsTest {

    private var now = 1_700_000_000_000L
    private val chain = listOf(narrator(1, "الحميدي"), narrator(2, "سفيان"), narrator(3, "يحيى"), narrator(4, "عمر", tier = "صحابي"))
    private val hadiths = FakeHadithRepository((1L..12L).map { hadith(it, chain) } + hadith(20, emptyList()))
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val scheduler = DualDecayScheduler()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A hadith on layer 1, due: its matn is close to, but short of, the mastery that opens the Sanad tree. */
    private fun core(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 8f, 0f, now - 8 * DAY_MS, null, now - DAY_MS, 3)
    }

    /** A hadith whose Sanad tree is open, due. */
    private fun tree(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 20f, 2f, now - DAY_MS, now - 5 * DAY_MS, now - DAY_MS, 4)
    }

    private fun reviewVm(mode: ReviewMode, targetId: Long? = null, with: SpacedRepetitionScheduler = scheduler) = ReviewViewModel(
        mode, targetId,
        GetDueHadithsUseCase(hadiths, reviews, with) { now },
        GetUnseenHadithsUseCase(hadiths),
        hadiths,
        SubmitReviewUseCase(hadiths, reviews, with) { now },
        GetNarratorChainHeatmapUseCase(hadiths, reviews),
        GetLayerStatusesUseCase(reviews, with),
    )

    private fun ReviewViewModel.session(): ReviewSession = (state.value as UiState.Success).data

    private fun ReviewViewModel.reviewCurrent() {
        onEvent(ReviewEvent.RateMatn(GOOD))
        onEvent(ReviewEvent.Submit)
    }

    /** A hadith whose Matn is learned and whose next review is tomorrow: not due, but it can be reviewed now. */
    private fun upcoming(id: Long, matnReviewedDaysAgo: Int = 1, stability: Float = 4f) {
        reviews.mastery[id] = MasteryRecordEntity(id, stability, 0f, now - matnReviewedDaysAgo * DAY_MS, null, now + DAY_MS, 1)
    }

    // -- Review now --------------------------------------------------------------------------

    @Test
    fun reviewingNowBringsTheWeakestHadithsThatAreNotDueInSmallBatches() {
        // Ids 1..7 were last reviewed 1..7 days ago at the same stability: the longer ago, the lower the recall.
        (1L..7L).forEach { upcoming(it, matnReviewedDaysAgo = it.toInt()) }

        val session = reviewVm(ReviewMode.AHEAD).session()

        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), session.queue.map { it.hadithId })
        assertEquals(listOf(2), session.laterBatches.map { it.size })
        assertEquals(2, session.batchCount)
    }

    @Test
    fun reviewingNowLeavesOutDueHadithsAndOnesWhoseMatnWasNeverLearned() {
        core(1) // due: the daily review's business
        upcoming(2)
        reviews.mastery[3] = MasteryRecordEntity(3, 0f, 0f, null, null, now + DAY_MS, 0) // started, never reviewed

        val session = reviewVm(ReviewMode.AHEAD).session()

        assertEquals(listOf(2L), session.queue.map { it.hadithId })
    }

    @Test
    fun reviewingNowWithNothingToReviewIsAnEmptySession() {
        val session = reviewVm(ReviewMode.AHEAD).session()

        assertTrue(session.queue.isEmpty())
        assertTrue(session.isFinished)
    }

    @Test
    fun aReviewDoneNowPushesTheHadithsNextReviewFurther() {
        upcoming(1, matnReviewedDaysAgo = 3)
        val before = reviews.mastery.getValue(1).nextReviewDue
        val vm = reviewVm(ReviewMode.AHEAD)

        vm.reviewCurrent()

        assertTrue(reviews.mastery.getValue(1).nextReviewDue > before)
    }

    // -- Backlog batches ---------------------------------------------------------------------

    @Test
    fun twelveDueReviewsStartWithAFirstBatchOfFive() {
        (1L..12L).forEach(::core)

        val session = reviewVm(ReviewMode.DUE).session()

        assertEquals(5, session.queue.size)
        assertEquals(listOf(5, 2), session.laterBatches.map { it.size })
        assertEquals(1, session.batchNumber)
        assertEquals(3, session.batchCount)
        assertTrue(session.isBatched)
        assertFalse(session.isBatchBreak)
    }

    @Test
    fun finishingABatchPausesAndTheNextOneStartsOnRequest() {
        (1L..12L).forEach(::core)
        val vm = reviewVm(ReviewMode.DUE)
        val firstBatch = vm.session().queue.map { it.hadithId }

        repeat(5) { vm.reviewCurrent() }

        assertTrue("the session pauses between batches", vm.session().isBatchBreak)
        assertNull(vm.session().current)
        assertEquals(5, vm.session().totalReviewed)

        vm.onEvent(ReviewEvent.NextBatch)

        val second = vm.session()
        assertEquals(2, second.batchNumber)
        assertEquals(5, second.queue.size)
        assertEquals(0, second.index)
        assertEquals(CardStage.RECALL, second.stage)
        assertEquals(5, second.reviewedInEarlierBatches)
        assertTrue("a different set of hadiths", second.queue.map { it.hadithId }.none { it in firstBatch })
        assertFalse(second.isBatchBreak)
    }

    @Test
    fun allThreeBatchesAreReviewedAndThenTheSessionIsDone() = runTest {
        (1L..12L).forEach(::core)
        val vm = reviewVm(ReviewMode.DUE)

        repeat(5) { vm.reviewCurrent() }
        vm.onEvent(ReviewEvent.NextBatch)
        repeat(5) { vm.reviewCurrent() }
        vm.onEvent(ReviewEvent.NextBatch)
        assertEquals(2, vm.session().queue.size)
        repeat(2) { vm.reviewCurrent() }

        val done = vm.session()
        assertTrue(done.isFinished)
        assertFalse("nothing is waiting after the last batch", done.isBatchBreak)
        assertEquals(12, done.totalReviewed)
        assertTrue("every hadith was reviewed, so none is due any more", GetDueHadithsUseCase(hadiths, reviews, scheduler) { now }().isEmpty())
    }

    @Test
    fun theNextBatchDoesNotStartBeforeTheCurrentOneIsDone() {
        (1L..12L).forEach(::core)
        val vm = reviewVm(ReviewMode.DUE)

        vm.onEvent(ReviewEvent.NextBatch)

        assertEquals(1, vm.session().batchNumber)
        assertEquals(5, vm.session().queue.size)
    }

    @Test
    fun fiveDueReviewsOrFewerAreASingleBatchWithNoBreak() {
        (1L..5L).forEach(::core)
        val vm = reviewVm(ReviewMode.DUE)

        assertEquals(1, vm.session().batchCount)
        assertFalse(vm.session().isBatched)
        assertTrue(vm.session().laterBatches.isEmpty())

        repeat(5) { vm.reviewCurrent() }

        assertTrue(vm.session().isFinished)
        assertFalse(vm.session().isBatchBreak)
        assertEquals(5, vm.session().totalReviewed)
    }

    @Test
    fun onlyTheDailyReviewIsBatched() {
        (1L..7L).forEach(::tree)

        val session = reviewVm(ReviewMode.NARRATOR, targetId = 1).session()

        assertEquals(7, session.queue.size)
        assertEquals(1, session.batchCount)
    }

    // -- Hints -------------------------------------------------------------------------------

    @Test
    fun hintsGoFromTheCompanionToTheTakhrijAndThenStop() {
        core(1)
        val vm = reviewVm(ReviewMode.DUE)
        assertTrue(vm.session().canHint)
        assertTrue(vm.session().shownHints.isEmpty())

        vm.onEvent(ReviewEvent.Hint)
        assertEquals(listOf(HintKind.COMPANION), vm.session().shownHints)
        assertTrue(vm.session().canHint)

        vm.onEvent(ReviewEvent.Hint)
        assertEquals(listOf(HintKind.COMPANION, HintKind.TAKHRIJ), vm.session().shownHints)
        assertFalse("nothing is left to give", vm.session().canHint)

        vm.onEvent(ReviewEvent.Hint)
        assertEquals(2, vm.session().hintsUsed)
    }

    @Test
    fun aHintNeverTurnsTheCardOver() {
        core(1)
        val vm = reviewVm(ReviewMode.DUE)

        vm.onEvent(ReviewEvent.Hint)
        vm.onEvent(ReviewEvent.Hint)

        assertEquals(CardStage.RECALL, vm.session().stage)
        assertNull(vm.session().matnRating)
    }

    @Test
    fun onceTheMatnIsRevealedThereIsNoHintToAskFor() {
        core(1)
        val vm = reviewVm(ReviewMode.DUE)
        vm.onEvent(ReviewEvent.Hint)

        vm.onEvent(ReviewEvent.Reveal)

        assertEquals(CardStage.MATN, vm.session().stage)
        assertFalse(vm.session().canHint)
        vm.onEvent(ReviewEvent.Hint)
        assertEquals("a hint after the reveal is ignored", 1, vm.session().hintsUsed)
    }

    @Test
    fun hintsStartOverOnTheNextCard() {
        core(1)
        core(2)
        val vm = reviewVm(ReviewMode.DUE)
        vm.onEvent(ReviewEvent.Hint)
        vm.onEvent(ReviewEvent.Hint)

        vm.onEvent(ReviewEvent.Reveal)
        vm.reviewCurrent()

        assertEquals(1, vm.session().index)
        assertEquals(0, vm.session().hintsUsed)
        assertTrue(vm.session().canHint)
    }

    @Test
    fun aCardThatNamesNoCompanionStartsWithTheTakhrij() {
        // Hadith 20 has no chain, hence no Companion to name.
        core(20)
        val vm = reviewVm(ReviewMode.DUE)

        vm.onEvent(ReviewEvent.Hint)

        assertEquals(listOf(HintKind.TAKHRIJ), vm.session().shownHints)
        assertFalse(vm.session().canHint)
    }

    @Test
    fun thereAreNoHintsOnTheChainLayerOrForAHadithBeingLearnedForTheFirstTime() {
        tree(1)
        assertFalse(reviewVm(ReviewMode.DUE).session().canHint)

        // A new hadith is shown with its matn from the start: nothing is being recalled.
        assertFalse(reviewVm(ReviewMode.NEW, targetId = 2).session().canHint)
    }

    // -- The advanced student ----------------------------------------------------------------

    @Test
    fun theAdvancedChoiceOpensTheChainOnACoreCardAndTakingItBackClosesItAgain() {
        core(1)
        val choice = InMemoryStudyPreferences()
        val advanced = DualDecayScheduler(unlockAllLayers = choice.unlockAllLayers)
        assertEquals(LAYER_1_CORE, reviewVm(ReviewMode.DUE, with = advanced).session().current!!.activeLayer)

        choice.setUnlockAllLayers(true)
        val open = reviewVm(ReviewMode.DUE, with = advanced).session()

        assertEquals(LAYER_3_TRANSMISSION_TERMS, open.current!!.activeLayer)
        assertEquals(CardStage.SANAD, open.current!!.finalStage)
        assertFalse("there is nothing to hint at once the whole card is open", open.canHint)

        choice.setUnlockAllLayers(false)
        assertEquals(LAYER_1_CORE, reviewVm(ReviewMode.DUE, with = advanced).session().current!!.activeLayer)
    }

    @Test
    fun theHomeSuggestsASanadDrillAsSoonAsTheChoiceIsMadeAndNeverReadsStaleLayers() = runTest {
        core(1)
        val choice = InMemoryStudyPreferences()
        val advanced = DualDecayScheduler(unlockAllLayers = choice.unlockAllLayers)
        val dashboard = ObserveStudyDashboardUseCase(hadiths, reviews, advanced, { ZoneOffset.UTC }, { now })
        val seen = mutableListOf<Long?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { dashboard().collect { seen += it.drillSuggestionId } }

        choice.setUnlockAllLayers(true)
        job.cancel()

        assertEquals("no drill while the chain is locked, then one without any review in between", listOf(null, 1L), seen)
    }

    @Test
    fun theStudyPageFollowsTheChoiceToo() = runTest {
        core(1)
        val choice = InMemoryStudyPreferences()
        val advanced = DualDecayScheduler(unlockAllLayers = choice.unlockAllLayers)
        val study = GetHadithStudyUseCase(hadiths, reviews, advanced) { now }
        val layers = mutableListOf<Any?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { study(1).collect { layers += it?.layer?.layer } }

        choice.setUnlockAllLayers(true)
        job.cancel()

        assertEquals(listOf(LAYER_1_CORE, LAYER_3_TRANSMISSION_TERMS), layers)
    }

    // -- Pacing: the third new hadith of a day -----------------------------------------------

    private class MemorySettings(override var matnScale: Float = MatnScale.DEFAULT) : ReaderSettings

    private val studyPreferences = InMemoryStudyPreferences()
    private val pacing = DailyPacing(studyPreferences, { ZoneOffset.UTC }, { now })

    /** A reader for hadith [id], followed for the rest of the test the way a screen would follow it. */
    private fun TestScope.studyVm(id: Long): HadithStudyViewModel {
        val vm = HadithStudyViewModel(
            id,
            GetHadithStudyUseCase(hadiths, reviews, scheduler) { now },
            StartMemorizingUseCase(hadiths, reviews, pacing) { now },
            MemorySettings(),
            pacing,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    private fun HadithStudyViewModel.ui(): HadithStudyUi = (state.value as UiState.Success).data

    @Test
    fun theFirstTwoNewHadithsOfTheDayStartWithoutAnyQuestion() = runTest {
        val opened = mutableListOf<Long>()

        studyVm(1).startMemorizing { opened += it }
        studyVm(2).startMemorizing { opened += it }

        assertEquals(listOf(1L, 2L), opened)
        assertEquals(2, pacing.startedToday())
        assertFalse(studyVm(3).ui().pacingPrompt)
    }

    @Test
    fun theThirdAsksFirstAndStartsNothingUntilTheUserAnswers() = runTest {
        studyVm(1).startMemorizing {}
        studyVm(2).startMemorizing {}
        val third = studyVm(3)
        var opened: Long? = null

        third.startMemorizing { opened = it }

        assertTrue(third.ui().pacingPrompt)
        assertNull("nothing opens while the question is open", opened)
        assertNull("and nothing is added to the wird", reviews.mastery[3L])
        assertEquals(2, pacing.startedToday())
    }

    @Test
    fun goingOnAfterTheQuestionStartsTheHadithAndCountsIt() = runTest {
        studyVm(1).startMemorizing {}
        studyVm(2).startMemorizing {}
        val third = studyVm(3)
        var opened: Long? = null
        third.startMemorizing { }

        third.confirmPacing { opened = it }

        assertEquals(3L, opened)
        assertNotNull(reviews.mastery[3L])
        assertFalse(third.ui().pacingPrompt)
        assertEquals(3, pacing.startedToday())
        assertTrue("and the fourth is asked about as well", studyVm(4).also { it.startMemorizing {} }.ui().pacingPrompt)
    }

    @Test
    fun savingItForTomorrowLeavesTheHadithUntouched() = runTest {
        studyVm(1).startMemorizing {}
        studyVm(2).startMemorizing {}
        val third = studyVm(3)
        var opened = 0
        third.startMemorizing { opened++ }

        third.dismissPacing()

        assertFalse(third.ui().pacingPrompt)
        assertEquals(0, opened)
        assertNull(reviews.mastery[3L])
        assertEquals(2, pacing.startedToday())
    }

    @Test
    fun theNextDayStartsFreshWithNoQuestion() = runTest {
        studyVm(1).startMemorizing {}
        studyVm(2).startMemorizing {}
        now += DAY_MS
        var opened: Long? = null

        studyVm(3).startMemorizing { opened = it }

        assertEquals(3L, opened)
        assertEquals(1, pacing.startedToday())
    }

    @Test
    fun aHadithAlreadyInStudyIsNotAskedAboutAgain() = runTest {
        studyVm(1).startMemorizing {}
        studyVm(2).startMemorizing {}
        // The reader of a hadith already in the wird offers a review, not "start"; the use case also leaves it alone.
        val again = studyVm(1)
        assertTrue(again.ui().study.isInStudy)
        assertEquals(2, pacing.startedToday())
    }
}

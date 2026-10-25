package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeNarratorRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.ReviewRating.AGAIN
import com.mutqan.hadith.domain.model.ReviewRating.EASY
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.model.ReviewRating.HARD
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.CheckSanadDrillAccessUseCase
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import com.mutqan.hadith.domain.usecase.SubmitLinkDrillUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.home.HomeViewModel
import com.mutqan.hadith.ui.missinglink.MissingLinkEvent
import com.mutqan.hadith.ui.missinglink.MissingLinkViewModel
import com.mutqan.hadith.ui.review.CardStage
import com.mutqan.hadith.ui.review.ReviewEvent
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewSession
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderEvent
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderUi
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {

    private val now = 1_700_000_000_000L
    private var clock = now
    private val scheduler = DualDecayScheduler()

    private val tabii = listOf(narrator(1, "الحميدي"), narrator(2, "سفيان"), narrator(3, "يحيى"))
    private val sahabi = narrator(4, "عمر", tier = "صحابي")
    private val chain = tabii + sahabi
    private val distractors = listOf(
        narrator(20, "مالك"), narrator(21, "نافع"), narrator(22, "الزهري"),
        narrator(30, "أنس", tier = "صحابي"), narrator(31, "أبو هريرة", tier = "صحابي"), narrator(32, "ابن عباس", tier = "صحابي"),
    )

    private fun library(count: Int = 3) = (1L..count).map { hadith(it, chain) }

    private val hadiths = FakeHadithRepository(library(7))
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val narrators = FakeNarratorRepository(chain + distractors)

    private val submitReview = SubmitReviewUseCase(hadiths, reviews, scheduler) { clock }
    private val getDue = GetDueHadithsUseCase(hadiths, reviews, scheduler) { clock }
    private val getUnseen = GetUnseenHadithsUseCase(hadiths)
    private val getHeatmap = GetNarratorChainHeatmapUseCase(hadiths, reviews)
    private val getLayers = GetLayerStatusesUseCase(reviews, scheduler)
    private val drillAccess = CheckSanadDrillAccessUseCase(reviews, scheduler)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun due(id: Long, stability: Float = 2f, overdueDays: Int = 1) {
        val at = now - 10 * DAY_MS
        reviews.mastery[id] = MasteryRecordEntity(id, stability, stability, at, at, now - overdueDays * DAY_MS, 2)
    }

    private fun reviewVm(mode: ReviewMode, hadithId: Long? = null, repo: HadithRepository = hadiths) =
        ReviewViewModel(mode, hadithId, getDue, getUnseen, repo, submitReview, getHeatmap, getLayers)

    private fun ReviewViewModel.session(): ReviewSession = (state.value as UiState.Success).data

    // -- Home ------------------------------------------------------------------------------

    @Test
    fun home_emitsTheDashboardAndRefreshesWhenAskedTo() = runTest {
        due(2)
        var observed = 0
        val counting = object : HadithRepository by hadiths {
            override fun observeAll(): Flow<List<HadithWithFullSanad>> =
                flow { observed++; emit(hadiths.getUnseen() + hadiths.getWithSanad(setOf(2L))) }
        }
        val vm = HomeViewModel(ObserveStudyDashboardUseCase(counting, reviews, scheduler) { clock })
        backgroundScope.launch { vm.state.collect {} }

        val state = vm.state.first { it !is UiState.Loading } as UiState.Success
        assertEquals(1, state.data.dueCount)
        assertEquals(6, state.data.newCount)
        assertEquals(1, observed)

        vm.refresh()
        assertEquals(2, observed)
        assertTrue(vm.state.value is UiState.Success)
    }

    @Test
    fun home_surfacesFailuresAsAnErrorState() = runTest {
        val broken = object : HadithRepository by hadiths {
            override fun observeAll(): Flow<List<HadithWithFullSanad>> = flow { throw IllegalStateException("db closed") }
        }
        val vm = HomeViewModel(ObserveStudyDashboardUseCase(broken, reviews, scheduler) { clock })
        backgroundScope.launch { vm.state.collect {} }

        val state = vm.state.first { it !is UiState.Loading }
        assertEquals(UiState.Error("db closed"), state)
    }

    // -- Review ----------------------------------------------------------------------------

    @Test
    fun review_dueSessionRunsThroughTheQueueAndFinishes() = runTest {
        due(1, overdueDays = 1)
        due(2, overdueDays = 5)
        val vm = reviewVm(ReviewMode.DUE)

        var s = vm.session()
        assertEquals(2, s.queue.size)
        assertEquals(CardStage.RECALL, s.stage)
        assertFalse(s.canSubmit)
        val firstId = s.current!!.hadithId

        vm.onEvent(ReviewEvent.Reveal)
        assertEquals(CardStage.MATN, vm.session().stage)
        vm.onEvent(ReviewEvent.Reveal)
        vm.onEvent(ReviewEvent.Reveal) // clamps at SANAD
        assertEquals(CardStage.SANAD, vm.session().stage)

        vm.onEvent(ReviewEvent.RateMatn(GOOD))
        assertFalse("both ratings are required", vm.session().canSubmit)
        vm.onEvent(ReviewEvent.RateSanad(EASY))
        assertTrue(vm.session().canSubmit)
        vm.onEvent(ReviewEvent.Submit)

        s = vm.session()
        assertEquals(1, s.index)
        assertNotEquals(firstId, s.current!!.hadithId)
        assertEquals(CardStage.RECALL, s.stage) // fresh card, ratings cleared
        assertNull(s.matnRating)
        assertNull(s.sanadRating)
        assertEquals(clock, reviews.mastery[firstId]!!.matnLastReviewedAt)
        assertTrue(reviews.mastery[firstId]!!.nextReviewDue > clock)

        vm.onEvent(ReviewEvent.RateMatn(HARD))
        vm.onEvent(ReviewEvent.RateSanad(HARD))
        vm.onEvent(ReviewEvent.Submit)
        s = vm.session()
        assertTrue(s.isFinished)
        assertNull(s.current)
        assertEquals(2, s.index)
    }

    @Test
    fun review_markedNarratorsReachTheLedgerAndResetPerCard() = runTest {
        due(1)
        due(2)
        val vm = reviewVm(ReviewMode.DUE)
        val first = vm.session().current!!.hadithId

        vm.onEvent(ReviewEvent.ToggleMissed(3))
        vm.onEvent(ReviewEvent.ToggleMissed(2))
        vm.onEvent(ReviewEvent.ToggleMissed(2)) // toggled back off
        assertEquals(setOf(3L), vm.session().missedNarratorIds)
        vm.onEvent(ReviewEvent.RateMatn(GOOD))
        vm.onEvent(ReviewEvent.RateSanad(HARD))
        vm.onEvent(ReviewEvent.Submit)

        val failed = reviews.events.filter { it.hadithId == first && !it.succeeded }
        assertEquals(listOf(3L), failed.map { it.narratorId })
        assertTrue(vm.session().missedNarratorIds.isEmpty())
    }

    @Test
    fun review_newSessionStartsOnTheMatnAndOffersAtMostFiveHadiths() = runTest {
        val vm = reviewVm(ReviewMode.NEW)

        val s = vm.session()
        assertEquals(ReviewViewModel.NEW_BATCH_SIZE, s.queue.size)
        assertEquals(listOf<Long>(1, 2, 3, 4, 5), s.queue.map { it.hadithId })
        assertEquals(CardStage.MATN, s.stage)
    }

    @Test
    fun review_singleSessionLoadsThatHadith_andAnUnknownOneIsAnError() = runTest {
        val single = reviewVm(ReviewMode.SINGLE, hadithId = 4)
        assertEquals(listOf(4L), single.session().queue.map { it.hadithId })
        assertEquals(4, single.session().current!!.links.size)
        assertEquals(4, single.session().current!!.hadithNumber)

        val missing = reviewVm(ReviewMode.SINGLE, hadithId = 99)
        assertTrue(missing.state.value is UiState.Error)
    }

    @Test
    fun review_nothingDueGivesAnEmptyFinishedSession() = runTest {
        val s = reviewVm(ReviewMode.DUE).session()
        assertTrue(s.queue.isEmpty() && s.isFinished)
    }

    @Test
    fun review_aFailedSubmitKeepsTheCardAndReportsTheError() = runTest {
        due(1)
        val throwing = object : HadithRepository by hadiths {
            override suspend fun getWithSanad(id: Long): HadithWithFullSanad? = throw IllegalStateException("disk full")
        }
        val vm = ReviewViewModel(
            ReviewMode.DUE, null, getDue, getUnseen, hadiths,
            SubmitReviewUseCase(throwing, reviews, scheduler) { clock },
            getHeatmap,
            getLayers,
        )
        vm.onEvent(ReviewEvent.RateMatn(GOOD))
        vm.onEvent(ReviewEvent.RateSanad(GOOD))
        vm.onEvent(ReviewEvent.Submit)

        val s = vm.session()
        assertEquals(0, s.index)
        assertFalse(s.isSubmitting)
        assertEquals("disk full", s.submitError)
        assertEquals(GOOD, s.matnRating) // the user's input survives so they can retry
        assertNotNull(s.current)

        vm.onEvent(ReviewEvent.DismissError)
        assertNull(vm.session().submitError)
    }

    // -- Sanad builder ---------------------------------------------------------------------

    /** The Sanad drills are for a hadith whose chain is open: hadith 1 is in study, with its Sanad tree opened. */
    private fun openTreeOfHadith1() {
        reviews.mastery[1] = MasteryRecordEntity(1, 20f, 2f, now - DAY_MS, now - 5 * DAY_MS, now - DAY_MS, 4)
    }

    private fun builderVm(): SanadBuilderViewModel {
        openTreeOfHadith1()
        return SanadBuilderViewModel(1, hadiths, submitReview, drillAccess, Random(5))
    }

    private fun SanadBuilderViewModel.ui(): SanadBuilderUi = (state.value as UiState.Success).data

    @Test
    fun builder_buildingTheChainAndSubmittingRecordsASanadOnlyReview() = runTest {
        val vm = builderVm()
        assertEquals(4, vm.ui().builder.tray.size)
        assertFalse(vm.ui().builder.isComplete)

        vm.onEvent(SanadBuilderEvent.Submit) // too early: ignored
        assertNull(vm.ui().result)

        (0..3).forEach { vm.onEvent(SanadBuilderEvent.PlaceChip(it)) }
        assertTrue(vm.ui().builder.isAllCorrect)
        assertEquals(4, vm.ui().feedbackTick)
        assertTrue(vm.ui().lastFeedback!!.correct)

        vm.onEvent(SanadBuilderEvent.Submit)

        val result = vm.ui().result!!
        assertEquals(GOOD, result.rating)
        assertTrue(result.misplacedNames.isEmpty())
        val record = reviews.mastery[1]!!
        assertEquals(now - DAY_MS, record.matnLastReviewedAt) // the Matn was only the anchor: its clock was not touched
        assertEquals(clock, record.sanadLastReviewedAt)
        assertEquals(4, reviews.events.count { it.succeeded })
    }

    @Test
    fun builder_wrongPlacementsFeedTheLedgerByName() = runTest {
        val vm = builderVm()

        vm.onEvent(SanadBuilderEvent.PlaceChip(3)) // the Sahabi into slot 0
        assertFalse(vm.ui().lastFeedback!!.correct)
        assertEquals(0, vm.ui().lastFeedback!!.slotIndex)
        assertEquals(1, vm.ui().feedbackTick)

        vm.onEvent(SanadBuilderEvent.RemoveFromSlot(0))
        (0..3).forEach { vm.onEvent(SanadBuilderEvent.PlaceChip(it)) }
        vm.onEvent(SanadBuilderEvent.Submit)

        val result = vm.ui().result!!
        assertEquals(HARD, result.rating)
        assertEquals(listOf("عمر"), result.misplacedNames)
        assertEquals(listOf(4L), reviews.events.filter { !it.succeeded }.map { it.narratorId })
        // Once submitted the board is frozen.
        vm.onEvent(SanadBuilderEvent.RemoveFromSlot(0))
        assertTrue(vm.ui().builder.isAllCorrect)
    }

    @Test
    fun builder_revealAllowsSubmittingAfterGivingUp_andRetryStartsOver() = runTest {
        val vm = builderVm()

        vm.onEvent(SanadBuilderEvent.Reveal)
        assertTrue(vm.ui().builder.isAllCorrect)
        vm.onEvent(SanadBuilderEvent.Submit)
        assertEquals(AGAIN, vm.ui().result!!.rating)
        assertEquals(4, reviews.events.count { !it.succeeded })

        vm.onEvent(SanadBuilderEvent.Retry)
        assertNull(vm.ui().result)
        assertEquals(4, vm.ui().builder.tray.size)
        assertTrue(vm.ui().builder.misplacedNarratorIds.isEmpty())
    }

    @Test
    fun builder_unknownHadithIsAnError() = runTest {
        val vm = SanadBuilderViewModel(99, hadiths, submitReview, drillAccess)
        assertTrue(vm.state.value is UiState.Error)
    }

    // -- Missing link ----------------------------------------------------------------------

    private fun linkVm(seed: Int = 11): MissingLinkViewModel {
        openTreeOfHadith1()
        return MissingLinkViewModel(
            1, hadiths, narrators, SubmitLinkDrillUseCase(hadiths, reviews, scheduler) { clock }, drillAccess, Random(seed),
        )
    }

    private fun MissingLinkViewModel.ui() = (state.value as UiState.Success).data

    @Test
    fun missingLink_correctAnswerGivesInstantFeedbackAndUpdatesTheLink() = runTest {
        val vm = linkVm()
        val round = vm.ui().round
        assertEquals(4, round.options.size)
        assertFalse(vm.ui().isAnswered)

        vm.onEvent(MissingLinkEvent.Choose(round.answer.id))

        val ui = vm.ui()
        assertEquals(true, ui.isCorrect)
        assertEquals(1, ui.roundsPlayed)
        assertEquals(1, ui.correctCount)
        assertEquals(1f, ui.outcome!!.retentionRate, 0f)
        assertEquals(round.answer.id, reviews.events.single().narratorId)
    }

    @Test
    fun missingLink_wrongAnswerIsRecordedAgainstTheHiddenNarrator() = runTest {
        due(1, stability = 10f)
        val before = reviews.mastery[1]!!
        val vm = linkVm()
        val round = vm.ui().round
        val wrong = round.options.first { it.id != round.answer.id }

        vm.onEvent(MissingLinkEvent.Choose(wrong.id))
        vm.onEvent(MissingLinkEvent.Choose(round.answer.id)) // a second tap is ignored

        val ui = vm.ui()
        assertEquals(false, ui.isCorrect)
        assertEquals(wrong.id, ui.chosenId)
        assertEquals(1, ui.roundsPlayed)
        assertEquals(0, ui.correctCount)
        assertEquals(1, ui.outcome!!.errorCount)
        assertEquals(0f, ui.outcome!!.retentionRate, 0f)
        val event = reviews.events.single()
        assertEquals(round.answer.id, event.narratorId)
        assertFalse(event.succeeded)
        assertTrue(reviews.mastery[1]!!.sanadStability < before.sanadStability)
    }

    @Test
    fun missingLink_nextAnswersWithAFreshRoundOnADifferentNode() = runTest {
        val vm = linkVm()
        val first = vm.ui().round

        vm.onEvent(MissingLinkEvent.Next) // not answered yet: ignored
        assertEquals(first, vm.ui().round)

        vm.onEvent(MissingLinkEvent.Choose(first.answer.id))
        vm.onEvent(MissingLinkEvent.Next)

        val ui = vm.ui()
        assertNotEquals(first.maskedIndex, ui.round.maskedIndex)
        assertFalse(ui.isAnswered)
        assertNull(ui.outcome)
        assertEquals(1, ui.roundsPlayed) // the session tally carries over
    }

    @Test
    fun missingLink_ignoresAnOptionThatWasNotOffered() = runTest {
        val vm = linkVm()
        vm.onEvent(MissingLinkEvent.Choose(9_999))
        assertFalse(vm.ui().isAnswered)
        assertTrue(reviews.events.isEmpty())
    }

    @Test
    fun missingLink_unknownHadithIsAnError() = runTest {
        val vm = MissingLinkViewModel(99, hadiths, narrators, SubmitLinkDrillUseCase(hadiths, reviews, scheduler), drillAccess)
        assertTrue(vm.state.value is UiState.Error)
    }
}

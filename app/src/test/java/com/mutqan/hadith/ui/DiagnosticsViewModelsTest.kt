package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.ChainHealth
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.details.HadithDetailsUi
import com.mutqan.hadith.ui.details.HadithDetailsViewModel
import com.mutqan.hadith.ui.graph.NarratorGraphEvent
import com.mutqan.hadith.ui.graph.NarratorGraphUi
import com.mutqan.hadith.ui.graph.NarratorGraphViewModel
import com.mutqan.hadith.ui.review.CardStage
import com.mutqan.hadith.ui.review.ReviewEvent
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewSession
import com.mutqan.hadith.ui.review.ReviewViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** ViewModels of the diagnostics features: the narrator graph, the Hadith details and the narrator review. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelsTest {

    private val scheduler = DualDecayScheduler()

    private val n1 = narrator(1, "الحميدي", "شيخ المصنف")
    private val n2 = narrator(2, "سفيان", "تابع التابعين")
    private val n3 = narrator(3, "الزهري", "تابعي").copy(isPivot = true)
    private val n4 = narrator(4, "أبو هريرة", "صحابي")
    private val n5 = narrator(5, "مسدد", "شيخ المصنف")

    //   h1: n4 -> n3 -> n2 -> n1      h2: n4 -> n3 -> n2 -> n5      h3: n4 -> n5 (does not touch n3)
    private val library = listOf(hadith(1, listOf(n1, n2, n3, n4)), hadith(2, listOf(n5, n2, n3, n4)), hadith(3, listOf(n5, n4)))

    private val hadiths = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun event(hadithId: Long, narratorId: Long, succeeded: Boolean) =
        NarratorReviewEventEntity(hadithId = hadithId, narratorId = narratorId, reviewedAt = 1L, succeeded = succeeded)

    private fun studied(vararg ids: Long) = ids.forEach { reviews.mastery[it] = MasteryRecordEntity(it, 1f, 1f, 1L, 1L, 2L, 1) }

    /** Hadiths whose Sanad tree is open, and ones well into the last layer, where the transmission terms are asked for. */
    private fun sanadOpen(vararg ids: Long) = studied(*ids)
    private fun mastered(vararg ids: Long) = ids.forEach { reviews.mastery[it] = MasteryRecordEntity(it, 60f, 60f, 1L, 1L, 2L, 5) }

    // -- Narrator graph --------------------------------------------------------------------

    private fun graphVm(
        hadithRepo: HadithRepository = hadiths,
        reviewRepo: ReviewRepository = reviews,
    ) = NarratorGraphViewModel(GetNarratorGraphUseCase(hadithRepo, reviewRepo), GetHadithsByNarratorUseCase(hadithRepo, reviewRepo))

    private fun NarratorGraphViewModel.ui(): NarratorGraphUi = (state.value as UiState.Success).data

    @Test
    fun graph_loadsTheNetworkTogetherWithItsLayout() = runTest {
        val ui = graphVm().ui()

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ui.graph.nodes.map { it.id })
        assertEquals(ui.graph.nodes.map { it.id }.toSet(), ui.layout.placements.keys)
        assertTrue(ui.layout.width > 0 && ui.layout.height > 0)
        assertFalse(ui.studiedOnly)
        assertNull(ui.selectedId)
        assertNull(ui.profile)
    }

    @Test
    fun graph_selectingANodeHighlightsItAndLoadsItsHadiths() = runTest {
        reviews.events += listOf(event(1, 3, false), event(1, 3, true), event(2, 3, true))
        studied(1)
        val vm = graphVm()

        vm.onEvent(NarratorGraphEvent.SelectNode(3))

        val ui = vm.ui()
        assertEquals(3L, ui.selectedId)
        val profile = ui.profile!!
        assertFalse(profile.isLoading)
        assertEquals("الزهري", profile.node.name)
        assertTrue(profile.node.isPivot)
        assertEquals(listOf(1L, 2L), profile.hadiths.map { it.hadithId }) // h3 does not pass through n3
        val first = profile.hadiths.first()
        assertEquals(1, first.errorCount)
        assertEquals(1, first.successCount)
        assertTrue(first.isStudied)
        assertFalse(profile.hadiths.last().isStudied)
    }

    @Test
    fun graph_aTapOnAnUnknownNodeIsIgnored() = runTest {
        val vm = graphVm()
        vm.onEvent(NarratorGraphEvent.SelectNode(999))
        assertNull(vm.ui().selectedId)
        assertNull(vm.ui().profile)
    }

    @Test
    fun graph_dismissingTheSheetKeepsTheHighlight_clearingDropsBoth() = runTest {
        val vm = graphVm()
        vm.onEvent(NarratorGraphEvent.SelectNode(2))

        vm.onEvent(NarratorGraphEvent.DismissSheet)
        assertEquals(2L, vm.ui().selectedId)
        assertNull(vm.ui().profile)

        vm.onEvent(NarratorGraphEvent.SelectNode(4))
        assertNotNull(vm.ui().profile)
        vm.onEvent(NarratorGraphEvent.ClearSelection)
        assertNull(vm.ui().selectedId)
        assertNull(vm.ui().profile)
    }

    @Test
    fun graph_studiedOnlyShrinksTheNetworkToTheHadithsTheUserHasStarted() = runTest {
        studied(3)
        val vm = graphVm()
        assertEquals(5, vm.ui().graph.nodes.size)

        vm.onEvent(NarratorGraphEvent.SetStudiedOnly(true))

        val ui = vm.ui()
        assertTrue(ui.studiedOnly)
        assertEquals(listOf(4L, 5L), ui.graph.nodes.map { it.id })
        assertEquals(ui.graph.nodes.map { it.id }.toSet(), ui.layout.placements.keys)

        vm.onEvent(NarratorGraphEvent.SetStudiedOnly(false))
        assertFalse(vm.ui().studiedOnly)
        assertEquals(5, vm.ui().graph.nodes.size)
    }

    @Test
    fun graph_aSelectionThatLeavesTheFilteredNetworkIsDropped_aSurvivingOneStays() = runTest {
        studied(3)
        val vm = graphVm()

        vm.onEvent(NarratorGraphEvent.SelectNode(4))
        vm.onEvent(NarratorGraphEvent.SetStudiedOnly(true))
        assertEquals(4L, vm.ui().selectedId)
        assertEquals(4L, vm.ui().profile!!.node.id)

        vm.onEvent(NarratorGraphEvent.SetStudiedOnly(false))
        vm.onEvent(NarratorGraphEvent.SelectNode(1))
        vm.onEvent(NarratorGraphEvent.SetStudiedOnly(true))
        assertNull(vm.ui().selectedId)
        assertNull(vm.ui().profile)
    }

    @Test
    fun graph_reloadingRefreshesTheHealthColoursWithoutLosingTheView() = runTest {
        val vm = graphVm()
        vm.onEvent(NarratorGraphEvent.SelectNode(3))
        assertEquals(0, vm.ui().graph.node(3)!!.attempts)

        reviews.events += listOf(event(1, 3, false), event(1, 3, true))
        vm.load()

        assertEquals(2, vm.ui().graph.node(3)!!.attempts)
        assertEquals(3L, vm.ui().selectedId)
        assertEquals(1, vm.ui().profile!!.node.errorCount) // the open profile shows the fresh counts
    }

    @Test
    fun graph_aFailedLoadIsAnErrorState_andRetryRecovers() = runTest {
        var broken = true
        val flaky = object : HadithRepository by hadiths {
            override suspend fun getAll(): List<HadithWithFullSanad> = if (broken) error("db closed") else hadiths.getAll()
        }
        val vm = graphVm(hadithRepo = flaky)
        assertEquals(UiState.Error("db closed"), vm.state.value)

        broken = false
        vm.load()
        assertTrue(vm.state.value is UiState.Success)
        assertEquals(5, vm.ui().graph.nodes.size)
    }

    @Test
    fun graph_aFailingHadithListLeavesAnEmptyProfileInsteadOfSpinningForever() = runTest {
        val flaky = object : HadithRepository by hadiths {
            override suspend fun getThroughNarrator(narratorId: Long): List<HadithWithFullSanad> = error("no disk")
        }
        val vm = graphVm(hadithRepo = flaky)

        vm.onEvent(NarratorGraphEvent.SelectNode(3))

        val profile = vm.ui().profile!!
        assertFalse(profile.isLoading)
        assertTrue(profile.hadiths.isEmpty())
    }

    @Test
    fun graph_aSlowHadithListDoesNotOverwriteTheNarratorTappedNext() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slowForTwo = object : HadithRepository by hadiths {
            override suspend fun getThroughNarrator(narratorId: Long): List<HadithWithFullSanad> {
                if (narratorId == 2L) gate.await()
                return hadiths.getThroughNarrator(narratorId)
            }
        }
        val vm = graphVm(hadithRepo = slowForTwo)

        vm.onEvent(NarratorGraphEvent.SelectNode(2)) // suspends on the gate
        assertTrue(vm.ui().profile!!.isLoading)
        vm.onEvent(NarratorGraphEvent.SelectNode(4))
        assertFalse(vm.ui().profile!!.isLoading)

        gate.complete(Unit)

        assertEquals(4L, vm.ui().profile!!.node.id)
        assertEquals(4L, vm.ui().selectedId)
    }

    // -- Hadith details --------------------------------------------------------------------

    private fun detailsVm(id: Long, hadithRepo: HadithRepository = hadiths) =
        HadithDetailsViewModel(id, hadithRepo, GetNarratorChainHeatmapUseCase(hadithRepo, reviews))

    private fun HadithDetailsViewModel.ui(): HadithDetailsUi = (state.value as UiState.Success).data

    @Test
    fun details_showTheHadithAndTheHealthOfItsChain() = runTest {
        reviews.events += listOf(event(1, 3, false), event(1, 3, false), event(1, 3, true), event(1, 4, true))
        val ui = detailsVm(1).ui()

        assertEquals(1L, ui.hadithId)
        assertEquals(1, ui.hadithNumber)
        assertEquals("متن 1", ui.matn)
        assertEquals(listOf(1L, 2L, 3L, 4L), ui.heatmap.nodes.map { it.performance.narratorId })
        val pivot = ui.heatmap.nodes.first { it.performance.narratorId == 3L }
        assertEquals(2, pivot.performance.errorCount)
        assertEquals(ChainHealth.FRAGILE, pivot.health)
        assertEquals(ChainHealth.UNTESTED, ui.heatmap.nodes.first { it.performance.narratorId == 1L }.health)
    }

    @Test
    fun details_anUnknownHadithIsAnError() = runTest {
        assertTrue(detailsVm(99).state.value is UiState.Error)
    }

    @Test
    fun details_retryAfterAFailureLoadsTheHadith() = runTest {
        var broken = true
        val flaky = object : HadithRepository by hadiths {
            override suspend fun getWithSanad(id: Long): HadithWithFullSanad? = if (broken) error("offline") else hadiths.getWithSanad(id)
        }
        val vm = detailsVm(2, flaky)
        assertEquals(UiState.Error("offline"), vm.state.value)

        broken = false
        vm.load()
        assertEquals(2, vm.ui().hadithNumber)
    }

    // -- Review of a narrator's hadiths ----------------------------------------------------

    private fun reviewVm(mode: ReviewMode, targetId: Long? = null, hadithRepo: HadithRepository = hadiths, heatmap: GetNarratorChainHeatmapUseCase? = null) =
        ReviewViewModel(
            mode, targetId,
            GetDueHadithsUseCase(hadithRepo, reviews, scheduler),
            GetUnseenHadithsUseCase(hadithRepo),
            hadithRepo,
            SubmitReviewUseCase(hadithRepo, reviews, scheduler),
            heatmap ?: GetNarratorChainHeatmapUseCase(hadithRepo, reviews),
            GetLayerStatusesUseCase(reviews, scheduler),
        )

    private fun ReviewViewModel.session(): ReviewSession = (state.value as UiState.Success).data

    @Test
    fun narratorReview_queuesEveryHadithThroughTheNarrator() = runTest {
        sanadOpen(1, 2)
        val s = reviewVm(ReviewMode.NARRATOR, targetId = 3).session()

        assertEquals(listOf(1L, 2L), s.queue.map { it.hadithId })
        assertEquals(CardStage.RECALL, s.stage)
        assertFalse(s.isFinished)
        assertEquals(0, s.lockedSkipped)
    }

    @Test
    fun narratorReview_leavesOutTheHadithsWhoseSanadTreeIsClosed() = runTest {
        sanadOpen(2) // hadith 1 is not studied yet: layer 1, no chain to review

        val s = reviewVm(ReviewMode.NARRATOR, targetId = 3).session()

        assertEquals(listOf(2L), s.queue.map { it.hadithId })
        assertEquals(1, s.lockedSkipped)
    }

    @Test
    fun narratorReview_withEveryTreeClosedIsAnEmptySessionThatSaysWhy() = runTest {
        val s = reviewVm(ReviewMode.NARRATOR, targetId = 3).session()

        assertTrue(s.queue.isEmpty() && s.isFinished)
        assertEquals(2, s.lockedSkipped)
    }

    @Test
    fun narratorReview_aNarratorWithoutHadithsIsAnEmptyFinishedSession() = runTest {
        val s = reviewVm(ReviewMode.NARRATOR, targetId = 999).session()
        assertTrue(s.queue.isEmpty() && s.isFinished)
    }

    @Test
    fun narratorReview_runsThroughTheQueueAndRecordsEachHadith() = runTest {
        sanadOpen(1, 2)
        val vm = reviewVm(ReviewMode.NARRATOR, targetId = 3)

        repeat(2) {
            vm.onEvent(ReviewEvent.RateMatn(GOOD))
            vm.onEvent(ReviewEvent.RateSanad(GOOD))
            vm.onEvent(ReviewEvent.Submit)
        }

        assertTrue(vm.session().isFinished)
        assertEquals(setOf(1L, 2L), reviews.mastery.keys)
        assertEquals(listOf(2, 2), reviews.mastery.values.map { it.repetitionCount })
    }

    @Test
    fun heatmap_isNotLoadedUntilTheSanadIsRevealed() = runTest {
        mastered(1)
        reviews.events += event(1, 3, false)
        val vm = reviewVm(ReviewMode.SINGLE, targetId = 1)

        assertTrue(vm.session().heatmaps.isEmpty())
        vm.onEvent(ReviewEvent.Reveal) // matn only
        assertEquals(CardStage.MATN, vm.session().stage)
        assertTrue(vm.session().heatmaps.isEmpty())

        vm.onEvent(ReviewEvent.Reveal)
        assertEquals(CardStage.SANAD, vm.session().stage)
        val heatmap = vm.session().heatmaps.getValue(1L)
        assertEquals(1, heatmap.nodes.first { it.performance.narratorId == 3L }.performance.errorCount)
    }

    @Test
    fun heatmap_isFetchedOncePerCard() = runTest {
        mastered(1)
        var calls = 0
        val counting = object : ReviewRepository by reviews {
            override suspend fun getNarratorOutcomeCounts(hadithId: Long) = reviews.getNarratorOutcomeCounts(hadithId).also { calls++ }
        }
        val vm = reviewVm(ReviewMode.SINGLE, targetId = 1, heatmap = GetNarratorChainHeatmapUseCase(hadiths, counting))

        repeat(4) { vm.onEvent(ReviewEvent.Reveal) }

        assertEquals(1, calls)
        assertEquals(setOf(1L), vm.session().heatmaps.keys)
    }

    @Test
    fun heatmap_isLeftForTheLastLayer() = runTest {
        sanadOpen(1) // layer 2: the chain is shown, but its narrators' records are not
        reviews.events += event(1, 3, false)
        val vm = reviewVm(ReviewMode.SINGLE, targetId = 1)

        repeat(2) { vm.onEvent(ReviewEvent.Reveal) }

        assertEquals(CardStage.SANAD, vm.session().stage)
        assertTrue(vm.session().heatmaps.isEmpty())
    }

    @Test
    fun heatmap_aFailureDoesNotBreakTheReview() = runTest {
        mastered(1)
        val broken = object : ReviewRepository by reviews {
            override suspend fun getNarratorOutcomeCounts(hadithId: Long) = error("ledger unreadable")
        }
        val vm = reviewVm(ReviewMode.SINGLE, targetId = 1, heatmap = GetNarratorChainHeatmapUseCase(hadiths, broken))

        repeat(2) { vm.onEvent(ReviewEvent.Reveal) }

        val s = vm.session()
        assertEquals(CardStage.SANAD, s.stage)
        assertTrue(s.heatmaps.isEmpty())
        vm.onEvent(ReviewEvent.RateMatn(GOOD))
        vm.onEvent(ReviewEvent.RateSanad(GOOD))
        assertTrue(vm.session().canSubmit)
    }
}

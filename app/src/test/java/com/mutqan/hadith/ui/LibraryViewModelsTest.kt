package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.LiveReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLibraryPageUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.HadithProgress
import com.mutqan.hadith.domain.usecase.LibraryPage
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.domain.withMatn
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.library.LibraryViewModel
import com.mutqan.hadith.ui.review.CardStage
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewSession
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.study.HadithStudyUi
import com.mutqan.hadith.ui.study.HadithStudyViewModel
import com.mutqan.hadith.ui.study.MatnScale
import com.mutqan.hadith.ui.study.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Library and the hadith study page's ViewModels, over fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelsTest {

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()
    private val companion = narrator(1, "عمر بن الخطاب", tier = "صحابي")
    private val teacher = narrator(2, "الحميدي", tier = "شيخ المصنف")

    // Two books; the second has a section, and the section a hadith of its own.
    private val groups = listOf(
        CatalogGroupEntity(1, null, "الكتاب الأول", "First book", 0),
        CatalogGroupEntity(2, null, "الكتاب الثاني", "Second book", 1),
        CatalogGroupEntity(3, 2, "باب الإيمان", "Faith", 0),
    )
    private val library = listOf(
        hadith(1, listOf(companion), position = 1, groupId = 1).withMatn("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ"),
        hadith(2, listOf(companion), position = 2, groupId = 1).withMatn("الدِّينُ النَّصِيحَةُ"),
        hadith(11, listOf(teacher, companion), position = 4, groupId = 2).withMatn("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ"),
        hadith(10, listOf(teacher, companion), position = 3, groupId = 3).withMatn("إِنَّمَا الْأَعْمَالُ بِالنِّيَّةِ"),
    )
    private val hadiths = FakeHadithRepository(library, groups)
    private val reviews = LiveReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    // -- LibraryViewModel -------------------------------------------------------------------

    private fun libraryVm(repo: HadithRepository = hadiths) =
        LibraryViewModel(GetLibraryPageUseCase(repo, reviews, scheduler) { now })

    private fun LibraryViewModel.page(): LibraryPage = (state.value as UiState.Success).data

    private fun LibraryPage.hadithIds() = hadiths.map { it.hadith.id }

    @Test
    fun library_startsOnTheShelfOfBooks() = runTest {
        val vm = libraryVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        assertNull(vm.openGroupId.value)
        assertEquals("", vm.query.value)
        assertEquals(listOf(1L, 2L), vm.page().groups.map { it.group.id })
        assertNull(vm.page().open)
        assertEquals("", vm.page().searched)
    }

    @Test
    fun library_openingAGroupListsItsSectionsAndItsOwnHadiths() = runTest {
        val vm = libraryVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.onOpenGroup(2)

        assertEquals(2L, vm.openGroupId.value)
        assertEquals(listOf(3L), vm.page().groups.map { it.group.id })
        assertEquals(listOf(11L), vm.page().hadithIds())
        assertEquals(listOf(2L), vm.page().trail.map { it.id })
    }

    @Test
    fun library_searchStaysInsideTheOpenGroupAndItsSections() = runTest {
        val vm = libraryVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onOpenGroup(2)

        vm.onQueryChange("الاعمال")

        assertEquals("الاعمال", vm.query.value)
        assertEquals("الاعمال", vm.page().searched)
        assertEquals(2L, vm.openGroupId.value)
        // «إنما الأعمال بالنية» is in the section of the second book; the first book's hadith is not looked in.
        assertEquals(listOf(10L), vm.page().hadithIds())
        // The whole group's size is still known while a search narrows what is listed.
        assertEquals(2, vm.page().status.total)
    }

    @Test
    fun library_searchIgnoresTashkeelAndClearingRestoresTheGroup() = runTest {
        val vm = libraryVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onOpenGroup(1)

        vm.onQueryChange("النَّصِيحَةُ")
        assertEquals(listOf(2L), vm.page().hadithIds())

        vm.onQueryChange("")
        assertEquals(listOf(1L, 2L), vm.page().hadithIds())
    }

    @Test
    fun library_aSearchThatFindsNothingEmptiesTheGroup() = runTest {
        val vm = libraryVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onOpenGroup(1)

        vm.onQueryChange("زززز")

        assertTrue(vm.page().hadiths.isEmpty())
        assertEquals(2, vm.page().status.total)
    }

    @Test
    fun library_aSearchBelongsToTheGroupItWasTypedInAndDoesNotFollowToAnother() = runTest {
        val vm = libraryVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onOpenGroup(1)
        vm.onQueryChange("الدين")

        vm.onOpenGroup(2)

        assertEquals("", vm.query.value)
        assertEquals(listOf(11L), vm.page().hadithIds())
    }

    @Test
    fun library_upLeavesASearchThenGoesUpOneGroupAtATimeAndSaysWhenThereIsNoFurther() = runTest {
        val vm = libraryVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onOpenGroup(2)
        vm.onOpenGroup(3)
        vm.onQueryChange("الاعمال")

        assertTrue(vm.onUp())
        assertEquals("", vm.query.value)
        assertEquals(3L, vm.openGroupId.value)

        assertTrue(vm.onUp())
        assertEquals(2L, vm.openGroupId.value)

        assertTrue(vm.onUp())
        assertNull(vm.openGroupId.value)
        assertEquals(listOf(1L, 2L), vm.page().groups.map { it.group.id })

        assertFalse("nothing above the shelf", vm.onUp())
    }

    @Test
    fun library_followsTheUsersProgress() = runTest {
        val vm = libraryVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onOpenGroup(1)
        assertEquals(HadithProgress.NEW, vm.page().hadiths.first().progress)

        SubmitReviewUseCase(hadiths, reviews, scheduler) { now }(1, ReviewRating.GOOD, ReviewRating.GOOD)

        assertEquals(HadithProgress.LEARNING, vm.page().hadiths.first().progress)
        assertEquals(1, vm.page().status.learning)
    }

    @Test
    fun library_surfacesFailuresAndCanTryAgain() = runTest {
        var failing = true
        val flaky = object : HadithRepository by hadiths {
            override fun observeGroups(): Flow<List<CatalogGroupEntity>> =
                flow { if (failing) throw IllegalStateException("db closed") else emitAll(hadiths.observeGroups()) }
        }
        val vm = libraryVm(flaky)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertTrue(vm.state.first { it !is UiState.Loading } is UiState.Error)

        failing = false
        vm.retry()

        assertEquals(listOf(1L, 2L), vm.page().groups.map { it.group.id })
    }

    // -- HadithStudyViewModel ---------------------------------------------------------------

    private class MemorySettings(override var matnScale: Float = MatnScale.DEFAULT) : ReaderSettings

    private fun studyVm(id: Long, settings: ReaderSettings = MemorySettings()) = HadithStudyViewModel(
        id,
        GetHadithStudyUseCase(hadiths, reviews, scheduler) { now },
        StartMemorizingUseCase(hadiths, reviews) { now },
        settings,
    )

    private fun HadithStudyViewModel.ui(): HadithStudyUi = (state.value as UiState.Success).data

    @Test
    fun study_showsTheHadithAndThatItIsNew() = runTest {
        val vm = studyVm(1)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        assertEquals(1L, vm.ui().study.hadith.hadith.id)
        assertFalse(vm.ui().study.isInStudy)
        assertEquals(MatnScale.DEFAULT, vm.ui().textScale, 0f)
        assertTrue(vm.ui().showTashkeel)
        assertFalse(vm.ui().isStarting)
        assertFalse(vm.ui().startFailed)
    }

    @Test
    fun study_tashkeelIsShownByDefaultAndTheToggleFlipsItBothWays() = runTest {
        val vm = studyVm(1)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertTrue(vm.ui().showTashkeel)

        vm.toggleTashkeel()
        assertFalse(vm.ui().showTashkeel)

        vm.toggleTashkeel()
        assertTrue(vm.ui().showTashkeel)
    }

    @Test
    fun study_tashkeelAndTextSizeAreIndependent() = runTest {
        val settings = MemorySettings()
        val vm = studyVm(1, settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.toggleTashkeel()
        vm.increaseText()
        assertFalse(vm.ui().showTashkeel)
        assertEquals(MatnScale.larger(MatnScale.DEFAULT), vm.ui().textScale, 0f)

        // The text size is a stored preference; the tashkeel is not, so a new visit opens on the full text.
        val again = studyVm(1, settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { again.state.collect {} }
        assertTrue(again.ui().showTashkeel)
        assertEquals(settings.matnScale, again.ui().textScale, 0f)
    }

    @Test
    fun study_aHadithThatDoesNotExistIsAnError() = runTest {
        val vm = studyVm(99)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        assertTrue(vm.state.first { it !is UiState.Loading } is UiState.Error)
    }

    @Test
    fun study_startingAddsTheHadithToTheWirdThenOpensTheLearningFlow() = runTest {
        val vm = studyVm(2)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        var opened: Long? = null

        vm.startMemorizing { opened = it }

        assertEquals(2L, opened)
        assertEquals(now, reviews.mastery.getValue(2L).nextReviewDue)
        assertTrue("the page now shows it as in study", vm.ui().study.isInStudy)
        assertFalse(vm.ui().isStarting)
        assertFalse(vm.ui().startFailed)
        assertEquals(listOf(1L, 10L, 11L), hadiths.getUnseen().map { it.hadith.id })
    }

    @Test
    fun study_aFailedStartIsReportedAndCanBeRetried() = runTest {
        val vm = studyVm(2)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        var opened = 0
        reviews.failSaves = true

        vm.startMemorizing { opened++ }

        assertEquals(0, opened)
        assertTrue(vm.ui().startFailed)
        assertFalse(vm.ui().isStarting)
        assertNull(reviews.mastery[2L])

        reviews.failSaves = false
        vm.startMemorizing { opened++ }

        assertEquals(1, opened)
        assertFalse(vm.ui().startFailed)
        assertTrue(vm.ui().study.isInStudy)
    }

    @Test
    fun study_aHadithInStudyShowsItsRetentionAndDueState() = runTest {
        reviews.mastery[1L] = MasteryRecordEntity(1, 2f, 2f, now - 10 * DAY_MS, now - 10 * DAY_MS, now - DAY_MS, 1)
        val vm = studyVm(1)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        val study = vm.ui().study
        assertTrue(study.isInStudy)
        assertTrue(study.isDue)
        assertTrue(study.retention!!.weakest in 0.01f..0.99f)
    }

    @Test
    fun study_textSizeStepsWithinItsLimitsAndIsRemembered() = runTest {
        val settings = MemorySettings()
        val vm = studyVm(1, settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.increaseText()
        assertEquals(1.2f, vm.ui().textScale, 0f)
        assertEquals(1.2f, settings.matnScale, 0f)

        repeat(10) { vm.increaseText() }
        assertEquals(MatnScale.STEPS.last(), vm.ui().textScale, 0f)

        repeat(10) { vm.decreaseText() }
        assertEquals(MatnScale.STEPS.first(), vm.ui().textScale, 0f)
        assertEquals(MatnScale.STEPS.first(), settings.matnScale, 0f)
    }

    @Test
    fun study_startsAtTheRememberedTextSize() = runTest {
        val vm = studyVm(1, MemorySettings(matnScale = 1.4f))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        assertEquals(1.4f, vm.ui().textScale, 0f)
    }

    // -- Review flow opened from the study page ---------------------------------------------

    private fun reviewVm(mode: ReviewMode, targetId: Long?) = ReviewViewModel(
        mode, targetId,
        GetDueHadithsUseCase(hadiths, reviews, scheduler) { now },
        GetUnseenHadithsUseCase(hadiths),
        hadiths,
        SubmitReviewUseCase(hadiths, reviews, scheduler) { now },
        GetNarratorChainHeatmapUseCase(hadiths, reviews),
        GetLayerStatusesUseCase(reviews, scheduler),
    )

    private fun ReviewViewModel.session(): ReviewSession = (state.value as UiState.Success).data

    @Test
    fun learningFlow_withATargetStudiesThatOneHadithMatnFirst() = runTest {
        val vm = reviewVm(ReviewMode.NEW, targetId = 10)

        assertEquals(listOf(10L), vm.session().queue.map { it.hadithId })
        assertEquals(CardStage.MATN, vm.session().stage)
    }

    @Test
    fun learningFlow_withATargetStillFindsAHadithThatWasJustStarted() = runTest {
        StartMemorizingUseCase(hadiths, reviews) { now }(10)

        val vm = reviewVm(ReviewMode.NEW, targetId = 10)

        // The new-hadith queue no longer holds it, but the page that started it opens it all the same.
        assertEquals(listOf(10L), vm.session().queue.map { it.hadithId })
    }

    @Test
    fun learningFlow_withoutATargetIsTheNextNewHadithsInCurriculumOrder() = runTest {
        val vm = reviewVm(ReviewMode.NEW, targetId = null)

        assertEquals(listOf(1L, 2L, 10L, 11L), vm.session().queue.map { it.hadithId })
    }

    @Test
    fun learningFlow_withAnUnknownTargetIsAnError() = runTest {
        assertTrue(reviewVm(ReviewMode.NEW, targetId = 99).state.value is UiState.Error)
    }

    @Test
    fun learningFlow_ratingTheStartedHadithGivesItsFirstStability() = runTest {
        StartMemorizingUseCase(hadiths, reviews) { now }(10)
        val vm = reviewVm(ReviewMode.NEW, targetId = 10)

        vm.onEvent(com.mutqan.hadith.ui.review.ReviewEvent.Reveal)
        vm.onEvent(com.mutqan.hadith.ui.review.ReviewEvent.RateMatn(ReviewRating.GOOD))
        vm.onEvent(com.mutqan.hadith.ui.review.ReviewEvent.Submit)

        // Layer 1: only the matn is learned; the chain waits for its layer.
        val record = reviews.mastery.getValue(10L)
        assertEquals(2f, record.matnStability, 1e-6f)
        assertEquals(0f, record.sanadStability, 0f)
        assertNull(record.sanadLastReviewedAt)
        assertEquals(1, record.repetitionCount)
        assertTrue(record.nextReviewDue > now)
    }
}

package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeNarratorRepository
import com.mutqan.hadith.domain.LiveReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetMasteryAnalyticsUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.home.HomeViewModel
import com.mutqan.hadith.ui.insights.InsightsViewModel
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DailyDashboardViewModelsTest {

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()

    // Level 3 has the lowest hadith number; level 1 must still come first.
    private val sahabi = narrator(10, "عمر", tier = "صحابي")
    private val library = listOf(
        hadith(1, listOf(narrator(1, "الحميدي"), sahabi), level = 3),
        hadith(2, listOf(sahabi), level = 1),
        hadith(3, listOf(narrator(11, "أنس", tier = "صحابي")), level = 1),
    )
    private val hadiths = FakeHadithRepository(library)
    private val reviews = LiveReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun homeVm() = HomeViewModel(ObserveStudyDashboardUseCase(hadiths, reviews, scheduler, { ZoneOffset.UTC }, { now }))

    private suspend fun HomeViewModel.loaded(): StudyDashboard = (state.first { it !is UiState.Loading } as UiState.Success).data

    // -- Home ------------------------------------------------------------------------------

    @Test
    fun home_recommendsTheFirstHadithOfLevelOneAndMovesOnOnceItIsStarted() = runTest {
        val vm = homeVm()
        backgroundScope.launch { vm.state.collect {} }

        val first = vm.loaded().nextToLearn
        assertEquals(2L, first?.hadithId)
        assertEquals(1, first?.level)
        assertEquals("عمر بن الخطاب", first?.sahabiName)

        StartMemorizingUseCase(hadiths, reviews) { now }(2)

        // The dashboard follows the database by itself: no refresh is needed to move the card on.
        assertEquals(3L, (vm.state.value as UiState.Success).data.nextToLearn?.hadithId)
    }

    @Test
    fun home_aStartedHadithIsDueAndLearningUntilItIsMemorized() = runTest {
        val vm = homeVm()
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(0, vm.loaded().dueCount)

        StartMemorizingUseCase(hadiths, reviews) { now }(2)

        val dashboard = (vm.state.value as UiState.Success).data
        assertEquals(1, dashboard.dueCount)
        assertEquals(1, dashboard.learningCount)
        assertEquals(0, dashboard.masteredCount)
        // Nothing of it has been reviewed yet, so there is no recall to average.
        assertNull(dashboard.retention)
    }

    @Test
    fun home_isCompleteWhenTheOnlyReviewsAreScheduledForLater() = runTest {
        reviews.mastery[2] = MasteryRecordEntity(2, 5f, 5f, now, now, now + 3 * DAY_MS, 2)
        val vm = homeVm()
        backgroundScope.launch { vm.state.collect {} }

        val dashboard = vm.loaded()

        assertEquals(0, dashboard.dueCount)
        assertEquals(1, dashboard.studiedCount)
        assertEquals(3L, dashboard.nextToLearn?.hadithId)
    }

    // -- Insights --------------------------------------------------------------------------

    private val narrators = FakeNarratorRepository(listOf(narrator(1, "الحميدي"), sahabi, narrator(11, "أنس", tier = "صحابي")))

    private fun insightsVm(repo: HadithRepository = hadiths) =
        InsightsViewModel(GetMasteryAnalyticsUseCase(repo, narrators, reviews, scheduler, { now }, { ZoneOffset.UTC }))

    @Test
    fun insights_loadsTheFiguresAndRefreshesThem() = runTest {
        val vm = insightsVm()
        assertEquals(3, (vm.state.value as UiState.Success).data.status.unseen)

        reviews.mastery[2] = MasteryRecordEntity(2, 2f, 2f, now, now, now + DAY_MS, 1)
        vm.refresh()

        assertEquals(2, (vm.state.value as UiState.Success).data.status.unseen)
    }

    @Test
    fun insights_aFailureIsAnErrorStateAndRetryRecovers() = runTest {
        var broken = true
        val flaky = object : HadithRepository by hadiths {
            override suspend fun getAll() = if (broken) error("db closed") else hadiths.getAll()
        }
        val vm = insightsVm(flaky)
        assertEquals(UiState.Error("db closed"), vm.state.value)

        broken = false
        vm.retry()

        assertTrue(vm.state.value is UiState.Success)
    }

    @Test
    fun insights_aFailedRefreshKeepsTheFiguresOnScreen() = runTest {
        var broken = false
        val flaky = object : HadithRepository by hadiths {
            override suspend fun getAll() = if (broken) error("db closed") else hadiths.getAll()
        }
        val vm = insightsVm(flaky)
        val shown = vm.state.value
        assertTrue(shown is UiState.Success)

        broken = true
        vm.refresh()

        assertEquals(shown, vm.state.value)
    }
}

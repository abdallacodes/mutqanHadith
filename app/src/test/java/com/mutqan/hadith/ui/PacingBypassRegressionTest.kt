package com.mutqan.hadith.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.details.HadithDetailsRoute
import com.mutqan.hadith.ui.details.HadithDetailsUi
import com.mutqan.hadith.ui.details.HadithDetailsViewModel
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewRoute
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.study.HadithStudyUi
import com.mutqan.hadith.ui.study.HadithStudyViewModel
import com.mutqan.hadith.ui.study.MatnScale
import com.mutqan.hadith.ui.study.ReaderSettings
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val NOW = 1_700_000_000_000L

/**
 * The daily limit on new hadiths held at the second door: a hadith met through the narrator network, in the details
 * dialog, used to be reviewable without ever being started, which took it into study with no question asked and no
 * count. Now such a hadith is started there, behind the same limit as on its own page, and a single review of one that
 * was never started reviews nothing. In Arabic; the English wording is in [PacingBypassEnglishScreensTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A tall screen keeps every node on screen: taps outside the window are dropped.
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class PacingBypassRegressionTest {

    @get:Rule
    val compose = createComposeRule()

    private val chain = listOf(narrator(1, "الحميدي"), narrator(2, "سفيان"), narrator(4, "عمر", tier = "صحابي"))
    private val hadiths = FakeHadithRepository((1L..5L).map { hadith(it, chain, level = 1) })
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val scheduler = DualDecayScheduler()
    private val stored = InMemoryStudyPreferences()
    private val pacing = DailyPacing(stored, { ZoneOffset.UTC }, { NOW })
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class MemorySettings(override var matnScale: Float = MatnScale.DEFAULT) : ReaderSettings

    private fun startUseCase(repo: ReviewRepository = reviews) = StartMemorizingUseCase(hadiths, repo, pacing) { NOW }

    private fun detailsVm(id: Long, repo: ReviewRepository = reviews) = HadithDetailsViewModel(
        id, hadiths, GetNarratorChainHeatmapUseCase(hadiths, repo), GetLayerStatusesUseCase(repo, scheduler), startUseCase(repo), pacing,
    )

    private fun readerVm(id: Long) =
        HadithStudyViewModel(id, GetHadithStudyUseCase(hadiths, reviews, scheduler) { NOW }, startUseCase(), MemorySettings(), pacing)

    private fun HadithDetailsViewModel.ui(): HadithDetailsUi = (state.value as UiState.Success).data

    private fun HadithStudyViewModel.ui(): HadithStudyUi = (state.value as UiState.Success).data

    /** [count] hadiths taken into study today, the way the reader's start button does it. */
    private fun startedToday(count: Int) = runBlocking { (1L..count).forEach { startUseCase()(it) } }

    private fun started(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 2f, 0f, NOW - DAY_MS, null, NOW, 1)
    }

    // -- The details dialog, as a view model -----------------------------------------------

    @Test
    fun details_aHadithNotInStudyIsOfferedForStartingAndOneInStudyForReview() {
        started(2)

        assertFalse(detailsVm(1).ui().isInStudy)
        assertTrue(detailsVm(2).ui().isInStudy)
    }

    @Test
    fun details_theFirstTwoStartsOfTheDayGoAheadWithoutAQuestion() {
        val opened = mutableListOf<Long>()

        detailsVm(1).startMemorizing { opened += it }
        detailsVm(2).startMemorizing { opened += it }

        assertEquals(listOf(1L, 2L), opened)
        assertEquals(2, pacing.startedToday())
        assertFalse(detailsVm(3).ui().pacingPrompt)
    }

    @Test
    fun details_theThirdStartAsksFirstAndTakesNothingIntoStudyUntilTheUserAnswers() {
        startedToday(2)
        val third = detailsVm(3)
        var opened: Long? = null

        third.startMemorizing { opened = it }

        assertTrue(third.ui().pacingPrompt)
        assertNull("nothing opens while the question is open", opened)
        assertNull("and nothing is taken into study", reviews.mastery[3L])
        assertEquals(2, pacing.startedToday())
    }

    @Test
    fun details_goingOnAfterTheQuestionStartsTheHadithAndCountsIt() {
        startedToday(2)
        val third = detailsVm(3)
        var opened: Long? = null
        third.startMemorizing {}

        third.confirmPacing { opened = it }

        assertEquals(3L, opened)
        assertNotNull(reviews.mastery[3L])
        assertFalse(third.ui().pacingPrompt)
        assertEquals(3, pacing.startedToday())
        assertTrue("the fourth is asked about as well", detailsVm(4).also { it.startMemorizing {} }.ui().pacingPrompt)
    }

    @Test
    fun details_savingItForTomorrowLeavesTheHadithAndTheCountUntouched() {
        startedToday(2)
        val third = detailsVm(3)
        var opened = 0
        third.startMemorizing { opened++ }

        third.dismissPacing()

        assertFalse(third.ui().pacingPrompt)
        assertEquals(0, opened)
        assertNull(reviews.mastery[3L])
        assertEquals(2, pacing.startedToday())
    }

    @Test
    fun details_theNextDayStartsFreshWithNoQuestion() {
        startedToday(2)
        val tomorrow = DailyPacing(stored, { ZoneOffset.UTC }, { NOW + DAY_MS })
        val dialog = HadithDetailsViewModel(
            3, hadiths, GetNarratorChainHeatmapUseCase(hadiths, reviews), GetLayerStatusesUseCase(reviews, scheduler),
            StartMemorizingUseCase(hadiths, reviews, tomorrow) { NOW + DAY_MS }, tomorrow,
        )
        var opened: Long? = null

        // The same stored counts, read a day later: yesterday's two do not count.
        assertEquals(0, tomorrow.startedToday())
        assertFalse(tomorrow.isOverLimit())
        dialog.startMemorizing { opened = it }

        assertEquals(3L, opened)
        assertFalse(dialog.ui().pacingPrompt)
        assertEquals(1, tomorrow.startedToday())
    }

    @Test
    fun details_theDaysCountIsSharedWithTheHadithsOwnPageWhicheverDoorIsUsed() = runTest {
        // Two through the reader, the third through the dialog.
        readerVm(1).startMemorizing {}
        readerVm(2).startMemorizing {}
        assertTrue(detailsVm(3).also { it.startMemorizing {} }.ui().pacingPrompt)
        assertNull(reviews.mastery[3L])

        // One through each, the third through the reader.
        val other = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
        val freshPacing = DailyPacing(InMemoryStudyPreferences(), { ZoneOffset.UTC }, { NOW })
        val start = StartMemorizingUseCase(hadiths, other, freshPacing) { NOW }
        fun details(id: Long) = HadithDetailsViewModel(
            id, hadiths, GetNarratorChainHeatmapUseCase(hadiths, other), GetLayerStatusesUseCase(other, scheduler), start, freshPacing,
        )
        fun reader(id: Long) = HadithStudyViewModel(id, GetHadithStudyUseCase(hadiths, other, scheduler) { NOW }, start, MemorySettings(), freshPacing)
        reader(1).startMemorizing {}
        details(2).startMemorizing {}
        val third = reader(3)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { third.state.collect {} }
        third.startMemorizing {}
        assertTrue(third.ui().pacingPrompt)
        assertNull(other.mastery[3L])
    }

    @Test
    fun details_aStartThatFailsIsShownAndCanBeTriedAgain() {
        var broken = true
        val flaky = object : ReviewRepository by reviews {
            override suspend fun saveReview(record: MasteryRecordEntity, narratorEvents: List<com.mutqan.hadith.data.entity.NarratorReviewEventEntity>) {
                if (broken) error("disk full") else reviews.saveReview(record, narratorEvents)
            }
        }
        val vm = detailsVm(1, flaky)
        var opened: Long? = null

        vm.startMemorizing { opened = it }

        assertTrue(vm.ui().startFailed)
        assertNull(opened)
        assertEquals(0, pacing.startedToday())

        broken = false
        vm.startMemorizing { opened = it }

        assertEquals(1L, opened)
        assertFalse(vm.ui().startFailed)
        assertEquals(1, pacing.startedToday())
    }

    // -- The details dialog, on screen -----------------------------------------------------

    private fun detailsRoute(id: Long) {
        val vm = detailsVm(id)
        compose.setContent {
            MutqanHadithTheme {
                HadithDetailsRoute(
                    vm,
                    onClose = { calls += "close" },
                    onReview = { calls += "review$it" },
                    onStartLearning = { calls += "learn$it" },
                )
            }
        }
    }

    @Test
    fun screen_aHadithNotInStudyOffersStartingAndNoReview() {
        detailsRoute(1)

        compose.onNodeWithText("بدء الحفظ").assertExists()
        compose.onNodeWithText("مراجعة هذا الحديث").assertDoesNotExist()
    }

    @Test
    fun screen_aHadithInStudyOffersReviewAndNoStarting() {
        started(1)
        detailsRoute(1)

        compose.onNodeWithText("مراجعة هذا الحديث").performClick()

        assertEquals(listOf("review1"), calls)
        compose.onNodeWithText("بدء الحفظ").assertDoesNotExist()
    }

    @Test
    fun screen_theFirstTwoHadithsOfTheDayStartWithoutAQuestion() {
        startedToday(1)
        detailsRoute(2)

        compose.onNodeWithText("بدء الحفظ").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("قليل دائم خير من كثير منقطع").assertDoesNotExist()
        assertEquals(listOf("learn2"), calls)
        assertNotNull(reviews.mastery[2L])
    }

    @Test
    fun screen_theThirdHadithAsksFirstAndSavingItForTomorrowLeavesItAlone() {
        startedToday(2)
        detailsRoute(3)

        compose.onNodeWithText("بدء الحفظ").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("قليل دائم خير من كثير منقطع").assertExists()
        compose.onNodeWithText("متابعة الحفظ").assertExists()
        assertNull("nothing is started while the question is open", reviews.mastery[3L])

        compose.onNodeWithText("الاكتفاء بما سبق").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("قليل دائم خير من كثير منقطع").assertDoesNotExist()
        compose.onNodeWithText("بدء الحفظ").assertExists()
        assertNull(reviews.mastery[3L])
        assertEquals(emptyList<String>(), calls)
        assertEquals(2, pacing.startedToday())
    }

    @Test
    fun screen_goingOnStartsTheThirdHadithAndOpensTheLearningFlow() {
        startedToday(2)
        detailsRoute(3)

        compose.onNodeWithText("بدء الحفظ").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("متابعة الحفظ").performClick()
        compose.waitForIdle()

        assertEquals(listOf("learn3"), calls)
        assertNotNull(reviews.mastery[3L])
        assertEquals(3, pacing.startedToday())
        compose.onNodeWithText("قليل دائم خير من كثير منقطع").assertDoesNotExist()
    }

    // -- A single review of a hadith never started -----------------------------------------

    private fun singleReview(id: Long): ReviewViewModel {
        val vm = ReviewViewModel(
            ReviewMode.SINGLE, id,
            GetDueHadithsUseCase(hadiths, reviews, scheduler) { NOW },
            GetUnseenHadithsUseCase(hadiths),
            hadiths,
            SubmitReviewUseCase(hadiths, reviews, scheduler) { NOW },
            GetNarratorChainHeatmapUseCase(hadiths, reviews),
            GetLayerStatusesUseCase(reviews, scheduler),
        )
        compose.setContent {
            MutqanHadithTheme {
                ReviewRoute(viewModel = vm, mode = ReviewMode.SINGLE, onClose = { calls += "close" }, onSanadBuilder = {}, onMissingLink = {})
            }
        }
        return vm
    }

    @Test
    fun review_aSingleReviewOfAHadithNeverStartedSaysSoAndStartsNothing() {
        startedToday(2)
        singleReview(3)

        compose.onNodeWithText(
            "لم يدخل هذا الحديث وِرد حفظك بعد. ابدأ حفظه من صفحته أولًا ليُحتسب ضمن أحاديث اليوم الجديدة.",
        ).assertExists()
        assertNull("no record is created behind the daily limit", reviews.mastery[3L])
        assertEquals(2, pacing.startedToday())

        compose.onNodeWithText("العودة إلى الرئيسية").performClick()
        assertEquals(listOf("close"), calls)
    }
}

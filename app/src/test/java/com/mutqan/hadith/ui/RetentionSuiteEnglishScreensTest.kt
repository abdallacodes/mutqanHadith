package com.mutqan.hadith.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.analytics.StudyStreak
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.home.HomeActions
import com.mutqan.hadith.ui.home.HomeScreen
import com.mutqan.hadith.ui.review.ReviewEvent
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewRoute
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.settings.SettingsActions
import com.mutqan.hadith.ui.settings.SettingsScreen
import com.mutqan.hadith.ui.settings.SettingsUi
import com.mutqan.hadith.ui.study.HadithStudyRoute
import com.mutqan.hadith.ui.study.HadithStudyViewModel
import com.mutqan.hadith.ui.study.MatnScale
import com.mutqan.hadith.ui.study.ReaderSettings
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TIME = 1_700_000_000_000L

/** The retention suite in English: the same behavior as in Arabic, with English plural forms and wording. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h1600dp-xxhdpi")
class RetentionSuiteEnglishScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val chain = listOf(narrator(1, "Al-Humaydi"), narrator(4, "Umar", tier = "صحابي"))
    private val hadiths = FakeHadithRepository((1L..12L).map { hadith(it, chain) })
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val scheduler = DualDecayScheduler()
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun core(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 8f, 0f, TIME - 8 * DAY_MS, null, TIME - DAY_MS, 3)
    }

    private fun dashboard(due: Int) = StudyDashboard(
        dueCount = due, newCount = 0, overallMastery = 0f, items = emptyList(), drillSuggestionId = null,
        learningCount = 4, masteredCount = 1, retention = 0.8f, streak = StudyStreak.None, nextToLearn = null,
    )

    private fun showHome(due: Int) = compose.setContent {
        MutqanHadithTheme {
            HomeScreen(UiState.Success(dashboard(due)), HomeActions({}, {}, {}, {}, {})) {}
        }
    }

    private fun showReview(): ReviewViewModel {
        val vm = ReviewViewModel(
            ReviewMode.DUE, null,
            GetDueHadithsUseCase(hadiths, reviews, scheduler) { TIME },
            GetUnseenHadithsUseCase(hadiths),
            hadiths,
            SubmitReviewUseCase(hadiths, reviews, scheduler) { TIME },
            GetNarratorChainHeatmapUseCase(hadiths, reviews),
            GetLayerStatusesUseCase(reviews, scheduler),
        )
        compose.setContent { MutqanHadithTheme { ReviewRoute(viewModel = vm, mode = ReviewMode.DUE, onClose = {}) } }
        return vm
    }

    @Test
    fun home_estimatesTheSittingInEnglish() {
        showHome(due = 3)

        compose.onNodeWithText("3 hadiths are due for review").assertExists()
        compose.onNodeWithText("3 hadiths · ~4 mins").assertExists()
    }

    @Test
    fun home_aSingleHadithIsAboutTwoMinutes() {
        showHome(due = 1)

        compose.onNodeWithText("1 hadith · ~2 mins").assertExists()
    }

    @Test
    fun home_aBacklogIsSoftenedAndEstimatedForTheNextBatch() {
        showHome(due = 12)

        compose.onNodeWithText("Today's review (5 hadiths out of 12 overdue)").assertExists()
        compose.onNodeWithText("We will catch up on what you missed step by step, without strain").assertExists()
        compose.onNodeWithText("5 hadiths · ~8 mins").assertExists()
    }

    @Test
    fun review_workedThroughInBatchesWithABreak() {
        (1L..12L).forEach(::core)
        val vm = showReview()
        compose.onNodeWithText("Batch 1 of 3 · 5 hadiths").assertExists()

        compose.runOnIdle {
            repeat(5) {
                vm.onEvent(ReviewEvent.RateMatn(GOOD))
                vm.onEvent(ReviewEvent.Submit)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Batch 1 of 3 complete").assertExists()
        compose.onNodeWithText("Still waiting: 7 hadiths").assertExists()
        compose.onNodeWithText("Continue to the next batch (5 hadiths)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Batch 2 of 3 · 5 hadiths").assertExists()
    }

    @Test
    fun hint_givesTheCompanionsLetterThenTheTakhrij() {
        core(1)
        showReview()

        compose.onNodeWithText("Hint").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Companion: U…").assertExists()

        compose.onNodeWithText("Hint").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Takhrij: متفق عليه").assertExists()
        compose.onNodeWithText("Hint").assertDoesNotExist()
    }

    @Test
    fun lock_tappingTheLockedSanadExplainsTheMethod() {
        core(1)
        showReview()
        compose.onNodeWithText("Recall the matn from memory, then tap to reveal it").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Master the core of the hadith to unlock the Sanad tree").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("The method of mastery").assertExists()
        compose.onNodeWithText("An advanced student? You can open every layer in Settings, under Unlock All Layers.").assertExists()
        compose.onNodeWithText("Got it").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("The method of mastery").assertDoesNotExist()
    }

    @Test
    fun pacing_theThirdNewHadithOfTheDayAsksFirst() {
        val pacing = DailyPacing(InMemoryStudyPreferences(), { ZoneOffset.UTC }, { TIME })
        runBlocking {
            val start = StartMemorizingUseCase(hadiths, reviews, pacing) { TIME }
            start(1)
            start(2)
        }
        val vm = HadithStudyViewModel(
            3,
            GetHadithStudyUseCase(hadiths, reviews, scheduler) { TIME },
            StartMemorizingUseCase(hadiths, reviews, pacing) { TIME },
            object : ReaderSettings { override var matnScale = MatnScale.DEFAULT },
            pacing,
        )
        compose.setContent {
            MutqanHadithTheme { HadithStudyRoute(vm, onClose = {}, onStartLearning = { calls += "learn$it" }, onReview = {}) }
        }

        compose.onNodeWithText("Start Memorizing").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Consistency Over Volume").assertExists()
        compose.onNodeWithText("Save for tomorrow").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Consistency Over Volume").assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)

        compose.onNodeWithText("Start Memorizing").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Proceed anyway").performClick()
        compose.waitForIdle()
        assertEquals(listOf("learn3"), calls)
    }

    @Test
    fun advanced_theSettingsSwitchIsLabelledInEnglish() {
        compose.setContent {
            MutqanHadithTheme {
                SettingsScreen(
                    SettingsUi(), "1.0",
                    SettingsActions({}, {}, {}, {}, {}, {}, {}, onUnlockAllLayersChanged = { calls += "unlock=$it" }),
                )
            }
        }

        compose.onNodeWithText("Study").assertExists()
        compose.onNodeWithText("Unlock All Layers (Advanced)").performClick()

        assertEquals(listOf("unlock=true"), calls)
    }
}

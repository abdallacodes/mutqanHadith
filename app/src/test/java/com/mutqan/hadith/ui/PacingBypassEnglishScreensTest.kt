package com.mutqan.hadith.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.details.HadithDetailsRoute
import com.mutqan.hadith.ui.details.HadithDetailsViewModel
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewRoute
import com.mutqan.hadith.ui.review.ReviewViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TIME = 1_700_000_000_000L

/** The details dialog's start button, the daily limit behind it and the single review of a hadith never started, in English. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h1600dp-xxhdpi")
class PacingBypassEnglishScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val chain = listOf(narrator(1, "Al-Humaydi"), narrator(4, "Umar", tier = "\u0635\u062d\u0627\u0628\u064a"))
    private val hadiths = FakeHadithRepository((1L..4L).map { hadith(it, chain) })
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val scheduler = DualDecayScheduler()
    private val pacing = DailyPacing(InMemoryStudyPreferences(), { ZoneOffset.UTC }, { TIME })
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun startedToday(count: Int) = runBlocking {
        val start = StartMemorizingUseCase(hadiths, reviews, pacing) { TIME }
        (1L..count).forEach { start(it) }
    }

    private fun detailsRoute(id: Long) {
        val vm = HadithDetailsViewModel(
            id, hadiths, GetNarratorChainHeatmapUseCase(hadiths, reviews), GetLayerStatusesUseCase(reviews, scheduler),
            StartMemorizingUseCase(hadiths, reviews, pacing) { TIME }, pacing,
        )
        compose.setContent {
            MutqanHadithTheme {
                HadithDetailsRoute(vm, onClose = {}, onReview = { calls += "review$it" }, onStartLearning = { calls += "learn$it" })
            }
        }
    }

    @Test
    fun details_aHadithNotInStudyOffersStartMemorizingAndNoReview() {
        detailsRoute(1)

        compose.onNodeWithText("Start Memorizing").assertExists()
        compose.onNodeWithText("Review this hadith").assertDoesNotExist()
    }

    @Test
    fun details_theThirdNewHadithOfTheDayAsksFirst() {
        startedToday(2)
        detailsRoute(3)

        compose.onNodeWithText("Start Memorizing").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Consistency Over Volume").assertExists()
        assertNull(reviews.mastery[3L])

        compose.onNodeWithText("Save for tomorrow").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Consistency Over Volume").assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)

        compose.onNodeWithText("Start Memorizing").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Proceed anyway").performClick()
        compose.waitForIdle()
        assertEquals(listOf("learn3"), calls)
        assertNotNull(reviews.mastery[3L])
    }

    @Test
    fun review_aSingleReviewOfAHadithNeverStartedSaysSoInEnglish() {
        val vm = ReviewViewModel(
            ReviewMode.SINGLE, 3,
            GetDueHadithsUseCase(hadiths, reviews, scheduler) { TIME },
            GetUnseenHadithsUseCase(hadiths),
            hadiths,
            SubmitReviewUseCase(hadiths, reviews, scheduler) { TIME },
            GetNarratorChainHeatmapUseCase(hadiths, reviews),
            GetLayerStatusesUseCase(reviews, scheduler),
        )
        compose.setContent {
            MutqanHadithTheme {
                ReviewRoute(viewModel = vm, mode = ReviewMode.SINGLE, onClose = {}, onSanadBuilder = {}, onMissingLink = {})
            }
        }

        compose.onNodeWithText(
            "This hadith is not in your memorization yet. Start it from its page first so it counts toward today\'s new hadiths.",
        ).assertExists()
        assertNull(reviews.mastery[3L])
    }
}

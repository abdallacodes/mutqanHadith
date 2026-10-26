package com.mutqan.hadith.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.domain.model.StartingPoint
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.PlacementUseCase
import com.mutqan.hadith.ui.placement.PlacementRoute
import com.mutqan.hadith.ui.placement.PlacementViewModel
import com.mutqan.hadith.ui.settings.AppLanguage
import com.mutqan.hadith.ui.settings.SettingsActions
import com.mutqan.hadith.ui.settings.SettingsScreen
import com.mutqan.hadith.ui.settings.SettingsUi
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** The starting-point screens with the app set to English. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h1600dp-xxhdpi")
class PlacementEnglishScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val hadiths = FakeHadithRepository((1L..2L).map { hadith(it, listOf(narrator(1, "عمر", tier = "صحابي")), position = it.toInt()) })
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val preferences = InMemoryStudyPreferences()
    private val placement = PlacementUseCase(hadiths, reviews, DualDecayScheduler(), preferences) { 1_700_000_000_000L }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theFlowReadsInEnglishFromTheChoiceToTheSummary() {
        val viewModel = PlacementViewModel(placement, hadiths, null)
        compose.setContent { MutqanHadithTheme { PlacementRoute(viewModel, firstRun = true, onClose = {}) } }

        compose.onNodeWithText("How much of the Forty do you know?").assertExists()
        compose.onNodeWithText("Beginner").assertExists()
        compose.onNodeWithText("Expert").assertExists()
        compose.onNodeWithText("Intermediate").performClick()

        compose.onNodeWithText("Grade each hadith yourself?").assertExists()
        compose.onNodeWithText("Skip, and mark them all \"Weak\"").assertExists()
        compose.onNodeWithText("Grade my hadiths").performClick()

        compose.onNodeWithText("1 of 2").assertExists()
        compose.onNodeWithText("Reveal the hadith").performClick()
        compose.onNodeWithText("How well did you know it?").assertExists()
        compose.onNodeWithText("Very good").performClick()
        compose.onNodeWithText("Reveal the hadith").performClick()
        compose.onNodeWithText("Not memorized").performClick()

        compose.onNodeWithText("Hadiths marked: 1").assertExists()
        compose.onNodeWithText("Very good: 1").assertExists()
        assertEquals(RecallBand.VERY_GOOD, RecallBand.of(reviews.mastery.getValue(1).matnStability))
        assertEquals(null, reviews.mastery[2])
        assertEquals(StartingPoint.INTERMEDIATE, preferences.startingPoint.value)
    }

    @Test
    fun settingsShowsTheStartingPointInEnglish() {
        compose.setContent {
            MutqanHadithTheme {
                SettingsScreen(SettingsUi(startingPoint = StartingPoint.EXPERT), "1.0", SettingsActions({}, {}, {}, {}, {}, {}, {}), AppLanguage.ENGLISH)
            }
        }

        compose.onNodeWithText("Your starting point").assertExists()
        compose.onNodeWithText("Your standing in the Forty: Expert. Tap to change it or to grade your hadiths.").assertExists()
    }
}

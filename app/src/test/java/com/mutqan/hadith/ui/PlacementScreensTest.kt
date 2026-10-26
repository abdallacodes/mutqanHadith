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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Telling the app the starting point, on screen: the choice, the offer, the self-check and where each answer lands. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class PlacementScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val companion = narrator(1, "عمر", tier = "صحابي")
    private val hadiths = FakeHadithRepository((1L..3L).map { hadith(it, listOf(companion), position = it.toInt()) })
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val preferences = InMemoryStudyPreferences()
    private val placement = PlacementUseCase(hadiths, reviews, DualDecayScheduler(), preferences) { 1_700_000_000_000L }
    private var closed = 0

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun show(firstRun: Boolean = false) {
        val viewModel = PlacementViewModel(placement, hadiths, preferences.startingPoint.value)
        compose.setContent { MutqanHadithTheme { PlacementRoute(viewModel, firstRun, onClose = { closed++ }) } }
    }

    private fun bandOf(hadithId: Long): RecallBand? = reviews.mastery[hadithId]?.let { RecallBand.of(it.matnStability) }

    @Test
    fun theThreeStartingPointsAreOffered() {
        show()

        compose.onNodeWithText("كم تحفظ من الأربعين؟").assertExists()
        compose.onNodeWithText("مبتدئ").assertExists()
        compose.onNodeWithText("متوسط").assertExists()
        compose.onNodeWithText("متقن").assertExists()
    }

    @Test
    fun aBeginnerMarksNothingAndTheScreenCloses() {
        show()

        compose.onNodeWithText("مبتدئ").performClick()
        compose.waitForIdle()

        assertEquals(StartingPoint.BEGINNER, preferences.startingPoint.value)
        assertTrue(reviews.mastery.isEmpty())
        assertEquals(1, closed)
    }

    @Test
    fun skippingTheCheckMarksEveryHadithWithTheDefaultOfTheChosenPoint() {
        show()

        compose.onNodeWithText("متقن").performClick()
        compose.onNodeWithText("تخطَّ، واعتبرها كلها «جيد»").performClick()
        compose.waitForIdle()

        assertEquals(StartingPoint.EXPERT, preferences.startingPoint.value)
        assertEquals(listOf(RecallBand.GOOD, RecallBand.GOOD, RecallBand.GOOD), listOf(1L, 2L, 3L).map(::bandOf))
        compose.onNodeWithText("عدد الأحاديث المقيَّمة: 3").assertExists()
        compose.onNodeWithText("جيد: 3").assertExists()
        compose.onNodeWithText("ابدأ").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun theCheckGradesEachHadithAtTheBandTheLearnerPicksAndLeavesNotMemorizedOnesUnmarked() {
        show()
        compose.onNodeWithText("متوسط").performClick()
        compose.onNodeWithText("قيّم أحاديثي").performClick()

        // The cue first: the grading buttons only appear once the hadith was revealed.
        compose.onNodeWithText("1 من 3").assertExists()
        compose.onNodeWithText("حديث رقم 1").assertExists()
        compose.onNodeWithText("ممتاز").assertDoesNotExist()
        compose.onNodeWithText("اكشف الحديث").performClick()
        compose.onNodeWithText("ممتاز").performClick()

        compose.onNodeWithText("2 من 3").assertExists()
        compose.onNodeWithText("اكشف الحديث").performClick()
        compose.onNodeWithText("لم أحفظه").performClick()

        compose.onNodeWithText("3 من 3").assertExists()
        compose.onNodeWithText("اكشف الحديث").performClick()
        compose.onNodeWithText("جيد جدًّا").performClick()
        compose.waitForIdle()

        assertEquals(listOf(RecallBand.EXCELLENT, null, RecallBand.VERY_GOOD), listOf(1L, 2L, 3L).map(::bandOf))
        compose.onNodeWithText("عدد الأحاديث المقيَّمة: 2").assertExists()
        compose.onNodeWithText("ممتاز: 1").assertExists()
        compose.onNodeWithText("جيد جدًّا: 1").assertExists()
    }

    @Test
    fun finishingEarlyMarksTheHadithNotYetShownWithTheDefaultAndKeepsTheAnswersGiven() {
        show()
        compose.onNodeWithText("متوسط").performClick()
        compose.onNodeWithText("قيّم أحاديثي").performClick()
        compose.onNodeWithText("اكشف الحديث").performClick()
        compose.onNodeWithText("ممتاز").performClick()

        compose.onNodeWithText("أنهِ التقييم، واعتبر الباقي «ضعيف»").performClick()
        compose.waitForIdle()

        assertEquals(listOf(RecallBand.EXCELLENT, RecallBand.WEAK, RecallBand.WEAK), listOf(1L, 2L, 3L).map(::bandOf))
    }

    @Test
    fun theCheckOnlyCoversHadithThatAreNotMarkedYet() {
        reviews.mastery[1] = DualDecayScheduler().place(1, RecallBand.GOOD, 0L)
        show()

        compose.onNodeWithText("متوسط").performClick()
        compose.onNodeWithText("قيّم أحاديثي").performClick()

        compose.onNodeWithText("1 من 2").assertExists()
        compose.onNodeWithText("حديث رقم 2").assertExists()
    }

    // -- Settings ----------------------------------------------------------------------------

    @Test
    fun settingsShowsTheStartingPointAndOpensTheScreen() {
        var opened = 0
        compose.setContent {
            MutqanHadithTheme {
                SettingsScreen(
                    SettingsUi(startingPoint = StartingPoint.INTERMEDIATE),
                    "1.0",
                    SettingsActions({}, {}, {}, {}, {}, {}, {}, onOpenPlacement = { opened++ }),
                    AppLanguage.ARABIC,
                )
            }
        }

        compose.onNodeWithText("نقطة البداية").performClick()

        assertEquals(1, opened)
        compose.onNodeWithText("حالتك في حفظ الأربعين: متوسط. اضغط للتغيير أو لتقييم أحاديثك.").assertExists()
    }

    @Test
    fun settingsInvitesTheLearnerToSetTheStartingPointWhenNoneIsSet() {
        compose.setContent {
            MutqanHadithTheme { SettingsScreen(SettingsUi(), "1.0", SettingsActions({}, {}, {}, {}, {}, {}, {}), AppLanguage.ARABIC) }
        }

        compose.onNodeWithText("لم تحدّد نقطة بدايتك بعد. اضغط لتحديدها.").assertExists()
    }
}

package com.mutqan.hadith.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.BookTestResult
import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.PlacementUseCase
import com.mutqan.hadith.ui.booktest.BookTestRoute
import com.mutqan.hadith.ui.booktest.BookTestViewModel
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The test on the whole book: the grading that overwrites, the score, the sittings and what ends up in the records. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class BookTestTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()
    private val hadiths = FakeHadithRepository((1L..12L).map { hadith(it, listOf(narrator(1, "عمر", tier = "صحابي")), position = it.toInt()) })
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val placement = PlacementUseCase(hadiths, reviews, scheduler, InMemoryStudyPreferences()) { now }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun band(id: Long) = reviews.mastery[id]?.let { RecallBand.of(it.matnStability) }

    // -- Grading ---------------------------------------------------------------------------------

    @Test
    fun gradingOverwritesAnExistingRecordSoATestCanLowerAStatus() = runTest {
        reviews.mastery[1] = scheduler.place(1, RecallBand.EXCELLENT, now - DAY_MS)

        placement.grade(1, RecallBand.WEAK)

        assertEquals(RecallBand.WEAK, band(1))
        assertEquals(now, reviews.mastery.getValue(1).matnLastReviewedAt)
        assertEquals(2, reviews.mastery.getValue(1).repetitionCount)
    }

    @Test
    fun gradingKeepsTheSanadStateOfAHadithThatHasOne() = runTest {
        reviews.mastery[1] = MasteryRecordEntity(1, 20f, 6f, now - DAY_MS, now - 2 * DAY_MS, now, 4)

        placement.grade(1, RecallBand.GOOD)

        val record = reviews.mastery.getValue(1)
        assertEquals(6f, record.sanadStability)
        assertEquals(now - 2 * DAY_MS, record.sanadLastReviewedAt)
        assertEquals(RecallBand.GOOD, band(1))
    }

    @Test
    fun notMemorizedLowersAHadithThatHasARecordToWeakAndLeavesAnUnmarkedOneAlone() = runTest {
        reviews.mastery[1] = scheduler.place(1, RecallBand.VERY_GOOD, now - DAY_MS)

        placement.grade(1, null)
        placement.grade(2, null)

        assertEquals(RecallBand.WEAK, band(1))
        assertNull(reviews.mastery[2])
    }

    @Test
    fun gradingAnUnmarkedHadithMarksItAtTheBand() = runTest {
        placement.grade(3, RecallBand.EXCELLENT)

        assertEquals(RecallBand.EXCELLENT, band(3))
    }

    // -- The score -------------------------------------------------------------------------------

    @Test
    fun theScoreIsTheBandsPointsAgainstTheBestPossible() {
        assertEquals(100, BookTestResult(mapOf(RecallBand.EXCELLENT to 4), 0).percent)
        assertEquals(25, BookTestResult(mapOf(RecallBand.WEAK to 4), 0).percent)
        assertEquals(0, BookTestResult(emptyMap(), 4).percent)
        // Two excellent and two not memorized: half the points.
        assertEquals(50, BookTestResult(mapOf(RecallBand.EXCELLENT to 2), 2).percent)
        assertEquals(0, BookTestResult(emptyMap(), 0).percent)
    }

    // -- The flow --------------------------------------------------------------------------------

    private fun show() {
        val viewModel = BookTestViewModel(placement, hadiths)
        compose.setContent { MutqanHadithTheme { BookTestRoute(viewModel, onClose = {}) } }
    }

    private fun answer(label: String) {
        compose.onNodeWithText("اكشف الحديث").performClick()
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theTestCoversEveryHadithInOrderAndPausesAfterASitting() {
        show()
        compose.onNodeWithText("1 من 12").assertExists()

        repeat(10) { answer("جيد") }

        compose.onNodeWithText("أجبت عن 10 حديثًا").assertExists()
        compose.onNodeWithText("ما أجبت عنه محفوظ، وبقي 2. يمكنك المتابعة الآن أو الاكتفاء بهذا والعودة لاحقًا.").assertExists()
        assertEquals((1L..10L).toSet(), reviews.mastery.keys)

        compose.onNodeWithText("تابع الاختبار").performClick()

        compose.onNodeWithText("11 من 12").assertExists()
    }

    @Test
    fun stoppingAtTheBreakEndsTheTestWithAScoreOverWhatWasAnswered() {
        show()
        repeat(9) { answer("ممتاز") }
        answer("لم أحفظه")

        compose.onNodeWithText("أنهِ الاختبار هنا").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("نتيجة الاختبار").assertExists()
        compose.onNodeWithText("90٪").assertExists()
        compose.onNodeWithText("قُيّم 10 من 12 حديثًا").assertExists()
        compose.onNodeWithText("ممتاز: 9").assertExists()
        compose.onNodeWithText("لم أحفظه: 1").assertExists()
        assertTrue("the hadith never reached stays unmarked", reviews.mastery.keys.none { it > 10 })
        assertNull("not memorized and unmarked stays unmarked", reviews.mastery[10])
    }

    @Test
    fun answeringEveryHadithEndsTheTestAndTheStatusesFollowTheAnswers() {
        show()
        repeat(10) { answer("جيد جدًّا") }
        compose.onNodeWithText("تابع الاختبار").performClick()
        answer("ضعيف")
        answer("ممتاز")
        compose.waitForIdle()

        compose.onNodeWithText("نتيجة الاختبار").assertExists()
        compose.onNodeWithText("قُيّم 12 من 12 حديثًا").assertExists()
        assertEquals(RecallBand.VERY_GOOD, band(1))
        assertEquals(RecallBand.WEAK, band(11))
        assertEquals(RecallBand.EXCELLENT, band(12))
    }

    @Test
    fun theGradingButtonsAreOnlyThereOnceTheHadithWasRevealed() {
        show()

        compose.onNodeWithText("ممتاز").assertDoesNotExist()
        compose.onNodeWithText("اكشف الحديث").performClick()
        compose.onNodeWithText("ممتاز").assertExists()
    }
}

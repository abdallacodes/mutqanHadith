package com.mutqan.hadith.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.Retrievability
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.HadithStudy
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.home.HomeActions
import com.mutqan.hadith.ui.home.HomeScreen
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewRoute
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.study.HadithStudyScreen
import com.mutqan.hadith.ui.study.HadithStudyUi
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

private const val NOW = 1_700_000_000_000L
private const val TASHKEEL = "إِنَّمَا الأَعْمَالُ بِالنِّيَّاتِ"
private const val PLAIN = "إنما الأعمال بالنيات"

/**
 * What the review and the reader show on each memorization layer, in Arabic: the chain and its drills are folded away
 * on layer 1 and unveiled on layer 2, the transmission terms are asked for on layer 3, and an unlock is announced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A tall screen keeps every node on screen: taps outside the window are dropped.
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class LayeredScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val chain = listOf(narrator(1, "الحميدي"), narrator(2, "سفيان"), narrator(3, "يحيى"), narrator(4, "عمر", tier = "صحابي"))
    private val library = (1L..3L).map { id ->
        hadith(id, chain).let { if (id == 1L) it.copy(hadith = it.hadith.copy(matnWithTashkeel = TASHKEEL)) else it }
    }

    private val scheduler = DualDecayScheduler()
    private val hadiths = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository().also { hadiths.seenIds = { it.mastery.keys } }
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun core(id: Long, matn: Float = 8f, daysAgo: Int = 8) {
        reviews.mastery[id] = MasteryRecordEntity(id, matn, 0f, NOW - daysAgo * DAY_MS, null, NOW - DAY_MS, 3)
    }

    private fun tree(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 20f, 2f, NOW - DAY_MS, NOW - 5 * DAY_MS, NOW - DAY_MS, 4)
    }

    private fun terms(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 60f, 60f, NOW - DAY_MS, NOW - DAY_MS, NOW - 1, 8)
    }

    private fun showReview(mode: ReviewMode, targetId: Long? = null) {
        val vm = ReviewViewModel(
            mode, targetId,
            GetDueHadithsUseCase(hadiths, reviews, scheduler) { NOW },
            GetUnseenHadithsUseCase(hadiths),
            hadiths,
            SubmitReviewUseCase(hadiths, reviews, scheduler) { NOW },
            GetNarratorChainHeatmapUseCase(hadiths, reviews),
            GetLayerStatusesUseCase(reviews, scheduler),
        )
        compose.setContent {
            MutqanHadithTheme {
                ReviewRoute(
                    viewModel = vm,
                    mode = mode,
                    onClose = {},
                    onSanadBuilder = { calls += "builder$it" },
                    onMissingLink = { calls += "link$it" },
                )
            }
        }
    }

    private fun revealSanad() {
        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.onNodeWithText("اضغط لكشف السند").performClick()
        compose.waitForIdle()
    }

    // -- layer 1 ---------------------------------------------------------------------------

    @Test
    fun layerOne_foldsTheChainAwayBehindALockAndOffersNoDrills() {
        showReview(ReviewMode.NEW, targetId = 1)

        compose.onNodeWithText("الطبقة الأولى: المتن والصحابي").assertExists()
        compose.onNodeWithText("التقدّم نحو فتح شجرة السند: 0٪").assertExists()
        compose.onNodeWithText(TASHKEEL).assertExists()
        // The chain is a single locked row, not a timeline.
        compose.onNodeWithText("شجرة السند").assertExists()
        compose.onNodeWithText("أتقن لبّ الحديث لفتح شجرة السند").assertExists()
        compose.onNodeWithText("الحميدي").assertDoesNotExist()
        compose.onNodeWithText("اضغط لكشف السند").assertDoesNotExist()
        compose.onNodeWithText("صحة السلسلة").assertDoesNotExist()
        // The drills are on the chain, so they are not there either, and the matn is graded alone.
        compose.onNodeWithText("بناء السند").assertDoesNotExist()
        compose.onNodeWithText("الراوي المفقود").assertDoesNotExist()
        compose.onNodeWithText("السند").assertDoesNotExist() // no rating row for it
        compose.onAllNodesWithText("جيد").assertCountEquals(1)
    }

    @Test
    fun layerOne_putsTheCompanionAndTheTakhrijInFront() {
        showReview(ReviewMode.NEW, targetId = 1)

        compose.onNodeWithText("الصحابي: عمر").assertExists()
        compose.onNodeWithText("التخريج: متفق عليه").assertExists()
    }

    @Test
    fun layerOne_holdsTheCuesBackAsHintsWhileTheMatnIsStillBeingRecalled() {
        core(1)
        showReview(ReviewMode.DUE)

        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").assertExists()
        compose.onNodeWithText("تلميح").assertExists()
        compose.onNodeWithText("الصحابي: عمر").assertDoesNotExist()
        compose.onNodeWithText("التخريج: متفق عليه").assertDoesNotExist()
        compose.onNodeWithText(TASHKEEL).assertDoesNotExist()

        // Once the matn is shown the cues are there in full, to check against.
        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الصحابي: عمر").assertExists()
        compose.onNodeWithText("التخريج: متفق عليه").assertExists()
        compose.onNodeWithText("تلميح").assertDoesNotExist()
    }

    @Test
    fun layerOne_theTashkeelCanBeHiddenAndPeekedAt() {
        showReview(ReviewMode.NEW, targetId = 1)
        compose.onNodeWithText(TASHKEEL).assertExists()

        compose.onNodeWithText("إخفاء التشكيل").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(PLAIN).assertExists()
        compose.onNodeWithText(TASHKEEL).assertDoesNotExist()
        compose.onNodeWithText("إخفاء التشكيل").assertDoesNotExist()
        compose.onNodeWithText("استحضر ضبط الكلمات أيضًا، ثم أظهر التشكيل لتتحقق").assertExists()

        compose.onNodeWithText("إظهار التشكيل").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(TASHKEEL).assertExists()
        compose.onNodeWithText(PLAIN).assertDoesNotExist()
    }

    @Test
    fun layerOne_isFinishedOnTheMatnRatingAlone() {
        showReview(ReviewMode.NEW, targetId = 1)
        compose.onNodeWithText("إنهاء").assertIsNotEnabled()

        compose.onAllNodesWithText("جيد")[0].performClick()
        compose.onNodeWithText("إنهاء").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(0f, reviews.mastery.getValue(1L).sanadStability, 0f)
        compose.onNodeWithText("أحسنت!").assertExists()
    }

    // -- layer 2 ---------------------------------------------------------------------------

    @Test
    fun layerTwo_unveilsTheChainAndTheDrills() {
        tree(1)
        showReview(ReviewMode.DUE)

        compose.onNodeWithText("الطبقة الثانية: شجرة السند").assertExists()
        compose.onNodeWithText("أتقن لبّ الحديث لفتح شجرة السند").assertDoesNotExist()
        // The drills come with the chain, once it is on screen.
        compose.onNodeWithText("بناء السند").assertDoesNotExist()
        revealSanad()

        listOf("الحميدي", "سفيان", "يحيى", "عمر").forEach { compose.onAllNodesWithText(it)[0].assertExists() }
        compose.onNodeWithText("بناء السند").assertIsEnabled().performClick()
        compose.onNodeWithText("الراوي المفقود").assertIsEnabled().performClick()
        assertEquals(listOf("builder1", "link1"), calls)
        compose.onAllNodesWithText("جيد").assertCountEquals(2) // the matn and the sanad are both graded
    }

    @Test
    fun layerTwo_doesNotAskForTheTransmissionTermsYet() {
        tree(1)
        showReview(ReviewMode.DUE)
        revealSanad()

        compose.onAllNodesWithText("عن").assertCountEquals(0)
        compose.onAllNodesWithText("؟").assertCountEquals(0)
        compose.onNodeWithText("ثبّت السند لفتح صيغ الأداء والضبط").assertExists()
        compose.onNodeWithText("إظهار صيغ الأداء").assertDoesNotExist()
        compose.onNodeWithText("صحة السلسلة").assertDoesNotExist()
    }

    // -- layer 3 ---------------------------------------------------------------------------

    @Test
    fun layerThree_asksForTheTransmissionTermsBeforeShowingThem() {
        terms(1)
        showReview(ReviewMode.DUE)
        compose.onNodeWithText("الطبقة الثالثة: صيغ الأداء والضبط").assertExists()
        revealSanad()

        // One blank per link, and no term anywhere until the user asks.
        compose.onAllNodesWithText("؟").assertCountEquals(4)
        compose.onAllNodesWithText("عن").assertCountEquals(0)
        compose.onNodeWithText("ثبّت السند لفتح صيغ الأداء والضبط").assertDoesNotExist()

        compose.onNodeWithText("إظهار صيغ الأداء").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("عن").assertCountEquals(4)
        compose.onAllNodesWithText("؟").assertCountEquals(0)
        compose.onNodeWithText("إظهار صيغ الأداء").assertDoesNotExist()
    }

    @Test
    fun layerThree_showsHowReliablyEachNarratorWasRecalled() {
        terms(1)
        showReview(ReviewMode.DUE)
        revealSanad()

        compose.onNodeWithText("صحة السلسلة").assertExists()
    }

    // -- unlocking -------------------------------------------------------------------------

    @Test
    fun anUnlock_isAnnouncedOnTheNextCardWithTheDrillsItOpens() {
        core(1, matn = 8f, daysAgo = 30) // first, being the most overdue
        core(2, matn = 2f, daysAgo = 2)
        showReview(ReviewMode.DUE)
        compose.onNodeWithText("تم فتح شجرة السند").assertDoesNotExist()

        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("سهل")[0].performClick()
        compose.onNodeWithText("التالي").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("تم فتح شجرة السند").assertExists()
        compose.onNodeWithText("أتقنت لبّ الحديث رقم 1؛ صار سنده وتدريباته متاحة لك.").assertExists()
        compose.onNodeWithText("بناء السند").performClick()
        compose.onNodeWithText("الراوي المفقود").performClick()
        assertEquals(listOf("builder1", "link1"), calls)

        compose.onNodeWithText("حسنًا").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("تم فتح شجرة السند").assertDoesNotExist()
    }

    @Test
    fun anUnlock_onTheLastCardIsSummarizedWhenTheSessionEnds() {
        core(1)
        showReview(ReviewMode.DUE)

        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("سهل")[0].performClick()
        compose.onNodeWithText("إنهاء").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("أحسنت!").assertExists()
        compose.onNodeWithText("طبقات فُتحت في هذه الجلسة").assertExists()
        compose.onNodeWithText("الحديث رقم 1: شجرة السند").assertExists()
    }

    @Test
    fun noUnlock_meansNoBannerAndNoSummary() {
        core(1, matn = 2f, daysAgo = 2)
        showReview(ReviewMode.DUE)

        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("جيد")[0].performClick()
        compose.onNodeWithText("إنهاء").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("أحسنت!").assertExists()
        compose.onNodeWithText("طبقات فُتحت في هذه الجلسة").assertDoesNotExist()
    }

    // -- the reader ------------------------------------------------------------------------

    private fun showReader(layer: LayerStatus?, retention: Retrievability = Retrievability(0.9f, 0.6f)) {
        val study = HadithStudy(
            hadith = library[0],
            metrics = layer?.let { StabilityMetrics(2f, 0f, 0.2f, 0.24f, 0f) },
            retention = layer?.let { retention },
            nextReviewDue = layer?.let { NOW },
            isDue = false,
            layer = layer,
        )
        compose.setContent {
            MutqanHadithTheme {
                HadithStudyScreen(
                    state = UiState.Success(HadithStudyUi(study, textScale = 1f)),
                    onRetry = {}, onClose = {}, onSmaller = {}, onLarger = {}, onStart = {}, onReview = {},
                    onSanadBuilder = { calls += "builder$it" },
                    onMissingLink = { calls += "link$it" },
                )
            }
        }
    }

    @Test
    fun reader_onLayerOne_showsTheProgressAndKeepsTheDrillsLocked() {
        showReader(LayerStatus(MemorizationLayer.LAYER_1_CORE, 0.4f))

        compose.onNodeWithText("مرحلة الحفظ").assertExists()
        compose.onNodeWithText("الطبقة الأولى: المتن والصحابي").assertExists()
        compose.onNodeWithText("التقدّم نحو فتح شجرة السند: 40٪").assertExists()
        compose.onNodeWithText("تُفتح تدريبات هذا الحديث بعد أن تُتقن لبّه").assertExists()
        compose.onNodeWithText("بناء السند").assertDoesNotExist()
        compose.onNodeWithText("الراوي المفقود").assertDoesNotExist()
        // Only the matn counts toward how well it is remembered while the chain is not asked for.
        compose.onNodeWithText("المتن 90٪").assertExists()
        compose.onNodeWithText("90٪").assertExists()
    }

    @Test
    fun reader_neverRoundsTheProgressUpToTheThresholdItHasNotReached() {
        showReader(LayerStatus(MemorizationLayer.LAYER_1_CORE, 0.497f))

        compose.onNodeWithText("التقدّم نحو فتح شجرة السند: 49٪").assertExists()
    }

    @Test
    fun reader_onLayerTwo_launchesTheDrillsDirectly() {
        showReader(LayerStatus(MemorizationLayer.LAYER_2_SANAD_TREE, 0.5f))

        compose.onNodeWithText("الطبقة الثانية: شجرة السند").assertExists()
        compose.onNodeWithText("التقدّم نحو فتح صيغ الأداء: 50٪").assertExists()
        compose.onNodeWithText("تُفتح تدريبات هذا الحديث بعد أن تُتقن لبّه").assertDoesNotExist()
        compose.onNodeWithText("المتن 90٪ · السند 60٪").assertExists()
        compose.onNodeWithText("60٪").assertExists() // the weaker of the two decides

        compose.onNodeWithText("بناء السند").assertIsEnabled().performClick()
        compose.onNodeWithText("الراوي المفقود").assertIsEnabled().performClick()

        assertEquals(listOf("builder1", "link1"), calls)
    }

    @Test
    fun reader_onTheLastLayer_saysEverythingIsOpen() {
        showReader(LayerStatus(MemorizationLayer.LAYER_3_TRANSMISSION_TERMS, 1f))

        compose.onNodeWithText("الطبقة الثالثة: صيغ الأداء والضبط").assertExists()
        compose.onNodeWithText("فُتحت جميع الطبقات").assertExists()
        compose.onNodeWithText("بناء السند").assertIsEnabled()
    }

    @Test
    fun reader_ofAHadithNotInStudy_hasNoLayer() {
        showReader(layer = null)

        compose.onNodeWithText("مرحلة الحفظ").assertDoesNotExist()
        compose.onNodeWithText("بناء السند").assertDoesNotExist()
        compose.onNodeWithText("أضف إلى وِرد الحفظ").assertExists()
    }

    // -- home ------------------------------------------------------------------------------

    private fun dashboard(learning: Int, drill: Long?) = StudyDashboard(
        dueCount = 0, newCount = 0, overallMastery = 0f, items = emptyList(), drillSuggestionId = drill, learningCount = learning,
    )

    private fun showHome(dashboard: StudyDashboard) {
        val actions = HomeActions(
            onStartDueReview = {}, onStudyHadith = {},
            onSanadBuilder = { calls += "builder$it" }, onMissingLink = { calls += "link$it" }, onOpenSettings = {},
        )
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard), actions) {} } }
    }

    @Test
    fun home_whileNoSanadTreeIsOpen_explainsWhyThereAreNoDrills() {
        showHome(dashboard(learning = 2, drill = null))

        compose.onNodeWithText("تدريبات السند").assertExists()
        compose.onNodeWithText("تُفتح تدريبات السند حين تُتقن لبّ أول حديث لك").assertExists()
        compose.onNodeWithText("بناء السند").assertDoesNotExist()
        compose.onNodeWithText("الراوي المفقود").assertDoesNotExist()
    }

    @Test
    fun home_onceATreeIsOpen_offersTheDrillsOnIt() {
        showHome(dashboard(learning = 2, drill = 7))

        compose.onNodeWithText("تُفتح تدريبات السند حين تُتقن لبّ أول حديث لك").assertDoesNotExist()
        compose.onNodeWithText("بناء السند").performClick()
        compose.onNodeWithText("الراوي المفقود").performClick()

        assertEquals(listOf("builder7", "link7"), calls)
    }

    @Test
    fun home_beforeAnythingIsStudied_saysNothingAboutDrills() {
        showHome(dashboard(learning = 0, drill = null))

        compose.onNodeWithText("تدريبات السند").assertDoesNotExist()
        compose.onNodeWithText("تُفتح تدريبات السند حين تُتقن لبّ أول حديث لك").assertDoesNotExist()
        assertTrue(calls.isEmpty())
    }
}

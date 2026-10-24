package com.mutqan.hadith.ui

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeNarratorRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.DashboardItem
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.domain.usecase.SubmitLinkDrillUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.home.HomeActions
import com.mutqan.hadith.ui.home.HomeScreen
import com.mutqan.hadith.ui.missinglink.MissingLinkRoute
import com.mutqan.hadith.ui.missinglink.MissingLinkViewModel
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewRoute
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderRoute
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderViewModel
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
import kotlin.random.Random

/**
 * Smoke tests of the real screens (Compose under Robolectric) wired to real ViewModels over fakes:
 * they prove each screen renders its state and that a tap reaches the ViewModel and back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A tall screen keeps every node on screen: lazy lists compose only visible rows and taps outside the window are dropped.
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xxhdpi")
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val chain = listOf(narrator(1, "الحميدي"), narrator(2, "سفيان"), narrator(3, "يحيى"))
    private val distractors = (4L..9L).map { narrator(it, "راوي $it") }
    private val library = listOf(hadith(1, chain))

    private val scheduler = DualDecayScheduler()
    private val hadiths = FakeHadithRepository(library)
    private val narrators = FakeNarratorRepository(chain + distractors)
    private val reviews = FakeReviewRepository().also { hadiths.seenIds = { it.mastery.keys } }
    private val submitReview = SubmitReviewUseCase(hadiths, reviews, scheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theme_forces_right_to_left() {
        var direction: LayoutDirection? = null
        compose.setContent { MutqanHadithTheme { direction = LocalLayoutDirection.current } }
        compose.waitForIdle()
        assertEquals(LayoutDirection.Rtl, direction)
    }

    @Test
    fun home_shows_the_dashboard_and_routes_taps() {
        val calls = mutableListOf<String>()
        val actions = HomeActions(
            onStartDueReview = { calls += "due" },
            onLearnNew = { calls += "new" },
            onReviewHadith = { calls += "review$it" },
            onSanadBuilder = { calls += "builder$it" },
            onMissingLink = { calls += "link$it" },
            onOpenDetails = { calls += "details$it" },
            onOpenGraph = { calls += "graph" },
        )
        val dashboard = StudyDashboard(
            dueCount = 1,
            newCount = 1,
            overallMastery = 0.36f,
            items = listOf(
                DashboardItem(1, 1, "صحيح البخاري", "يحيى", StabilityMetrics(3f, 2f, 0.72f, 0.72f, 0.4f), isDue = true),
                DashboardItem(2, 2, "صحيح البخاري", "عمر", metrics = null, isDue = false),
            ),
            drillSuggestionId = 1,
        )
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard), actions) {} } }

        compose.onNodeWithText("ورد اليوم").assertExists()
        compose.onNodeWithText("ورد المراجعة (1)").performClick()
        compose.onNodeWithText("حفظ جديد (1)").performClick()
        compose.onNodeWithText("بناء السند").performClick()
        compose.onNodeWithText("الراوي المفقود").performClick()
        assertEquals(listOf("due", "new", "builder1", "link1"), calls)

        compose.onNodeWithText("الصحابي: يحيى").performClick()
        compose.onNodeWithText("مراجعة هذا الحديث").performClick()
        assertEquals("review1", calls.last())
    }

    @Test
    fun home_disables_review_when_nothing_is_due() {
        val dashboard = StudyDashboard(0, 0, 1f, emptyList(), drillSuggestionId = null)
        val actions = HomeActions({}, {}, {}, {}, {}, {}, {})
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard), actions) {} } }

        compose.onNodeWithText("ورد المراجعة (0)").assertIsNotEnabled()
        compose.onNodeWithText("أنهيت كل الأحاديث الجديدة").assertExists()
    }

    private fun reviewViewModel(mode: ReviewMode) = ReviewViewModel(
        mode, null,
        GetDueHadithsUseCase(hadiths, reviews, scheduler),
        GetUnseenHadithsUseCase(hadiths),
        hadiths,
        submitReview,
        GetNarratorChainHeatmapUseCase(hadiths, reviews),
    )

    @Test
    fun new_hadith_starts_with_the_matn_shown_and_is_rated_after_the_sanad_is_revealed() {
        val viewModel = reviewViewModel(ReviewMode.NEW)
        var closed = false
        compose.setContent { MutqanHadithTheme { ReviewRoute(viewModel, ReviewMode.NEW, onClose = { closed = true }) } }

        // A hadith never seen is studied, not recalled: its text is shown straight away.
        compose.onNodeWithText("متن 1").assertExists()
        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").assertDoesNotExist()
        compose.onAllNodesWithText("جيد")[0].assertIsEnabled()
        compose.onAllNodesWithText("جيد")[1].assertIsNotEnabled()
        compose.onNodeWithText("إنهاء").assertIsNotEnabled()

        compose.onNodeWithText("اضغط لكشف السند").performClick()
        compose.waitForIdle()
        // Each narrator now appears twice: in the chain to mark misses, and in the heatmap strip.
        compose.onAllNodesWithText("الحميدي").assertCountEquals(2)
        compose.onAllNodesWithText("يحيى").assertCountEquals(2)
        compose.onNodeWithText("صحة السلسلة").assertExists()

        compose.onAllNodesWithText("جيد")[0].performClick()
        compose.onAllNodesWithText("جيد")[1].assertIsEnabled().performClick()
        compose.onNodeWithText("إنهاء").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertTrue(1L in reviews.mastery)
        compose.onNodeWithText("أحسنت!").assertExists()
        compose.onNodeWithText("العودة إلى الرئيسية").performClick()
        assertTrue(closed)
    }

    @Test
    fun due_hadith_hides_the_matn_until_the_user_has_tried_to_recall_it() {
        reviews.mastery[1] = MasteryRecordEntity(
            hadithId = 1,
            matnStability = 2f,
            sanadStability = 2f,
            matnLastReviewedAt = 0L,
            sanadLastReviewedAt = 0L,
            nextReviewDue = 0L,
            repetitionCount = 1,
        )
        val viewModel = reviewViewModel(ReviewMode.DUE)
        compose.setContent { MutqanHadithTheme { ReviewRoute(viewModel, ReviewMode.DUE, onClose = {}) } }

        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").assertExists()
        compose.onNodeWithText("متن 1").assertDoesNotExist()
        compose.onAllNodesWithText("جيد")[0].assertIsNotEnabled()
        compose.onAllNodesWithText("جيد")[1].assertIsNotEnabled()

        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("متن 1").assertExists()
        compose.onAllNodesWithText("جيد")[0].assertIsEnabled()
    }

    @Test
    fun sanad_builder_accepts_the_chain_in_order_and_submits_it() {
        val viewModel = SanadBuilderViewModel(1, hadiths, submitReview, Random(7))
        compose.setContent { MutqanHadithTheme { SanadBuilderRoute(viewModel, onClose = {}) } }

        compose.onNodeWithText("تسليم السلسلة").assertIsNotEnabled()
        chain.forEach { compose.onNodeWithText(it.primaryName).performClick() }
        compose.waitForIdle()

        compose.onNodeWithText("تسليم السلسلة").assertIsEnabled().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("سلسلة متقنة!").assertExists()
        assertTrue(1L in reviews.mastery)
    }

    @Test
    fun sanad_builder_lets_a_wrong_placement_be_taken_back() {
        val viewModel = SanadBuilderViewModel(1, hadiths, submitReview, Random(7))
        compose.setContent { MutqanHadithTheme { SanadBuilderRoute(viewModel, onClose = {}) } }

        // The wrong narrator goes first on purpose: the second link (سفيان) into the first slot.
        compose.onNodeWithText("سفيان").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("موضع خاطئ — اضغط لإعادته").assertExists()

        compose.onNodeWithText("سفيان").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("موضع خاطئ — اضغط لإعادته").assertDoesNotExist()
    }

    @Test
    fun missing_link_marks_the_hidden_narrator_and_reports_the_answer() {
        val viewModel = MissingLinkViewModel(1, hadiths, narrators, SubmitLinkDrillUseCase(hadiths, reviews, scheduler), Random(3))
        compose.setContent { MutqanHadithTheme { MissingLinkRoute(viewModel, onClose = {}) } }

        compose.onNodeWithText("[ ؟؟؟ ]").assertExists()
        compose.onNodeWithText("السؤال التالي").assertDoesNotExist()

        val answer = (viewModel.state.value as UiState.Success).data.round.answer.primaryName
        compose.onNodeWithText(answer).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("إجابة صحيحة، أحسنت").assertExists()
        compose.onNodeWithText("[ ؟؟؟ ]").assertDoesNotExist()
        compose.onNodeWithText("السؤال التالي").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("[ ؟؟؟ ]").assertExists()
    }
}

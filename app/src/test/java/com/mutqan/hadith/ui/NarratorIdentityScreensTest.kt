package com.mutqan.hadith.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.graph.NarratorGraphScreen
import com.mutqan.hadith.ui.graph.NarratorGraphViewModel
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewRoute
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a reader sees once a narrator written two ways is one man: the hint, the Companion's name, and the network. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A tall screen keeps every node on screen: taps outside the window are dropped.
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class NarratorIdentityScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L
    private val companion = "صحابي"
    private val scheduler = DualDecayScheduler()

    private val hadiths = FakeHadithRepository(
        listOf(
            hadith(1, listOf(narrator(16, "ابن عمر", companion))),
            hadith(4, listOf(narrator(5, "نافع", "راوٍ"), narrator(42, "ابن عباس", companion))),
            hadith(5, listOf(narrator(9, "سالم", "راوٍ"), narrator(58, "عبد الله بن عباس", companion))),
        ),
    )
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun showReview() {
        reviews.mastery[1] = MasteryRecordEntity(1, 8f, 0f, now - 8 * DAY_MS, null, now - DAY_MS, 3)
        val vm = ReviewViewModel(
            ReviewMode.DUE, null,
            GetDueHadithsUseCase(hadiths, reviews, scheduler) { now },
            GetUnseenHadithsUseCase(hadiths),
            hadiths,
            SubmitReviewUseCase(hadiths, reviews, scheduler) { now },
            GetNarratorChainHeatmapUseCase(hadiths, reviews),
            GetLayerStatusesUseCase(reviews, scheduler),
        )
        compose.setContent { MutqanHadithTheme { ReviewRoute(viewModel = vm, mode = ReviewMode.DUE, onClose = {}) } }
    }

    @Test
    fun hint_aCompanionWrittenAsIbnUmarHintsFromHisCanonicalName() {
        showReview()

        compose.onNodeWithText("تلميح").performClick()
        compose.waitForIdle()

        // «ابن عمر» alone would give «ا…»; the man is «عبد الله بن عمر», whose letter is «ع».
        compose.onNodeWithText("الصحابي: عـ…").assertExists()
        compose.onNodeWithText("الصحابي: ا…").assertDoesNotExist()
    }

    @Test
    fun label_onceTheMatnIsShownTheCompanionIsNamedAsTheHintPromised() {
        showReview()

        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("عبد الله بن عمر").assertExists()
        compose.onNodeWithText("ابن عمر").assertDoesNotExist()
    }

    private fun showGraph() {
        val vm = NarratorGraphViewModel(GetNarratorGraphUseCase(hadiths, reviews), GetHadithsByNarratorUseCase(hadiths, reviews))
        compose.setContent {
            MutqanHadithTheme {
                val state by vm.state.collectAsState()
                NarratorGraphScreen(state, vm::onEvent, vm::load, {}, {}, {})
            }
        }
    }

    @Test
    fun network_aManWrittenTwoWaysIsOneNode() {
        showGraph()

        compose.onAllNodesWithText("عبد الله بن عباس").assertCountEquals(1)
        compose.onNodeWithText("ابن عباس").assertDoesNotExist()
    }

    @Test
    fun network_theProfileListsTheHadithsOfBothSpellingsAndTheOtherName() {
        showGraph()

        compose.onNodeWithText("عبد الله بن عباس").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("يُعرف أيضًا بـ: ابن عباس").assertExists()
        compose.onNodeWithText("حديث رقم 4").assertExists()
        compose.onNodeWithText("حديث رقم 5").assertExists()
        compose.onNodeWithText("حديث رقم 1").assertDoesNotExist()
    }

    @Test
    fun network_aManWithOneNameHasNoAlsoKnownAsLine() {
        showGraph()

        compose.onNodeWithText("نافع").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("يُعرف أيضًا بـ", substring = true).assertCountEquals(0)
    }
}

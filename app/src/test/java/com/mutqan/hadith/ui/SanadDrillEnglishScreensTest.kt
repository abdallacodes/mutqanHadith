package com.mutqan.hadith.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.CheckSanadDrillAccessUseCase
import com.mutqan.hadith.domain.usecase.HadithStudy
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.orderchain.OrderChainRoute
import com.mutqan.hadith.ui.orderchain.OrderChainViewModel
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** The Sanad drill suite in English: every label, instruction and closed-drill reason exists and reads. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h1600dp-xxhdpi")
class SanadDrillEnglishScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L
    private val chain4 = listOf(
        narrator(1, "Humaydi", "شيخ المصنف"), narrator(2, "Nafi"), narrator(3, "Zuhri"), narrator(4, "Abu Hurayra", "صحابي"),
    )
    private val chain1 = listOf(narrator(5, "Anas", "صحابي"))
    private val library = listOf(hadith(1, chain4), hadith(2, chain1))
    private val scheduler = DualDecayScheduler()
    private val hadiths = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun showReader(hadithId: Long) {
        val study = HadithStudy(
            hadith = library.first { it.hadith.id == hadithId },
            metrics = StabilityMetrics(2f, 0f, 0.2f, 0.24f, 0f),
            retention = null,
            nextReviewDue = now,
            isDue = false,
            layer = LayerStatus(MemorizationLayer.LAYER_2_SANAD_TREE, 0.5f),
        )
        compose.setContent {
            MutqanHadithTheme {
                HadithStudyScreen(
                    state = UiState.Success(HadithStudyUi(study, textScale = 1f)),
                    onRetry = {}, onClose = {}, onSmaller = {}, onLarger = {}, onStart = {}, onReview = {},
                    onSanadBuilder = { calls += "builder$it" }, onMissingLink = { calls += "link$it" }, onOrderChain = { calls += "order$it" },
                )
            }
        }
    }

    private fun orderVm(): OrderChainViewModel = OrderChainViewModel(
        1, hadiths, SubmitReviewUseCase(hadiths, reviews, scheduler) { now }, CheckSanadDrillAccessUseCase(reviews, scheduler), Random(4),
    )

    @Test
    fun reader_offersTheThreeDrillsOnALongChain() {
        showReader(1)

        compose.onNodeWithText("Missing Narrator").assertIsEnabled().performClick()
        compose.onNodeWithText("Order the Chain").assertIsEnabled().performClick()
        compose.onNodeWithText("Sanad Builder").assertIsEnabled().performClick()

        assertEquals(listOf("link1", "order1", "builder1"), calls)
    }

    @Test
    fun reader_saysWhyAChainOfOneHasNoDrills() {
        showReader(2)

        compose.onNodeWithText("This hadith's chain has a single narrator, so there are no drills on it").assertExists()
        compose.onNodeWithText("Order the Chain").assertDoesNotExist()
    }

    @Test
    fun orderChain_readsInEnglishFromTheInstructionToTheVerdict() {
        reviews.mastery[1] = MasteryRecordEntity(1, 20f, 2f, now - DAY_MS, now - 5 * DAY_MS, now - DAY_MS, 4)
        val vm = orderVm()
        compose.setContent { MutqanHadithTheme { OrderChainRoute(vm, onClose = {}) } }

        compose.onNodeWithText("First to narrate the hadith").assertExists()
        compose.onNodeWithText("End of the chain: the collector's teacher").assertExists()
        compose.onNodeWithText("Verify Order").performClick()
        compose.waitForIdle()
        val right = (vm.state.value as UiState.Success).data.chain.verdict!!.correctCount
        compose.onNodeWithText("$right of 4 are in the right place. Fix the red ones, then verify again.").assertExists()

        compose.onNodeWithText("Show the order").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("This is the right order, and it needs another look.").assertExists()
        compose.onNodeWithText("New attempt").assertExists()
    }

    @Test
    fun orderChain_closedOnLayerOneSaysHowItOpens() {
        reviews.mastery[1] = MasteryRecordEntity(1, 8f, 0f, now - 8 * DAY_MS, null, now - DAY_MS, 3)
        val vm = orderVm()
        compose.setContent { MutqanHadithTheme { OrderChainRoute(vm, onClose = {}) } }

        compose.onNodeWithText("This drill is closed for now").assertExists()
        compose.onNodeWithText("Sanad drills open once you master the core of the hadith. An advanced student can open them in Settings, under Unlock All Layers.").assertExists()
    }
}

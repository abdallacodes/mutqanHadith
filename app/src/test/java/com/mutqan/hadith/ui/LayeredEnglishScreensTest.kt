package com.mutqan.hadith.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The layered review and reader in English: every layer string is wired, with the same behaviour as in Arabic. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h1600dp-xxhdpi")
class LayeredEnglishScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L
    private val chain = listOf(narrator(1, "Humaydi"), narrator(2, "Sufyan"), narrator(3, "Yahya"), narrator(4, "Umar", tier = "صحابي"))
    private val library = (1L..2L).map { hadith(it, chain) }

    private val scheduler = DualDecayScheduler()
    private val hadiths = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository().also { hadiths.seenIds = { it.mastery.keys } }
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun showReview(mode: ReviewMode, targetId: Long? = null) {
        val vm = ReviewViewModel(
            mode, targetId,
            GetDueHadithsUseCase(hadiths, reviews, scheduler) { now },
            GetUnseenHadithsUseCase(hadiths),
            hadiths,
            SubmitReviewUseCase(hadiths, reviews, scheduler) { now },
            GetNarratorChainHeatmapUseCase(hadiths, reviews),
            GetLayerStatusesUseCase(reviews, scheduler),
        )
        compose.setContent {
            MutqanHadithTheme {
                ReviewRoute(vm, mode, onClose = {}, onSanadBuilder = { calls += "builder$it" }, onMissingLink = { calls += "link$it" })
            }
        }
    }

    private fun revealSanad() {
        compose.onNodeWithText("Recall the matn from memory, then tap to reveal it").performClick()
        compose.onNodeWithText("Tap to reveal the sanad").performClick()
        compose.waitForIdle()
    }

    @Test
    fun layerOne_foldsTheChainAwayAndShowsTheCues() {
        showReview(ReviewMode.NEW, targetId = 1)

        compose.onNodeWithText("Layer 1: matn and Companion").assertExists()
        compose.onNodeWithText("Progress toward unlocking the Sanad tree: 0%").assertExists()
        compose.onNodeWithText("Master the core of the hadith to unlock the Sanad tree").assertExists()
        compose.onNodeWithText("Companion: Umar").assertExists()
        compose.onNodeWithText("Takhrij: متفق عليه").assertExists()
        compose.onNodeWithText("Hide tashkeel").assertExists()
        compose.onNodeWithText("Humaydi").assertDoesNotExist()
        compose.onNodeWithText("Sanad Builder").assertDoesNotExist()
        compose.onNodeWithText("Missing Narrator").assertDoesNotExist()
        compose.onAllNodesWithText("Good").assertCountEquals(1)
    }

    @Test
    fun layerTwo_unveilsTheChainAndTheDrills() {
        reviews.mastery[1] = MasteryRecordEntity(1, 20f, 2f, now - DAY_MS, now - 5 * DAY_MS, now - DAY_MS, 4)
        showReview(ReviewMode.DUE)
        compose.onNodeWithText("Layer 2: the Sanad tree").assertExists()
        revealSanad()

        compose.onAllNodesWithText("Humaydi")[0].assertExists()
        compose.onNodeWithText("Sanad Builder").assertIsEnabled().performClick()
        compose.onNodeWithText("Missing Narrator").assertIsEnabled().performClick()
        assertEquals(listOf("builder1", "link1"), calls)
        compose.onNodeWithText("Firm up the chain to unlock transmission terms and precision").assertExists()
    }

    @Test
    fun layerThree_masksTheTransmissionTermsUntilShown() {
        reviews.mastery[1] = MasteryRecordEntity(1, 60f, 60f, now - DAY_MS, now - DAY_MS, now - 1, 8)
        showReview(ReviewMode.DUE)
        revealSanad()

        compose.onAllNodesWithText("?").assertCountEquals(4)
        compose.onAllNodesWithText("عن").assertCountEquals(0)

        compose.onNodeWithText("Show transmission terms").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("عن").assertCountEquals(4)
        compose.onAllNodesWithText("?").assertCountEquals(0)
    }

    @Test
    fun anUnlock_isAnnouncedAndSummarized() {
        reviews.mastery[1] = MasteryRecordEntity(1, 8f, 0f, now - 8 * DAY_MS, null, now - DAY_MS, 3)
        showReview(ReviewMode.DUE)

        compose.onNodeWithText("Recall the matn from memory, then tap to reveal it").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Easy")[0].performClick()
        compose.onNodeWithText("Finish").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Layers unlocked this session").assertExists()
        compose.onNodeWithText("Hadith 1: The Sanad tree").assertExists()
    }

    @Test
    fun anUnlock_bannerReadsInEnglish() {
        reviews.mastery[1] = MasteryRecordEntity(1, 8f, 0f, now - 30 * DAY_MS, null, now - DAY_MS, 3)
        reviews.mastery[2] = MasteryRecordEntity(2, 2f, 0f, now - 2 * DAY_MS, null, now - DAY_MS, 1)
        showReview(ReviewMode.DUE)

        compose.onNodeWithText("Recall the matn from memory, then tap to reveal it").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Easy")[0].performClick()
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Sanad tree unlocked").assertExists()
        compose.onNodeWithText("You mastered the core of hadith 1; its chain and drills are now open to you.").assertExists()
        compose.onNodeWithText("Got it").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sanad tree unlocked").assertDoesNotExist()
    }

    @Test
    fun narratorReview_withEveryTreeClosed_saysWhy() {
        showReview(ReviewMode.NARRATOR, targetId = 3)

        compose.onNodeWithText(
            "This narrator's hadiths are still on layer 1; master the core of each one to unlock its chain and review it from here.",
        ).assertExists()
    }

    private fun showReader(layer: LayerStatus) {
        val study = HadithStudy(
            hadith = library[0],
            metrics = StabilityMetrics(2f, 0f, 0.2f, 0.24f, 0f),
            retention = Retrievability(0.9f, 0.6f),
            nextReviewDue = now,
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
    fun reader_onLayerOne_keepsTheDrillsLocked() {
        showReader(LayerStatus(MemorizationLayer.LAYER_1_CORE, 0.4f))

        compose.onNodeWithText("Memorization stage").assertExists()
        compose.onNodeWithText("Layer 1: matn and Companion").assertExists()
        compose.onNodeWithText("Progress toward unlocking the Sanad tree: 40%").assertExists()
        compose.onNodeWithText("This hadith's drills unlock once you master its core").assertExists()
        compose.onNodeWithText("Sanad Builder").assertDoesNotExist()
        compose.onNodeWithText("Matn 90%").assertExists()
    }

    @Test
    fun reader_onLayerTwo_launchesTheDrills() {
        showReader(LayerStatus(MemorizationLayer.LAYER_2_SANAD_TREE, 0.5f))

        compose.onNodeWithText("Progress toward unlocking transmission terms: 50%").assertExists()
        compose.onNodeWithText("Matn 90% · Sanad 60%").assertExists()
        compose.onNodeWithText("Sanad Builder").performClick()
        compose.onNodeWithText("Missing Narrator").performClick()
        assertEquals(listOf("builder1", "link1"), calls)
    }

    @Test
    fun home_whileNoSanadTreeIsOpen_explainsWhyThereAreNoDrills() {
        val actions = HomeActions({}, {}, { calls += "builder$it" }, { calls += "link$it" }, {})
        val dashboard = StudyDashboard(0, 0, 0f, emptyList(), drillSuggestionId = null, learningCount = 1)
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard), actions) {} } }

        compose.onNodeWithText("Sanad drills unlock once you master the core of your first hadith").assertExists()
        compose.onNodeWithText("Sanad Builder").assertDoesNotExist()
    }
}

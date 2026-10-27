package com.mutqan.hadith.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeNarratorRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.drill.SanadDrill
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.CheckSanadDrillAccessUseCase
import com.mutqan.hadith.domain.usecase.DashboardItem
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.HadithStudy
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.domain.usecase.SubmitLinkDrillUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.home.HomeActions
import com.mutqan.hadith.ui.home.HomeScreen
import com.mutqan.hadith.ui.missinglink.MissingLinkRoute
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onFirst
import com.mutqan.hadith.ui.common.LocalOpenNarrator
import com.mutqan.hadith.ui.missinglink.MissingLinkViewModel
import com.mutqan.hadith.ui.orderchain.OrderChainRoute
import com.mutqan.hadith.ui.orderchain.OrderChainUi
import com.mutqan.hadith.ui.orderchain.OrderChainViewModel
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewRoute
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderRoute
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderViewModel
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
import kotlin.random.Random

/** The Sanad drill suite as the student meets it: the three entry points, the closed panel, and the two drills. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A tall screen keeps every row on screen: taps outside the window are dropped.
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class SanadDrillScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L
    private val companion = "صحابي"

    private val chain4 = listOf(
        narrator(1, "الحميدي", "شيخ المصنف"), narrator(2, "نافع"), narrator(3, "الزهري"), narrator(4, "أبو هريرة", companion),
    )
    private val chain2 = listOf(narrator(6, "نعيم المجمر"), narrator(7, "عائشة", companion))
    private val chain1 = listOf(narrator(5, "أنس بن مالك", companion))
    private val scheduler = DualDecayScheduler()
    private val library = listOf(hadith(1, chain4), hadith(2, chain2), hadith(3, chain1))
    private val hadiths = FakeHadithRepository(library)
    private val narrators = FakeNarratorRepository(
        chain4 + chain2 + chain1 + (20L..30L).map { narrator(it, "راوي $it") } + (31L..36L).map { narrator(it, "صحابي $it", companion) },
    )
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val access = CheckSanadDrillAccessUseCase(reviews, scheduler)
    private val submitReview = SubmitReviewUseCase(hadiths, reviews, scheduler) { now }
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun core(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 8f, 0f, now - 8 * DAY_MS, null, now - DAY_MS, 3)
    }

    private fun tree(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 20f, 2f, now - DAY_MS, now - 5 * DAY_MS, now - DAY_MS, 4)
    }

    // -- The reader ------------------------------------------------------------------------

    private fun showReader(hadithId: Long, layer: MemorizationLayer) {
        val study = HadithStudy(
            hadith = library.first { it.hadith.id == hadithId },
            metrics = StabilityMetrics(2f, 0f, 0.2f, 0.24f, 0f),
            retention = null,
            nextReviewDue = now,
            isDue = false,
            layer = LayerStatus(layer, 0.5f),
        )
        compose.setContent {
            MutqanHadithTheme {
                HadithStudyScreen(
                    state = UiState.Success(HadithStudyUi(study, textScale = 1f)),
                    onRetry = {}, onClose = {}, onSmaller = {}, onLarger = {}, onStart = {}, onReview = {},
                    onSanadBuilder = { calls += "builder$it" },
                    onMissingLink = { calls += "link$it" },
                    onOrderChain = { calls += "order$it" },
                )
            }
        }
    }

    @Test
    fun reader_aLongChainOnLayerTwoOffersAllThreeDrills() {
        showReader(1, MemorizationLayer.LAYER_2_SANAD_TREE)

        compose.onNodeWithText("الراوي المفقود").assertIsEnabled().performClick()
        compose.onNodeWithText("ترتيب السند").assertIsEnabled().performClick()
        compose.onNodeWithText("بناء السند").assertIsEnabled().performClick()

        assertEquals(listOf("link1", "order1", "builder1"), calls)
        compose.onAllNodesWithText("ترتيب السند", substring = true).assertCountEquals(1)
    }

    @Test
    fun reader_aChainOfTwoOffersNoOrderingAndSaysWhy() {
        showReader(2, MemorizationLayer.LAYER_2_SANAD_TREE)

        compose.onNodeWithText("الراوي المفقود").assertExists()
        compose.onNodeWithText("بناء السند").assertExists()
        compose.onNodeWithText("ترتيب السند").assertDoesNotExist()
        compose.onNodeWithText("ترتيب السند يحتاج سندًا من 3 رواة فأكثر").assertExists()
    }

    @Test
    fun reader_aChainOfOneOffersNoDrillAndSaysWhy() {
        showReader(3, MemorizationLayer.LAYER_2_SANAD_TREE)

        compose.onNodeWithText("الراوي المفقود").assertDoesNotExist()
        compose.onNodeWithText("ترتيب السند").assertDoesNotExist()
        compose.onNodeWithText("بناء السند").assertDoesNotExist()
        compose.onNodeWithText("سند هذا الحديث راوٍ واحد، فلا تمارين عليه").assertExists()
    }

    @Test
    fun reader_onLayerOneTheDrillsStayLockedWhateverTheChain() {
        showReader(1, MemorizationLayer.LAYER_1_CORE)

        compose.onNodeWithText("تُفتح تدريبات هذا الحديث بعد أن تُتقن لبّه").assertExists()
        compose.onNodeWithText("ترتيب السند").assertDoesNotExist()
    }

    // -- Home ------------------------------------------------------------------------------

    private fun showHome(dashboard: StudyDashboard) {
        val actions = HomeActions(
            onStartDueReview = {}, onStudyHadith = {}, onSanadBuilder = { calls += "builder$it" },
            onMissingLink = { calls += "link$it" }, onOpenSettings = {}, onOrderChain = { calls += "order$it" },
        )
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard), actions) {} } }
    }

    private fun dashboard(items: List<DashboardItem> = emptyList(), drill: Long?, drills: List<SanadDrill>) = StudyDashboard(
        dueCount = 0, newCount = 0, overallMastery = 0f, items = items, drillSuggestionId = drill, learningCount = 1,
        drillSuggestionDrills = drills,
    )

    @Test
    fun home_offersTheDrillsOpenOnTheSuggestedHadith() {
        showHome(dashboard(drill = 1, drills = listOf(SanadDrill.MISSING_NARRATOR, SanadDrill.ORDER_CHAIN, SanadDrill.SANAD_BUILDER)))

        compose.onNodeWithText("ترتيب السند").performClick()
        compose.onNodeWithText("الراوي المفقود").performClick()

        assertEquals(listOf("order1", "link1"), calls)
    }

    @Test
    fun home_whenEveryOpenChainIsTooShortSaysSoInsteadOfPretendingTheyAreLocked() {
        val opened = DashboardItem(
            hadithId = 3, hadithNumber = 3, sourceBook = "كتاب", sahabiName = "أنس بن مالك",
            metrics = StabilityMetrics(2f, 1f, 0.5f, 0.5f, 0.5f), isDue = false,
            layer = LayerStatus(MemorizationLayer.LAYER_2_SANAD_TREE, 0.5f),
        )

        showHome(dashboard(items = listOf(opened), drill = null, drills = emptyList()))

        compose.onNodeWithText("تمارين السند تحتاج حديثًا سنده راويان فأكثر، وأحاديثك المفتوحة الآن سندها راوٍ واحد").assertExists()
        compose.onNodeWithText("تُفتح تدريبات السند حين تُتقن لبّ أول حديث لك").assertDoesNotExist()
    }

    // -- After a review --------------------------------------------------------------------

    private fun showReview() {
        val vm = ReviewViewModel(
            ReviewMode.DUE, null,
            GetDueHadithsUseCase(hadiths, reviews, scheduler) { now },
            GetUnseenHadithsUseCase(hadiths),
            hadiths,
            submitReview,
            GetNarratorChainHeatmapUseCase(hadiths, reviews),
            GetLayerStatusesUseCase(reviews, scheduler),
        )
        compose.setContent {
            MutqanHadithTheme {
                ReviewRoute(vm, ReviewMode.DUE, onClose = {}, onMissingLink = { calls += "link$it" }, onOrderChain = { calls += "order$it" })
            }
        }
    }

    @Test
    fun review_afterTheSanadOfALongChainTheOrderingDrillIsOneTapAway() {
        tree(1)
        showReview()

        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.onNodeWithText("اضغط لكشف السند").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("ترتيب السند").performClick()

        assertEquals(listOf("order1"), calls)
    }

    // -- Order the chain -------------------------------------------------------------------

    private fun orderVm(hadithId: Long = 1): OrderChainViewModel =
        OrderChainViewModel(hadithId, hadiths, submitReview, access, Random(4))

    private fun OrderChainViewModel.ui(): OrderChainUi = (state.value as UiState.Success).data

    private fun OrderChainViewModel.tapIntoPlace() {
        val target = ui().chain.target
        target.forEachIndexed { place, chip ->
            val order = ui().chain.order
            if (order[place].chipId != chip.chipId) {
                compose.onNodeWithText(order[place].narrator.primaryName).performClick()
                compose.onNodeWithText(chip.narrator.primaryName).performClick()
                compose.waitForIdle()
            }
        }
    }

    @Test
    fun orderChain_showsTheScrambledNarratorsWithTheTwoEndsNamed() {
        tree(1)
        val vm = orderVm()
        compose.setContent { MutqanHadithTheme { OrderChainRoute(vm, onClose = {}) } }

        chain4.forEach { compose.onNodeWithText(it.primaryName).assertExists() }
        compose.onNodeWithText("أول من روى الحديث").assertExists()
        compose.onNodeWithText("آخر السند: شيخ المصنِّف").assertExists()
        compose.onNodeWithText("تحقق من الترتيب").assertIsEnabled()
    }

    @Test
    fun orderChain_tappingTwoNarratorsSwapsThemOnScreen() {
        tree(1)
        val vm = orderVm()
        compose.setContent { MutqanHadithTheme { OrderChainRoute(vm, onClose = {}) } }
        val before = vm.ui().chain.order

        compose.onNodeWithText(before[0].narrator.primaryName).performClick()
        compose.onNodeWithText(before[2].narrator.primaryName).performClick()
        compose.waitForIdle()

        assertEquals(before[2], vm.ui().chain.order[0])
        assertEquals(before[0], vm.ui().chain.order[2])
    }

    @Test
    fun orderChain_aWrongOrderIsMarkedAndNothingIsEnded() {
        tree(1)
        val vm = orderVm()
        compose.setContent { MutqanHadithTheme { OrderChainRoute(vm, onClose = {}) } }

        compose.onNodeWithText("تحقق من الترتيب").performClick()
        compose.waitForIdle()

        val right = vm.ui().chain.verdict!!.correctCount
        compose.onNodeWithText("$right من 4 في موضعها الصحيح. صحّح المواضع الحمراء ثم تحقق مرة أخرى.").assertExists()
        compose.onNodeWithText("الترتيب صحيح، أحسنت!").assertDoesNotExist()
        compose.onNodeWithText("تحقق من الترتيب").assertIsEnabled()
    }

    @Test
    fun orderChain_theRightOrderEndsTheRoundWithItsResult() {
        tree(1)
        val vm = orderVm()
        compose.setContent { MutqanHadithTheme { OrderChainRoute(vm, onClose = {}) } }

        vm.tapIntoPlace()
        compose.onNodeWithText("تحقق من الترتيب").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الترتيب صحيح، أحسنت!").assertExists()
        compose.onNodeWithText("سلسلة متقنة!").assertExists()
        compose.onNodeWithText("تحقق من الترتيب").assertDoesNotExist()
        compose.onNodeWithText("محاولة جديدة").assertExists()
        assertTrue(reviews.mastery.getValue(1).sanadLastReviewedAt == now)
    }

    @Test
    fun orderChain_givingUpShowsTheOrderAndSaysItNeedsAnotherLook() {
        tree(1)
        val vm = orderVm()
        compose.setContent { MutqanHadithTheme { OrderChainRoute(vm, onClose = {}) } }

        compose.onNodeWithText("أظهر الترتيب").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("هذا هو الترتيب الصحيح، وهو يحتاج إلى مراجعة.").assertExists()
        compose.onNodeWithText("محاولة جديدة").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("تحقق من الترتيب").assertExists()
    }

    @Test
    fun orderChain_theArrowsMoveANarratorOnePlace() {
        tree(1)
        val vm = orderVm()
        compose.setContent { MutqanHadithTheme { OrderChainRoute(vm, onClose = {}) } }
        val before = vm.ui().chain.order

        compose.onAllNodesWithContentDescription("حرّك إلى الأسفل")[0].performClick()
        compose.waitForIdle()

        assertEquals(before[0], vm.ui().chain.order[1])
        assertEquals(before[1], vm.ui().chain.order[0])
        // The row still shows the narrator: moving is not hiding.
        compose.onNodeWithText(before[0].narrator.primaryName).assertExists()
    }

    // -- A drill that is closed ------------------------------------------------------------

    @Test
    fun closedDrill_onLayerOneSaysHowItOpens() {
        core(1)
        val vm = orderVm()
        compose.setContent { MutqanHadithTheme { OrderChainRoute(vm, onClose = {}) } }

        compose.onNodeWithText("هذا التمرين مغلق الآن").assertExists()
        compose.onNodeWithText("تُفتح تمارين السند بعد أن تُتقن لبّ الحديث. وطالب العلم المتقدم يفتحها من الإعدادات عند «تجاوز التدرج».").assertExists()
        compose.onNodeWithText("تحقق من الترتيب").assertDoesNotExist()
    }

    @Test
    fun closedDrill_onAShortChainSaysHowManyNarratorsItNeeds() {
        tree(2)
        val vm = orderVm(hadithId = 2)
        compose.setContent { MutqanHadithTheme { OrderChainRoute(vm, onClose = {}) } }

        compose.onNodeWithText("في سند هذا الحديث 2 من الرواة، وهذا التمرين يحتاج 3 فأكثر.").assertExists()
    }

    @Test
    fun closedDrill_theBuilderIsClosedToo() {
        core(1)
        val builder = SanadBuilderViewModel(1, hadiths, submitReview, access, Random(1))
        compose.setContent { MutqanHadithTheme { SanadBuilderRoute(builder, onClose = {}) } }
        compose.onNodeWithText("هذا التمرين مغلق الآن").assertExists()
    }

    @Test
    fun closedDrill_theMissingNarratorOnAChainOfOne() {
        tree(3)
        val vm = MissingLinkViewModel(3, hadiths, narrators, SubmitLinkDrillUseCase(hadiths, reviews, scheduler), access, Random(1))
        compose.setContent { MutqanHadithTheme { MissingLinkRoute(vm, onClose = {}) } }

        compose.onNodeWithText("في سند هذا الحديث 1 من الرواة، وهذا التمرين يحتاج 2 فأكثر.").assertExists()
    }

    // -- The missing narrator --------------------------------------------------------------

    @Test
    fun missingNarrator_afterTheAnswerTheCardNamesHimAndWhereHeStands() {
        tree(1)
        val vm = MissingLinkViewModel(1, hadiths, narrators, SubmitLinkDrillUseCase(hadiths, reviews, scheduler) { now }, access, Random(3))
        compose.setContent { MutqanHadithTheme { MissingLinkRoute(vm, onClose = {}) } }
        val round = (vm.state.value as UiState.Success).data.round

        compose.onNodeWithText("[ ؟ ]").assertExists()
        compose.onNodeWithText(round.answer.name).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("إجابة صحيحة، أحسنت").assertExists()
        compose.onNodeWithText("الراوي: ${round.answer.name}").assertExists()
        round.narratedFrom?.let { compose.onNodeWithText("روى عن: ${it.name}").assertExists() }
        round.narratedTo?.let { compose.onNodeWithText("روى عنه: ${it.name}").assertExists() }
        if (round.isCollectorsTeacher) compose.onNodeWithText("هو شيخ المصنِّف").assertExists()
        if (round.isLast) compose.onNodeWithText("هو أول رواة السند").assertExists()
    }

    @Test
    fun missingNarrator_aWrongAnswerSaysWhoItWas() {
        tree(1)
        val vm = MissingLinkViewModel(1, hadiths, narrators, SubmitLinkDrillUseCase(hadiths, reviews, scheduler) { now }, access, Random(3))
        compose.setContent { MutqanHadithTheme { MissingLinkRoute(vm, onClose = {}) } }
        val round = (vm.state.value as UiState.Success).data.round
        val wrong = round.options.first { it.id != round.answer.id }

        compose.onNodeWithText(wrong.name).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الصحيح: ${round.answer.name}").assertExists()
        compose.onNodeWithText("إجابة صحيحة، أحسنت").assertDoesNotExist()
    }

    @Test
    fun missingNarrator_aNameInTheChainOpensHisPage_theHiddenOneOnlyOnceAnswered() {
        tree(1)
        val vm = MissingLinkViewModel(1, hadiths, narrators, SubmitLinkDrillUseCase(hadiths, reviews, scheduler) { now }, access, Random(3))
        val opened = mutableListOf<Long>()
        compose.setContent {
            MutqanHadithTheme { CompositionLocalProvider(LocalOpenNarrator provides { opened += it }) { MissingLinkRoute(vm, onClose = {}) } }
        }
        val round = (vm.state.value as UiState.Success).data.round
        val shown = chain4.first { it.primaryName != round.answer.name }

        compose.onAllNodesWithText(shown.primaryName).onFirst().performClick()
        assertEquals(listOf(shown.id), opened)
        // The hidden narrator has no name on screen to tap while the question is open.
        compose.onNodeWithText("[ ؟ ]").assertExists()

        compose.onNodeWithText(round.answer.name).performClick()
        compose.waitForIdle()
        opened.clear()
        compose.onAllNodesWithText(round.answer.name).onFirst().performClick()

        assertEquals(listOf(round.answer.id), opened)
    }
}

package com.mutqan.hadith.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.ChainHeatmap
import com.mutqan.hadith.domain.usecase.DashboardItem
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.details.HadithDetailsContent
import com.mutqan.hadith.ui.details.HadithDetailsUi
import com.mutqan.hadith.ui.graph.NarratorGraphScreen
import com.mutqan.hadith.ui.graph.NarratorGraphViewModel
import com.mutqan.hadith.ui.heatmap.ChainHeatmapView
import com.mutqan.hadith.ui.home.HomeActions
import com.mutqan.hadith.ui.home.HomeScreen
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewRoute
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

/** Smoke tests of the diagnostics screens: the chain heatmap, the narrator graph and the details dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A tall screen keeps every node on screen: taps outside the window are dropped.
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xxhdpi")
class DiagnosticsScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val n1 = narrator(1, "الحميدي", "شيخ المصنف")
    private val n2 = narrator(2, "سفيان", "تابع التابعين")
    private val n3 = narrator(3, "الزهري", "تابعي").copy(isPivot = true)
    private val n4 = narrator(4, "أبو هريرة", "صحابي")
    private val n5 = narrator(5, "مسدد", "شيخ المصنف")

    //   h1: n4 -> n3 -> n2 -> n1      h2: n4 -> n3 -> n2 -> n5      h3: n4 -> n5
    private val library = listOf(hadith(1, listOf(n1, n2, n3, n4)), hadith(2, listOf(n5, n2, n3, n4)), hadith(3, listOf(n5, n4)))
    private val scheduler = DualDecayScheduler()
    private val hadiths = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun record(hadithId: Long, narratorId: Long, errors: Int, successes: Int) {
        repeat(errors) { reviews.events += NarratorReviewEventEntity(hadithId = hadithId, narratorId = narratorId, reviewedAt = 1L, succeeded = false) }
        repeat(successes) { reviews.events += NarratorReviewEventEntity(hadithId = hadithId, narratorId = narratorId, reviewedAt = 1L, succeeded = true) }
    }

    private fun studied(vararg ids: Long) = ids.forEach { reviews.mastery[it] = MasteryRecordEntity(it, 1f, 1f, 1L, 1L, 2L, 1) }

    private fun heatmap(hadithId: Long): ChainHeatmap = runBlocking { GetNarratorChainHeatmapUseCase(hadiths, reviews)(hadithId) }

    // -- Chain heatmap ---------------------------------------------------------------------

    @Test
    fun heatmap_showsEveryLinkWithItsRecordTermsAndLegend() {
        record(1, 2, errors = 3, successes = 7)
        record(1, 1, errors = 0, successes = 5)
        val map = heatmap(1)
        compose.setContent { MutqanHadithTheme { ChainHeatmapView(map) } }

        listOf("الحميدي", "سفيان", "الزهري", "أبو هريرة").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("المصنّف: جامع").assertExists()
        compose.onAllNodesWithText("عن").assertCountEquals(4) // one transmission term above each link
        compose.onNodeWithText("أخطأت 3 من 10").assertExists()
        compose.onNodeWithText("أخطأت 0 من 5").assertExists()
        compose.onAllNodesWithText("لم يُختبر بعد").assertCountEquals(2) // the two links nobody has been asked about
        compose.onNodeWithText("أضعف حلقة").assertExists()
        compose.onNodeWithText("قوي (أكثر من 85٪)").assertExists()
        compose.onNodeWithText("متوسط (60–85٪)").assertExists()
        compose.onNodeWithText("هش (أقل من 60٪)").assertExists()
    }

    @Test
    fun heatmap_tappingALinkOpensItsRecord() {
        record(1, 2, errors = 3, successes = 7)
        val map = heatmap(1)
        compose.setContent { MutqanHadithTheme { ChainHeatmapView(map) } }

        compose.onNodeWithText("نسبة التذكّر").assertDoesNotExist()
        compose.onNodeWithText("سفيان").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("نسبة التذكّر").assertExists()
        compose.onNodeWithText("الأخطاء").assertExists()
        compose.onNodeWithText("مرات الاختبار").assertExists()
        compose.onNodeWithText("70٪").assertExists() // 7 of 10 remembered
        compose.onNodeWithText("الحلقة 2 من 4").assertExists()
        compose.onAllNodesWithText("سفيان").assertCountEquals(2) // the chain and the sheet title
    }

    @Test
    fun heatmap_compactModeKeepsEveryLinkTappable() {
        record(1, 4, errors = 1, successes = 1)
        val map = heatmap(1)
        compose.setContent { MutqanHadithTheme { ChainHeatmapView(map, compact = true, showLegend = false) } }

        compose.onNodeWithText("قوي (أكثر من 85٪)").assertDoesNotExist()
        listOf("الحميدي", "سفيان", "الزهري", "أبو هريرة").forEach { compose.onNodeWithText(it).assertExists() }

        compose.onNodeWithText("أبو هريرة").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("نسبة التذكّر").assertExists()
    }

    @Test
    fun heatmap_anUntestedLinkSaysSoInsteadOfShowingAPercentage() {
        val map = heatmap(1)
        compose.setContent { MutqanHadithTheme { ChainHeatmapView(map) } }

        compose.onAllNodesWithText("لم يُختبر بعد").assertCountEquals(4)
        compose.onNodeWithText("أضعف حلقة").assertDoesNotExist()

        compose.onNodeWithText("الزهري").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("نسبة التذكّر").assertDoesNotExist()
        compose.onAllNodesWithText("لم يُختبر بعد").assertCountEquals(5)
    }

    // -- Narrator graph --------------------------------------------------------------------

    private fun graphVm() = NarratorGraphViewModel(GetNarratorGraphUseCase(hadiths, reviews), GetHadithsByNarratorUseCase(hadiths, reviews))

    private fun setGraph(
        vm: NarratorGraphViewModel,
        onReview: (Long) -> Unit = {},
        onOpen: (Long) -> Unit = {},
        onClose: () -> Unit = {},
    ) = compose.setContent {
        MutqanHadithTheme {
            val state by vm.state.collectAsState()
            NarratorGraphScreen(state, vm::onEvent, vm::load, onClose, onReview, onOpen)
        }
    }

    @Test
    fun graph_drawsEveryNarratorWithItsTierAndLegend() {
        setGraph(graphVm())

        listOf("الحميدي", "سفيان", "الزهري", "أبو هريرة", "مسدد").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("شبكة الأسانيد").assertExists()
        compose.onNodeWithText("كل الأحاديث").assertExists()
        compose.onNodeWithText("المحفوظة فقط").assertExists()
        compose.onNodeWithText("مدار").assertExists()
        compose.onNodeWithText("ملاءمة العرض").assertIsEnabled()
        compose.onNodeWithText("صحابي").assertExists() // a tier label
    }

    @Test
    fun graph_tappingAPivotOpensItsProfileAndOffersToReviewIt() {
        record(1, 3, errors = 1, successes = 3)
        var reviewed: Long? = null
        setGraph(graphVm(), onReview = { reviewed = it })

        compose.onNodeWithText("راجع أحاديث هذا المدار").assertDoesNotExist()
        compose.onNodeWithText("الزهري").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("أحاديثك عبر هذا الراوي").assertExists()
        compose.onNodeWithText("حديث رقم 1").assertExists()
        compose.onNodeWithText("حديث رقم 2").assertExists()
        compose.onNodeWithText("حديث رقم 3").assertDoesNotExist() // h3 does not pass through the Zuhri
        compose.onNodeWithText("نسبة الخطأ").assertExists()
        compose.onNodeWithText("25٪").assertExists() // 1 miss in 4 attempts

        compose.onNodeWithText("راجع أحاديث هذا المدار").assertIsEnabled().performClick()
        assertEquals(3L, reviewed)
    }

    @Test
    fun graph_aPlainNarratorGetsThePlainReviewLabel() {
        setGraph(graphVm())

        compose.onNodeWithText("مسدد").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("راجع أحاديث هذا الراوي").assertIsEnabled()
        compose.onNodeWithText("راجع أحاديث هذا المدار").assertDoesNotExist()
    }

    @Test
    fun graph_aHadithInTheProfileOpensItsDetails() {
        var opened: Long? = null
        setGraph(graphVm(), onOpen = { opened = it })

        compose.onNodeWithText("الزهري").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("متن 2").performClick()

        assertEquals(2L, opened)
    }

    @Test
    fun graph_studiedOnlyFilterHidesNarratorsOfUntouchedHadiths() {
        studied(3)
        setGraph(graphVm())
        compose.onNodeWithText("الحميدي").assertExists()

        compose.onNodeWithText("المحفوظة فقط").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الحميدي").assertDoesNotExist()
        compose.onNodeWithText("الزهري").assertDoesNotExist()
        compose.onNodeWithText("مسدد").assertExists()
        compose.onNodeWithText("أبو هريرة").assertExists()

        compose.onNodeWithText("كل الأحاديث").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("الحميدي").assertExists()
    }

    @Test
    fun graph_saysSoWhenTheUserHasNotStudiedAnything() {
        setGraph(graphVm())

        compose.onNodeWithText("المحفوظة فقط").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("لم تحفظ أي حديث بعد").assertExists()
        compose.onNodeWithText("ملاءمة العرض").assertDoesNotExist()
    }

    @Test
    fun graph_aFailedLoadOffersARetry() {
        var offline = true
        val flaky = object : HadithRepository by hadiths {
            override suspend fun getAll() = if (offline) error("db closed") else hadiths.getAll()
        }
        val vm = NarratorGraphViewModel(GetNarratorGraphUseCase(flaky, reviews), GetHadithsByNarratorUseCase(flaky, reviews))
        setGraph(vm)

        compose.onNodeWithText("الزهري").assertDoesNotExist()
        offline = false
        compose.onNodeWithText("إعادة المحاولة").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الزهري").assertExists()
    }

    // -- Hadith details --------------------------------------------------------------------

    private fun detailsUi(): HadithDetailsUi {
        val h = library.first()
        return HadithDetailsUi(1, h.hadith.hadithNumber, h.hadith.sourceBook, h.hadith.chapter, h.hadith.grade, h.hadith.matnWithTashkeel, heatmap(1))
    }

    @Test
    fun details_showTheMatnAndTheChainHealthAndRouteTheButtons() {
        record(1, 3, errors = 2, successes = 1)
        var reviewed: Long? = null
        var closed = false
        compose.setContent {
            MutqanHadithTheme { HadithDetailsContent(UiState.Success(detailsUi()), {}, { closed = true }, { reviewed = it }) }
        }

        compose.onNodeWithText("تفاصيل الحديث").assertExists()
        compose.onNodeWithText("متن 1").assertExists()
        compose.onNodeWithText("صحة السلسلة").assertExists()
        listOf("الحميدي", "سفيان", "الزهري", "أبو هريرة").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("أخطأت 2 من 3").assertExists()

        compose.onNodeWithText("مراجعة هذا الحديث").assertIsEnabled().performClick()
        assertEquals(1L, reviewed)
        compose.onNodeWithText("إغلاق").performClick()
        assertTrue(closed)
    }

    @Test
    fun details_cannotBeReviewedBeforeTheyHaveLoaded() {
        var reviewed = false
        compose.setContent {
            MutqanHadithTheme { HadithDetailsContent(UiState.Loading, {}, {}, { reviewed = true }) }
        }

        compose.onNodeWithText("مراجعة هذا الحديث").assertIsNotEnabled()
        compose.onNodeWithText("إغلاق").assertIsEnabled()
        assertEquals(false, reviewed)
    }

    // -- Home entry points -----------------------------------------------------------------

    @Test
    fun home_graphIconAndDetailsActionReachTheirDestinations() {
        val calls = mutableListOf<String>()
        val actions = HomeActions(
            onStartDueReview = {}, onLearnNew = {}, onReviewHadith = {}, onSanadBuilder = {}, onMissingLink = {},
            onOpenDetails = { calls += "details$it" },
            onOpenGraph = { calls += "graph" },
            onOpenAnalytics = { calls += "analytics" },
            onOpenSettings = { calls += "settings" },
        )
        val dashboard = StudyDashboard(
            dueCount = 1,
            newCount = 0,
            overallMastery = 0.5f,
            items = listOf(DashboardItem(1, 1, "صحيح البخاري", "أبو هريرة", StabilityMetrics(3f, 2f, 0.72f, 0.72f, 0.4f), isDue = true)),
            drillSuggestionId = 1,
        )
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard), actions) {} } }

        compose.onNodeWithContentDescription("شبكة الأسانيد").performClick()
        compose.onNodeWithContentDescription("الإحصائيات").performClick()
        compose.onNodeWithContentDescription("الإعدادات").performClick()
        assertEquals(listOf("graph", "analytics", "settings"), calls)

        compose.onNodeWithText("الصحابي: أبو هريرة").performClick()
        compose.onNodeWithText("تفاصيل الحديث وصحة السند").performClick()
        assertEquals(listOf("graph", "analytics", "settings", "details1"), calls)
    }

    // -- Review of a narrator's hadiths ----------------------------------------------------

    private fun narratorReviewVm(narratorId: Long) = ReviewViewModel(
        ReviewMode.NARRATOR, narratorId,
        GetDueHadithsUseCase(hadiths, reviews, scheduler),
        GetUnseenHadithsUseCase(hadiths),
        hadiths,
        SubmitReviewUseCase(hadiths, reviews, scheduler),
        GetNarratorChainHeatmapUseCase(hadiths, reviews),
    )

    @Test
    fun narratorReview_hasItsOwnTitleAndStartsFromRecall() {
        compose.setContent { MutqanHadithTheme { ReviewRoute(narratorReviewVm(3), ReviewMode.NARRATOR, onClose = {}) } }

        compose.onNodeWithText("أحاديث الراوي").assertExists()
        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").assertExists()
    }

    @Test
    fun narratorReview_showsTheChainHealthOnceTheSanadIsRevealed() {
        record(1, 3, errors = 1, successes = 1)
        compose.setContent { MutqanHadithTheme { ReviewRoute(narratorReviewVm(3), ReviewMode.NARRATOR, onClose = {}) } }

        compose.onNodeWithText("صحة السلسلة").assertDoesNotExist()
        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.onNodeWithText("اضغط لكشف السند").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("صحة السلسلة").assertExists()
        compose.onNodeWithText("أضعف حلقة").assertExists() // the Zuhri, the only link with a miss on record
    }

    @Test
    fun narratorReview_saysSoWhenNoHadithPassesThroughTheNarrator() {
        compose.setContent { MutqanHadithTheme { ReviewRoute(narratorReviewVm(999), ReviewMode.NARRATOR, onClose = {}) } }

        compose.onNodeWithText("لا توجد أحاديث تمر بهذا الراوي.").assertExists()
    }
}

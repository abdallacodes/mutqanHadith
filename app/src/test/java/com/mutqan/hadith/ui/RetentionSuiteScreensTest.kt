package com.mutqan.hadith.ui

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
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.model.StabilityMetrics
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.HadithStudy
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.home.HomeActions
import com.mutqan.hadith.ui.home.HomeScreen
import com.mutqan.hadith.ui.review.ReviewEvent
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewRoute
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.settings.SettingsActions
import com.mutqan.hadith.ui.settings.SettingsScreen
import com.mutqan.hadith.ui.settings.SettingsUi
import com.mutqan.hadith.ui.study.HadithStudyRoute
import com.mutqan.hadith.ui.study.HadithStudyScreen
import com.mutqan.hadith.ui.study.HadithStudyUi
import com.mutqan.hadith.ui.study.HadithStudyViewModel
import com.mutqan.hadith.ui.study.MatnScale
import com.mutqan.hadith.ui.study.ReaderSettings
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val DAY = 1_700_000_000_000L
private const val MATN = "إِنَّمَا الأَعْمَالُ بِالنِّيَّاتِ"

/**
 * The retention suite on screen, in Arabic: the question before a third new hadith of the day, a backlog worked
 * through in batches with an estimate of each sitting, hints on a layer 1 card, the explanation behind a locked Sanad,
 * and the advanced student's switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A tall screen keeps every node on screen: taps outside the window are dropped.
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class RetentionSuiteScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val chain = listOf(narrator(1, "الحميدي"), narrator(2, "سفيان"), narrator(3, "يحيى"), narrator(4, "عمر", tier = "صحابي"))
    private val library = (1L..12L).map { id ->
        hadith(id, chain, level = 1).let { if (id == 1L) it.copy(hadith = it.hadith.copy(matnWithTashkeel = MATN)) else it }
    }
    private val hadiths = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val scheduler = DualDecayScheduler()
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun core(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 8f, 0f, DAY - 8 * DAY_MS, null, DAY - DAY_MS, 3)
    }

    // -- Pacing: the third new hadith of a day -----------------------------------------------

    private class MemorySettings(override var matnScale: Float = MatnScale.DEFAULT) : ReaderSettings

    private val studyPreferences = InMemoryStudyPreferences()
    private val pacing = DailyPacing(studyPreferences, { ZoneOffset.UTC }, { DAY })

    private fun startedToday(count: Int) = runBlocking {
        val start = StartMemorizingUseCase(hadiths, reviews, pacing) { DAY }
        (1L..count).forEach { start(it) }
    }

    private fun studyRoute(id: Long, onStartLearning: (Long) -> Unit = { calls += "learn$it" }) {
        val vm = HadithStudyViewModel(
            id,
            GetHadithStudyUseCase(hadiths, reviews, scheduler) { DAY },
            StartMemorizingUseCase(hadiths, reviews, pacing) { DAY },
            MemorySettings(),
            pacing,
        )
        compose.setContent {
            MutqanHadithTheme { HadithStudyRoute(vm, onClose = {}, onStartLearning = onStartLearning, onReview = {}) }
        }
    }

    @Test
    fun pacing_theFirstTwoHadithsOfTheDayStartWithoutAQuestion() {
        startedToday(1)
        studyRoute(2)

        compose.onNodeWithText("أضف إلى وِرد الحفظ").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("قليل دائم خير من كثير منقطع").assertDoesNotExist()
        assertEquals(listOf("learn2"), calls)
    }

    @Test
    fun pacing_theThirdAsksFirstAndSavingItForTomorrowLeavesItAlone() {
        startedToday(2)
        studyRoute(3)

        compose.onNodeWithText("أضف إلى وِرد الحفظ").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("قليل دائم خير من كثير منقطع").assertExists()
        compose.onNodeWithText(
            "إتقان حديث أو حديثين كل يوم يُثبّت المحفوظ على المدى البعيد، ويمنع تراكم المراجعات حتى لا تثقل عليك. وقد بلغتَ اليوم القدر المريح من الأحاديث الجديدة.",
        ).assertExists()
        compose.onNodeWithText("متابعة الحفظ").assertExists()
        assertNull("nothing is started while the question is open", reviews.mastery[3L])

        compose.onNodeWithText("الاكتفاء بما سبق").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("قليل دائم خير من كثير منقطع").assertDoesNotExist()
        compose.onNodeWithText("أضف إلى وِرد الحفظ").assertExists()
        assertNull(reviews.mastery[3L])
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun pacing_goingOnStartsTheHadithAndOpensTheLearningFlow() {
        startedToday(2)
        studyRoute(3)

        compose.onNodeWithText("أضف إلى وِرد الحفظ").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("متابعة الحفظ").performClick()
        compose.waitForIdle()

        assertEquals(listOf("learn3"), calls)
        assertNotNull(reviews.mastery[3L])
        compose.onNodeWithText("قليل دائم خير من كثير منقطع").assertDoesNotExist()
    }

    // -- The daily review on the home -----------------------------------------------------

    private val homeActions = HomeActions(
        onStartDueReview = { calls += "due" },
        onStudyHadith = { calls += "study$it" },
        onSanadBuilder = { calls += "builder$it" },
        onMissingLink = { calls += "link$it" },
        onOpenSettings = { calls += "settings" },
    )

    private fun dashboard(due: Int) = StudyDashboard(
        dueCount = due,
        newCount = 0,
        overallMastery = 0f,
        items = emptyList(),
        drillSuggestionId = null,
        learningCount = 4,
        masteredCount = 1,
        retention = 0.8f,
        streak = com.mutqan.hadith.domain.analytics.StudyStreak.None,
        nextToLearn = null,
    )

    private fun showHome(due: Int) =
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard(due)), homeActions) {} } }

    @Test
    fun home_aBacklogIsOfferedFiveAtATimeWithAKindNoteAndAnEstimate() {
        showHome(due = 12)

        compose.onNodeWithText("مراجعة اليوم (5 أحاديث من أصل 12 متأخرة)").assertExists()
        compose.onNodeWithText("سنثبت ما فاتك خطوة بخطوة دون إجهاد").assertExists()
        compose.onNodeWithText("5 أحاديث · ~8 دقائق").assertExists()
        compose.onNodeWithText("لديك 12 حديثًا مستحقًا للمراجعة").assertDoesNotExist()
    }

    @Test
    fun home_aShortListIsEstimatedAtAMinuteAndAHalfEach() {
        showHome(due = 3)

        compose.onNodeWithText("لديك 3 أحاديث مستحقة للمراجعة").assertExists()
        compose.onNodeWithText("3 أحاديث · ~4 دقائق").assertExists()
        compose.onNodeWithText("سنثبت ما فاتك خطوة بخطوة دون إجهاد").assertDoesNotExist()
    }

    @Test
    fun home_oneHadithIsAboutTwoMinutes() {
        showHome(due = 1)

        compose.onNodeWithText("حديث واحد · ~دقيقتان").assertExists()
    }

    @Test
    fun home_exactlyFiveDueIsNotYetABacklog() {
        showHome(due = 5)

        compose.onNodeWithText("لديك 5 أحاديث مستحقة للمراجعة").assertExists()
        compose.onNodeWithText("5 أحاديث · ~8 دقائق").assertExists()
        compose.onNodeWithText("سنثبت ما فاتك خطوة بخطوة دون إجهاد").assertDoesNotExist()
    }

    @Test
    fun home_withNothingDueTheCompletionBadgeStaysAndNoEstimateIsGiven() {
        showHome(due = 0)

        compose.onNodeWithText("أتممت ورد اليوم بنجاح").assertExists()
        compose.onAllNodesWithText("دقائق", substring = true).assertCountEquals(0)
        compose.onNodeWithText("ابدأ ورد اليوم").assertDoesNotExist()
    }

    // -- The review ------------------------------------------------------------------------

    private fun showReview(
        mode: ReviewMode,
        targetId: Long? = null,
        using: SpacedRepetitionScheduler = scheduler,
    ): ReviewViewModel {
        val vm = ReviewViewModel(
            mode, targetId,
            GetDueHadithsUseCase(hadiths, reviews, using) { DAY },
            GetUnseenHadithsUseCase(hadiths),
            hadiths,
            SubmitReviewUseCase(hadiths, reviews, using) { DAY },
            GetNarratorChainHeatmapUseCase(hadiths, reviews),
            GetLayerStatusesUseCase(reviews, using),
        )
        compose.setContent {
            MutqanHadithTheme {
                ReviewRoute(
                    viewModel = vm,
                    mode = mode,
                    onClose = { calls += "close" },
                    onSanadBuilder = { calls += "builder$it" },
                    onMissingLink = { calls += "link$it" },
                )
            }
        }
        return vm
    }

    private fun ReviewViewModel.finishCards(count: Int) {
        compose.runOnIdle {
            repeat(count) {
                onEvent(ReviewEvent.RateMatn(GOOD))
                onEvent(ReviewEvent.Submit)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun review_aBacklogRunsInBatchesWithABreakBetweenThem() {
        (1L..12L).forEach(::core)
        val vm = showReview(ReviewMode.DUE)

        compose.onNodeWithText("دفعة 1 من 3 · 5 أحاديث").assertExists()
        vm.finishCards(5)

        // A small success, and the choice to go on or to stop.
        compose.onNodeWithText("أتممت الدفعة 1 من 3").assertExists()
        compose.onNodeWithText("المتبقي: 7 أحاديث").assertExists()
        compose.onNodeWithText("متابعة الدفعة التالية (5 أحاديث)").assertExists()
        compose.onNodeWithText("الاكتفاء بهذا الورد اليوم").assertExists()
        compose.onNodeWithText("دفعة 1 من 3 · 5 أحاديث").assertDoesNotExist()

        compose.onNodeWithText("متابعة الدفعة التالية (5 أحاديث)").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("دفعة 2 من 3 · 5 أحاديث").assertExists()
        vm.finishCards(5)
        compose.onNodeWithText("متابعة الدفعة التالية (حديثان)").assertExists()
        compose.onNodeWithText("المتبقي: حديثان").assertExists()

        compose.onNodeWithText("متابعة الدفعة التالية (حديثان)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("دفعة 3 من 3 · حديثان").assertExists()
        vm.finishCards(2)

        // The last batch ends the session the usual way, counting every hadith reviewed.
        compose.onNodeWithText("متابعة الدفعة التالية", substring = true).assertDoesNotExist()
        compose.onNodeWithText("أحسنت!").assertExists()
    }

    @Test
    fun review_stoppingAtABreakClosesTheSession() {
        (1L..12L).forEach(::core)
        val vm = showReview(ReviewMode.DUE)
        vm.finishCards(5)

        compose.onNodeWithText("الاكتفاء بهذا الورد اليوم").performClick()
        compose.waitForIdle()

        assertEquals(listOf("close"), calls)
        // What was reviewed is saved; the rest is still due for another day.
        assertEquals(5, reviews.mastery.values.count { it.nextReviewDue > DAY })
        assertEquals(7, reviews.mastery.values.count { it.nextReviewDue <= DAY })
    }

    @Test
    fun review_fiveDueOrFewerHaveNoBatchLabel() {
        (1L..5L).forEach(::core)
        showReview(ReviewMode.DUE)

        compose.onAllNodesWithText("دفعة", substring = true).assertCountEquals(0)
    }

    // -- Hints -----------------------------------------------------------------------------

    @Test
    fun hint_firstGivesTheCompanionsLetterThenTheTakhrijAndNeverTheMatn() {
        core(1)
        showReview(ReviewMode.DUE)
        compose.onNodeWithText("الصحابي: عمر بن الخطاب").assertDoesNotExist()
        compose.onNodeWithText("التخريج: متفق عليه").assertDoesNotExist()

        compose.onNodeWithText("تلميح").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الصحابي: عـ…").assertExists()
        compose.onNodeWithText("التخريج: متفق عليه").assertDoesNotExist()
        compose.onNodeWithText("تلميح").assertExists()

        compose.onNodeWithText("تلميح").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الصحابي: عـ…").assertExists()
        compose.onNodeWithText("التخريج: متفق عليه").assertExists()
        // Nothing is left to ask for, and the card was never turned over.
        compose.onNodeWithText("تلميح").assertDoesNotExist()
        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").assertExists()
        compose.onNodeWithText(MATN).assertDoesNotExist()
        compose.onNodeWithText("الصحابي: عمر بن الخطاب").assertDoesNotExist()
    }

    @Test
    fun hint_aCardWithTheMatnShownHasNoHintButtonAndGivesTheCuesInFull() {
        core(1)
        showReview(ReviewMode.DUE)

        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("تلميح").assertDoesNotExist()
        compose.onNodeWithText("الصحابي: عمر بن الخطاب").assertExists()
        compose.onNodeWithText("التخريج: متفق عليه").assertExists()
    }

    // -- The explanation behind a locked Sanad ---------------------------------------------

    @Test
    fun lock_tappingTheLockedSanadInTheReviewExplainsTheMethod() {
        core(1)
        showReview(ReviewMode.DUE)
        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("منهجية الإتقان").assertDoesNotExist()

        compose.onNodeWithText("أتقن لبّ الحديث لفتح شجرة السند").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("منهجية الإتقان").assertExists()
        compose.onNodeWithText(
            "لا يُبنى السند حتى يثبت المتن في ذاكرتك، حمايةً للذهن من التشتت. بمجرد استقرار المتن تُفتح شجرة السند وتمارين الرواة تلقائيًا، ثم تُفتح صيغ الأداء حين يثبت السند.",
        ).assertExists()
        compose.onNodeWithText("طالب علم متقدم؟ يمكنك فتح كل الطبقات من الإعدادات، عند «تجاوز التدرج».").assertExists()

        compose.onNodeWithText("فهمت").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("منهجية الإتقان").assertDoesNotExist()
    }

    @Test
    fun lock_theReaderExplainsItToo() {
        val study = HadithStudy(
            hadith = library[0],
            metrics = StabilityMetrics(2f, 0f, 0.2f, 0.24f, 0f),
            retention = com.mutqan.hadith.domain.model.Retrievability(0.9f, 0.6f),
            nextReviewDue = DAY,
            isDue = false,
            layer = LayerStatus(MemorizationLayer.LAYER_1_CORE, 0.4f),
        )
        compose.setContent {
            MutqanHadithTheme {
                HadithStudyScreen(
                    state = UiState.Success(HadithStudyUi(study, textScale = 1f)),
                    onRetry = {}, onClose = {}, onSmaller = {}, onLarger = {}, onStart = {}, onReview = {},
                )
            }
        }

        compose.onNodeWithText("تُفتح تدريبات هذا الحديث بعد أن تُتقن لبّه").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("منهجية الإتقان").assertExists()
        compose.onNodeWithText("فهمت").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("منهجية الإتقان").assertDoesNotExist()
    }

    // -- The advanced student ---------------------------------------------------------------

    @Test
    fun advanced_withTheChoiceOnACoreCardAsksForTheChainAndHasNothingLocked() {
        core(1)
        val choice = InMemoryStudyPreferences().also { it.setUnlockAllLayers(true) }
        showReview(ReviewMode.DUE, using = DualDecayScheduler(unlockAllLayers = choice.unlockAllLayers))

        compose.onNodeWithText("استحضر المتن من ذاكرتك، ثم اضغط لكشفه").performClick()
        compose.onNodeWithText("اضغط لكشف السند").assertExists()
        compose.onNodeWithText("أتقن لبّ الحديث لفتح شجرة السند").assertDoesNotExist()
        compose.onNodeWithText("تلميح").assertDoesNotExist()
        compose.onNodeWithText("الطبقة الأولى: المتن والصحابي").assertDoesNotExist()
    }

    private fun showSettings(ui: SettingsUi) = compose.setContent {
        MutqanHadithTheme {
            SettingsScreen(
                ui, "1.0",
                SettingsActions({}, {}, {}, {}, {}, {}, {}, onUnlockAllLayersChanged = { calls += "unlock=$it" }),
            )
        }
    }

    @Test
    fun advanced_theSettingsOfferTheSwitchAndReportAChange() {
        showSettings(SettingsUi())

        compose.onNodeWithText("الدراسة").assertExists()
        compose.onNodeWithText("تجاوز التدرج (طالب علم متقدم)").assertExists()
        compose.onNodeWithText(
            "تُفتح شجرة السند وصيغ الأداء على كل الأحاديث فورًا دون انتظار ثبات المتن. يناسب من تمرّس بالحديث.",
        ).assertExists()

        compose.onNodeWithText("تجاوز التدرج (طالب علم متقدم)").performClick()

        assertEquals(listOf("unlock=true"), calls)
    }

    @Test
    fun advanced_aSwitchThatIsOnIsTurnedOffByATap() {
        showSettings(SettingsUi(unlockAllLayers = true))

        compose.onNodeWithText("تجاوز التدرج (طالب علم متقدم)").performClick()

        assertEquals(listOf("unlock=false"), calls)
    }
}

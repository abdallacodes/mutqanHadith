package com.mutqan.hadith.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.mutqan.hadith.domain.analytics.StudyStreak
import com.mutqan.hadith.domain.usecase.ComponentRetention
import com.mutqan.hadith.domain.usecase.DualRetention
import com.mutqan.hadith.domain.usecase.LibraryStatus
import com.mutqan.hadith.domain.usecase.MasteryAnalytics
import com.mutqan.hadith.domain.usecase.NextToLearn
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.home.HomeActions
import com.mutqan.hadith.ui.home.HomeScreen
import com.mutqan.hadith.ui.insights.InsightsActions
import com.mutqan.hadith.ui.insights.InsightsScreen
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The daily hub and the insights tab, in Arabic: what each state says and where each control leads. */
@RunWith(RobolectricTestRunner::class)
// A tall screen keeps every node on screen: lazy lists compose only visible rows and taps outside the window are dropped.
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class DailyDashboardScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = HomeActions(
        onStartDueReview = { calls += "due" },
        onStartAheadReview = { calls += "ahead" },
        onStartBookTest = { calls += "booktest" },
        onStudyHadith = { calls += "study$it" },
        onSanadBuilder = { calls += "builder$it" },
        onMissingLink = { calls += "link$it" },
        onOpenSettings = { calls += "settings" },
    )

    private val arbaeen1 = NextToLearn(
        hadithId = 11,
        hadithNumber = 1,
        sourceBook = "الأربعون النووية",
        sahabiName = "عمر بن الخطاب",
        matn = "إنما الأعمال بالنيات",
    )

    private fun dashboard(
        due: Int = 0,
        learning: Int = 0,
        mastered: Int = 0,
        retention: Float? = null,
        streak: StudyStreak = StudyStreak.None,
        next: NextToLearn? = null,
        drill: Long? = null,
        ahead: Int = 0,
    ) = StudyDashboard(
        dueCount = due,
        aheadCount = ahead,
        newCount = if (next == null) 0 else 5,
        overallMastery = 0f,
        items = emptyList(),
        drillSuggestionId = drill,
        learningCount = learning,
        masteredCount = mastered,
        retention = retention,
        streak = streak,
        nextToLearn = next,
    )

    private fun showHome(dashboard: StudyDashboard) =
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard), actions) {} } }

    @Test
    fun home_pullingDownAsksForARefreshOfTheLibraryAndTheProgress() {
        var refreshes = 0
        compose.setContent {
            MutqanHadithTheme {
                HomeScreen(UiState.Success(dashboard(learning = 4, ahead = 4)), actions, refreshing = false, onRefresh = { refreshes++ }) {}
            }
        }

        compose.onRoot().performTouchInput { swipeDown(startY = top + 200f, endY = top + 1_400f, durationMillis = 400) }
        compose.waitForIdle()

        assertEquals(1, refreshes)
    }

    @Test
    fun home_withNothingToRefreshAndNoPullTheRefreshIsNotAsked() {
        var refreshes = 0
        compose.setContent {
            MutqanHadithTheme {
                HomeScreen(UiState.Success(dashboard(learning = 4, ahead = 4)), actions, refreshing = true, onRefresh = { refreshes++ }) {}
            }
        }
        compose.waitForIdle()

        assertEquals(0, refreshes)
    }

    private fun streakOf(days: Int, studiedToday: Boolean = true) =
        StudyStreak(current = days, longest = days, studiedToday = studiedToday, studyDays = days, lastSevenDays = List(7) { true })

    // -- Daily review ----------------------------------------------------------------------

    @Test
    fun home_whenNothingIsDueStillOffersToReviewNowAndSaysHowItGoes() {
        showHome(dashboard(learning = 4, ahead = 4))

        compose.onNodeWithText("أتممت ورد اليوم بنجاح").assertExists()
        compose.onNodeWithText("يبدأ بالأحاديث الأضعف حفظًا، في جلسات قصيرة من خمسة أحاديث. والأفضل أن تعود غدًا لمزيد.").assertExists()

        compose.onNodeWithText("راجع الآن").performClick()

        assertEquals(listOf("ahead"), calls)
    }

    @Test
    fun home_withReviewsDueTheReviewNowOptionIsThereToo() {
        showHome(dashboard(due = 3, learning = 4, ahead = 2))

        compose.onNodeWithText("ابدأ ورد اليوم").assertExists()
        compose.onNodeWithText("راجع الآن").assertExists()
    }

    @Test
    fun home_offersTheTestOnTheWholeBook() {
        showHome(dashboard(learning = 2, next = arbaeen1))

        compose.onNodeWithText("اختبر نفسك في الأربعين").assertExists()
        compose.onNodeWithText("ابدأ الاختبار").performClick()

        assertEquals(listOf("booktest"), calls)
    }

    @Test
    fun home_withNothingInTheLibraryOffersNoBookTest() {
        showHome(dashboard())

        compose.onNodeWithText("ابدأ الاختبار").assertDoesNotExist()
    }

    @Test
    fun home_withNothingToReviewAheadThereIsNoReviewNowOption() {
        showHome(dashboard(learning = 4, ahead = 0))

        compose.onNodeWithText("راجع الآن").assertDoesNotExist()
    }

    @Test
    fun home_withReviewsDue_countsThemAndStartsTheDailyReview() {
        showHome(dashboard(due = 3, learning = 4))

        compose.onNodeWithText("ورد المراجعة").assertExists()
        compose.onNodeWithText("لديك 3 أحاديث مستحقة للمراجعة").assertExists()
        compose.onNodeWithText("أتممت ورد اليوم بنجاح").assertDoesNotExist()

        compose.onNodeWithText("ابدأ ورد اليوم").performClick()

        assertEquals(listOf("due"), calls)
    }

    @Test
    fun home_theDueCountAgreesWithItsNumberInArabic() {
        var due by mutableIntStateOf(1)
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard(due = due)), actions) {} } }

        // Arabic has a word form for one, two and a few; a longer list is a backlog and is worded as one (see the backlog tests).
        listOf(
            1 to "لديك حديث واحد مستحق للمراجعة",
            2 to "لديك حديثان مستحقان للمراجعة",
            3 to "لديك 3 أحاديث مستحقة للمراجعة",
            5 to "لديك 5 أحاديث مستحقة للمراجعة",
            11 to "مراجعة اليوم (5 أحاديث من أصل 11 متأخرة)",
            100 to "مراجعة اليوم (5 أحاديث من أصل 100 متأخرة)",
        ).forEach { (count, text) ->
            compose.runOnIdle { due = count }
            compose.onNodeWithText(text).assertExists()
        }
    }

    @Test
    fun home_withNothingDue_showsTheCompletionBadgeAndNoStartButton() {
        showHome(dashboard(due = 0, learning = 2, mastered = 1, retention = 0.9f))

        compose.onNodeWithText("أتممت ورد اليوم بنجاح").assertExists()
        compose.onNodeWithText("ابدأ ورد اليوم").assertDoesNotExist()
    }

    @Test
    fun home_beforeAnythingIsStudied_doesNotClaimACompletedDay() {
        showHome(dashboard(next = arbaeen1))

        compose.onNodeWithText("أتممت ورد اليوم بنجاح").assertDoesNotExist()
        compose.onNodeWithText("ابدأ ورد اليوم").assertDoesNotExist()
        compose.onNodeWithText("لا مراجعات بعد؛ تبدأ مراجعاتك حين تحفظ أول حديث").assertExists()
    }

    // -- The next hadith -------------------------------------------------------------------

    @Test
    fun home_nextToLearn_showsTheHadithAndStartsItsFirstStudy() {
        showHome(dashboard(next = arbaeen1))

        compose.onNodeWithText("المحطة التالية في الحفظ").assertExists()
        compose.onNodeWithText("الحديث رقم 1").assertExists()
        compose.onNodeWithText("المستوى 1").assertDoesNotExist()
        compose.onNodeWithText("الصحابي: عمر بن الخطاب").assertExists()
        compose.onNodeWithText("إنما الأعمال بالنيات").assertExists()

        compose.onNodeWithText("ابدأ حفظ هذا الحديث").performClick()

        assertEquals(listOf("study11"), calls)
    }

    @Test
    fun home_nextToLearn_staysOfferedWhileReviewsAreDue() {
        showHome(dashboard(due = 2, learning = 2, next = arbaeen1))

        compose.onNodeWithText("ابدأ ورد اليوم").assertExists()
        compose.onNodeWithText("ابدأ حفظ هذا الحديث").performClick()

        assertEquals(listOf("study11"), calls)
    }

    @Test
    fun home_withEveryHadithStarted_saysThereIsNothingNew() {
        showHome(dashboard(due = 0, learning = 3, next = null))

        compose.onNodeWithText("أنهيت كل الأحاديث الجديدة").assertExists()
        compose.onNodeWithText("ابدأ حفظ هذا الحديث").assertDoesNotExist()
    }

    // -- Streak and stats ------------------------------------------------------------------

    @Test
    fun home_streakHeader_agreesWithItsNumber() {
        showHome(dashboard(streak = streakOf(5)))

        compose.onNodeWithText("سلسلة الحفظ: 5 أيام").assertExists()
        compose.onNodeWithText("راجعت اليوم، أحسنت").assertExists()
    }

    @Test
    fun home_streakHeader_urgesTheDayIfItIsNotStudiedYet() {
        showHome(dashboard(streak = streakOf(1, studiedToday = false)))

        compose.onNodeWithText("سلسلة الحفظ: يوم واحد").assertExists()
        compose.onNodeWithText("راجع اليوم لتحافظ على سلسلتك").assertExists()
    }

    @Test
    fun home_streakHeader_isNotShownUntilThereIsAStreak() {
        showHome(dashboard())

        compose.onNodeWithText("ابدأ سلسلة الحفظ اليوم").assertDoesNotExist()
    }

    @Test
    fun home_quickStats_showLearningMasteredAndRetention() {
        showHome(dashboard(due = 1, learning = 7, mastered = 3, retention = 0.834f))

        // Each figure sits with its label in one tile ("متقن" is also the name of the app in the title bar).
        compose.onNode(hasText("7") and hasText("قيد الحفظ")).assertExists()
        compose.onNode(hasText("3") and hasText("متقن")).assertExists()
        compose.onNode(hasText("83٪") and hasText("نسبة التذكّر")).assertExists()
    }

    @Test
    fun home_quickStats_leaveRetentionEmptyUntilSomethingIsReviewed() {
        showHome(dashboard())

        compose.onNode(hasText("—") and hasText("نسبة التذكّر")).assertExists()
    }

    // -- Settings and drills ---------------------------------------------------------------

    @Test
    fun home_headerOffersTheSettings() {
        showHome(dashboard())

        compose.onNodeWithContentDescription("الإعدادات").performClick()

        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun home_keepsTheSanadDrillsForTheSuggestedHadith() {
        showHome(dashboard(learning = 1, drill = 4))

        compose.onNodeWithText("بناء السند").performClick()
        compose.onNodeWithText("الراوي المفقود").performClick()

        assertEquals(listOf("builder4", "link4"), calls)
    }

    @Test
    fun home_failedLoadOffersARetry() {
        var retried = false
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Error("boom"), actions) { retried = true } } }

        compose.onNodeWithText("إعادة المحاولة").performClick()

        assertEquals(true, retried)
    }

    // -- Insights --------------------------------------------------------------------------

    private val insightsActions = InsightsActions(
        onOpenGraph = { calls += "graph" },
        onOpenAnalytics = { calls += "analytics" },
        onOpenSettings = { calls += "settings" },
    )

    private val analytics = MasteryAnalytics(
        streak = StudyStreak(current = 5, longest = 9, studiedToday = true, studyDays = 20, lastSevenDays = listOf(true, true, false, true, true, true, true)),
        retention = DualRetention(ComponentRetention(0.82f, 12.34f), ComponentRetention(0.64f, 5f)),
        status = LibraryStatus(total = 9, unseen = 4, learning = 2, mastered = 3),
        weakestNarrators = emptyList(),
    )

    private fun showInsights(state: UiState<MasteryAnalytics> = UiState.Success(analytics), onRetry: () -> Unit = {}) =
        compose.setContent { MutqanHadithTheme { InsightsScreen(state, insightsActions, onRetry) } }

    @Test
    fun insights_offersTheGraphTheAnalyticsAndTheMemoryClocks() {
        showInsights()

        compose.onNodeWithText("الشبكة والتحليلات").assertExists()
        compose.onNodeWithText("شبكة الرواة").assertExists()
        compose.onNodeWithText("تحليلات الإتقان").assertExists()
        compose.onNodeWithText("سجل الذاكرة").assertExists()
        // Mastery analytics: how well each component holds, and how much of the library is mastered.
        compose.onNodeWithText("82٪").assertExists()
        compose.onNodeWithText("64٪").assertExists()
        compose.onNodeWithText("3 من 9 متقن").assertExists()
        // Memory clocks: the streak, and how long each component's memory holds.
        compose.onNodeWithText("سلسلة الحفظ: 5 أيام").assertExists()
        compose.onNodeWithText("ساعة المتن: 12.3 يوم").assertExists()
        compose.onNodeWithText("ساعة السند: 5 يوم").assertExists()
    }

    @Test
    fun insights_eachCardLeadsToItsScreen() {
        showInsights()

        compose.onNodeWithText("شبكة الرواة").performClick()
        compose.onNodeWithText("تحليلات الإتقان").performClick()
        compose.onNodeWithText("سجل الذاكرة").performClick()
        compose.onNodeWithText("الإعدادات").performClick()
        compose.onNodeWithContentDescription("الإعدادات").performClick()

        assertEquals(listOf("graph", "analytics", "analytics", "settings", "settings"), calls)
    }

    @Test
    fun insights_aFreshInstallShowsEmptyClocks() {
        val empty = MasteryAnalytics(
            streak = StudyStreak.None,
            retention = DualRetention(null, null),
            status = LibraryStatus(total = 10, unseen = 10, learning = 0, mastered = 0),
            weakestNarrators = emptyList(),
        )
        showInsights(UiState.Success(empty))

        compose.onNodeWithText("ابدأ سلسلة الحفظ اليوم").assertExists()
        compose.onNodeWithText("ساعة المتن: —").assertExists()
        compose.onNodeWithText("ساعة السند: —").assertExists()
        compose.onNodeWithText("0 من 10 متقن").assertExists()
    }

    @Test
    fun insights_theNetworkAndSettingsStayReachableWhileTheFiguresLoadOrFail() {
        showInsights(UiState.Loading)
        compose.onAllNodesWithText("جارٍ التحميل…").assertCountEquals(2)
        compose.onNodeWithText("شبكة الرواة").performClick()
        compose.onNodeWithText("الإعدادات").performClick()

        assertEquals(listOf("graph", "settings"), calls)
    }

    @Test
    fun insights_aFailedLoadOffersARetry() {
        var retried = 0
        showInsights(UiState.Error("boom")) { retried++ }

        compose.onAllNodesWithText("تعذّر تحميل البيانات").assertCountEquals(2)
        compose.onAllNodesWithText("إعادة المحاولة").assertCountEquals(2)
        compose.onNodeWithText("شبكة الرواة").assertExists()

        compose.onAllNodesWithText("إعادة المحاولة")[0].performClick()

        assertEquals(1, retried)
    }
}

package com.mutqan.hadith.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLibraryByLevelUseCase
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.ui.library.LibraryRoute
import com.mutqan.hadith.ui.library.LibraryViewModel
import com.mutqan.hadith.ui.study.HadithStudyRoute
import com.mutqan.hadith.ui.study.HadithStudyViewModel
import com.mutqan.hadith.ui.study.MatnScale
import com.mutqan.hadith.ui.study.ReaderSettings
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.usecase.ChainHeatmap
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.domain.analytics.StudyStreak
import com.mutqan.hadith.domain.usecase.ComponentRetention
import com.mutqan.hadith.domain.usecase.DualRetention
import com.mutqan.hadith.domain.usecase.LibraryStatus
import com.mutqan.hadith.domain.usecase.MasteryAnalytics
import com.mutqan.hadith.domain.usecase.NextToLearn
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.tierLabel
import com.mutqan.hadith.ui.graph.NarratorGraphScreen
import com.mutqan.hadith.ui.graph.NarratorGraphViewModel
import com.mutqan.hadith.ui.heatmap.ChainHeatmapView
import com.mutqan.hadith.ui.home.HomeActions
import com.mutqan.hadith.ui.home.HomeScreen
import com.mutqan.hadith.ui.insights.InsightsActions
import com.mutqan.hadith.ui.insights.InsightsScreen
import com.mutqan.hadith.ui.navigation.MutqanBottomBar
import com.mutqan.hadith.ui.settings.AppLanguage
import com.mutqan.hadith.ui.settings.SettingsActions
import com.mutqan.hadith.ui.settings.SettingsScreen
import com.mutqan.hadith.ui.settings.SettingsUi
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

/**
 * The screens with the app set to English. The interface is English and left-to-right, while everything the
 * dataset supplies (narrator names, the matn, transmission terms, collectors) stays in its original Arabic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h1600dp-xxhdpi")
class EnglishScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val n1 = narrator(1, "الحميدي", "شيخ المصنف")
    private val n2 = narrator(2, "سفيان", "تابع التابعين")
    private val n3 = narrator(3, "الزهري", "تابعي").copy(isPivot = true)
    private val n4 = narrator(4, "أبو هريرة", "صحابي")
    private val n5 = narrator(5, "مسدد", "شيخ المصنف")

    //   h1: n4 -> n3 -> n2 -> n1      h2: n4 -> n3 -> n2 -> n5      h3: n4 -> n5
    private val library = listOf(hadith(1, listOf(n1, n2, n3, n4)), hadith(2, listOf(n5, n2, n3, n4)), hadith(3, listOf(n5, n4)))
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

    private fun heatmap(hadithId: Long): ChainHeatmap = runBlocking { GetNarratorChainHeatmapUseCase(hadiths, reviews)(hadithId) }

    // -- Direction and tiers ---------------------------------------------------------------

    @Test
    fun theThemeIsLeftToRightInEnglish() {
        var direction: LayoutDirection? = null
        compose.setContent { MutqanHadithTheme { direction = LocalLayoutDirection.current } }
        compose.waitForIdle()
        assertEquals(LayoutDirection.Ltr, direction)
    }

    @Test
    fun everyTierIsNamedInEnglishAndAnUnknownOneIsLeftAsStored() {
        compose.setContent {
            MutqanHadithTheme {
                Column {
                    listOf("صحابي", "تابعي", "تابع التابعين", "شيخ المصنف", "راوٍ", "طبقة أخرى").forEach { Text(tierLabel(it)) }
                }
            }
        }

        listOf("Companion", "Successor", "Successors\' followers", "Collector\'s teacher", "Narrator", "طبقة أخرى").forEach {
            compose.onNodeWithText(it).assertExists()
        }
    }

    // -- Home and the tabs -----------------------------------------------------------------

    private val noActions = HomeActions({}, {}, {}, {}, {})

    private fun dashboard(
        due: Int = 0,
        learning: Int = 0,
        streak: StudyStreak = StudyStreak.None,
        next: NextToLearn? = null,
        retention: Float? = null,
    ) = StudyDashboard(
        dueCount = due,
        newCount = 0,
        overallMastery = 0f,
        items = emptyList(),
        drillSuggestionId = 1,
        learningCount = learning,
        masteredCount = 2,
        retention = retention,
        streak = streak,
        nextToLearn = next,
    )

    @Test
    fun home_withReviewsDue_isEnglishWhileTheHadithDataStaysArabic() {
        val next = NextToLearn(11, 1, 1, "الأربعون النووية", "عمر بن الخطاب", "إنما الأعمال بالنيات")
        val streak = StudyStreak(current = 5, longest = 5, studiedToday = true, studyDays = 5, lastSevenDays = List(7) { true })
        compose.setContent {
            MutqanHadithTheme { HomeScreen(UiState.Success(dashboard(due = 3, learning = 4, streak = streak, next = next, retention = 0.834f)), noActions) {} }
        }

        compose.onNodeWithText("Memorization streak: 5 days").assertExists()
        compose.onNodeWithText("Daily Review").assertExists()
        compose.onNodeWithText("3 hadiths are due for review").assertExists()
        compose.onNodeWithText("Start Daily Review").assertExists()
        compose.onNodeWithText("Next Step in Memorization").assertExists()
        compose.onNodeWithText("Hadith no. 1").assertExists()
        compose.onNodeWithText("Level 1").assertExists()
        compose.onNodeWithText("Companion: عمر بن الخطاب").assertExists()
        compose.onNodeWithText("إنما الأعمال بالنيات").assertExists()
        compose.onNodeWithText("Learn This Hadith").assertExists()
        compose.onNodeWithText("Learning").assertExists()
        compose.onNodeWithText("Mastered").assertExists()
        compose.onNodeWithText("Retention").assertExists()
        compose.onNodeWithText("83%").assertExists()
        compose.onNodeWithText("Sanad Builder").assertExists()
        compose.onNodeWithText("Missing Link").assertExists()
    }

    @Test
    fun home_dueCountAndStreakAgreeWithTheirNumbersInEnglish() {
        val one = StudyStreak(current = 1, longest = 1, studiedToday = false, studyDays = 1, lastSevenDays = List(7) { false })
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard(due = 1, learning = 1, streak = one)), noActions) {} } }

        compose.onNodeWithText("1 hadith is due for review").assertExists()
        compose.onNodeWithText("Memorization streak: 1 day").assertExists()
        compose.onNodeWithText("Review today to keep your streak going").assertExists()
    }

    @Test
    fun home_withNothingDue_showsTheCompletionBadge() {
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard(due = 0, learning = 2)), noActions) {} } }

        compose.onNodeWithText("You completed today\'s review").assertExists()
        compose.onNodeWithText("Come back tomorrow, or move on to a new hadith").assertExists()
        compose.onNodeWithText("Start Daily Review").assertDoesNotExist()
        compose.onNodeWithText("You\'ve finished all the new hadiths").assertExists()
    }

    @Test
    fun home_beforeAnythingIsStudied_invitesTheFirstMemorization() {
        compose.setContent { MutqanHadithTheme { HomeScreen(UiState.Success(dashboard(learning = 0).copy(masteredCount = 0)), noActions) {} } }

        compose.onNodeWithText("Start your memorization streak today").assertExists()
        compose.onNodeWithText("No reviews yet; they begin once you memorize your first hadith").assertExists()
        compose.onNodeWithText("You completed today\'s review").assertDoesNotExist()
    }

    @Test
    fun insights_isEnglishWithItsThreeCards() {
        val analytics = MasteryAnalytics(
            streak = StudyStreak(current = 5, longest = 9, studiedToday = true, studyDays = 20, lastSevenDays = List(7) { true }),
            retention = DualRetention(ComponentRetention(0.82f, 12.34f), ComponentRetention(0.64f, 5f)),
            status = LibraryStatus(total = 9, unseen = 4, learning = 2, mastered = 3),
            weakestNarrators = emptyList(),
        )
        compose.setContent { MutqanHadithTheme { InsightsScreen(UiState.Success(analytics), InsightsActions({}, {}, {}), {}) } }

        listOf(
            "Network & Insights", "Narrator Network", "Mastery Analytics", "Memory Clocks", "Settings",
            "Matn", "Sanad", "82%", "64%", "3 of 9 mastered",
            "Memorization streak: 5 days", "Matn clock: 12.3 days", "Sanad clock: 5 days",
        ).forEach { compose.onNodeWithText(it).assertExists() }
    }

    @Test
    fun theBottomBarNamesItsTabsInEnglish() {
        compose.setContent { MutqanHadithTheme { MutqanBottomBar(currentRoute = null, onSelect = {}) } }

        listOf("Daily Study", "Library", "Network & Insights").forEach { compose.onNodeWithText(it).assertExists() }
    }

    // -- Chain heatmap ---------------------------------------------------------------------

    @Test
    fun heatmap_isEnglishWithArabicNamesTermsAndTiers() {
        record(1, 2, errors = 3, successes = 7)
        record(1, 1, errors = 0, successes = 5)
        val map = heatmap(1)
        compose.setContent { MutqanHadithTheme { ChainHeatmapView(map) } }

        listOf("الحميدي", "سفيان", "الزهري", "أبو هريرة").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onAllNodesWithText("عن").assertCountEquals(4) // the transmission terms are not translated
        compose.onNodeWithText("Missed 3 of 10").assertExists()
        compose.onNodeWithText("Missed 0 of 5").assertExists()
        compose.onAllNodesWithText("Not tested yet").assertCountEquals(2)
        compose.onNodeWithText("Weakest Link").assertExists()
        compose.onNodeWithText("Strong (over 85%)").assertExists()
        compose.onNodeWithText("Fair (60–85%)").assertExists()
        compose.onNodeWithText("Fragile (under 60%)").assertExists()
        compose.onNodeWithText("Companion").assertExists()
        compose.onNodeWithText("Successors\' followers").assertExists()
    }

    @Test
    fun heatmap_aLinkOpensItsRecordInEnglish() {
        record(1, 2, errors = 3, successes = 7)
        val map = heatmap(1)
        compose.setContent { MutqanHadithTheme { ChainHeatmapView(map) } }

        compose.onNodeWithText("سفيان").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Retention").assertExists()
        compose.onNodeWithText("Errors").assertExists()
        compose.onNodeWithText("Times tested").assertExists()
        compose.onNodeWithText("70%").assertExists()
        compose.onNodeWithText("Link 2 of 4").assertExists()
    }

    // -- Narrator graph --------------------------------------------------------------------

    private fun graphVm() = NarratorGraphViewModel(GetNarratorGraphUseCase(hadiths, reviews), GetHadithsByNarratorUseCase(hadiths, reviews))

    private fun setGraph(vm: NarratorGraphViewModel) = compose.setContent {
        MutqanHadithTheme {
            val state by vm.state.collectAsState()
            NarratorGraphScreen(state, vm::onEvent, vm::load, {}, {}, {})
        }
    }

    @Test
    fun graph_isEnglishWithArabicNarratorsAndEnglishTierBands() {
        setGraph(graphVm())

        listOf("الحميدي", "سفيان", "الزهري", "أبو هريرة", "مسدد").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Isnad Network").assertExists()
        compose.onNodeWithText("All hadiths").assertExists()
        compose.onNodeWithText("Memorized only").assertExists()
        compose.onNodeWithText("Pivot").assertExists()
        compose.onNodeWithText("Companion").assertExists()
        compose.onNodeWithText("Successor").assertExists()
        compose.onNodeWithText("Collector\'s teacher").assertExists()
    }

    @Test
    fun graph_aPivotProfileIsEnglish() {
        record(1, 3, errors = 1, successes = 3)
        setGraph(graphVm())

        compose.onNodeWithText("الزهري").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Your hadiths through this narrator").assertExists()
        compose.onNodeWithText("Hadith no. 1").assertExists()
        compose.onNodeWithText("Error rate").assertExists()
        compose.onNodeWithText("25%").assertExists()
        compose.onNodeWithText("Review this pivot narrator\'s hadiths").assertExists()
    }

    // -- Library and reader ----------------------------------------------------------------

    private class MemorySettings(override var matnScale: Float = MatnScale.DEFAULT) : ReaderSettings

    @Test
    fun library_isEnglishWhileTheHadithTextStaysArabic() {
        val vm = LibraryViewModel(GetLibraryByLevelUseCase(hadiths, reviews, DualDecayScheduler()) { 1_700_000_000_000L })
        compose.setContent { MutqanHadithTheme { LibraryRoute(vm, onClose = {}, onOpenHadith = {}) } }

        compose.onNodeWithText("Library").assertExists()
        compose.onNodeWithText("Search the hadith text…").assertExists()
        listOf("Level 1: Core Foundations", "Level 2: Hadiths of Rulings", "Level 3: The Sunnah Collections").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        // Level 1 has none of the fixtures: it says so, in English.
        compose.onNodeWithText("Coming soon").assertExists()

        compose.onNodeWithText("Level 3: The Sunnah Collections").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("New: 3").assertExists()
        compose.onNodeWithText("Learning: 0").assertExists()
        compose.onAllNodesWithText("New").assertCountEquals(3)
        compose.onAllNodesWithText("Companion: أبو هريرة").assertCountEquals(3)
    }

    @Test
    fun reader_isEnglishWhileTheHadithAndItsChainStayArabic() {
        val vm = HadithStudyViewModel(
            1,
            GetHadithStudyUseCase(hadiths, reviews, DualDecayScheduler()) { 1_700_000_000_000L },
            StartMemorizingUseCase(hadiths, reviews) { 1_700_000_000_000L },
            MemorySettings(),
        )
        compose.setContent { MutqanHadithTheme { HadithStudyRoute(vm, onClose = {}, onStartLearning = {}, onReview = {}) } }

        compose.onNodeWithText("Hadith Reader").assertExists()
        compose.onNodeWithText("Matn").assertExists()
        compose.onNodeWithText("Chain of Transmission").assertExists()
        compose.onNodeWithText("Hadith no. 1").assertExists()
        compose.onNodeWithText("Takhrij: متفق عليه").assertExists()
        compose.onNodeWithContentDescription("Smaller text").assertExists()
        compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithText("Copy").assertExists()
        // The chain: the compiler, then each narrator with his tier worded in English and his name as the dataset gives it.
        compose.onNodeWithText("Compiler").assertExists()
        listOf("الحميدي", "سفيان", "الزهري", "أبو هريرة").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Collector\'s teacher").assertExists()
        compose.onNodeWithText("Start Memorizing").assertExists()
        compose.onNodeWithText("You will learn its matn, then its sanad, and it joins your review schedule.").assertExists()
    }

    // -- Settings --------------------------------------------------------------------------

    @Test
    fun settings_isEnglishAndTheLanguageRowIsBilingual() {
        compose.setContent {
            MutqanHadithTheme { SettingsScreen(SettingsUi(), "1.0", SettingsActions({}, {}, {}, {}, {}, {}, {}), AppLanguage.ENGLISH) }
        }

        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Language / اللغة").assertExists()
        compose.onNodeWithText("English").assertExists()
        compose.onNodeWithText("Export a backup").assertExists()
        compose.onNodeWithText("Reset progress").assertExists()
        compose.onNodeWithText("Version 1.0").assertExists()
    }

    @Test
    fun settings_aboutDialogIsEnglish() {
        compose.setContent {
            MutqanHadithTheme { SettingsScreen(SettingsUi(), "1.0", SettingsActions({}, {}, {}, {}, {}, {}, {}), AppLanguage.ENGLISH) }
        }

        compose.onNodeWithText("About Mutqan - Hadith").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Part of the Mutqan Suite").assertExists()
        compose.onNodeWithText("The Method").assertExists()
    }
}

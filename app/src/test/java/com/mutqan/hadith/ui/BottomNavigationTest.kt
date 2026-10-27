package com.mutqan.hadith.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.di.AppContainer
import com.mutqan.hadith.ui.navigation.MutqanBottomBar
import com.mutqan.hadith.ui.navigation.MutqanNavHost
import com.mutqan.hadith.ui.navigation.Routes
import com.mutqan.hadith.ui.navigation.TopLevelTab
import com.mutqan.hadith.ui.navigation.navigateToTab
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

/** The three-tab bottom bar: how it switches, what a tab remembers, and when it gives way to a pushed screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A tall screen keeps every node on screen: lazy lists compose only visible rows and taps outside the window are dropped.
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class BottomNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A tab of the bar by its label, apart from any title of the same words on the screen it shows. */
    private fun tab(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    // -- The bar and its navigation, over stand-in screens ----------------------------------

    private lateinit var nav: NavHostController

    private fun showStandIns() {
        compose.setContent {
            MutqanHadithTheme {
                nav = rememberNavController()
                val route = nav.currentBackStackEntryAsState().value?.destination?.route
                Scaffold(bottomBar = { MutqanBottomBar(route, nav::navigateToTab) }) { padding ->
                    NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
                        composable(Routes.HOME) { Text("home screen") }
                        composable(Routes.LIBRARY) {
                            var count by rememberSaveable { mutableIntStateOf(0) }
                            Column {
                                Text("library screen $count")
                                Button(onClick = { count++ }) { Text("add one") }
                            }
                        }
                        composable(Routes.INSIGHTS) { Text("insights screen") }
                    }
                }
            }
        }
    }

    @Test
    fun theBarListsTheThreeTabsWithTheFirstSelected() {
        showStandIns()

        listOf("الورد اليومي", "المكتبة", "الشبكة والتحليلات").forEach { tab(it).assertExists() }
        tab("الورد اليومي").assertIsSelected()
        compose.onNodeWithText("home screen").assertExists()
    }

    @Test
    fun choosingATabShowsItAndMarksItSelected() {
        showStandIns()

        tab("المكتبة").performClick()
        compose.onNodeWithText("library screen 0").assertExists()
        tab("المكتبة").assertIsSelected()

        tab("الشبكة والتحليلات").performClick()
        compose.onNodeWithText("insights screen").assertExists()
        tab("الشبكة والتحليلات").assertIsSelected()

        tab("الورد اليومي").performClick()
        compose.onNodeWithText("home screen").assertExists()
        tab("الورد اليومي").assertIsSelected()
    }

    @Test
    fun aTabKeepsItsStateWhileAnotherIsShown() {
        showStandIns()
        tab("المكتبة").performClick()
        compose.onNodeWithText("add one").performClick()
        compose.onNodeWithText("add one").performClick()
        compose.onNodeWithText("library screen 2").assertExists()

        tab("الشبكة والتحليلات").performClick()
        compose.onNodeWithText("library screen 2").assertDoesNotExist()
        tab("المكتبة").performClick()

        compose.onNodeWithText("library screen 2").assertExists()
    }

    @Test
    fun choosingTheTabAlreadyShownDoesNotStackIt() {
        showStandIns()
        tab("المكتبة").performClick()

        tab("المكتبة").performClick()
        tab("المكتبة").performClick()

        // Back leaves for the first tab at once, not through copies of the library.
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("home screen").assertExists()
        assertEquals(Routes.HOME, nav.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun backFromAnyTabReturnsToTheFirstOne() {
        showStandIns()
        tab("المكتبة").performClick()
        tab("الشبكة والتحليلات").performClick()

        compose.runOnIdle { nav.popBackStack() }

        compose.onNodeWithText("home screen").assertExists()
        tab("الورد اليومي").assertIsSelected()
    }

    @Test
    fun theTabsAreTheOnlyTopLevelRoutes() {
        assertEquals(
            listOf(Routes.HOME, Routes.LIBRARY, Routes.INSIGHTS),
            TopLevelTab.entries.map { it.route },
        )
        assertEquals(false, TopLevelTab.isTopLevel(Routes.REVIEW))
        assertEquals(false, TopLevelTab.isTopLevel(Routes.SETTINGS))
        assertEquals(false, TopLevelTab.isTopLevel(Routes.NARRATOR_GRAPH))
        assertEquals(false, TopLevelTab.isTopLevel(null))
    }

    // -- The app, over its real screens and a fresh database ---------------------------------

    private fun showApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context) {
            MutqanDatabase.builder(it, name = null).allowMainThreadQueries().setQueryExecutor { command -> command.run() }.build()
        }
        compose.setContent { MutqanHadithTheme { MutqanNavHost(container) } }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText("ابدأ حفظ هذا الحديث")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theAppOpensOnTheDailyHubWhichPointsAtTheFirstHadithOfTheCurriculum() {
        showApp()

        tab("الورد اليومي").assertIsSelected()
        compose.onNodeWithText("سلسلة الحفظ").assertDoesNotExist()
        compose.onNodeWithText("ابدأ سلسلة الحفظ اليوم").assertDoesNotExist()
        // A fresh install has nothing to review and no day to claim as done.
        compose.onNodeWithText("لا مراجعات بعد؛ تبدأ مراجعاتك حين تحفظ أول حديث").assertExists()
        compose.onNodeWithText("المحطة التالية في الحفظ").assertExists()
        compose.onNodeWithText("الحديث رقم 1").assertExists()
        // There are no levels any more: the hadith is told by its number alone.
        compose.onNodeWithText("المستوى 1").assertDoesNotExist()
    }

    @Test
    fun theTabsOpenTheLibraryAndTheInsightsWithoutABackArrow() {
        showApp()

        tab("المكتبة").performClick()
        // The shelf of books: the Forty Hadith, and a note that more will come. Nothing is searched from here.
        compose.onNodeWithText("الأربعون النووية").assertExists()
        compose.onNodeWithText("قريبًا بإذن الله").assertExists()
        compose.onNodeWithText("ابحث في نص الحديث…").assertDoesNotExist()
        compose.onNodeWithContentDescription("رجوع").assertDoesNotExist()
        tab("المكتبة").assertIsSelected()

        tab("الشبكة والتحليلات").performClick()
        compose.onNodeWithText("شبكة الرواة").assertExists()
        compose.onNodeWithText("تحليلات الإتقان").assertExists()
        compose.onNodeWithText("سجل الذاكرة").assertExists()
        compose.onNodeWithContentDescription("رجوع").assertDoesNotExist()
        tab("الشبكة والتحليلات").assertIsSelected()
    }

    @Test
    fun theLibraryKeepsItsSearchAcrossTabs() {
        showApp()
        tab("المكتبة").performClick()
        compose.onNodeWithText("الأربعون النووية").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("الأعمال")

        tab("الورد اليومي").performClick()
        tab("المكتبة").performClick()

        // The library is still in the book, with the search in it.
        compose.onNode(hasSetTextAction()).assertTextContains("الأعمال")
    }

    @Test
    fun aPushedScreenTakesTheBarAndBackBringsItBack() {
        showApp()

        compose.onNodeWithText("ابدأ حفظ هذا الحديث").performClick()
        compose.waitForIdle()

        // The reader is a task of its own: no bar under it, a back arrow above it.
        compose.onNodeWithText("قراءة الحديث").assertExists()
        tab("المكتبة").assertDoesNotExist()
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.waitForIdle()

        tab("الورد اليومي").assertIsSelected()
        compose.onNodeWithText("ابدأ حفظ هذا الحديث").assertExists()
    }

    @Test
    fun theNarratorNetworkOpensFromTheInsightsTabAndReturnsToIt() {
        showApp()
        tab("الشبكة والتحليلات").performClick()

        compose.onNodeWithText("شبكة الرواة").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("شبكة الأسانيد").assertExists()
        tab("المكتبة").assertDoesNotExist()
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.waitForIdle()

        tab("الشبكة والتحليلات").assertIsSelected()
        compose.onNodeWithText("تحليلات الإتقان").assertExists()
    }

    @Test
    fun settingsOpenFromTheDailyHubAndFromTheInsights() {
        showApp()

        compose.onNodeWithContentDescription("الإعدادات").performClick()
        compose.waitForIdle()
        tab("المكتبة").assertDoesNotExist()
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.waitForIdle()

        tab("الشبكة والتحليلات").performClick()
        compose.onNodeWithText("الإعدادات").performClick()
        compose.waitForIdle()
        tab("المكتبة").assertDoesNotExist()
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.waitForIdle()

        tab("الشبكة والتحليلات").assertIsSelected()
    }
}

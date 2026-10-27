package com.mutqan.hadith.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.ui.graph.NarratorGraphScreen
import com.mutqan.hadith.ui.graph.NarratorGraphViewModel
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

/**
 * The narrator graph is a coordinate plane: an edge is drawn between two points and a node sits on one of
 * them, so none of that may flip when the app is switched between right-to-left (Arabic) and left-to-right
 * (English). Only the chrome around the canvas follows the direction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class GraphDirectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val names = listOf("الحميدي", "سفيان", "الزهري", "أبو هريرة", "مسدد")

    //   h1: n4 -> n3 -> n2 -> n1      h2: n4 -> n3 -> n2 -> n5      h3: n4 -> n5
    private val n1 = narrator(1, names[0], "شيخ المصنف")
    private val n2 = narrator(2, names[1], "تابع التابعين")
    private val n3 = narrator(3, names[2], "تابعي").copy(isPivot = true)
    private val n4 = narrator(4, names[3], "صحابي")
    private val n5 = narrator(5, names[4], "شيخ المصنف")
    private val hadiths = FakeHadithRepository(listOf(hadith(1, listOf(n1, n2, n3, n4)), hadith(2, listOf(n5, n2, n3, n4)), hadith(3, listOf(n5, n4))))
    private val reviews = FakeReviewRepository()

    private var direction by mutableStateOf(LayoutDirection.Ltr)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun showGraph() {
        val vm = NarratorGraphViewModel(GetNarratorGraphUseCase(hadiths, reviews))
        compose.setContent {
            MutqanHadithTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val state by vm.state.collectAsState()
                    NarratorGraphScreen(state, vm::onEvent, vm::load, {}, {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun nodeBounds() = names.associateWith { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }

    @Test
    fun nodesStayWhereTheLayoutPutThemInBothDirections() {
        showGraph()
        val leftToRight = nodeBounds()
        val filterInLeftToRight = compose.onNodeWithText("كل الأحاديث").fetchSemanticsNode().boundsInRoot

        direction = LayoutDirection.Rtl
        compose.waitForIdle()
        val rightToLeft = nodeBounds()
        val filterInRightToLeft = compose.onNodeWithText("كل الأحاديث").fetchSemanticsNode().boundsInRoot

        assertTrue("the chrome around the canvas does mirror, so the switch took effect", filterInLeftToRight != filterInRightToLeft)
        assertEquals(leftToRight, rightToLeft)
    }

    @Test
    fun generationsStillRunTopToBottomFromTheCompanionInBothDirections() {
        showGraph()
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { layoutDirection ->
            direction = layoutDirection
            compose.waitForIdle()
            val bounds = nodeBounds()
            val tops = listOf("أبو هريرة", "الزهري", "سفيان", "الحميدي").map { bounds.getValue(it).top }
            assertEquals("$layoutDirection: ${tops}", tops.sorted(), tops)
            assertTrue("$layoutDirection: each generation is on its own row", tops.zipWithNext().all { (a, b) -> a < b })
        }
    }

    @Test
    fun theTierLabelsStayOnTheRightEdgeInBothDirections() {
        showGraph()
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { layoutDirection ->
            direction = layoutDirection
            compose.waitForIdle()
            val label = compose.onNodeWithText("صحابي").fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("$layoutDirection: label at ${label.center.x} of ${root.width}", label.center.x > root.width / 2)
        }
    }
}

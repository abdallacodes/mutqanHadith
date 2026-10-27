package com.mutqan.hadith.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.ui.common.LocalOpenCompanion
import com.mutqan.hadith.ui.common.LocalOpenNarrator
import com.mutqan.hadith.ui.common.SahabiCard
import com.mutqan.hadith.ui.common.companionOpener
import com.mutqan.hadith.ui.study.SanadTimeline
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A narrator's name opens his page wherever the app lets it, and is plain text where nothing can open it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class NarratorLinksTest {

    @get:Rule
    val compose = createComposeRule()

    private val chain = hadith(1, listOf(narrator(10, "الحميدي", "شيخ المصنف"), narrator(11, "الزهري"), narrator(12, "أبو هريرة", "صحابي"))).sanad

    @Test
    fun aNameInTheChainOpensThatNarratorsPage() {
        val opened = mutableListOf<Long>()
        compose.setContent {
            MutqanHadithTheme {
                CompositionLocalProvider(LocalOpenNarrator provides { opened += it }) { SanadTimeline(collector = "البخاري", links = chain) }
            }
        }

        compose.onNodeWithText("الزهري").performClick()
        compose.onNodeWithText("أبو هريرة").performClick()

        assertEquals(listOf(11L, 12L), opened)
    }

    @Test
    fun theCompilerAtTheHeadIsNotANarratorAndOpensNothing() {
        val opened = mutableListOf<Long>()
        compose.setContent {
            MutqanHadithTheme {
                CompositionLocalProvider(LocalOpenNarrator provides { opened += it }) { SanadTimeline(collector = "البخاري", links = chain) }
            }
        }

        compose.onNodeWithText("البخاري").performClick()

        assertTrue(opened.isEmpty())
    }

    @Test
    fun withNothingToOpenThemANameIsPlainText() {
        compose.setContent { MutqanHadithTheme { SanadTimeline(collector = "", links = chain) } }

        compose.onNodeWithText("الزهري").performClick() // a tap that goes nowhere, and does not crash
        compose.onNodeWithText("الزهري").assertExists()
    }

    @Test
    fun theCompanionCardOpensTheCompanionOfItsHadith() {
        val opened = mutableListOf<Long>()
        compose.setContent {
            MutqanHadithTheme {
                CompositionLocalProvider(LocalOpenCompanion provides { opened += it }) { SahabiCard("أبو هريرة", onOpen = companionOpener(7)) }
            }
        }

        compose.onNodeWithText("أبو هريرة").performClick()

        assertEquals(listOf(7L), opened)
    }
}

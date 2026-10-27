package com.mutqan.hadith.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.usecase.CompanionBio
import com.mutqan.hadith.domain.usecase.HadithStudy
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.study.HadithStudyScreen
import com.mutqan.hadith.ui.study.HadithStudyUi
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What an admin wrote about the Companion, on the study screen: not there unless written, folded until asked for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class CompanionBioScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(bio: CompanionBio?) {
        val study = HadithStudy(
            hadith = hadith(1, listOf(narrator(1, "عمر بن الخطاب", tier = "صحابي"))),
            metrics = null, retention = null, nextReviewDue = null, isDue = false,
            companionBio = bio,
        )
        compose.setContent {
            MutqanHadithTheme {
                HadithStudyScreen(
                    state = UiState.Success(HadithStudyUi(study, textScale = 1f)),
                    onRetry = {}, onClose = {}, onSmaller = {}, onLarger = {}, onStart = {}, onReview = {},
                )
            }
        }
    }

    @Test
    fun withNothingWrittenThereIsNoCard() {
        show(null)

        compose.onNodeWithText("الصحابي الراوي").assertExists()
        compose.onNodeWithText("نبذة عن الصحابي").assertDoesNotExist()
    }

    @Test
    fun theCardIsFoldedAtFirstAndOpensToEveryParagraph() {
        show(CompanionBio("عمر بن الخطاب", listOf("الفقرة الأولى", "الفقرة الثانية")))

        compose.onNodeWithText("نبذة عن الصحابي").assertExists()
        compose.onNodeWithText("الفقرة الأولى").assertDoesNotExist()

        compose.onNodeWithText("اقرأ").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الفقرة الأولى").assertExists()
        compose.onNodeWithText("الفقرة الثانية").assertExists()

        compose.onNodeWithText("إخفاء").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الفقرة الأولى").assertDoesNotExist()
    }
}

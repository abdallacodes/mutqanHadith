package com.mutqan.hadith.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.LiveReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLibraryByLevelUseCase
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.withMatn
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.library.LibraryRoute
import com.mutqan.hadith.ui.library.LibraryViewModel
import com.mutqan.hadith.ui.study.HadithStudyRoute
import com.mutqan.hadith.ui.study.HadithStudyViewModel
import com.mutqan.hadith.ui.study.MatnScale
import com.mutqan.hadith.ui.study.ReaderSettings
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Library, the hadith reader and the way into them from Home, driven through their ViewModels over fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class LibraryScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()
    private val companion = narrator(1, "عمر بن الخطاب", tier = "صحابي")
    private val middle = narrator(3, "مالك", tier = "راوٍ")
    private val teacher = narrator(2, "الحميدي", tier = "شيخ المصنف")

    private val library = listOf(
        hadith(1, listOf(companion), level = 1).withMatn("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ"),
        hadith(2, listOf(companion), level = 1).withMatn("الدِّينُ النَّصِيحَةُ"),
        hadith(10, listOf(teacher, middle, companion), level = 3).withMatn("إِنَّمَا الْأَعْمَالُ بِالنِّيَّةِ"),
        hadith(11, listOf(teacher, companion), level = 3).withMatn("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ"),
    )
    private val hadiths = FakeHadithRepository(library)
    private val reviews = LiveReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    private class MemorySettings(override var matnScale: Float = MatnScale.DEFAULT) : ReaderSettings

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun libraryRoute(onOpenHadith: (Long) -> Unit = {}) {
        val vm = LibraryViewModel(GetLibraryByLevelUseCase(hadiths, reviews, scheduler) { now })
        compose.setContent { MutqanHadithTheme { LibraryRoute(vm, onClose = {}, onOpenHadith = onOpenHadith) } }
    }

    private fun studyRoute(
        id: Long,
        settings: ReaderSettings = MemorySettings(),
        onStartLearning: (Long) -> Unit = {},
        onReview: (Long) -> Unit = {},
    ) {
        val vm = HadithStudyViewModel(
            id,
            GetHadithStudyUseCase(hadiths, reviews, scheduler) { now },
            StartMemorizingUseCase(hadiths, reviews) { now },
            settings,
        )
        compose.setContent { MutqanHadithTheme { HadithStudyRoute(vm, onClose = {}, onStartLearning = onStartLearning, onReview = onReview) } }
    }

    // -- Library ----------------------------------------------------------------------------

    @Test
    fun library_listsTheFirstLevelWithItsTabsStatusAndProgress() {
        libraryRoute()

        listOf("المستوى الأول: الأصول والأسس", "المستوى الثاني: أحاديث الأحكام", "المستوى الثالث: دواوين السنّة").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("الأربعون النووية: متون جامعة، مع راويها من الصحابة وتخريجها").assertExists()
        compose.onNodeWithText("جديد: 2").assertExists()
        compose.onNodeWithText("قيد الحفظ: 0").assertExists()
        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ").assertExists()
        compose.onNodeWithText("الدِّينُ النَّصِيحَةُ").assertExists()
        compose.onNodeWithText("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ").assertDoesNotExist() // that is level 3
        compose.onAllNodesWithText("جديد").assertCountEquals(2)
        compose.onAllNodesWithText("الصحابي: عمر بن الخطاب").assertCountEquals(2)
    }

    @Test
    fun library_tappingACardOpensItsHadith() {
        var opened: Long? = null
        libraryRoute { opened = it }

        compose.onNodeWithText("الدِّينُ النَّصِيحَةُ").performClick()

        assertEquals(2L, opened)
    }

    @Test
    fun library_anotherLevelShowsItsOwnHadiths() {
        var opened: Long? = null
        libraryRoute { opened = it }

        compose.onNodeWithText("المستوى الثالث: دواوين السنّة").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ").assertExists()
        compose.onNodeWithText("الدِّينُ النَّصِيحَةُ").assertDoesNotExist()
        compose.onNodeWithText("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ").performClick()
        assertEquals(11L, opened)
    }

    @Test
    fun library_aLevelWithNothingYetSaysSo() {
        libraryRoute()

        compose.onNodeWithText("المستوى الثاني: أحاديث الأحكام").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("قريبًا بإذن الله").assertExists()
    }

    @Test
    fun library_searchNarrowsEveryLevelAndClearingRestoresIt() {
        libraryRoute()

        compose.onNode(hasSetTextAction()).performTextInput("الأعمال")
        compose.waitForIdle()

        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ").assertExists()
        compose.onNodeWithText("الدِّينُ النَّصِيحَةُ").assertDoesNotExist()
        compose.onNodeWithText("جديد: 1").assertExists()

        compose.onNodeWithContentDescription("مسح البحث").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الدِّينُ النَّصِيحَةُ").assertExists()
        compose.onNodeWithText("جديد: 2").assertExists()
    }

    @Test
    fun library_aSearchWithoutResultsSaysSo() {
        libraryRoute()

        compose.onNode(hasSetTextAction()).performTextInput("زززز")
        compose.waitForIdle()

        compose.onNodeWithText("لا نتائج لـ «زززز» في هذا المستوى").assertExists()
    }

    @Test
    fun library_showsWhereTheUserStands() {
        reviews.mastery[2] = MasteryRecordEntity(2, 3f, 3f, matnLastReviewedAt = now - 1_000, sanadLastReviewedAt = now - 1_000, nextReviewDue = now + 86_400_000L, repetitionCount = 2)
        libraryRoute()

        compose.onNodeWithText("جديد: 1").assertExists()
        compose.onNodeWithText("قيد الحفظ: 1").assertExists()
        compose.onNodeWithText("قيد الحفظ").assertExists() // the card's own badge
    }

    // -- Hadith reader ----------------------------------------------------------------------

    @Test
    fun study_showsTheHadithItsSourceAndItsShortChain() {
        studyRoute(1)

        compose.onNodeWithText("كتاب").assertExists()
        compose.onNodeWithText("حديث رقم 1").assertExists()
        compose.onNodeWithText("التخريج: متفق عليه").assertExists()
        compose.onNodeWithText("الدرجة: صحيح").assertExists()
        compose.onNodeWithText("الباب: باب").assertExists()
        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ").assertExists()
        compose.onNodeWithText("عمر بن الخطاب").assertExists()
        compose.onNodeWithText("صحابي").assertExists()
        compose.onNodeWithText("يُذكر الحديث هنا دون سلسلته الطويلة، فالحلقة الوحيدة هي الصحابي راوي الحديث.").assertExists()
    }

    @Test
    fun study_aFullChainIsShownFromTheCompilerToTheCompanion() {
        studyRoute(10)

        listOf("جامع", "الحميدي", "مالك", "عمر بن الخطاب").forEach { compose.onNodeWithText(it).assertExists() }
        // An unclassified narrator is named plainly, and the long-chain note is not shown.
        compose.onNodeWithText("راوٍ").assertExists()
        compose.onNodeWithText("يُذكر الحديث هنا دون سلسلته الطويلة، فالحلقة الوحيدة هي الصحابي راوي الحديث.").assertDoesNotExist()
    }

    @Test
    fun study_aBlankGradeIsNotShown() {
        val ungraded = hadith(3, listOf(companion), level = 1)
        val repo = FakeHadithRepository(library + ungraded.copy(hadith = ungraded.hadith.copy(grade = "")))
        val vm = HadithStudyViewModel(3, GetHadithStudyUseCase(repo, reviews, scheduler) { now }, StartMemorizingUseCase(repo, reviews) { now }, MemorySettings())
        compose.setContent { MutqanHadithTheme { HadithStudyRoute(vm, onClose = {}, onStartLearning = {}, onReview = {}) } }

        compose.onNodeWithText("التخريج: متفق عليه").assertExists()
        compose.onAllNodesWithText("الدرجة", substring = true).assertCountEquals(0)
    }

    @Test
    fun study_startingMemorizingJoinsTheScheduleAndOpensTheLearningFlow() {
        var learning: Long? = null
        studyRoute(2, onStartLearning = { learning = it })
        compose.onNodeWithText("أضف إلى وِرد الحفظ").assertExists()
        compose.onNodeWithText("مراجعة الآن").assertDoesNotExist()

        compose.onNodeWithText("أضف إلى وِرد الحفظ").performClick()
        compose.waitForIdle()

        assertEquals(2L, learning)
        assertNotNull(reviews.mastery[2])
        // Now it is in the wird, the page offers a review instead.
        compose.onNodeWithText("مراجعة الآن").assertExists()
        compose.onNodeWithText("نسبة التذكّر الآن").assertExists()
    }

    @Test
    fun study_aHadithInStudyOffersAReview() {
        reviews.mastery[1] = MasteryRecordEntity(1, 4f, 4f, matnLastReviewedAt = now - 1_000, sanadLastReviewedAt = now - 1_000, nextReviewDue = now - 500, repetitionCount = 3)
        var reviewed: Long? = null
        studyRoute(1, onReview = { reviewed = it })

        compose.onNodeWithText("أضف إلى وِرد الحفظ").assertDoesNotExist()
        compose.onNodeWithText("نسبة التذكّر الآن").assertExists()
        compose.onNodeWithText("مستحق").assertExists()
        compose.onNodeWithText("مراجعة الآن").performClick()

        assertEquals(1L, reviewed)
    }

    @Test
    fun study_theTextSizeStepsWithinItsLimitsAndIsRemembered() {
        val settings = MemorySettings()
        studyRoute(1, settings)
        compose.onNodeWithText("100٪").assertExists()

        compose.onNodeWithContentDescription("تكبير الخط").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("120٪").assertExists()
        assertEquals(1.2f, settings.matnScale, 0f)

        repeat(2) { compose.onNodeWithContentDescription("تكبير الخط").performClick() }
        compose.waitForIdle()
        compose.onNodeWithText("170٪").assertExists()
        compose.onNodeWithContentDescription("تكبير الخط").assertIsNotEnabled()
        compose.onNodeWithContentDescription("تصغير الخط").assertIsEnabled()
    }

    @Test
    fun study_aHadithThatIsNotThereIsAnError() {
        studyRoute(99)

        compose.waitForIdle()

        assertTrue(compose.onAllNodesWithText("حديث رقم", substring = true).fetchSemanticsNodes().isEmpty())
    }
}

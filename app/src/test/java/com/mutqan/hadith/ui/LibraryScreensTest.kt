package com.mutqan.hadith.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.LiveReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLibraryPageUseCase
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

    // Two books; the second has a section, and the section a hadith of its own.
    private val groups = listOf(
        CatalogGroupEntity(1, null, "الكتاب الأول", "First book", 0),
        CatalogGroupEntity(2, null, "الكتاب الثاني", "Second book", 1),
        CatalogGroupEntity(3, 2, "باب الإيمان", "Faith", 0),
    )
    private val library = listOf(
        hadith(1, listOf(companion), position = 1, groupId = 1).withMatn("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ"),
        hadith(2, listOf(companion), position = 2, groupId = 1).withMatn("الدِّينُ النَّصِيحَةُ"),
        hadith(11, listOf(teacher, companion), position = 4, groupId = 2).withMatn("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ"),
        hadith(10, listOf(teacher, middle, companion), position = 3, groupId = 3).withMatn("إِنَّمَا الْأَعْمَالُ بِالنِّيَّةِ").let { it.copy(hadith = it.hadith.copy(sanadSource = "صحيح البخاري")) },
    )
    private val hadiths = FakeHadithRepository(library, groups)
    private val reviews = LiveReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    private class MemorySettings(override var matnScale: Float = MatnScale.DEFAULT) : ReaderSettings

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun libraryRoute(onOpenHadith: (Long) -> Unit = {}) {
        val vm = LibraryViewModel(GetLibraryPageUseCase(hadiths, reviews, scheduler) { now })
        compose.setContent { MutqanHadithTheme { LibraryRoute(vm, onClose = {}, onOpenHadith = onOpenHadith) } }
    }

    private val FULL_MATN = "إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ"
    private val PLAIN_MATN = "إنما الأعمال بالنيات"

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

    private fun openFirstBook() {
        compose.onNodeWithText("الكتاب الأول").performClick()
        compose.waitForIdle()
    }

    @Test
    fun library_startsOnTheShelfOfBooksWithTheirSizeAndProgress() {
        libraryRoute()

        compose.onNodeWithText("المكتبة").assertExists()
        compose.onNodeWithText("الكتب").assertExists()
        compose.onNodeWithText("الكتاب الأول").assertExists()
        compose.onNodeWithText("الكتاب الثاني").assertExists()
        compose.onNodeWithText("حديثان").assertExists()
        // The second book holds a hadith of its own and one in its section: two, and one section.
        compose.onNodeWithText("حديثان · قسم واحد").assertExists()
        compose.onAllNodesWithText("جديد: 2").assertCountEquals(2)
        // No hadith is listed on the shelf, and nothing is searched there.
        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        // The library says it will grow.
        compose.onNodeWithText("قريبًا بإذن الله").assertExists()
        compose.onNodeWithText("ستُضاف كتب أخرى من كتب السنّة في التحديثات القادمة.").assertExists()
    }

    @Test
    fun library_noLevelIsMentionedAnywhere() {
        libraryRoute()

        compose.onAllNodesWithText("المستوى", substring = true).assertCountEquals(0)
        openFirstBook()
        compose.onAllNodesWithText("المستوى", substring = true).assertCountEquals(0)
    }

    @Test
    fun library_openingABookListsItsHadithsWithTheirProgress() {
        libraryRoute()

        openFirstBook()

        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ").assertExists()
        compose.onNodeWithText("الدِّينُ النَّصِيحَةُ").assertExists()
        compose.onNodeWithText("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ").assertDoesNotExist() // that is in the second book
        compose.onNodeWithText("جديد: 2").assertExists()
        compose.onNodeWithText("قيد الحفظ: 0").assertExists()
        compose.onAllNodesWithText("جديد").assertCountEquals(2)
        compose.onAllNodesWithText("الصحابي: عمر بن الخطاب").assertCountEquals(2)
        compose.onNode(hasSetTextAction()).assertExists()
    }

    @Test
    fun library_tappingACardOpensItsHadith() {
        var opened: Long? = null
        libraryRoute { opened = it }
        openFirstBook()

        compose.onNodeWithText("الدِّينُ النَّصِيحَةُ").performClick()

        assertEquals(2L, opened)
    }

    @Test
    fun library_aBookWithSectionsListsThemAboveItsOwnHadiths() {
        var opened: Long? = null
        libraryRoute { opened = it }

        compose.onNodeWithText("الكتاب الثاني").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الأقسام").assertExists()
        compose.onNodeWithText("باب الإيمان").assertExists()
        compose.onNodeWithText("الأحاديث").assertExists()
        compose.onNodeWithText("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ").assertExists()
        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّةِ").assertDoesNotExist() // that is in the section
        compose.onNodeWithText("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ").performClick()
        assertEquals(11L, opened)

        compose.onNodeWithText("باب الإيمان").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّةِ").assertExists()
        compose.onNodeWithText("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ").assertDoesNotExist()
        // Where the section sits is said above it.
        compose.onNodeWithText("الكتاب الثاني").assertExists()
    }

    @Test
    fun library_theArrowOfTheBarGoesUpOneGroupAtATime() {
        libraryRoute()
        compose.onNodeWithText("الكتاب الثاني").performClick()
        compose.onNodeWithText("باب الإيمان").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ").assertExists()

        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("الكتب").assertExists()
        compose.onNodeWithText("الكتاب الأول").assertExists()
    }

    @Test
    fun library_aBookWithNothingInItSaysSo() {
        val empty = LibraryViewModel(
            GetLibraryPageUseCase(FakeHadithRepository(emptyList(), groups), reviews, scheduler) { now },
        )
        compose.setContent { MutqanHadithTheme { LibraryRoute(empty, onClose = {}, onOpenHadith = {}) } }

        compose.onNodeWithText("الكتاب الأول").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("لا أحاديث في هذا القسم بعد").assertExists()
    }

    @Test
    fun library_searchStaysInTheOpenBookAndClearingRestoresIt() {
        libraryRoute()
        openFirstBook()

        compose.onNode(hasSetTextAction()).performTextInput("الأعمال")
        compose.waitForIdle()

        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ").assertExists()
        compose.onNodeWithText("الدِّينُ النَّصِيحَةُ").assertDoesNotExist()
        compose.onNodeWithText("حديث واحد").assertExists()
        // «إنما الأعمال بالنية» is in the other book, so it is not found from here.
        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّةِ").assertDoesNotExist()

        compose.onNodeWithContentDescription("مسح البحث").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("الدِّينُ النَّصِيحَةُ").assertExists()
        compose.onNodeWithText("جديد: 2").assertExists()
    }

    @Test
    fun library_aSearchInABookFindsTheHadithsOfItsSectionsToo() {
        libraryRoute()
        compose.onNodeWithText("الكتاب الثاني").performClick()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performTextInput("الأعمال")
        compose.waitForIdle()

        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّةِ").assertExists()
        compose.onNodeWithText("باب الإيمان").assertDoesNotExist() // matches only: the section cards give way
    }

    @Test
    fun library_aSearchWithoutResultsNamesTheGroupItLookedIn() {
        libraryRoute()
        openFirstBook()

        compose.onNode(hasSetTextAction()).performTextInput("زززز")
        compose.waitForIdle()

        compose.onNodeWithText("لا نتائج لـ «زززز» في «الكتاب الأول»").assertExists()
    }

    @Test
    fun library_showsWhereTheUserStands() {
        reviews.mastery[2] = MasteryRecordEntity(2, 3f, 3f, matnLastReviewedAt = now - 1_000, sanadLastReviewedAt = now - 1_000, nextReviewDue = now + 86_400_000L, repetitionCount = 2)
        libraryRoute()

        // On the shelf, the book already shows how far the user has come in it.
        compose.onNodeWithText("قيد الحفظ: 1").assertExists()
        openFirstBook()

        compose.onNodeWithText("جديد: 1").assertExists()
        compose.onNodeWithText("قيد الحفظ: 1").assertExists()
        compose.onNodeWithText("قيد الحفظ").assertExists() // the card's own badge
    }

    // -- Hadith reader ----------------------------------------------------------------------

    @Test
    fun study_showsTheHadithItsSourceAndItsShortChain() {
        studyRoute(1)
        compose.onNodeWithText("إظهار السند").performClick()

        compose.onNodeWithText("كتاب").assertExists()
        compose.onNodeWithText("حديث رقم 1").assertExists()
        compose.onNodeWithText("متفق عليه").assertExists()
        compose.onNodeWithText("الدرجة: صحيح").assertExists()
        compose.onNodeWithText("الباب: باب").assertExists()
        compose.onNodeWithText("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ").assertExists()
        // The Companion is named twice: in his card at the top of the page and as the one link of the chain.
        compose.onNodeWithText("الصحابي الراوي").assertExists()
        compose.onAllNodesWithText("عمر بن الخطاب").assertCountEquals(2)
        compose.onNodeWithText("صحابي").assertExists()
        compose.onNodeWithText("يُعرض هذا الحديث بالصحابي وحده، إذ لم نجد في مصدر الأسانيد روايةً يمكن تتبّع سندها كاملًا إلى الصحابي.").assertExists()
        // No narration was found for it, so no book is named for its chain.
        compose.onNodeWithText("السند من: صحيح البخاري").assertDoesNotExist()
    }

    @Test
    fun study_theCompanionComesFirstThenTheMatnTheMukhrijAndTheChainLastAsCardsOfTheirOwn() {
        studyRoute(1)

        fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y
        val companion = top("الصحابي الراوي")
        val matn = top("المتن")
        val chain = top("سلسلة الإسناد")
        val mukhrij = top("متفق عليه")

        assertTrue("the Companion is above the matn", companion < matn)
        assertTrue("the takhrij is below the matn", matn < mukhrij)
        assertTrue("the chain is last, below the takhrij", mukhrij < chain)
        // The author's words alone, not a labelled chip: «رواه …» / «متفق عليه» is the whole card.
        compose.onAllNodesWithText("التخريج", substring = true).assertCountEquals(0)
    }

    @Test
    fun study_theChainIsHiddenUntilAskedForAndCanBeHiddenAgain() {
        studyRoute(10)

        compose.onNodeWithText("سلسلة الإسناد").assertExists()
        listOf("جامع", "الحميدي", "مالك").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("السند من: صحيح البخاري").assertDoesNotExist()

        compose.onNodeWithText("إظهار السند").performClick()
        compose.onNodeWithText("الحميدي").assertExists()

        compose.onNodeWithText("إخفاء السند").performClick()
        compose.onNodeWithText("الحميدي").assertDoesNotExist()
    }

    @Test
    fun study_aFullChainIsShownFromTheCompilerToTheCompanion() {
        studyRoute(10)
        compose.onNodeWithText("جامع").assertDoesNotExist()
        compose.onNodeWithText("إظهار السند").performClick()

        listOf("جامع", "الحميدي", "مالك").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onAllNodesWithText("عمر بن الخطاب").assertCountEquals(2)
        // An unclassified narrator is named plainly, the book the chain is read from is named, and the short-chain note is not shown.
        compose.onNodeWithText("راوٍ").assertExists()
        compose.onNodeWithText("السند من: صحيح البخاري").assertExists()
        compose.onNodeWithText("يُعرض هذا الحديث بالصحابي وحده، إذ لم نجد في مصدر الأسانيد روايةً يمكن تتبّع سندها كاملًا إلى الصحابي.").assertDoesNotExist()
    }

    @Test
    fun study_aBlankGradeIsNotShown() {
        val ungraded = hadith(3, listOf(companion))
        val repo = FakeHadithRepository(library + ungraded.copy(hadith = ungraded.hadith.copy(grade = "")), groups)
        val vm = HadithStudyViewModel(3, GetHadithStudyUseCase(repo, reviews, scheduler) { now }, StartMemorizingUseCase(repo, reviews) { now }, MemorySettings())
        compose.setContent { MutqanHadithTheme { HadithStudyRoute(vm, onClose = {}, onStartLearning = {}, onReview = {}) } }

        compose.onNodeWithText("متفق عليه").assertExists()
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
    fun study_aHadithShowsHowWellItsMatnIsHeldInWords() {
        reviews.mastery[1] = MasteryRecordEntity(1, 12f, 0f, matnLastReviewedAt = now - 1_000, sanadLastReviewedAt = null, nextReviewDue = now + 10_000, repetitionCount = 1)
        studyRoute(1)

        compose.onNodeWithText("الحفظ: جيد جدًّا").assertExists()
    }

    @Test
    fun study_aHadithNeverLearnedShowsNoWordForItsMemory() {
        studyRoute(2)

        compose.onAllNodesWithText("الحفظ:", substring = true).assertCountEquals(0)
    }

    @Test
    fun library_aHadithWithAMatnThatIsHeldCarriesTheWordForIt() {
        reviews.mastery[1] = MasteryRecordEntity(1, 45f, 0f, matnLastReviewedAt = now - 1_000, sanadLastReviewedAt = null, nextReviewDue = now + 10_000, repetitionCount = 1)
        libraryRoute()

        openFirstBook()

        compose.onAllNodesWithText("الحفظ: ممتاز").assertCountEquals(1)
        compose.onNodeWithText("الحفظ: جيد").assertDoesNotExist()
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
    fun study_theTashkeelToggleSwapsTheMatnBetweenFullAndPlainText() {
        studyRoute(1)

        // It opens on the full text, and the button offers to hide the tashkeel.
        compose.onNodeWithText(FULL_MATN).assertExists()
        compose.onNodeWithText(PLAIN_MATN).assertDoesNotExist()
        compose.onNodeWithContentDescription("إظهار التشكيل على المتن").assertDoesNotExist()

        compose.onNodeWithContentDescription("إخفاء التشكيل من المتن").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(PLAIN_MATN).assertExists()
        compose.onNodeWithText(FULL_MATN).assertDoesNotExist()
        compose.onNodeWithContentDescription("إخفاء التشكيل من المتن").assertDoesNotExist()

        compose.onNodeWithContentDescription("إظهار التشكيل على المتن").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(FULL_MATN).assertExists()
        compose.onNodeWithText(PLAIN_MATN).assertDoesNotExist()
        compose.onNodeWithContentDescription("إخفاء التشكيل من المتن").assertExists()
    }

    @Test
    fun study_theTashkeelToggleLeavesTheRestOfThePageAlone() {
        studyRoute(1)

        compose.onNodeWithContentDescription("إخفاء التشكيل من المتن").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("حديث رقم 1").assertExists()
        compose.onNodeWithText("متفق عليه").assertExists()
        // The chain stays hidden; only the Companion's card names him.
        compose.onAllNodesWithText("عمر بن الخطاب").assertCountEquals(1)
        compose.onNodeWithText("100٪").assertExists()
    }

    @Test
    fun study_aHadithThatIsNotThereIsAnError() {
        studyRoute(99)

        compose.waitForIdle()

        assertTrue(compose.onAllNodesWithText("حديث رقم", substring = true).fetchSemanticsNodes().isEmpty())
    }
}

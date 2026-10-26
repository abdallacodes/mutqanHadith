package com.mutqan.hadith.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.R
import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeCollectionRepository
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.CreateCollectionUseCase
import com.mutqan.hadith.domain.usecase.DeleteCollectionUseCase
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetLibraryPageUseCase
import com.mutqan.hadith.domain.usecase.ObserveCollectionDetailUseCase
import com.mutqan.hadith.domain.usecase.RenameCollectionUseCase
import com.mutqan.hadith.domain.usecase.SetCollectionMembershipUseCase
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.withMatn
import com.mutqan.hadith.ui.collections.CollectionDetailRoute
import com.mutqan.hadith.ui.collections.CollectionDetailViewModel
import com.mutqan.hadith.ui.collections.CollectionPracticeRoute
import com.mutqan.hadith.ui.collections.CollectionPracticeViewModel
import com.mutqan.hadith.ui.collections.CollectionsViewModel
import com.mutqan.hadith.ui.library.LibraryRoute
import com.mutqan.hadith.ui.library.LibraryViewModel
import com.mutqan.hadith.ui.study.HadithStudyRoute
import com.mutqan.hadith.ui.study.HadithStudyViewModel
import com.mutqan.hadith.ui.study.MatnScale
import com.mutqan.hadith.ui.study.ReaderSettings
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The personal collections as the user meets them: the library's «My collections» section, filing a hadith from the library
 * and the reader, naming a collection, the collection page with its rename and delete, and the practice. Every sentence
 * the screens say is looked up in the language being run, so [CollectionsScreensTest] and [CollectionsEnglishScreensTest]
 * hold the Arabic and the English screens to exactly the same behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class CollectionsScreensBase {

    @get:Rule
    val compose = createComposeRule()

    protected val context: Context = ApplicationProvider.getApplicationContext()

    protected fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun hadithCount(n: Int): String = context.resources.getQuantityString(R.plurals.hadith_count, n, n)

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()
    private val companion = narrator(1, "عمر بن الخطاب", tier = "صحابي")
    private val middle = narrator(3, "مالك", tier = "راوٍ")
    private val teacher = narrator(2, "الحميدي", tier = "شيخ المصنف")

    private val firstMatn = "إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ"
    private val secondMatn = "الدِّينُ النَّصِيحَةُ"
    private val thirdMatn = "بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ"

    // One book holding all three, with no English title: in English the library shows the Arabic one.
    private val bookTitle = "كتاب الاختبار"
    private val groups = listOf(CatalogGroupEntity(1, null, bookTitle, "", 0))
    private val library = listOf(
        hadith(1, listOf(companion), position = 1, groupId = 1).withMatn(firstMatn),
        hadith(2, listOf(companion), position = 2, groupId = 1).withMatn(secondMatn),
        hadith(3, listOf(teacher, middle, companion), position = 3, groupId = 1).withMatn(thirdMatn),
    )
    private val hadiths = FakeHadithRepository(library, groups)
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    protected val collections = FakeCollectionRepository(library)

    private class MemorySettings(override var matnScale: Float = MatnScale.DEFAULT) : ReaderSettings

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    // -- Screens ------------------------------------------------------------------------------------------------------

    private fun collectionsVm() = CollectionsViewModel(
        collections, CreateCollectionUseCase(collections) { now }, SetCollectionMembershipUseCase(collections) { now },
    )

    /** The library with its book already opened: the hadiths are inside it. */
    private fun libraryScreen(withCollections: Boolean = true, onOpenHadith: (Long) -> Unit = {}, onOpenCollection: (Long) -> Unit = {}) {
        val vm = LibraryViewModel(GetLibraryPageUseCase(hadiths, reviews, scheduler) { now })
        val collectionsVm = if (withCollections) collectionsVm() else null
        compose.setContent {
            MutqanHadithTheme {
                LibraryRoute(vm, onClose = {}, onOpenHadith = onOpenHadith, collectionsViewModel = collectionsVm, onOpenCollection = onOpenCollection)
            }
        }
        compose.onNodeWithText(bookTitle).performClick()
        compose.waitForIdle()
    }

    private fun detailScreen(
        id: Long,
        onClose: () -> Unit = {},
        onOpenHadith: (Long) -> Unit = {},
        onReview: () -> Unit = {},
        onBrowseLibrary: () -> Unit = {},
    ) {
        val vm = CollectionDetailViewModel(
            id,
            ObserveCollectionDetailUseCase(collections, reviews, scheduler) { now },
            RenameCollectionUseCase(collections),
            DeleteCollectionUseCase(collections),
            SetCollectionMembershipUseCase(collections) { now },
        )
        compose.setContent {
            MutqanHadithTheme { CollectionDetailRoute(vm, onClose, onOpenHadith, onReview, onBrowseLibrary) }
        }
    }

    private fun practiceScreen(id: Long, onClose: () -> Unit = {}) {
        val vm = CollectionPracticeViewModel(id, collections, hadiths, GetLayerStatusesUseCase(reviews, scheduler))
        compose.setContent { MutqanHadithTheme { CollectionPracticeRoute(vm, onClose) } }
    }

    private fun readerScreen(id: Long, withCollections: Boolean = true) {
        val vm = HadithStudyViewModel(
            id,
            GetHadithStudyUseCase(hadiths, reviews, scheduler) { now },
            StartMemorizingUseCase(hadiths, reviews) { now },
            MemorySettings(),
        )
        val collectionsVm = if (withCollections) collectionsVm() else null
        compose.setContent {
            MutqanHadithTheme {
                HadithStudyRoute(vm, onClose = {}, onStartLearning = {}, onReview = {}, collectionsViewModel = collectionsVm)
            }
        }
    }

    // -- Data ---------------------------------------------------------------------------------------------------------

    private fun collection(title: String, vararg hadithIds: Long): Long = runBlocking {
        val id = collections.create(title, accent = 0, createdAt = now)
        hadithIds.forEachIndexed { i, hadithId -> collections.add(id, hadithId, addedAt = now + i) }
        id
    }

    private fun titles(): List<String> = runBlocking { collections.observeAll().first().map { it.title } }

    private fun hadithsOf(id: Long): List<Long> = runBlocking { collections.getHadithIds(id) }

    private fun studied(id: Long, matn: Float, sanad: Float) {
        reviews.mastery[id] = MasteryRecordEntity(id, matn, sanad, now - DAY_MS, now - DAY_MS, now + DAY_MS, 3)
    }

    private fun clickText(text: String) = compose.onNodeWithText(text).performClick()

    /** The chip counts the collections once there are some («My collections · 2»), so it is found by its name. */
    private fun openCollectionsSection() =
        compose.onNode(hasText(str(R.string.library_section_collections), substring = true)).performClick()

    private fun typeInto(label: String, text: String) = compose.onNodeWithText(label).performTextInput(text)

    // -- Library: the section ---------------------------------------------------------------------------------------

    @Test
    fun library_withoutCollectionsIsJustTheLevels() {
        libraryScreen(withCollections = false)

        compose.onNodeWithText(str(R.string.library_section_collections)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.library_section_hadiths)).assertDoesNotExist()
        compose.onNodeWithText(firstMatn).assertExists()
        compose.onAllNodesWithContentDescription(str(R.string.collect_action)).assertCountEquals(0)
    }

    @Test
    fun library_offersTheHadithsAndMyCollectionsStartingOnTheHadiths() {
        libraryScreen()

        compose.onNodeWithText(str(R.string.library_section_hadiths)).assertExists()
        compose.onNodeWithText(str(R.string.library_section_collections)).assertExists()
        compose.onNodeWithText(firstMatn).assertExists()
        compose.onNodeWithText(str(R.string.collections_new)).assertDoesNotExist()
    }

    @Test
    fun library_noCollectionsYetInvitesTheFirstOne() {
        libraryScreen()

        openCollectionsSection()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.collections_empty_title)).assertExists()
        compose.onNodeWithText(str(R.string.collections_empty_body)).assertExists()
        compose.onNodeWithText(str(R.string.collections_new)).assertExists()
        compose.onNodeWithText(firstMatn).assertDoesNotExist()
    }

    @Test
    fun library_theFirstCollectionIsMadeFromTheInvitation() {
        libraryScreen()
        openCollectionsSection()
        clickText(str(R.string.collections_new))
        compose.waitForIdle()

        typeInto(str(R.string.collection_name_label), "Friday sermon")
        clickText(str(R.string.collection_create))
        compose.waitForIdle()

        assertEquals(listOf("Friday sermon"), titles())
        compose.onNodeWithText("Friday sermon").assertExists()
        compose.onNodeWithText(hadithCount(0)).assertExists()
        compose.onNodeWithText(str(R.string.collections_empty_title)).assertDoesNotExist()
        compose.onNodeWithText("${str(R.string.library_section_collections)} · 1").assertExists()
        compose.onNodeWithText(str(R.string.collection_name_label)).assertDoesNotExist()
    }

    @Test
    fun library_listsEachCollectionWithItsSizeAndOpensIt() {
        val sermon = collection("Sermon", 1, 2)
        collection("Adhkar", 3)
        var opened: Long? = null
        libraryScreen(onOpenCollection = { opened = it })

        openCollectionsSection()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.collections_intro)).assertExists()
        compose.onNodeWithText("Sermon").assertExists()
        compose.onNodeWithText("Adhkar").assertExists()
        compose.onNodeWithText(hadithCount(2)).assertExists()
        compose.onNodeWithText(hadithCount(1)).assertExists()
        compose.onNodeWithText("${str(R.string.library_section_collections)} · 2").assertExists()

        clickText("Sermon")
        assertEquals(sermon, opened)
    }

    @Test
    fun library_goingBackToTheHadithsKeepsTheCollections() {
        collection("Sermon", 1)
        libraryScreen()

        openCollectionsSection()
        compose.waitForIdle()
        clickText(str(R.string.library_section_hadiths))
        compose.waitForIdle()

        compose.onNodeWithText(firstMatn).assertExists()
        compose.onNodeWithText("Sermon").assertDoesNotExist()
    }

    // -- The name of a collection ---------------------------------------------------------------------------------

    private fun openNewCollectionDialog() {
        openCollectionsSection()
        clickText(str(R.string.collections_new))
        compose.waitForIdle()
    }

    @Test
    fun editor_aNameThatIsEmptyOrOnlySpacesCannotBeSaved() {
        libraryScreen()
        openNewCollectionDialog()

        compose.onNodeWithText(str(R.string.collection_create)).assertIsNotEnabled()
        typeInto(str(R.string.collection_name_label), "    ")
        compose.onNodeWithText(str(R.string.collection_create)).assertIsNotEnabled()
        typeInto(str(R.string.collection_name_label), "a")
        compose.onNodeWithText(str(R.string.collection_create)).assertIsEnabled()
        assertTrue(titles().isEmpty())
    }

    @Test
    fun editor_aNameOfInvisibleCharactersIsRefusedWithAnExplanation() {
        libraryScreen()
        openNewCollectionDialog()

        typeInto(str(R.string.collection_name_label), "​‏")
        clickText(str(R.string.collection_create))
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.collection_error_empty)).assertExists()
        assertTrue(titles().isEmpty())
    }

    @Test
    fun editor_aNameAlreadyUsedIsExplainedAndTypingAgainClearsTheMessage() {
        collection("Adhkar")
        libraryScreen()
        openNewCollectionDialog()

        typeInto(str(R.string.collection_name_label), "  adhkar ")
        clickText(str(R.string.collection_create))
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.collection_error_duplicate)).assertExists()
        assertEquals(listOf("Adhkar"), titles())

        typeInto(str(R.string.collection_name_label), "2")
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.collection_error_duplicate)).assertDoesNotExist()
    }

    @Test
    fun editor_aNameOneCharacterOverTheLimitIsExplainedAndCannotBeSaved() {
        libraryScreen()
        openNewCollectionDialog()

        typeInto(str(R.string.collection_name_label), "ب".repeat(40))
        compose.onNodeWithText(str(R.string.collection_error_too_long, 40)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.collection_create)).assertIsEnabled()

        typeInto(str(R.string.collection_name_label), "ب")
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.collection_error_too_long, 40)).assertExists()
        compose.onNodeWithText(str(R.string.collection_create)).assertIsNotEnabled()
    }

    @Test
    fun editor_cancellingMakesNothing() {
        libraryScreen()
        openNewCollectionDialog()
        typeInto(str(R.string.collection_name_label), "Never made")

        clickText(str(R.string.collection_cancel))
        compose.waitForIdle()

        assertTrue(titles().isEmpty())
        compose.onNodeWithText(str(R.string.collection_create)).assertDoesNotExist()
    }

    @Test
    fun editor_offersTheSixTonesToChooseFrom() {
        libraryScreen()
        openNewCollectionDialog()

        (1..6).forEach { compose.onNodeWithContentDescription(str(R.string.collection_accent_label, it)).assertExists() }
        compose.onNodeWithContentDescription(str(R.string.collection_accent_label, 7)).assertDoesNotExist()
    }

    // -- Filing a hadith from the library ---------------------------------------------------------------------------

    private fun fileFirstLibraryHadith() = compose.onAllNodesWithContentDescription(str(R.string.collect_action))[0].performClick()

    @Test
    fun library_everyHadithCardHasABookmarkThatOpensTheSheet() {
        collection("Sermon")
        libraryScreen()

        compose.onAllNodesWithContentDescription(str(R.string.collect_action)).assertCountEquals(3)
        fileFirstLibraryHadith()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.add_to_collection_hint)).assertExists()
        compose.onNodeWithText("Sermon").assertExists()
        compose.onNodeWithText(str(R.string.add_to_collection_create)).assertExists()
    }

    @Test
    fun sheet_tappingACollectionFilesTheHadithAndTappingAgainTakesItOut() {
        val sermon = collection("Sermon")
        libraryScreen()
        fileFirstLibraryHadith()
        compose.waitForIdle()

        clickText("Sermon")
        compose.waitForIdle()
        assertEquals(listOf(1L), hadithsOf(sermon))
        compose.onNodeWithText(hadithCount(1)).assertExists()

        clickText("Sermon")
        compose.waitForIdle()
        assertTrue(hadithsOf(sermon).isEmpty())
        compose.onNodeWithText(hadithCount(0)).assertExists()
    }

    @Test
    fun sheet_aHadithCanBeInSeveralCollectionsAtOnce() {
        val sermon = collection("Sermon")
        val circle = collection("Circle")
        libraryScreen()
        fileFirstLibraryHadith()
        compose.waitForIdle()

        clickText("Sermon")
        clickText("Circle")
        compose.waitForIdle()

        assertEquals(listOf(1L), hadithsOf(sermon))
        assertEquals(listOf(1L), hadithsOf(circle))
    }

    @Test
    fun sheet_theBookmarkShowsOnceTheHadithIsInACollection() {
        collection("Sermon")
        libraryScreen()
        compose.onAllNodesWithContentDescription(str(R.string.collect_action_filed)).assertCountEquals(0)

        fileFirstLibraryHadith()
        compose.waitForIdle()
        clickText("Sermon")
        compose.waitForIdle()
        clickText(str(R.string.add_to_collection_done))
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription(str(R.string.collect_action_filed)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(str(R.string.collect_action)).assertCountEquals(2)
    }

    @Test
    fun sheet_withoutCollectionsItSaysSoAndOffersToCreateOne() {
        libraryScreen()

        fileFirstLibraryHadith()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.add_to_collection_none)).assertExists()
        compose.onNodeWithText(str(R.string.add_to_collection_create)).assertIsNotEnabled()
    }

    @Test
    fun sheet_aNewCollectionIsMadeAndTheHadithJoinsItInOneStep() {
        libraryScreen()
        fileFirstLibraryHadith()
        compose.waitForIdle()

        typeInto(str(R.string.add_to_collection_new_label), "Morning adhkar")
        compose.onNodeWithText(str(R.string.add_to_collection_create)).assertIsEnabled()
        clickText(str(R.string.add_to_collection_create))
        compose.waitForIdle()

        assertEquals(listOf("Morning adhkar"), titles())
        assertEquals(listOf(1L), hadithsOf(runBlocking { collections.observeAll().first().single().id }))
        compose.onNodeWithText("Morning adhkar").assertExists()
        compose.onNodeWithText(str(R.string.add_to_collection_none)).assertDoesNotExist()
    }

    @Test
    fun sheet_aRefusedNameIsExplainedInTheSheetAndNothingIsMade() {
        collection("Adhkar")
        libraryScreen()
        fileFirstLibraryHadith()
        compose.waitForIdle()

        typeInto(str(R.string.add_to_collection_new_label), "ADHKAR")
        clickText(str(R.string.add_to_collection_create))
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.collection_error_duplicate)).assertExists()
        assertEquals(listOf("Adhkar"), titles())
    }

    @Test
    fun sheet_aWhitespaceOnlyNameCannotBeCreated() {
        libraryScreen()
        fileFirstLibraryHadith()
        compose.waitForIdle()

        typeInto(str(R.string.add_to_collection_new_label), "   \t ")

        compose.onNodeWithText(str(R.string.add_to_collection_create)).assertIsNotEnabled()
        assertTrue(titles().isEmpty())
    }

    // -- The reader -------------------------------------------------------------------------------------------------

    @Test
    fun reader_hasNoCollectionActionWithoutCollections() {
        readerScreen(1, withCollections = false)

        compose.onAllNodesWithContentDescription(str(R.string.collect_action)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(str(R.string.collect_action_filed)).assertCountEquals(0)
    }

    @Test
    fun reader_filesTheHadithBeingReadIntoACollection() {
        val sermon = collection("Sermon")
        readerScreen(2)

        compose.onNodeWithContentDescription(str(R.string.collect_action)).performClick()
        compose.waitForIdle()
        clickText("Sermon")
        compose.waitForIdle()

        assertEquals(listOf(2L), hadithsOf(sermon))
        compose.onNodeWithContentDescription(str(R.string.collect_action_filed)).assertExists()
    }

    @Test
    fun reader_showsTheActionFilledWhenTheHadithIsAlreadyInACollection() {
        collection("Sermon", 2)
        readerScreen(2)

        compose.onNodeWithContentDescription(str(R.string.collect_action_filed)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.collect_action)).assertDoesNotExist()
    }

    @Test
    fun reader_createsACollectionForTheHadithBeingRead() {
        readerScreen(3)

        compose.onNodeWithContentDescription(str(R.string.collect_action)).performClick()
        compose.waitForIdle()
        typeInto(str(R.string.add_to_collection_new_label), "My circle")
        clickText(str(R.string.add_to_collection_create))
        compose.waitForIdle()

        assertEquals(listOf("My circle"), titles())
        assertEquals(listOf(3L), hadithsOf(runBlocking { collections.observeAll().first().single().id }))
    }

    // -- A collection ------------------------------------------------------------------------------------------------

    @Test
    fun detail_showsItsHadithsTheSizeAndTheReviewAction() {
        val id = collection("Sermon", 2, 1)
        var reviewed = 0
        var opened: Long? = null
        detailScreen(id, onReview = { reviewed++ }, onOpenHadith = { opened = it })

        compose.onNodeWithText("Sermon").assertExists()
        compose.onNodeWithText(hadithCount(2)).assertExists()
        compose.onNodeWithText(secondMatn).assertExists()
        compose.onNodeWithText(firstMatn).assertExists()
        compose.onNodeWithText(thirdMatn).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.collection_review_hint)).assertExists()

        clickText(str(R.string.collection_review))
        assertEquals(1, reviewed)

        clickText(secondMatn)
        assertEquals(2L, opened)
    }

    @Test
    fun detail_removingAHadithTakesItOutOfTheCollectionOnly() {
        val id = collection("Sermon", 1, 2)
        studied(1, matn = 5f, sanad = 2f)
        val progress = reviews.mastery.toMap()
        detailScreen(id)

        compose.onAllNodesWithContentDescription(str(R.string.collection_remove_hadith))[0].performClick()
        compose.waitForIdle()

        assertEquals(listOf(2L), hadithsOf(id))
        compose.onNodeWithText(firstMatn).assertDoesNotExist()
        compose.onNodeWithText(secondMatn).assertExists()
        compose.onNodeWithText(hadithCount(1)).assertExists()
        assertEquals("the progress on it stays", progress, reviews.mastery)
        assertEquals(3, runBlocking { hadiths.getAll().size })
    }

    @Test
    fun detail_anEmptyCollectionInvitesToAddAndHasNothingToReview() {
        val id = collection("Empty one")
        var browsed = 0
        detailScreen(id, onBrowseLibrary = { browsed++ })

        compose.onNodeWithText(str(R.string.collection_empty_title)).assertExists()
        compose.onNodeWithText(str(R.string.collection_empty_body)).assertExists()
        compose.onNodeWithText(str(R.string.collection_review)).assertDoesNotExist()

        clickText(str(R.string.collection_browse_library))
        assertEquals(1, browsed)
    }

    @Test
    fun detail_theLastHadithGoingMakesItAnEmptyCollectionNotAnError() {
        val id = collection("Sermon", 1)
        detailScreen(id)

        compose.onNodeWithContentDescription(str(R.string.collection_remove_hadith)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.collection_empty_title)).assertExists()
        compose.onNodeWithText("Sermon").assertExists()
    }

    @Test
    fun detail_renamingChangesTheTitleAndKeepsTheHadiths() {
        val id = collection("Before", 1)
        detailScreen(id)

        compose.onNodeWithContentDescription(str(R.string.collection_edit)).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("After")
        clickText(str(R.string.collection_save))
        compose.waitForIdle()

        assertEquals(listOf("After"), titles())
        assertEquals(listOf(1L), hadithsOf(id))
        compose.onNodeWithText("After").assertExists()
        compose.onNodeWithText("Before").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.collection_editor_edit_title)).assertDoesNotExist()
    }

    @Test
    fun detail_renamingToAnotherCollectionsNameIsExplained() {
        collection("Other")
        val id = collection("Mine")
        detailScreen(id)

        compose.onNodeWithContentDescription(str(R.string.collection_edit)).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("other")
        clickText(str(R.string.collection_save))
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.collection_error_duplicate)).assertExists()
        assertEquals(setOf("Other", "Mine"), titles().toSet())
    }

    @Test
    fun detail_renamingToNothingCannotBeSaved() {
        val id = collection("Mine")
        detailScreen(id)

        compose.onNodeWithContentDescription(str(R.string.collection_edit)).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextClearance()

        compose.onNodeWithText(str(R.string.collection_save)).assertIsNotEnabled()
        assertEquals(listOf("Mine"), titles())
    }

    @Test
    fun detail_deletingAsksFirstAndSaysTheHadithsAndProgressStay() {
        val id = collection("Doomed", 1, 2)
        var closed = 0
        detailScreen(id, onClose = { closed++ })

        compose.onNodeWithContentDescription(str(R.string.collection_delete)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.collection_delete_title)).assertExists()
        compose.onNodeWithText(str(R.string.collection_delete_body, "Doomed")).assertExists()
        assertEquals("nothing is deleted before the answer", listOf("Doomed"), titles())

        clickText(str(R.string.collection_cancel))
        compose.waitForIdle()
        assertEquals(listOf("Doomed"), titles())
        assertEquals(0, closed)
        compose.onNodeWithText(str(R.string.collection_delete_title)).assertDoesNotExist()
    }

    @Test
    fun detail_confirmingTheDeleteRemovesTheCollectionAndLeavesTheLibraryAndProgress() {
        val id = collection("Doomed", 1, 2)
        val other = collection("Kept", 1)
        studied(1, matn = 5f, sanad = 2f)
        val progress = reviews.mastery.toMap()
        var closed = 0
        detailScreen(id, onClose = { closed++ })

        compose.onNodeWithContentDescription(str(R.string.collection_delete)).performClick()
        compose.waitForIdle()
        clickText(str(R.string.collection_delete_confirm))
        compose.waitForIdle()

        assertEquals(listOf("Kept"), titles())
        assertEquals(listOf(1L), hadithsOf(other))
        assertEquals(1, closed)
        assertEquals(progress, reviews.mastery)
        assertEquals(3, runBlocking { hadiths.getAll().size })
    }

    // -- The practice ------------------------------------------------------------------------------------------------

    @Test
    fun practice_goesThroughTheCardsAndEndsWithWhatNeedsAnotherLook() {
        val id = collection("Sermon", 1, 2)
        var closed = 0
        practiceScreen(id, onClose = { closed++ })

        compose.onNodeWithText("Sermon").assertExists()
        compose.onNodeWithText(str(R.string.review_progress, 1, 2)).assertExists()
        compose.onNodeWithText(str(R.string.practice_free_note)).assertExists()
        compose.onNodeWithText(str(R.string.practice_recalled)).assertDoesNotExist()
        compose.onNodeWithText(firstMatn).assertDoesNotExist()

        clickText(str(R.string.practice_reveal_matn))
        compose.waitForIdle()
        compose.onNodeWithText(firstMatn).assertExists()
        compose.onNodeWithText(str(R.string.practice_reveal_matn)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.practice_recalled)).assertExists()
        compose.onNodeWithText(str(R.string.practice_needs_look)).assertExists()

        clickText(str(R.string.practice_recalled))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.review_progress, 2, 2)).assertExists()

        clickText(str(R.string.practice_reveal_matn))
        compose.waitForIdle()
        clickText(str(R.string.practice_needs_look))
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.practice_done_title)).assertExists()
        compose.onNodeWithText(str(R.string.practice_done_body, hadithCount(2), "Sermon")).assertExists()
        compose.onNodeWithText(str(R.string.practice_tally, 1, 1)).assertExists()
        compose.onNodeWithText(str(R.string.practice_nothing_changed)).assertExists()
        compose.onNodeWithText(str(R.string.practice_again, 1)).assertExists()

        clickText(str(R.string.practice_back))
        assertEquals(1, closed)
    }

    @Test
    fun practice_theCardsThatNeededAnotherLookComeAroundAgainAsANewRound() {
        val id = collection("Sermon", 1, 2)
        practiceScreen(id)
        clickText(str(R.string.practice_reveal_matn))
        compose.waitForIdle()
        clickText(str(R.string.practice_needs_look))
        compose.waitForIdle()
        clickText(str(R.string.practice_reveal_matn))
        compose.waitForIdle()
        clickText(str(R.string.practice_recalled))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.practice_tally, 1, 1)).assertExists()

        clickText(str(R.string.practice_again, 1))
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.practice_round, 2) + " · Sermon").assertExists()
        compose.onNodeWithText(str(R.string.review_progress, 1, 1)).assertExists()
        clickText(str(R.string.practice_reveal_matn))
        compose.waitForIdle()
        compose.onNodeWithText(firstMatn).assertExists()
        clickText(str(R.string.practice_recalled))
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.practice_all_known)).assertExists()
        compose.onAllNodesWithText(str(R.string.practice_again, 1)).assertCountEquals(0)
    }

    @Test
    fun practice_aHadithWithItsChainOpenIsShownTheChainBeforeItIsJudged() {
        studied(3, matn = 6f, sanad = 2f)
        val id = collection("Sermon", 3)
        practiceScreen(id)

        clickText(str(R.string.practice_reveal_matn))
        compose.waitForIdle()
        compose.onNodeWithText(thirdMatn).assertExists()
        compose.onNodeWithText(str(R.string.practice_recalled)).assertDoesNotExist()

        clickText(str(R.string.practice_reveal_sanad))
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.practice_recalled)).assertExists()
        compose.onNodeWithText(str(R.string.practice_needs_look)).assertExists()
    }

    @Test
    fun practice_neverWritesAnythingForTheScheduleNotEvenForAHadithNeverStarted() {
        studied(3, matn = 6f, sanad = 2f)
        val id = collection("Sermon", 1, 3)
        val before = reviews.mastery.toMap()
        val events = reviews.events.toList()
        practiceScreen(id)

        clickText(str(R.string.practice_reveal_matn))
        compose.waitForIdle()
        clickText(str(R.string.practice_recalled))
        compose.waitForIdle()
        clickText(str(R.string.practice_reveal_matn))
        compose.waitForIdle()
        clickText(str(R.string.practice_reveal_sanad))
        compose.waitForIdle()
        clickText(str(R.string.practice_needs_look))
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.practice_done_title)).assertExists()
        assertEquals(before, reviews.mastery)
        assertEquals(events, reviews.events)
        assertFalse("hadith 1 was not taken into study", 1L in reviews.mastery)
    }

    @Test
    fun practice_anEmptyCollectionHasNothingToPracticeAndSaysItKindly() {
        val id = collection("Empty one")
        var closed = 0
        practiceScreen(id, onClose = { closed++ })

        compose.onNodeWithText(str(R.string.practice_empty_title)).assertExists()
        compose.onNodeWithText(str(R.string.practice_empty_body)).assertExists()
        compose.onNodeWithText(str(R.string.practice_reveal_matn)).assertDoesNotExist()

        clickText(str(R.string.practice_back))
        assertEquals(1, closed)
    }
}

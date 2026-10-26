package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeCollectionRepository
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.ReadOnlyReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.CollectionAccent
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.TitleProblem
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.repository.CollectionRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.CreateCollectionUseCase
import com.mutqan.hadith.domain.usecase.DeleteCollectionUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.ObserveCollectionDetailUseCase
import com.mutqan.hadith.domain.usecase.RenameCollectionUseCase
import com.mutqan.hadith.domain.usecase.SetCollectionMembershipUseCase
import com.mutqan.hadith.ui.collections.CollectionChoice
import com.mutqan.hadith.ui.collections.CollectionDetailViewModel
import com.mutqan.hadith.ui.collections.CollectionPracticeViewModel
import com.mutqan.hadith.ui.collections.CollectionsViewModel
import com.mutqan.hadith.ui.collections.DarkTones
import com.mutqan.hadith.ui.collections.EditorState
import com.mutqan.hadith.ui.collections.LightTones
import com.mutqan.hadith.ui.collections.PracticeEvent
import com.mutqan.hadith.ui.collections.PracticeSession
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.review.CardStage
import com.mutqan.hadith.ui.review.HintKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The collection screens' view models: curating, renaming and deleting, and the practice that must not touch the schedule. */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsViewModelsTest {

    private val now = 1_700_000_000_000L
    private var clock = now
    private val scheduler = DualDecayScheduler()

    private val chain = listOf(narrator(1, "الحميدي"), narrator(2, "سفيان"), narrator(3, "عمر", tier = "صحابي"))
    private val library = (1L..6L).map { hadith(it, chain, level = 1) }
    private val hadiths = FakeHadithRepository(library)
    private val collections = FakeCollectionRepository(library)
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    private val create = CreateCollectionUseCase(collections) { clock }
    private val rename = RenameCollectionUseCase(collections)
    private val delete = DeleteCollectionUseCase(collections)
    private val membership = SetCollectionMembershipUseCase(collections) { clock }
    private val detail = ObserveCollectionDetailUseCase(collections, reviews, scheduler) { clock }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun listVm(repo: CollectionRepository = collections) =
        CollectionsViewModel(repo, CreateCollectionUseCase(repo) { clock }, SetCollectionMembershipUseCase(repo) { clock })

    private fun detailVm(id: Long) = CollectionDetailViewModel(id, detail, rename, delete, membership)

    private fun practiceVm(id: Long) =
        CollectionPracticeViewModel(id, collections, hadiths, GetLayerStatusesUseCase(ReadOnlyReviewRepository(reviews), scheduler))

    private fun CollectionPracticeViewModel.session(): PracticeSession = (state.value as UiState.Success).data

    private fun CollectionDetailViewModel.loaded() = (state.value as UiState.Success).data

    /** Lets the flows behind the view models deliver what was just written or subscribed to. */
    private suspend fun settle() = repeat(10) { yield() }

    private fun studied(id: Long, matn: Float, sanad: Float, dueInDays: Int = 1) {
        reviews.mastery[id] = MasteryRecordEntity(id, matn, sanad, now - DAY_MS, now - DAY_MS, now + dueInDays * DAY_MS, 3)
    }

    // -- The list and the sheet --------------------------------------------------------------------------------------

    @Test
    fun list_startsEmptyAndFollowsTheCollectionsNewestFirst() = runTest {
        val vm = listVm()
        backgroundScope.launch { vm.list.collect {} }
        settle()
        assertEquals(UiState.Success(emptyList<Any>()), vm.list.value)

        clock = 100; vm.create("الأولى", 0)
        clock = 200; vm.create("الثانية", 1)
        settle()

        assertEquals(listOf("الثانية", "الأولى"), (vm.list.value as UiState.Success).data.map { it.title })
    }

    @Test
    fun create_makesTheCollectionWithTheHadithAndReportsItsId() = runTest {
        val vm = listVm()
        var savedId: Long? = null

        vm.create("  خطبة الجمعة ", accent = 2, firstHadithId = 4) { savedId = it }

        val collection = collections.observeAll().first().single()
        assertEquals(collection.id, savedId)
        assertEquals(Triple("خطبة الجمعة", 2, 1), Triple(collection.title, collection.accent, collection.hadithCount))
        assertEquals(listOf(4L), collections.getHadithIds(collection.id))
        assertEquals(EditorState(), vm.editor.value)
    }

    @Test
    fun create_aRefusedNameIsReportedAndNothingIsMade() = runTest {
        val vm = listVm()
        var calls = 0
        vm.create("Exists", 0)

        listOf("" to TitleProblem.EMPTY, "   " to TitleProblem.EMPTY, "exists" to TitleProblem.DUPLICATE, "ب".repeat(41) to TitleProblem.TOO_LONG)
            .forEach { (title, problem) ->
                vm.create(title, 0) { calls++ }
                assertEquals(title, EditorState(problem = problem), vm.editor.value)
            }

        assertEquals(0, calls)
        assertEquals(1, collections.observeAll().first().size)
    }

    @Test
    fun create_aRefusalIsForgottenOnceTheUserTypesAgain() = runTest {
        val vm = listVm()
        vm.create("", 0)
        assertEquals(TitleProblem.EMPTY, vm.editor.value.problem)

        vm.clearEditor()

        assertEquals(EditorState(), vm.editor.value)
    }

    @Test
    fun create_aFailureToSaveIsReportedNotThrown() = runTest {
        val vm = listVm()
        collections.failWrites = true
        var called = false

        vm.create("Name", 0) { called = true }

        assertEquals(EditorState(failed = true), vm.editor.value)
        assertFalse(called)
        collections.failWrites = false
        assertTrue(collections.observeAll().first().isEmpty())
    }

    @Test
    fun create_aSecondTapWhileSavingIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = object : CollectionRepository by collections {
            override suspend fun create(title: String, accent: Int, createdAt: Long, firstHadithId: Long?): Long {
                gate.await()
                return collections.create(title, accent, createdAt, firstHadithId)
            }
        }
        val vm = listVm(slow)

        vm.create("Once", 0)
        assertTrue(vm.editor.value.saving)
        vm.create("Twice", 0)
        gate.complete(Unit)

        assertEquals(listOf("Once"), collections.observeAll().first().map { it.title })
        assertFalse(vm.editor.value.saving)
    }

    @Test
    fun choices_markTheCollectionsAHadithIsIn() = runTest {
        val vm = listVm()
        vm.create("A", 0, firstHadithId = 2)
        clock += 10
        vm.create("B", 0)

        val choices: List<CollectionChoice> = vm.choices(2).first()

        assertEquals(listOf("B" to false, "A" to true), choices.map { it.collection.title to it.isMember })
    }

    @Test
    fun setMember_filesAndUnfilesAHadithAndTheSheetFollows() = runTest {
        val vm = listVm()
        vm.create("A", 0)
        val id = collections.observeAll().first().single().id
        backgroundScope.launch { vm.collected.collect {} }

        vm.setMember(5, id, true)
        settle()
        assertEquals(setOf(5L), vm.collected.value)
        assertTrue(vm.choices(5).first().single().isMember)

        vm.setMember(5, id, false)
        settle()
        assertTrue(vm.collected.value.isEmpty())
        assertFalse(vm.choices(5).first().single().isMember)
    }

    @Test
    fun setMember_aFailureIsReportedNotThrown() = runTest {
        val vm = listVm()
        vm.create("A", 0)
        val id = collections.observeAll().first().single().id
        collections.failWrites = true

        vm.setMember(5, id, true)

        assertTrue(vm.editor.value.failed)
    }

    @Test
    fun theAccentPaletteHasATonePerAccentIndexInBothThemes() {
        assertEquals(CollectionAccent.COUNT, LightTones.size)
        assertEquals(CollectionAccent.COUNT, DarkTones.size)
        assertEquals(CollectionAccent.COUNT, LightTones.toSet().size)
    }

    // -- One collection ---------------------------------------------------------------------------------------------

    private fun made(title: String, vararg hadithIds: Long): Long = kotlinx.coroutines.runBlocking {
        val id = collections.create(title, 0, clock)
        hadithIds.forEach { clock += 10; collections.add(id, it, clock) }
        id
    }

    @Test
    fun detail_showsTheHadithsAndAnEmptyCollectionIsNotAnError() = runTest {
        val full = made("Full", 3, 1)
        val empty = made("Empty")
        val fullVm = detailVm(full)
        val emptyVm = detailVm(empty)
        backgroundScope.launch { fullVm.state.collect {} }
        backgroundScope.launch { emptyVm.state.collect {} }
        settle()

        assertEquals(listOf(3L, 1L), fullVm.loaded().hadiths.map { it.hadith.id })
        assertTrue(emptyVm.loaded().isEmpty)
    }

    @Test
    fun detail_aMissingCollectionIsAnError() = runTest {
        val vm = detailVm(404)
        backgroundScope.launch { vm.state.collect {} }
        settle()

        assertTrue(vm.state.value is UiState.Error)
    }

    @Test
    fun detail_removingAHadithTakesItOutOfTheCollectionOnly() = runTest {
        val id = made("Mine", 1, 2)
        val vm = detailVm(id)
        backgroundScope.launch { vm.state.collect {} }
        settle()

        vm.removeHadith(1)
        settle()

        assertEquals(listOf(2L), vm.loaded().hadiths.map { it.hadith.id })
        assertEquals(6, hadiths.getAll().size)
    }

    @Test
    fun detail_renameSavesAndClosesAndARefusalStaysOpen() = runTest {
        made("Other")
        val id = made("Mine")
        val vm = detailVm(id)
        backgroundScope.launch { vm.state.collect {} }
        settle()
        var closed = 0

        vm.rename("other", 1) { closed++ }
        assertEquals(EditorState(problem = TitleProblem.DUPLICATE), vm.editor.value)
        assertEquals(0, closed)

        vm.rename("  Renamed ", 3) { closed++ }
        settle()
        assertEquals(1, closed)
        assertEquals(EditorState(), vm.editor.value)
        assertEquals("Renamed", vm.loaded().collection.title)
        assertEquals(3, vm.loaded().collection.accent)
    }

    @Test
    fun detail_deleteRemovesOnlyThisCollectionAndNeverShowsAnErrorOnTheWay() = runTest {
        val doomed = made("Doomed", 1, 2)
        val kept = made("Kept", 1)
        val vm = detailVm(doomed)
        val seen = mutableListOf<UiState<*>>()
        backgroundScope.launch { vm.state.collect { seen += it } }
        settle()
        var closed = false

        vm.delete { closed = true }
        settle()

        assertTrue(closed)
        assertEquals(listOf("Kept"), collections.observeAll().first().map { it.title })
        assertEquals(listOf(1L), collections.getHadithIds(kept))
        assertTrue("the screen is closing, not failing: $seen", seen.none { it is UiState.Error })
    }

    @Test
    fun detail_aFailedDeleteKeepsTheCollectionAndSaysSo() = runTest {
        val id = made("Mine", 1)
        val vm = detailVm(id)
        backgroundScope.launch { vm.state.collect {} }
        settle()
        collections.failWrites = true
        var closed = false

        vm.delete { closed = true }
        settle()

        assertFalse(closed)
        assertTrue(vm.editor.value.failed)
        collections.failWrites = false
        assertTrue(vm.state.value is UiState.Success)
    }

    // -- The practice -----------------------------------------------------------------------------------------------

    @Test
    fun practice_loadsTheCollectionInTheOrderItListsAndKnowsItsName() = runTest {
        val id = made("Friday", 4, 2, 6)

        val vm = practiceVm(id)

        val s = vm.session()
        assertEquals("Friday", s.title)
        assertEquals(listOf(4L, 2L, 6L), s.deck.map { it.hadithId })
        assertEquals(CardStage.RECALL, s.stage)
        assertEquals(1, s.round)
    }

    @Test
    fun practice_aCollectionWithNoHadithsIsAnEmptyFinishedSession() = runTest {
        val s = practiceVm(made("Empty")).session()

        assertTrue(s.deck.isEmpty())
        assertTrue(s.isFinished)
        assertNull(s.current)
    }

    @Test
    fun practice_aMissingCollectionIsAnError() = runTest {
        assertTrue(practiceVm(404).state.value is UiState.Error)
    }

    @Test
    fun practice_aCardIsJudgedOnlyOnceItIsFullyShown() = runTest {
        val vm = practiceVm(made("One", 1))

        vm.onEvent(PracticeEvent.Recalled)
        assertEquals("not judged before the matn is shown", 0, vm.session().index)
        assertFalse(vm.session().canJudge)

        vm.onEvent(PracticeEvent.Reveal)
        assertEquals(CardStage.MATN, vm.session().stage)
        assertTrue("a layer 1 card ends at its matn", vm.session().canJudge)
        vm.onEvent(PracticeEvent.Recalled)

        assertTrue(vm.session().isFinished)
        assertEquals(1, vm.session().recalled)
    }

    @Test
    fun practice_aCardWhoseChainIsOpenIsJudgedAfterTheChainToo() = runTest {
        studied(2, matn = 40f, sanad = 1f)
        assertEquals(MemorizationLayer.LAYER_2_SANAD_TREE, scheduler.layerStatus(reviews.mastery[2]).layer)
        val vm = practiceVm(made("Open chain", 2))

        vm.onEvent(PracticeEvent.Reveal)
        assertFalse("the matn alone is not the whole card", vm.session().canJudge)
        vm.onEvent(PracticeEvent.NeedsLook)
        assertEquals(0, vm.session().index)
        vm.onEvent(PracticeEvent.Reveal)
        assertEquals(CardStage.SANAD, vm.session().stage)
        vm.onEvent(PracticeEvent.Reveal)
        assertEquals("there is nothing past the chain", CardStage.SANAD, vm.session().stage)
        assertTrue(vm.session().canJudge)
    }

    @Test
    fun practice_theDeckFollowsEachHadithsOwnLayer() = runTest {
        studied(2, matn = 40f, sanad = 1f)
        val s = practiceVm(made("Mixed", 1, 2)).session()

        assertEquals(listOf(MemorizationLayer.LAYER_1_CORE, MemorizationLayer.LAYER_2_SANAD_TREE), s.deck.map { it.activeLayer })
    }

    /** Reveals every card to its end and judges it, until the round is over. */
    private fun judgeAll(vm: CollectionPracticeViewModel, recalled: (Long) -> Boolean) {
        var guard = 0
        while (!(vm.session().isFinished) && guard++ < 50) {
            val card = vm.session().current!!
            repeat(2) { vm.onEvent(PracticeEvent.Reveal) }
            vm.onEvent(if (recalled(card.hadithId)) PracticeEvent.Recalled else PracticeEvent.NeedsLook)
        }
    }

    @Test
    fun practice_theRoundTalliesWhatWasKnownAndWhatNeedsAnotherLook() = runTest {
        val vm = practiceVm(made("Round", 1, 2, 3, 4))

        judgeAll(vm) { it % 2 == 1L }

        val s = vm.session()
        assertTrue(s.isFinished)
        assertEquals(2, s.recalled)
        assertEquals(listOf(2L, 4L), s.needsLook)
    }

    @Test
    fun practice_goingThroughTheOnesThatNeedALookIsANewRound() = runTest {
        val vm = practiceVm(made("Round", 1, 2, 3, 4))
        judgeAll(vm) { it % 2 == 1L }

        vm.onEvent(PracticeEvent.PracticeAgain)

        val s = vm.session()
        assertEquals(2, s.round)
        assertEquals(listOf(2L, 4L), s.deck.map { it.hadithId })
        assertEquals(0, s.index)
        assertEquals(0, s.recalled)
        assertTrue(s.needsLook.isEmpty())
        assertEquals(CardStage.RECALL, s.stage)

        judgeAll(vm) { true }
        assertTrue(vm.session().isFinished)
        assertTrue("nothing left to go through again", vm.session().needsLook.isEmpty())
        vm.onEvent(PracticeEvent.PracticeAgain)
        assertEquals("a round with nothing to repeat stays finished", 2, vm.session().round)
    }

    @Test
    fun practice_goingAgainIsNotOfferedBeforeTheRoundIsDone() = runTest {
        val vm = practiceVm(made("Round", 1, 2))
        vm.onEvent(PracticeEvent.Reveal)
        vm.onEvent(PracticeEvent.NeedsLook)

        vm.onEvent(PracticeEvent.PracticeAgain)

        assertEquals(1, vm.session().round)
        assertEquals(2, vm.session().deck.size)
        assertEquals(1, vm.session().index)
    }

    @Test
    fun practice_aLayerOneCardGivesItsHintsOneByOneAndOnlyWhileRecalling() = runTest {
        val vm = practiceVm(made("Hints", 1))

        assertTrue(vm.session().canHint)
        vm.onEvent(PracticeEvent.Hint)
        assertEquals(listOf(HintKind.COMPANION), vm.session().shownHints)
        vm.onEvent(PracticeEvent.Hint)
        assertEquals(listOf(HintKind.COMPANION, HintKind.TAKHRIJ), vm.session().shownHints)
        assertFalse("every hint is given", vm.session().canHint)
        vm.onEvent(PracticeEvent.Hint)
        assertEquals(2, vm.session().hintsUsed)

        vm.onEvent(PracticeEvent.Reveal)
        assertFalse("no hints once the matn is shown", vm.session().canHint)
    }

    @Test
    fun practice_theNextCardStartsFreshButTheTashkeelChoiceStays() = runTest {
        val vm = practiceVm(made("Fresh", 1, 2))
        vm.onEvent(PracticeEvent.ToggleTashkeel)
        vm.onEvent(PracticeEvent.Hint)
        vm.onEvent(PracticeEvent.Reveal)

        vm.onEvent(PracticeEvent.Recalled)

        val s = vm.session()
        assertEquals(CardStage.RECALL, s.stage)
        assertEquals(0, s.hintsUsed)
        assertFalse(s.termsRevealed)
        assertTrue(s.hideTashkeel)
    }
}

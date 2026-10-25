package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeNarratorRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.drill.DrillAccess
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.CheckSanadDrillAccessUseCase
import com.mutqan.hadith.domain.usecase.SubmitLinkDrillUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.missinglink.MissingLinkEvent
import com.mutqan.hadith.ui.missinglink.MissingLinkViewModel
import com.mutqan.hadith.ui.orderchain.OrderChainEvent
import com.mutqan.hadith.ui.orderchain.OrderChainUi
import com.mutqan.hadith.ui.orderchain.OrderChainViewModel
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** The three Sanad drill screens' view models: who may play, how an order is checked, and what reaches the ledger. */
@OptIn(ExperimentalCoroutinesApi::class)
class SanadDrillViewModelsTest {

    private val now = 1_700_000_000_000L
    private val companion = "صحابي"

    // Hadith 1 has four narrators, 2 has one, 3 has two.
    private val humaydi = narrator(1, "الحميدي", "شيخ المصنف")
    private val nafi = narrator(2, "نافع")
    private val zuhri = narrator(3, "الزهري")
    private val abuHurayra = narrator(4, "أبو هريرة", companion)
    private val hadiths = FakeHadithRepository(
        listOf(
            hadith(1, listOf(humaydi, nafi, zuhri, abuHurayra)),
            hadith(2, listOf(narrator(5, "أنس بن مالك", companion))),
            hadith(3, listOf(narrator(6, "نعيم المجمر"), narrator(7, "عائشة", companion))),
        ),
    )
    private val narrators = FakeNarratorRepository(
        listOf(humaydi, nafi, zuhri, abuHurayra) + (20L..30L).map { narrator(it, "راوي $it") } +
            (31L..36L).map { narrator(it, "صحابي $it", companion) },
    )
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val advanced = MutableStateFlow(false)
    private val scheduler = DualDecayScheduler(unlockAllLayers = advanced)
    private var clock = now
    private val access = CheckSanadDrillAccessUseCase(reviews, scheduler)
    private val submitReview = SubmitReviewUseCase(hadiths, reviews, scheduler) { clock }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun core(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 8f, 0f, now - 8 * DAY_MS, null, now - DAY_MS, 3)
    }

    private fun tree(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 20f, 2f, now - DAY_MS, now - 5 * DAY_MS, now - DAY_MS, 4)
    }

    private fun orderVm(
        hadithId: Long = 1,
        repo: ReviewRepository = reviews,
        seed: Int = 4,
    ) = OrderChainViewModel(
        hadithId, hadiths, SubmitReviewUseCase(hadiths, repo, scheduler) { clock }, CheckSanadDrillAccessUseCase(repo, scheduler), Random(seed),
    )

    private fun OrderChainViewModel.ui(): OrderChainUi = (state.value as UiState.Success).data

    /** Puts the chain right by tapping: swaps each place with the narrator that belongs there. */
    private fun OrderChainViewModel.putRight() {
        val target = ui().chain.target
        target.indices.forEach { place ->
            val at = ui().chain.order.indexOfFirst { it.chipId == target[place].chipId }
            if (at != place) {
                onEvent(OrderChainEvent.Tap(place))
                onEvent(OrderChainEvent.Tap(at))
            }
        }
    }

    // -- Who may play ----------------------------------------------------------------------

    @Test
    fun orderChain_isBlockedWhileTheSanadTreeIsLocked() {
        core(1)

        assertEquals(UiState.Blocked(DrillAccess.LayerLocked), orderVm().state.value)
    }

    @Test
    fun orderChain_isBlockedForAHadithNotInStudy() {
        assertEquals(UiState.Blocked(DrillAccess.LayerLocked), orderVm().state.value)
    }

    @Test
    fun orderChain_isBlockedOnAChainOfFewerThanThree() {
        tree(2)
        tree(3)

        assertEquals(UiState.Blocked(DrillAccess.ChainTooShort(3, 1)), orderVm(hadithId = 2).state.value)
        assertEquals(UiState.Blocked(DrillAccess.ChainTooShort(3, 2)), orderVm(hadithId = 3).state.value)
    }

    @Test
    fun orderChain_theAdvancedStudentPlaysItAtOnce() {
        core(1)
        advanced.value = true

        assertTrue(orderVm().state.value is UiState.Success)
    }

    @Test
    fun orderChain_anUnknownHadithIsAnError() {
        assertTrue(orderVm(hadithId = 99).state.value is UiState.Error)
    }

    @Test
    fun everyDrillIsBlockedWhileTheSanadTreeIsLocked_andOpensWithIt() {
        fun states() = listOf(
            SanadBuilderViewModel(1, hadiths, submitReview, access, Random(1)).state.value,
            MissingLinkViewModel(1, hadiths, narrators, SubmitLinkDrillUseCase(hadiths, reviews, scheduler), access, Random(1)).state.value,
            orderVm().state.value,
        )

        core(1)
        assertTrue(states().all { it == UiState.Blocked(DrillAccess.LayerLocked) })

        tree(1)
        assertTrue(states().all { it is UiState.Success })
    }

    @Test
    fun missingNarrator_isBlockedOnAChainOfOne_andOpensOnTwo() {
        tree(2)
        tree(3)
        fun vm(id: Long) = MissingLinkViewModel(id, hadiths, narrators, SubmitLinkDrillUseCase(hadiths, reviews, scheduler), access, Random(1))

        assertEquals(UiState.Blocked(DrillAccess.ChainTooShort(2, 1)), vm(2).state.value)
        assertTrue(vm(3).state.value is UiState.Success)
    }

    // -- Playing the order ---------------------------------------------------------------------

    @Test
    fun orderChain_startsScrambledWithTheMatnShownAsTheAnchor() {
        tree(1)

        val ui = orderVm().ui()

        assertEquals(4, ui.chain.order.size)
        assertFalse(ui.chain.isInOrder)
        assertEquals(1, ui.hadithNumber)
        assertNull(ui.result)
    }

    @Test
    fun orderChain_tappingTwoNarratorsSwapsThem() {
        tree(1)
        val vm = orderVm()
        val before = vm.ui().chain.order

        vm.onEvent(OrderChainEvent.Tap(0))
        vm.onEvent(OrderChainEvent.Tap(3))

        assertEquals(before[3], vm.ui().chain.order[0])
        assertEquals(before[0], vm.ui().chain.order[3])
    }

    @Test
    fun orderChain_aWrongCheckMarksTheMistakesAndRecordsNothingYet() {
        tree(1)
        val before = reviews.mastery.getValue(1)
        val vm = orderVm()

        vm.onEvent(OrderChainEvent.Verify)

        val ui = vm.ui()
        assertFalse(ui.chain.verdict!!.isCorrect)
        assertEquals(1, ui.feedbackTick)
        assertNull(ui.result)
        assertEquals(before, reviews.mastery.getValue(1))
        assertTrue(reviews.events.isEmpty())
    }

    @Test
    fun orderChain_aRightCheckEndsTheRoundAndRecordsASanadOnlyReview() {
        tree(1)
        clock = now + 1_000
        val vm = orderVm()

        vm.putRight()
        vm.onEvent(OrderChainEvent.Verify)

        val result = vm.ui().result!!
        assertEquals(ReviewRating.GOOD, result.rating)
        assertTrue(result.missedNames.isEmpty())
        assertFalse(result.revealed)
        assertTrue(vm.ui().chain.isSolved)
        val record = reviews.mastery.getValue(1)
        assertEquals(clock, record.sanadLastReviewedAt)
        assertEquals(now - DAY_MS, record.matnLastReviewedAt) // the Matn was the anchor, not the question
        assertEquals(4, reviews.events.count { it.succeeded })
    }

    @Test
    fun orderChain_theMistakesOfAnEarlierCheckAreHeldAgainstTheRoundAndTheLedger() {
        tree(1)
        val vm = orderVm()
        vm.onEvent(OrderChainEvent.Verify) // scrambled: wrong
        val missed = vm.ui().chain.missedNarratorIds
        assertTrue(missed.isNotEmpty())

        vm.putRight()
        vm.onEvent(OrderChainEvent.Verify)

        val result = vm.ui().result!!
        assertTrue(result.rating != ReviewRating.GOOD)
        assertEquals(missed.size, result.missedNames.size)
        assertEquals(missed, reviews.events.filter { !it.succeeded }.map { it.narratorId }.toSet())
    }

    @Test
    fun orderChain_givingUpShowsTheOrderAndRecordsTheRoundAsMissed() {
        tree(1)
        val vm = orderVm()

        vm.onEvent(OrderChainEvent.Reveal)

        val ui = vm.ui()
        assertTrue(ui.result!!.revealed)
        assertEquals(ui.chain.target, ui.chain.order)
        assertTrue(ui.result!!.rating != ReviewRating.GOOD)
        assertTrue(reviews.events.any { !it.succeeded })
    }

    @Test
    fun orderChain_aRoundThatEndedIsFinalUntilANewOneIsStarted() {
        tree(1)
        val vm = orderVm()
        vm.putRight()
        vm.onEvent(OrderChainEvent.Verify)
        val order = vm.ui().chain.order
        val events = reviews.events.size

        vm.onEvent(OrderChainEvent.Tap(0))
        vm.onEvent(OrderChainEvent.Tap(1))
        vm.onEvent(OrderChainEvent.Move(0, +1))
        vm.onEvent(OrderChainEvent.Verify)
        vm.onEvent(OrderChainEvent.Reveal)

        assertEquals(order, vm.ui().chain.order)
        assertEquals(events, reviews.events.size) // not recorded twice

        vm.onEvent(OrderChainEvent.NewRound)

        assertNull(vm.ui().result)
        assertFalse(vm.ui().chain.isSolved)
        assertEquals(4, vm.ui().chain.order.size)
    }

    @Test
    fun orderChain_aNewRoundCannotBeStartedInTheMiddleOfOne() {
        tree(1)
        val vm = orderVm()
        vm.onEvent(OrderChainEvent.Tap(0))
        val before = vm.ui()

        vm.onEvent(OrderChainEvent.NewRound)

        assertEquals(before, vm.ui())
    }

    @Test
    fun orderChain_aFailedWriteLeavesTheRoundOpenToBeCheckedAgain() {
        tree(1)
        var failing = true
        val flaky = object : ReviewRepository by reviews {
            override suspend fun saveReview(record: MasteryRecordEntity, narratorEvents: List<NarratorReviewEventEntity>) {
                if (failing) error("disk full") else reviews.saveReview(record, narratorEvents)
            }
        }
        val vm = orderVm(repo = flaky)
        vm.putRight()

        vm.onEvent(OrderChainEvent.Verify)

        assertNotNull(vm.ui().submitError)
        assertNull(vm.ui().result)
        assertFalse(vm.ui().chain.isSolved)
        assertFalse(vm.ui().isSubmitting)

        vm.onEvent(OrderChainEvent.DismissError)
        failing = false
        vm.onEvent(OrderChainEvent.Verify)

        assertNull(vm.ui().submitError)
        assertEquals(ReviewRating.GOOD, vm.ui().result!!.rating)
        assertEquals(4, reviews.events.count { it.succeeded })
    }

    // -- The missing-narrator drill as the screen plays it -------------------------------------

    @Test
    fun missingNarrator_theCardsAreCanonicalAndTheLedgerKeepsTheChainsOwnRow() {
        tree(1)
        val vm = MissingLinkViewModel(1, hadiths, narrators, SubmitLinkDrillUseCase(hadiths, reviews, scheduler) { clock }, access, Random(3))
        val round = (vm.state.value as UiState.Success).data.round
        val hidden = round.hiddenNarrator

        vm.onEvent(MissingLinkEvent.Choose(round.answer.id))

        val ui = (vm.state.value as UiState.Success).data
        assertEquals(true, ui.isCorrect)
        assertEquals(listOf(hidden.id), reviews.events.map { it.narratorId })
        assertTrue(reviews.events.single().succeeded)
        // The hidden man is never a bare first name, and the options are four different people.
        assertEquals(4, round.options.map { it.id }.toSet().size)
    }
}

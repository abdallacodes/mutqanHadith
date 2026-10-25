package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.domain.drill.ChainOrderState
import com.mutqan.hadith.domain.drill.DrillAccess
import com.mutqan.hadith.domain.drill.MissingLinkRoundFactory
import com.mutqan.hadith.domain.drill.SanadDrill
import com.mutqan.hadith.domain.drill.SanadDrillPolicy
import com.mutqan.hadith.domain.drill.ratingForChainMistakes
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.CheckSanadDrillAccessUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * The Sanad drill suite's rules: which drills a hadith offers, how the missing-narrator question is built from people
 * rather than catalog rows, and how an order of the chain is moved, checked and rated.
 */
class SanadDrillSuiteTest {

    private val now = 1_700_000_000_000L
    private val companion = "صحابي"
    private val layer1 = LayerStatus(MemorizationLayer.LAYER_1_CORE, 0.4f)
    private val layer2 = LayerStatus(MemorizationLayer.LAYER_2_SANAD_TREE, 0.4f)

    /** The collector's teacher first, the Companion last, as the books write a chain. */
    private fun chainOf(vararg names: String) = hadith(
        1,
        names.mapIndexed { i, name -> narrator(i + 1L, name, if (i == names.lastIndex) companion else "راوٍ") },
    ).sanad

    // -- Which drills a hadith offers ---------------------------------------------------------

    @Test
    fun policy_layerOneLocksEveryDrill() {
        val chain = chainOf("الحميدي", "ابن عيينة", "الزهري", "عمر")

        SanadDrill.entries.forEach { assertEquals(it.name, DrillAccess.LayerLocked, SanadDrillPolicy.access(it, layer1, chain)) }
    }

    @Test
    fun policy_aHadithNotInStudyHasNoLayerAndEveryDrillIsLocked() {
        val chain = chainOf("الحميدي", "ابن عيينة", "الزهري", "عمر")

        SanadDrill.entries.forEach { assertEquals(DrillAccess.LayerLocked, SanadDrillPolicy.access(it, null, chain)) }
    }

    @Test
    fun policy_layerTwoOpensEveryDrillOnALongEnoughChain() {
        val chain = chainOf("الحميدي", "ابن عيينة", "الزهري", "عمر")

        assertEquals(SanadDrill.entries.toList(), SanadDrillPolicy.openDrills(layer2, chain))
    }

    @Test
    fun policy_everyDrillAsksForTwoNarratorsAndOrderingForThree() {
        val two = chainOf("نعيم المجمر", "أبو هريرة")
        val one = chainOf("أبو هريرة")

        assertEquals(DrillAccess.Open, SanadDrillPolicy.access(SanadDrill.MISSING_NARRATOR, layer2, two))
        assertEquals(DrillAccess.Open, SanadDrillPolicy.access(SanadDrill.SANAD_BUILDER, layer2, two))
        assertEquals(DrillAccess.ChainTooShort(3, 2), SanadDrillPolicy.access(SanadDrill.ORDER_CHAIN, layer2, two))
        assertEquals(listOf(SanadDrill.MISSING_NARRATOR, SanadDrill.SANAD_BUILDER), SanadDrillPolicy.openDrills(layer2, two))

        SanadDrill.entries.forEach {
            assertEquals(DrillAccess.ChainTooShort(it.minLinks, 1), SanadDrillPolicy.access(it, layer2, one))
        }
        assertTrue(SanadDrillPolicy.openDrills(layer2, one).isEmpty())
    }

    @Test
    fun policy_theLayerIsSaidBeforeTheLengthOfTheChain() {
        assertEquals(DrillAccess.LayerLocked, SanadDrillPolicy.access(SanadDrill.ORDER_CHAIN, layer1, chainOf("أبو هريرة")))
    }

    @Test
    fun policy_aChainOfBareFirstNamesHasNoNarratorToAskFor_butCanStillBeOrdered() {
        val chain = chainOf("سفيان", "يحيى", "محمد")

        assertEquals(DrillAccess.NoIdentifiedNarrator, SanadDrillPolicy.access(SanadDrill.MISSING_NARRATOR, layer2, chain))
        assertEquals(DrillAccess.Open, SanadDrillPolicy.access(SanadDrill.ORDER_CHAIN, layer2, chain))
    }

    @Test
    fun policy_oneNarratorKnownByNameIsEnoughToAskFor() {
        val chain = chainOf("سفيان", "يحيى", "الزهري")

        assertEquals(DrillAccess.Open, SanadDrillPolicy.access(SanadDrill.MISSING_NARRATOR, layer2, chain))
        assertEquals(listOf(2), SanadDrillPolicy.identifiedIndices(chain))
    }

    // -- The layer, as the student's setting changes it ----------------------------------------

    private val reviews = FakeReviewRepository()
    private val advanced = MutableStateFlow(false)
    private val access = CheckSanadDrillAccessUseCase(reviews, DualDecayScheduler(unlockAllLayers = advanced))
    private val studied = hadith(1, chainOf("الحميدي", "ابن عيينة", "الزهري", "عمر").map { it.narrator })

    private fun core(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 8f, 0f, now - 8 * DAY_MS, null, now - DAY_MS, 3)
    }

    private fun tree(id: Long) {
        reviews.mastery[id] = MasteryRecordEntity(id, 20f, 2f, now - DAY_MS, now - 5 * DAY_MS, now - DAY_MS, 4)
    }

    @Test
    fun access_isBlockedWhileTheSanadTreeIsLocked() = runTest {
        core(1)

        SanadDrill.entries.forEach { assertEquals(DrillAccess.LayerLocked, access(it, studied)) }
    }

    @Test
    fun access_opensWithTheSanadTree() = runTest {
        tree(1)

        SanadDrill.entries.forEach { assertEquals(DrillAccess.Open, access(it, studied)) }
    }

    @Test
    fun access_theAdvancedStudentHasEveryDrillOpenAtOnce_andClosesThemAgainAtOnce() = runTest {
        core(1)
        assertEquals(DrillAccess.LayerLocked, access(SanadDrill.ORDER_CHAIN, studied))

        advanced.value = true
        SanadDrill.entries.forEach { assertEquals(DrillAccess.Open, access(it, studied)) }

        advanced.value = false
        assertEquals(DrillAccess.LayerLocked, access(SanadDrill.ORDER_CHAIN, studied))
    }

    @Test
    fun access_theAdvancedStudentStillNeedsTheHadithToBeInStudy() = runTest {
        advanced.value = true

        assertEquals(DrillAccess.LayerLocked, access(SanadDrill.MISSING_NARRATOR, studied))
    }

    @Test
    fun access_theAdvancedStudentStillNeedsAChainWithSomethingInIt() = runTest {
        core(1)
        advanced.value = true

        val oneLink = hadith(1, listOf(narrator(1, "أبو هريرة", companion)))

        assertEquals(DrillAccess.ChainTooShort(2, 1), access(SanadDrill.MISSING_NARRATOR, oneLink))
        assertEquals(DrillAccess.ChainTooShort(3, 1), access(SanadDrill.ORDER_CHAIN, oneLink))
    }

    // -- The missing-narrator question ---------------------------------------------------------

    // Two spellings of the same Companion in the pool, as the Arba'in and 'Umdat write them.
    private val pool = listOf(
        narrator(16, "ابن عمر", companion), narrator(36, "عبد الله بن عمر", companion),
        narrator(42, "ابن عباس", companion), narrator(58, "عبد الله بن عباس", companion),
        narrator(12, "عمر", companion), narrator(6, "عمر بن الخطاب", companion),
        narrator(21, "أنس", companion), narrator(72, "أنس بن مالك", companion),
        narrator(34, "أبو هريرة", companion), narrator(74, "عائشة", companion),
        narrator(8, "مالك"), narrator(35, "نافع"), narrator(40, "الزهري"), narrator(27, "ابن شهاب"),
        // Bare first names, which name no one by themselves.
        narrator(2, "سفيان"), narrator(18, "يحيى"), narrator(38, "عبد الله"), narrator(13, "محمد"),
        narrator(10, "سفيان الثوري"), narrator(11, "يحيى بن سعيد القطان"),
    )

    private fun directoryOf(vararg extra: NarratorEntity) = NarratorDirectory.of(pool + extra)

    @Test
    fun missingNarrator_askedAboutAManWrittenTwoWaysItOffersHimOnce_asTheCanonicalPerson() {
        val chain = hadith(1, listOf(narrator(1, "الحميدي", "شيخ المصنف"), narrator(42, "ابن عباس", companion)))
        val directory = directoryOf(chain.sanad[0].narrator)

        repeat(60) { seed ->
            val round = MissingLinkRoundFactory.create(chain, pool + chain.sanad[0].narrator, Random(seed), avoidIndex = 0)

            // The hidden node is the chain's own row «ابن عباس»; the answer is the person, by his canonical row and name.
            assertEquals(42L, round.hiddenNarrator.id)
            assertEquals(58L, round.answer.id)
            assertEquals("عبد الله بن عباس", round.answer.name)
            assertEquals(1, round.options.count { it.id == round.answer.id })
            assertTrue(round.options.none { it.id == 42L })
            assertTrue("seed $seed: not canonical", round.options.all { directory.canonicalOf(it.id)!!.id == it.id })
        }
    }

    @Test
    fun missingNarrator_hasFourDistinctPeople_noneOfThemInTheChainOrAnAliasOfOneInIt() {
        val chain = hadith(
            1,
            listOf(narrator(1, "الحميدي", "شيخ المصنف"), narrator(16, "ابن عمر", companion), narrator(72, "أنس بن مالك", companion)),
        )
        val all = pool + chain.sanad[0].narrator
        val directory = NarratorDirectory.of(all)
        val inChain = chain.sanad.map { directory.canonicalId(it.narrator.id) }.toSet()

        repeat(80) { seed ->
            val round = MissingLinkRoundFactory.create(chain, all, Random(seed))
            val people = round.options.map { it.id }

            assertEquals("seed $seed", 4, people.size)
            assertEquals("seed $seed: a man twice", 4, people.toSet().size)
            assertTrue(round.answer.id in people)
            val distractors = people.filter { it != round.answer.id }
            assertTrue("seed $seed: a chain member offered: $distractors", distractors.none { it in inChain })
            // Neither spelling of Ibn 'Umar nor of Anas is a distractor, whichever of them was hidden.
            assertTrue(distractors.none { directory.membersOf(it).any { row -> row in setOf(16L, 36L, 21L, 72L) } })
        }
    }

    @Test
    fun missingNarrator_neverHidesOrOffersABareFirstName() {
        val chain = hadith(
            1,
            listOf(narrator(1, "الحميدي", "شيخ المصنف"), narrator(2, "سفيان"), narrator(18, "يحيى"), narrator(12, "عمر", companion)),
        )
        val all = pool + chain.sanad[0].narrator
        val bare = setOf(2L, 18L, 38L, 13L)
        val seen = mutableSetOf<Int>()

        repeat(120) { seed ->
            val round = MissingLinkRoundFactory.create(chain, all, Random(seed))
            seen += round.maskedIndex

            assertTrue("seed $seed hid ${round.hiddenNarrator.primaryName}", round.hiddenNarrator.id !in bare)
            assertTrue("seed $seed offered a bare name: ${round.options}", round.options.none { it.id in bare })
        }
        // Both of the narrators that can be asked for are asked for, and only those.
        assertEquals(setOf(0, 3), seen)
    }

    @Test
    fun missingNarrator_doesNotOfferAFullerNameOfABareNameInTheChain() {
        // «سفيان» in the chain may be al-Thawri; offering «سفيان الثوري» as a wrong answer could mark a right one wrong.
        val chain = hadith(1, listOf(narrator(1, "الحميدي", "شيخ المصنف"), narrator(2, "سفيان"), narrator(12, "عمر", companion)))
        val all = pool + chain.sanad[0].narrator

        repeat(120) { seed ->
            val round = MissingLinkRoundFactory.create(chain, all, Random(seed))

            assertTrue("seed $seed: ${round.options}", round.options.none { it.id == 10L })
        }
    }

    @Test
    fun missingNarrator_theNeighboursAreTheRelationOfTheHiddenNarrator() {
        val chain = hadith(
            1,
            listOf(
                narrator(1, "الحميدي", "شيخ المصنف"), narrator(35, "نافع"), narrator(40, "الزهري"), narrator(36, "عبد الله بن عمر", companion),
            ),
        )
        val all = pool + chain.sanad[0].narrator
        val names = chain.sanad.map { it.narrator.primaryName }

        repeat(80) { seed ->
            val round = MissingLinkRoundFactory.create(chain, all, Random(seed))
            val i = round.maskedIndex

            // Index 0 is the collector's teacher; whoever stands after a narrator is the one he received the hadith from.
            assertEquals("seed $seed from", names.getOrNull(i + 1), round.narratedFrom?.name)
            assertEquals("seed $seed to", names.getOrNull(i - 1), round.narratedTo?.name)
            assertEquals(i == 0, round.isCollectorsTeacher)
            assertEquals(i == chain.sanad.lastIndex, round.isLast)
            assertEquals(i == 0, round.narratedTo == null)
            assertEquals(i == chain.sanad.lastIndex, round.narratedFrom == null)
        }
    }

    @Test
    fun missingNarrator_aChainOfOneNarratorHasNothingToAskAbout() {
        try {
            MissingLinkRoundFactory.create(hadith(1, listOf(narrator(34, "أبو هريرة", companion))), pool, Random(1))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun missingNarrator_aChainOfBareNamesCannotBeAsked() {
        try {
            MissingLinkRoundFactory.create(hadith(1, listOf(narrator(2, "سفيان"), narrator(18, "يحيى"))), pool, Random(1))
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    // -- Putting the chain in order ------------------------------------------------------------

    private fun orderOf(vararg names: String, seed: Int = 3) = ChainOrderState.start(chainOf(*names), Random(seed))

    private fun ChainOrderState.names() = order.map { it.narrator.primaryName }

    /** The same chips arranged as [placeNames], first place first. */
    private fun ChainOrderState.arranged(vararg placeNames: String) =
        copy(order = placeNames.map { n -> order.first { it.narrator.primaryName == n } }, verdict = null)

    @Test
    fun order_theTargetRunsFromTheCompanionDownToTheCollectorsTeacher() {
        val state = orderOf("الحميدي", "ابن عيينة", "الزهري", "أبو هريرة")

        assertEquals(listOf("أبو هريرة", "الزهري", "ابن عيينة", "الحميدي"), state.target.map { it.narrator.primaryName })
        assertEquals(listOf(0, 1, 2, 3), state.target.map { it.chipId })
    }

    @Test
    fun order_startsScrambled_withEveryNarratorStillThere() {
        repeat(60) { seed ->
            val state = orderOf("الحميدي", "ابن عيينة", "الزهري", "أبو هريرة", seed = seed)

            assertEquals(state.target.map { it.chipId }.toSet(), state.order.map { it.chipId }.toSet())
            assertEquals(4, state.order.size)
            assertFalse("seed $seed started solved", state.isInOrder)
            assertNull(state.verdict)
            assertNull(state.selected)
        }
    }

    @Test
    fun order_tappingOneNarratorAndThenAnotherSwapsThem() {
        val start = orderOf("الحميدي", "ابن عيينة", "الزهري", "أبو هريرة")
        val first = start.order[0]
        val third = start.order[2]

        val selected = start.tap(0)
        assertEquals(0, selected.selected)
        assertEquals(start.order, selected.order)

        val swapped = selected.tap(2)
        assertNull(swapped.selected)
        assertEquals(third, swapped.order[0])
        assertEquals(first, swapped.order[2])
        assertEquals(start.order[1], swapped.order[1])
    }

    @Test
    fun order_tappingTheSelectedNarratorAgainLetsGo_andTheArrowsMoveOnePlace() {
        val start = orderOf("الحميدي", "ابن عيينة", "الزهري", "أبو هريرة")

        assertNull(start.tap(1).tap(1).selected)

        val down = start.move(0, +1)
        assertEquals(start.order[0], down.order[1])
        assertEquals(start.order[1], down.order[0])

        val up = start.move(3, -1)
        assertEquals(start.order[3], up.order[2])
        assertEquals(start.order[2], up.order[3])
    }

    @Test
    fun order_thereIsNoMovingPastEitherEndOrTappingOutsideTheList() {
        val start = orderOf("الحميدي", "ابن عيينة", "الزهري")

        assertEquals(start, start.move(0, -1))
        assertEquals(start, start.move(2, +1))
        assertEquals(start, start.tap(7))
    }

    @Test
    fun order_checkingARightOrderSolvesItAndRatesItGood() {
        val state = orderOf("الحميدي", "ابن عيينة", "الزهري", "أبو هريرة")
            .arranged("أبو هريرة", "الزهري", "ابن عيينة", "الحميدي")

        val checked = state.verify()

        assertTrue(checked.isSolved)
        assertTrue(checked.verdict!!.isCorrect)
        assertEquals(4, checked.verdict!!.correctCount)
        assertTrue(checked.missedNarratorIds.isEmpty())
        assertEquals(ReviewRating.GOOD, checked.rating())
    }

    @Test
    fun order_checkingAWrongOrderMarksEachPlace_andRemembersWhoWasOutOfPlace() {
        // The first two have traded places.
        val state = orderOf("الحميدي", "ابن عيينة", "الزهري", "أبو هريرة")
            .arranged("الزهري", "أبو هريرة", "ابن عيينة", "الحميدي")

        val checked = state.verify()

        assertFalse(checked.isSolved)
        assertEquals(listOf(false, false, true, true), checked.verdict!!.correctPlaces)
        assertEquals(2, checked.verdict!!.misplacedCount)
        assertEquals(setOf(3L, 4L), checked.missedNarratorIds) // الزهري (3) and أبو هريرة (4)
        assertEquals(1, checked.checks)
    }

    @Test
    fun order_aMistakeIsRememberedEvenWhenItIsFixedAndTheOrderChecksOut() {
        val wrong = orderOf("الحميدي", "ابن عيينة", "الزهري", "أبو هريرة")
            .arranged("الزهري", "أبو هريرة", "ابن عيينة", "الحميدي")
            .verify()

        val fixed = wrong.arranged("أبو هريرة", "الزهري", "ابن عيينة", "الحميدي").verify()

        assertTrue(fixed.isSolved)
        assertEquals(setOf(3L, 4L), fixed.missedNarratorIds)
        assertEquals(2, fixed.checks)
        // Two of four slipped: as many as the allowance of a third of the chain, rounded up.
        assertEquals(ReviewRating.HARD, fixed.rating())
    }

    @Test
    fun order_anyChangeMakesTheLastVerdictStale() {
        val checked = orderOf("الحميدي", "ابن عيينة", "الزهري", "أبو هريرة").verify()
        assertNotNull(checked.verdict)

        assertNull(checked.tap(0).tap(1).verdict)
        assertNull(checked.move(0, +1).verdict)
    }

    @Test
    fun order_aSolvedOrderIsFinal() {
        val solved = orderOf("الحميدي", "ابن عيينة", "الزهري", "أبو هريرة")
            .arranged("أبو هريرة", "الزهري", "ابن عيينة", "الحميدي").verify()

        assertEquals(solved, solved.tap(0))
        assertEquals(solved, solved.move(0, +1))
        assertEquals(solved, solved.verify())
        assertEquals(solved, solved.reveal())
    }

    @Test
    fun order_aSelectionIsLeftBehindByTheCheck() {
        val state = orderOf("الحميدي", "ابن عيينة", "الزهري").tap(1)

        assertNull(state.verify().selected)
    }

    @Test
    fun order_givingUpShowsTheRightOrderAndCountsEveryoneNotYetInPlace() {
        val state = orderOf("الحميدي", "ابن عيينة", "الزهري", "أبو هريرة")
            .arranged("الزهري", "أبو هريرة", "ابن عيينة", "الحميدي")

        val revealed = state.reveal()

        assertEquals(revealed.target, revealed.order)
        assertTrue(revealed.isSolved)
        assertEquals(setOf(3L, 4L), revealed.missedNarratorIds)
    }

    @Test
    fun order_ratingScalesWithTheShareOfTheChainThatSlipped() {
        assertEquals(ReviewRating.GOOD, ratingForChainMistakes(0, 6))
        assertEquals(ReviewRating.HARD, ratingForChainMistakes(1, 6))
        assertEquals(ReviewRating.HARD, ratingForChainMistakes(2, 6)) // a third of six
        assertEquals(ReviewRating.AGAIN, ratingForChainMistakes(3, 6))
        assertEquals(ReviewRating.HARD, ratingForChainMistakes(1, 3))
        assertEquals(ReviewRating.AGAIN, ratingForChainMistakes(2, 3))
    }

    // -- Two narrators who read alike ----------------------------------------------------------

    @Test
    fun order_oneManWrittenTwoWaysMayStandAtEitherOfHisPlaces() {
        // «ابن عمر» and «عبد الله بن عمر» are one Companion; a chain that carries both has him at two places.
        val chain = hadith(
            1,
            listOf(narrator(5, "نافع"), narrator(16, "ابن عمر", companion), narrator(36, "عبد الله بن عمر", companion)),
        ).sanad
        val state = ChainOrderState.start(chain, Random(1))

        val rightWay = state.arranged("عبد الله بن عمر", "ابن عمر", "نافع").verify()
        val otherWay = state.arranged("ابن عمر", "عبد الله بن عمر", "نافع").verify()

        assertTrue(rightWay.isSolved)
        assertTrue(otherWay.isSolved)
        assertTrue(otherWay.missedNarratorIds.isEmpty())
    }

    @Test
    fun order_twoDifferentMenWithOneNameAreNotInterchangeable_andAreToldApart() {
        val first = NarratorEntity(1, "محمد", "محمد بن سلام", "راوٍ", false)
        val second = NarratorEntity(2, "محمد", "محمد بن يوسف", "راوٍ", false)
        val sahabi = narrator(3, "أبو هريرة", companion)
        val state = ChainOrderState.start(hadith(1, listOf(first, second, sahabi)).sanad, Random(2))

        // Target: أبو هريرة, then محمد بن يوسف, then محمد بن سلام.
        val right = state.copy(order = listOf(state.target[0], state.target[1], state.target[2]), verdict = null).verify()
        val swapped = state.copy(order = listOf(state.target[0], state.target[2], state.target[1]), verdict = null).verify()

        assertTrue(right.isSolved)
        assertFalse(swapped.isSolved)
        assertEquals(setOf(1L, 2L), swapped.missedNarratorIds)
        assertTrue(state.needsQualifier(state.target[1]))
        assertTrue(state.needsQualifier(state.target[2]))
        assertFalse(state.needsQualifier(state.target[0]))
    }

    @Test
    fun order_aNameThatOpensAnotherNeedsToBeToldApartToo() {
        val chain = chainOf("سفيان", "سفيان الثوري", "أبو هريرة")
        val state = ChainOrderState.start(chain, Random(2))

        assertTrue(state.target.filter { it.narrator.primaryName.startsWith("سفيان") }.all { state.needsQualifier(it) })
        assertFalse(state.needsQualifier(state.target.first { it.narrator.primaryName == "أبو هريرة" }))
    }

    @Test
    fun order_aChainOfOneManThroughoutCannotBeScrambledAndStartsAsItIs() {
        val chain = hadith(1, listOf(narrator(16, "ابن عمر", companion), narrator(36, "عبد الله بن عمر", companion))).sanad

        val state = ChainOrderState.start(chain, Random(5))

        assertTrue(state.isInOrder) // nothing to scramble: both places hold the same man
        assertEquals(2, state.order.size)
    }
}

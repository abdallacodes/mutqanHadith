package com.mutqan.hadith.domain

import com.mutqan.hadith.domain.drill.MissingLinkRoundFactory
import com.mutqan.hadith.domain.drill.SanadBuilderState
import com.mutqan.hadith.domain.model.ReviewRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class DrillLogicTest {

    private fun chainOf(size: Int) = (1L..size).map { narrator(it) }
    private fun start(size: Int, seed: Int = 7) = SanadBuilderState.start(hadith(1, chainOf(size)).sanad, Random(seed))

    // -- Sanad builder -------------------------------------------------------------------

    @Test
    fun builder_startsScrambledWithEveryChipInTheTray() {
        repeat(20) { seed ->
            val state = start(6, seed)
            assertEquals(6, state.tray.size)
            assertEquals((0..5).toSet(), state.tray.map { it.chipId }.toSet())
            assertNotEquals("tray must not start solved (seed $seed)", (0..5).toList(), state.tray.map { it.chipId })
            assertTrue(state.placed.all { it == null })
            assertFalse(state.isComplete)
        }
    }

    @Test
    fun builder_placingTheChainInOrderIsAllCorrectAndRatedGood() {
        var state = start(5)
        for (chipId in 0..4) {
            val (next, feedback) = state.place(chipId)
            assertEquals(chipId, feedback!!.slotIndex) // fills the first empty slot each time
            assertTrue(feedback.correct)
            state = next
        }
        assertTrue(state.isComplete && state.isAllCorrect)
        assertTrue(state.tray.isEmpty())
        assertTrue(state.misplacedNarratorIds.isEmpty())
        assertEquals(ReviewRating.GOOD, state.rating())
    }

    @Test
    fun builder_wrongPlacementIsFlaggedRememberedAndRemovable() {
        val (afterWrong, feedback) = start(4).place(chipId = 3) // the Sahabi dropped into slot 0
        assertEquals(0, feedback!!.slotIndex)
        assertFalse(feedback.correct)
        assertEquals(setOf(4L), afterWrong.misplacedNarratorIds)
        assertFalse(afterWrong.isCorrect(0))

        val back = afterWrong.removeFromSlot(0)
        assertNull(back.placed[0])
        assertEquals(4, back.tray.size)
        // The slip stays on record even after fixing it, because the ledger should learn from it.
        assertEquals(setOf(4L), back.misplacedNarratorIds)

        val (fixed, ok) = back.place(chipId = 0)
        assertTrue(ok!!.correct)
        assertTrue(fixed.isCorrect(0))
    }

    @Test
    fun builder_correctPlacementsAreLocked() {
        val (state, _) = start(4).place(0)
        assertEquals(state, state.removeFromSlot(0))
    }

    @Test
    fun builder_aSelectedSlotReceivesTheNextChip() {
        val state = start(4).selectSlot(2)
        assertEquals(2, state.selectedSlot)
        val (placed, feedback) = state.place(2)
        assertEquals(2, feedback!!.slotIndex)
        assertTrue(feedback.correct)
        assertNull(placed.selectedSlot)
        assertNotNull(placed.placed[2])
        assertNull(placed.placed[0])
        // Tapping the selected slot again cancels the selection; filled slots cannot be selected.
        assertNull(start(4).selectSlot(1).selectSlot(1).selectedSlot)
        assertNull(placed.selectSlot(2).selectedSlot)
    }

    @Test
    fun builder_ignoresChipsNotInTheTrayAndPlacementsWhenFull() {
        val state = start(2)
        assertEquals(null, state.place(chipId = 9).second)
        var full = state
        full = full.place(0).first.place(1).first
        assertEquals(null, full.place(0).second)
    }

    @Test
    fun builder_revealFillsTheChainAndCountsWhatWasNotAlreadyRight() {
        var state = start(6).place(0).first // slot 0 correct
        state = state.place(5).first // Sahabi into slot 1: wrong, misplaced
        val revealed = state.reveal()

        assertTrue(revealed.isComplete && revealed.isAllCorrect)
        assertTrue(revealed.tray.isEmpty())
        // Narrator 1 was right; the other five were missed or misplaced.
        assertEquals(setOf(2L, 3L, 4L, 5L, 6L), revealed.misplacedNarratorIds)
        assertEquals(ReviewRating.AGAIN, revealed.rating())
    }

    @Test
    fun builder_ratingScalesWithTheShareOfTheChainMissed() {
        fun ratingAfterMisplacing(size: Int, mistakes: Int): ReviewRating {
            var state = start(size)
            val order = state.tray.map { it.chipId }.filter { it != 0 } // chips that are wrong in slot 0
            val toMisplace = order.take(mistakes)
            toMisplace.forEach { chip ->
                // Place into slot 0, then send it back, leaving the slip on record.
                state = state.place(chip).first.removeFromSlot(0)
            }
            return state.rating()
        }
        assertEquals(ReviewRating.GOOD, ratingAfterMisplacing(6, 0))
        assertEquals(ReviewRating.HARD, ratingAfterMisplacing(6, 1))
        assertEquals(ReviewRating.HARD, ratingAfterMisplacing(6, 2)) // a third of 6
        assertEquals(ReviewRating.AGAIN, ratingAfterMisplacing(6, 3))
        assertEquals(ReviewRating.HARD, ratingAfterMisplacing(4, 2)) // ceil(4 / 3)
        assertEquals(ReviewRating.AGAIN, ratingAfterMisplacing(4, 3))
    }

    // -- Missing link rounds ---------------------------------------------------------------

    private val pool = listOf(
        narrator(10, tier = "صحابي"), narrator(11, tier = "صحابي"), narrator(12, tier = "صحابي"),
        narrator(13, tier = "صحابي"), narrator(20, tier = "تابعي"), narrator(21, tier = "تابعي"),
        narrator(22, tier = "تابعي"), narrator(30, tier = "تابع التابعين"),
    )

    private fun sahabiChain() =
        hadith(1, listOf(narrator(1, tier = "تابعي"), narrator(2, tier = "تابعي"), narrator(3, tier = "صحابي")))

    @Test
    fun missingLink_hasFourDistinctOptionsIncludingTheAnswerAndNoChainMembers() {
        repeat(30) { seed ->
            val hadith = sahabiChain()
            val round = MissingLinkRoundFactory.create(hadith, pool + hadith.sanad.map { it.narrator }, Random(seed))

            assertEquals(4, round.options.size)
            assertEquals(4, round.options.map { it.id }.toSet().size)
            assertTrue(round.answer in round.options)
            val chainIds = hadith.sanad.map { it.narrator.id }.toSet()
            assertTrue(round.options.filter { it != round.answer }.none { it.id in chainIds })
        }
    }

    @Test
    fun missingLink_prefersDistractorsOfTheSameTier() {
        val hadith = hadith(1, listOf(narrator(1, tier = "تابعي"), narrator(3, tier = "صحابي")))
        repeat(30) { seed ->
            val round = MissingLinkRoundFactory.create(hadith, pool, Random(seed))
            val tiers = round.options.filter { it != round.answer }.map { it.tier }
            if (round.answer.tier == "صحابي") {
                assertTrue("only Sahabi distractors available in numbers: $tiers", tiers.all { it == "صحابي" })
            } else {
                // Three Tabi'i distractors exist in the pool, so no other tier is needed.
                assertTrue(tiers.all { it == "تابعي" })
            }
        }
    }

    @Test
    fun missingLink_topsUpFromOtherTiersWhenTheAnswersTierIsThin() {
        val thin = listOf(narrator(10, tier = "صحابي"), narrator(20, tier = "تابعي"), narrator(21, tier = "تابعي"))
        val h = hadith(1, listOf(narrator(1, tier = "تابع التابعين"), narrator(3, tier = "صحابي")))
        val round = MissingLinkRoundFactory.create(h, thin, Random(3), avoidIndex = 0) // masks the Sahabi
        assertEquals("صحابي", round.answer.tier)
        assertEquals(4, round.options.size)
        assertTrue(10L in round.options.map { it.id }) // the lone same-tier candidate is always used
    }

    @Test
    fun missingLink_neverRepeatsTheAvoidedIndex() {
        val hadith = hadith(1, chainOf(5))
        repeat(50) { seed ->
            val round = MissingLinkRoundFactory.create(hadith, pool, Random(seed), avoidIndex = 2)
            assertNotEquals(2, round.maskedIndex)
        }
    }

    @Test
    fun missingLink_usesWhatDistractorsExistAndFailsWithNone() {
        val h = hadith(1, chainOf(3))
        val two = MissingLinkRoundFactory.create(h, listOf(narrator(50), narrator(51)), Random(1))
        assertEquals(3, two.options.size)

        try {
            MissingLinkRoundFactory.create(h, h.sanad.map { it.narrator }, Random(1))
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }
}

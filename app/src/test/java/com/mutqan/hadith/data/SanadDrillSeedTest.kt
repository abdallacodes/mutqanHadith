package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.data.relation.SanadEdgeWithNarrator
import com.mutqan.hadith.data.seed.SeedLoader
import com.mutqan.hadith.domain.drill.ChainOrderState
import com.mutqan.hadith.domain.drill.MissingLinkRoundFactory
import com.mutqan.hadith.domain.drill.SanadDrill
import com.mutqan.hadith.domain.drill.SanadDrillPolicy
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.narrator.NarratorNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The drills held to the catalog they run on: on every hadith that can be drilled, a missing-narrator question has
 * four different people to choose from out of the 110 the catalog holds, none of them in the chain or a spelling of
 * someone in it, and an order of the chain can be made and checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SanadDrillSeedTest {

    private val seed = SeedLoader.load(ApplicationProvider.getApplicationContext<Context>())
    private val rows = seed.narrators
    private val directory = NarratorDirectory.of(rows)
    private val layer2 = LayerStatus(MemorizationLayer.LAYER_2_SANAD_TREE, 0.5f)

    private val library: List<HadithWithFullSanad> = seed.hadiths.map { h ->
        val byId = rows.associateBy { it.id }
        HadithWithFullSanad(
            h,
            seed.edges.filter { it.hadithId == h.id }.map { SanadEdgeWithNarrator(it, byId.getValue(it.narratorId)) },
        )
    }

    private val askable = library.filter { SanadDrillPolicy.access(SanadDrill.MISSING_NARRATOR, layer2, it.sanad).isOpen }
    private val orderable = library.filter { SanadDrillPolicy.access(SanadDrill.ORDER_CHAIN, layer2, it.sanad).isOpen }

    @Test
    fun theCatalogHasEighteenHadithsToAskAboutAndTenToOrder() {
        assertEquals(18, askable.size)
        assertEquals(10, orderable.size)
        // Every one of the ten long chains is in Sahih al-Bukhari; the eight short ones are 'Umdat al-Ahkam's.
        assertTrue(orderable.all { it.hadith.sourceBook == "صحيح البخاري" })
        assertEquals(8, askable.count { it.sanad.size == 2 })
    }

    @Test
    fun theDrillsAreOpenOnNothingBelowLayerTwo() {
        library.forEach { h ->
            SanadDrill.entries.forEach {
                assertFalse(SanadDrillPolicy.access(it, LayerStatus(MemorizationLayer.LAYER_1_CORE, 1f), h.sanad).isOpen)
            }
        }
    }

    @Test
    fun everyQuestionHasFourDifferentPeopleOfTheCanonicalPoolAndNoOneFromTheChain() {
        assertEquals(110, directory.narrators.size)
        val canonicalIds = directory.narrators.map { it.id }.toSet()
        var questions = 0

        askable.forEach { h ->
            val inChain = h.sanad.map { directory.canonicalId(it.narrator.id) }.toSet()
            val chainNames = h.sanad.flatMap { link ->
                directory.canonicalOf(link.narrator.id)!!.let { listOf(it.name, it.fullName) + it.aliases }
            }
            // Every place the question can fall on, and every place it can be asked after.
            val places = listOf<Int?>(null) + h.sanad.indices
            places.forEach { avoid ->
                repeat(12) { s ->
                    val round = MissingLinkRoundFactory.create(h, rows, Random(h.hadith.id.toInt() * 31 + s), avoidIndex = avoid)
                    questions++
                    val where = "hadith ${h.hadith.id} avoiding $avoid seed $s"

                    assertEquals(where, 4, round.options.size)
                    assertEquals("$where: a man twice", 4, round.options.map { it.id }.toSet().size)
                    assertTrue("$where: not canonical", round.options.all { it.id in canonicalIds })
                    assertTrue("$where: answer missing", round.answer in round.options)
                    assertEquals(where, directory.canonicalId(round.hiddenNarrator.id), round.answer.id)
                    val distractors = round.options.filter { it != round.answer }
                    assertTrue("$where: in the chain: $distractors", distractors.none { it.id in inChain })
                    distractors.forEach { d ->
                        val named = directory.canonicalOf(d.id)!!.let { listOf(it.name, it.fullName) + it.aliases }
                        assertTrue(
                            "$where: «${d.name}» may be a man of the chain",
                            named.none { x -> chainNames.any { y -> NarratorNormalizer.mayBeSamePerson(x, y) } },
                        )
                    }
                    assertTrue("$where: an unidentified man offered", round.options.all { directory.canonicalOf(it.id)!!.isResolved })
                    assertTrue("$where: an unidentified man hidden", directory.canonicalOf(round.hiddenNarrator.id)!!.isResolved)
                }
            }
        }
        assertTrue("only $questions questions", questions > 400)
    }

    @Test
    fun theBareNamesOfTheBukhariChainsAreNeverTheAnswerNorAnOption() {
        val names = setOf("سفيان", "يحيى", "عبد الله").map(NarratorNormalizer::key)
        val bare = rows.filter { NarratorNormalizer.key(it.primaryName) in names }.map { it.id }.toSet()
        assertEquals(setOf(2L, 18L, 38L), bare)

        askable.forEach { h ->
            repeat(30) { s ->
                val round = MissingLinkRoundFactory.create(h, rows, Random(s))
                assertTrue(round.hiddenNarrator.id !in bare)
                assertTrue(round.options.none { it.id in bare })
            }
        }
    }

    @Test
    fun aQuestionOnTheIntentionHadithAsksAboutAManTheChainNames() {
        val intention = library.first { it.hadith.sourceBook == "صحيح البخاري" && it.hadith.hadithNumber == 1 }

        val asked = (0 until 60).map { MissingLinkRoundFactory.create(intention, rows, Random(it)).hiddenNarrator.primaryName }.toSet()

        // The chain runs الحميدي ← سفيان ← يحيى بن سعيد ← محمد بن إبراهيم ← علقمة ← عمر: «سفيان» is never asked.
        assertFalse("سفيان" in asked)
        assertTrue("الحميدي عبد الله بن الزبير" in asked)
    }

    @Test
    fun theSameCompanionIsNotADistractorToHimselfAcrossTheBooks() {
        // 'Umdat al-Ahkam writes Ibn 'Umar as «عبد الله بن عمر», the Arba'in as «ابن عمر»: asked about either, never offered as the other.
        val ibnUmar = rows.filter { directory.canonicalOf(it.id)!!.name == "عبد الله بن عمر" }
        assertTrue(ibnUmar.size >= 2)

        library.filter { h -> h.sanad.size >= 2 && h.sanad.any { it.narrator.id in ibnUmar.map { r -> r.id } } }.forEach { h ->
            repeat(30) { s ->
                val round = MissingLinkRoundFactory.create(h, rows, Random(s))
                val ibnUmarOptions = round.options.filter { directory.canonicalOf(it.id)!!.name == "عبد الله بن عمر" }
                assertTrue(ibnUmarOptions.size <= 1)
                if (round.answer.name != "عبد الله بن عمر") assertTrue(ibnUmarOptions.isEmpty())
            }
        }
    }

    @Test
    fun everyLongChainCanBeScrambledAndPutRightFromTheCompanionDown() {
        orderable.forEach { h ->
            repeat(20) { s ->
                val state = ChainOrderState.start(h.sanad, Random(s))
                val where = "hadith ${h.hadith.id} seed $s"

                // The Companion heads the target and the collector's teacher ends it.
                assertEquals(where, h.sanad.last().narrator.id, state.target.first().narrator.id)
                assertEquals(where, h.sanad.first().narrator.id, state.target.last().narrator.id)
                assertEquals(where, h.sanad.size, state.target.map { it.personId }.toSet().size)
                assertFalse("$where started solved", state.isInOrder)
                assertTrue(where, state.copy(order = state.target).verify().isSolved)
            }
        }
    }
}

package com.mutqan.hadith.domain

import com.mutqan.hadith.domain.narrator.NarratorAliases
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.narrator.NarratorNormalizer
import com.mutqan.hadith.domain.narrator.getThroughPerson
import com.mutqan.hadith.ui.review.companionHint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who a narrator's name names: the normalizer that reads a spelling, the curated table that says which spellings are one
 * person, and the directory that applies it to the narrator rows of a catalog.
 */
class NarratorIdentityTest {

    // -- Reading a name ---------------------------------------------------------------------

    @Test
    fun theNarrationsWordsAroundAName_areSetAside() {
        val parsed = NarratorNormalizer.parse("عن أبي هريرة رضي الله عنه")

        assertEquals("أبو هريرة", parsed.core)
        assertEquals("رضي الله عنه", parsed.blessing)
        assertTrue(parsed.titles.isEmpty())
        assertEquals("أبو هريرة", NarratorNormalizer.displayName("وعن أبي هريرة"))
    }

    @Test
    fun theBlessingInAnyOfItsFormsIsNotPartOfTheName() {
        listOf(
            "ابن عمر رضي الله عنهما" to "ابن عمر",
            "عائشة رضي الله تعالى عنها" to "عائشة",
            "أنس بن مالك رضي الله عنه" to "أنس بن مالك",
            "الزهري رحمه الله" to "الزهري",
            "مالك رحمه الله تعالى" to "مالك",
            "(عمر بن الخطاب) - رضي الله عنه -" to "عمر بن الخطاب",
        ).forEach { (raw, name) -> assertEquals(raw, name, NarratorNormalizer.displayName(raw)) }
    }

    @Test
    fun theTashkeelOfAFullyVowelledNameIsDropped() {
        assertEquals("ابن عمر", NarratorNormalizer.displayName("عَنِ ابْنِ عُمَرَ رَضِيَ اللَّهُ عَنْهُمَا"))
    }

    @Test
    fun aTitleIsKeptAsMetadataAndLeftOutOfTheName() {
        val parsed = NarratorNormalizer.parse("أمير المؤمنين أبي حفص عمر بن الخطاب")

        assertEquals("أبو حفص عمر بن الخطاب", parsed.core)
        assertEquals(listOf("أمير المؤمنين"), parsed.titles)
        assertEquals(listOf("أم المؤمنين"), NarratorNormalizer.parse("أم المؤمنين أم عبد الله عائشة").titles)
        assertEquals("أم عبد الله عائشة", NarratorNormalizer.displayName("أم المؤمنين أم عبد الله عائشة"))
    }

    @Test
    fun theHeadNounOfAKunyaIsPutInTheNominative() {
        assertEquals("أبو هريرة", NarratorNormalizer.displayName("أبي هريرة"))
        assertEquals("أبو بكرة", NarratorNormalizer.displayName("أبا بكرة"))
        assertEquals("أخو بني فلان", NarratorNormalizer.displayName("أخي بني فلان"))
    }

    @Test
    fun aNounInTheMiddleOfANameAndTheCompanionUbayyAreLeftAlone() {
        assertEquals("علي بن أبي طالب", NarratorNormalizer.displayName("علي بن أبي طالب"))
        // «أبي بن كعب»: there «أبي» is the Companion's own name, not the noun.
        assertEquals("أبي بن كعب", NarratorNormalizer.displayName("أبي بن كعب"))
        assertNotEquals(NarratorNormalizer.key("أبي بن كعب"), NarratorNormalizer.key("أبو كعب"))
    }

    @Test
    fun aNameIsNeverReducedToItsBlessing() {
        assertEquals("رضي الله عنه", NarratorNormalizer.displayName("رضي الله عنه"))
        assertEquals("", NarratorNormalizer.displayName("   "))
        assertEquals("", NarratorNormalizer.key(""))
    }

    @Test
    fun spellingsOfOneNameShareAKey() {
        val abuHurayra = listOf("أبو هريرة", "أبي هريرة", "أبا هريرة", "ابو هريرة", "عن أبي هريرة رضي الله عنه", "أَبُو هُرَيْرَةَ")
        assertEquals(1, abuHurayra.map(NarratorNormalizer::key).distinct().size)

        assertEquals(NarratorNormalizer.key("ابن عباس"), NarratorNormalizer.key("إبن عباس"))
        assertEquals(NarratorNormalizer.key("سلمة بن الأكوع"), NarratorNormalizer.key("سلمة ابن الأكوع"))
        assertEquals(NarratorNormalizer.key("عبد الله بن عمر"), NarratorNormalizer.key("عبدالله بن عمر"))
        assertEquals(NarratorNormalizer.key("عبد الله بن عمر"), NarratorNormalizer.key("عبد الله ابن عمر"))
        assertEquals(NarratorNormalizer.key("عبد الرحمن بن سمرة"), NarratorNormalizer.key("عبدالرحمن بن سمرة"))
        assertEquals(NarratorNormalizer.key("عائشة"), NarratorNormalizer.key("عائشه"))
        assertEquals(NarratorNormalizer.key("علي بن أبي طالب"), NarratorNormalizer.key("علي بن أبو طالب"))
        assertEquals(NarratorNormalizer.key("إسحاق بن إبراهيم"), NarratorNormalizer.key("اسحاق بن ابراهيم"))
    }

    @Test
    fun namesThatAreNotTheSameStayApart() {
        assertNotEquals(NarratorNormalizer.key("عبد الله بن عمر"), NarratorNormalizer.key("عبد الله بن عمرو"))
        assertNotEquals(NarratorNormalizer.key("همام بن منبه"), NarratorNormalizer.key("همام بن الحارث"))
        assertNotEquals(NarratorNormalizer.key("عقبة بن عامر"), NarratorNormalizer.key("عقبة بن الحارث"))
        assertNotEquals(NarratorNormalizer.key("سفيان"), NarratorNormalizer.key("أبو عمرو سفيان بن عبد الله"))
    }

    // -- The curated table ------------------------------------------------------------------

    @Test
    fun aSpellingInNarrationFormFindsItsPerson() {
        assertEquals("أبو هريرة", NarratorAliases.canonicalName("عن أبي هريرة رضي الله عنه"))
        assertEquals("أبو هريرة", NarratorAliases.canonicalName("أبي هريرة"))
        assertEquals("عبد الله بن عباس", NarratorAliases.canonicalName("ابن عباس رضي الله عنهما"))
        assertEquals("عبد الله بن عمر", NarratorAliases.canonicalName("ابن عمر"))
        assertEquals("عمر بن الخطاب", NarratorAliases.canonicalName("أمير المؤمنين أبي حفص عمر بن الخطاب"))
        assertEquals("عائشة", NarratorAliases.canonicalName("أم المؤمنين أم عبد الله عائشة"))
    }

    @Test
    fun theBareNamesTheHadithBooksUseForACompanionResolveToHim() {
        assertEquals("عمر بن الخطاب", NarratorAliases.canonicalName("عمر"))
        assertEquals("أنس بن مالك", NarratorAliases.canonicalName("أنس"))
        assertEquals("أبو موسى الأشعري", NarratorAliases.canonicalName("أبو موسى"))
        assertEquals("حذيفة بن اليمان", NarratorAliases.canonicalName("حذيفة"))
        assertEquals("عبد الله بن عمرو بن العاص", NarratorAliases.canonicalName("عبد الله بن عمرو"))
    }

    @Test
    fun theOrthographyOfTheCanonicalNamesIsSettled() {
        assertEquals("عبد الله بن عمرو بن العاص", NarratorAliases.canonicalName("عبد الله بن عمرو بن العاصي"))
        assertEquals("سلمة بن الأكوع", NarratorAliases.canonicalName("سلمة ابن الأكوع"))
    }

    @Test
    fun aNameNobodyMergesIsReturnedCleanButOtherwiseAsItIs() {
        assertEquals("نافع", NarratorAliases.canonicalName("نافع رحمه الله"))
        assertEquals("أم سلمة", NarratorAliases.canonicalName("عن أم سلمة رضي الله عنها"))
        assertNull(NarratorAliases.groupOf("نافع"))
        assertEquals("", NarratorAliases.canonicalName(""))
    }

    @Test
    fun twoCompanionsWithAnAlmostIdenticalNameAreNotMerged() {
        assertEquals("عبد الله بن عمر", NarratorAliases.canonicalName("عبد الله بن عمر"))
        assertEquals("عبد الله بن عمرو بن العاص", NarratorAliases.canonicalName("عبد الله بن عمرو بن العاص"))
        assertNotEquals(NarratorAliases.canonicalName("عبد الله بن عمر"), NarratorAliases.canonicalName("عبد الله بن عمرو"))
        // The bare names of the Bukhari chains are nobody in particular.
        assertNull(NarratorAliases.groupOf("سفيان"))
        assertNull(NarratorAliases.groupOf("يحيى"))
        assertNull(NarratorAliases.groupOf("عبد الله"))
    }

    @Test
    fun everyGroupIsReachableByItsOwnName() {
        NarratorAliases.groups.forEach { group ->
            assertEquals(group.name, NarratorAliases.groupOf(group.name)?.name)
            group.spellings.forEach { assertEquals("«$it»", group.name, NarratorAliases.groupOf(it)?.name) }
        }
    }

    // -- The hint, from the canonical name ----------------------------------------------------

    @Test
    fun theCompanionHintComesFromTheCanonicalNameWhicheverWayTheBookWritesHim() {
        // «ابن عمر» starts with a hamza-less alef, «عبد الله بن عمر» with an ain: one man, one hint.
        assertEquals("عـ…", companionHint(NarratorAliases.canonicalName("ابن عمر")))
        assertEquals("عـ…", companionHint(NarratorAliases.canonicalName("عبد الله بن عمر")))
        assertEquals("عـ…", companionHint(NarratorAliases.canonicalName("أبو عبد الرحمن عبد الله بن عمر بن الخطاب")))
        // «أبي هريرة» and «أبو هريرة» begin with the same letter after the case is settled.
        assertEquals("أ…", companionHint(NarratorAliases.canonicalName("عن أبي هريرة رضي الله عنه")))
        assertEquals("أ…", companionHint(NarratorAliases.canonicalName("أبو هريرة عبد الرحمن بن صخر")))
        assertEquals("عـ…", companionHint(NarratorAliases.canonicalName("عمر")))
        assertEquals("عـ…", companionHint(NarratorAliases.canonicalName("ابن عباس")))
    }

    // -- The directory ----------------------------------------------------------------------

    private val companion = "صحابي"

    @Test
    fun rowsThatNameOneManAreOnePersonUnderOneOfTheirIds() {
        val directory = NarratorDirectory.of(
            listOf(
                narrator(52, "أبو هريرة عبد الرحمن بن صخر", companion),
                narrator(34, "أبو هريرة", companion),
                narrator(9, "نافع"),
            ),
        )

        val person = directory.canonicalOf(52)!!
        assertEquals(34L, person.id)
        assertEquals(setOf(34L, 52L), person.memberIds)
        assertEquals("أبو هريرة", person.name)
        assertEquals("أبو هريرة عبد الرحمن بن صخر", person.fullName)
        assertEquals(person, directory.canonicalOf(34))
        assertEquals(34L, directory.canonicalId(52))
        assertEquals(setOf(34L, 52L), directory.membersOf(34))
        assertEquals(setOf(34L, 52L), directory.membersOf(52))
        assertEquals(listOf(9L, 34L), directory.narrators.map { it.id })
    }

    @Test
    fun theCanonicalIdIsTheRowWrittenAsTheNameElseTheLowest() {
        val withTheName = NarratorDirectory.of(listOf(narrator(3, "ابن عباس", companion), narrator(8, "عبد الله بن عباس", companion)))
        assertEquals(8L, withTheName.canonicalId(3))

        val without = NarratorDirectory.of(listOf(narrator(12, "عمر", companion), narrator(46, "أمير المؤمنين أبي حفص عمر بن الخطاب", companion)))
        assertEquals(12L, without.canonicalId(46))
        assertEquals("عمر بن الخطاب", without.canonicalOf(46)!!.name)
    }

    @Test
    fun aTitleStaysOutOfTheNameButIsKept() {
        val directory = NarratorDirectory.of(
            listOf(narrator(6, "عمر بن الخطاب", companion), narrator(46, "أمير المؤمنين أبي حفص عمر بن الخطاب", companion)),
        )

        val umar = directory.canonicalOf(46)!!

        assertEquals("عمر بن الخطاب", umar.name)
        assertEquals("أبو حفص عمر بن الخطاب", umar.fullName)
        assertEquals(listOf("أمير المؤمنين"), umar.titles)
        assertEquals(emptyList<String>(), umar.aliases)
    }

    @Test
    fun theOtherNamesAreKeptAsAliases() {
        val directory = NarratorDirectory.of(
            listOf(
                narrator(36, "عبد الله بن عمر", companion),
                narrator(16, "ابن عمر", companion),
                narrator(47, "أبو عبد الرحمن عبد الله بن عمر بن الخطاب", companion),
            ),
        )

        val person = directory.canonicalOf(16)!!

        assertEquals(36L, person.id)
        assertEquals("أبو عبد الرحمن عبد الله بن عمر بن الخطاب", person.fullName)
        assertEquals(listOf("ابن عمر"), person.aliases)
    }

    @Test
    fun aCompanionsBareNameDoesNotSwallowALaterNarratorWhoSharesIt() {
        val directory = NarratorDirectory.of(
            listOf(narrator(1, "أنس بن مالك", companion), narrator(2, "أنس", "تابعي"), narrator(3, "أنس", companion)),
        )

        assertEquals(setOf(1L, 3L), directory.membersOf(1))
        assertEquals(setOf(2L), directory.membersOf(2))
    }

    @Test
    fun aRowThatIsNobodysAliasIsItsOwnPerson() {
        val directory = NarratorDirectory.of(listOf(narrator(7, "عبد الله"), narrator(8, "عبد الله بن مسلمة")))

        assertEquals(7L, directory.canonicalId(7))
        assertEquals(8L, directory.canonicalId(8))
        assertEquals(setOf(7L), directory.membersOf(7))
        assertEquals("عبد الله", directory.canonicalOf(7)!!.name)
    }

    @Test
    fun anUnknownIdIsNotInventedButStillAnswersToItself() {
        val directory = NarratorDirectory.of(listOf(narrator(1, "نافع")))

        assertNull(directory.canonicalOf(99))
        assertEquals(99L, directory.canonicalId(99))
        assertEquals(setOf(99L), directory.membersOf(99))
    }

    @Test
    fun groupedPivotIsPivotWhenAnyRowIs() {
        val pivot = narrator(2, "ابن شهاب").copy(isPivot = true)
        val directory = NarratorDirectory.of(listOf(narrator(1, "الزهري"), pivot))

        assertTrue(directory.canonicalOf(1)!!.isPivot)
        assertEquals(1, directory.narrators.size)
    }

    // -- Hadiths through a person -----------------------------------------------------------

    @Test
    fun theHadithsThroughAPersonAreThoseOfAllHisSpellings() = runTest {
        val short = narrator(16, "ابن عمر", companion)
        val long = narrator(36, "عبد الله بن عمر", companion)
        val other = narrator(6, "عمر بن الخطاب", companion)
        val repository = FakeHadithRepository(
            listOf(hadith(1, listOf(short), level = 1), hadith(2, listOf(long), level = 2), hadith(3, listOf(other), level = 1)),
        )

        assertEquals(listOf(1L, 2L), repository.getThroughPerson(36).map { it.hadith.id }.sorted())
        assertEquals(listOf(1L, 2L), repository.getThroughPerson(16).map { it.hadith.id }.sorted())
        assertEquals(listOf(3L), repository.getThroughPerson(6).map { it.hadith.id })
        assertEquals(emptyList<Long>(), repository.getThroughPerson(404).map { it.hadith.id })
    }
}

package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.review.CardStage
import com.mutqan.hadith.ui.review.HintKind
import com.mutqan.hadith.ui.review.ReviewEvent
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewSession
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.review.companionHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The review session with narrators known by more than one name: the card, its hint, and a review through a man. */
@OptIn(ExperimentalCoroutinesApi::class)
class NarratorIdentityViewModelsTest {

    private val now = 1_700_000_000_000L
    private val companion = "صحابي"
    private val scheduler = DualDecayScheduler()

    // The Arba'in writes «ابن عمر» and 'Umdat «عبد الله بن عمر»; the intention hadith's Zuhri is «ابن شهاب» in one chain.
    private val ibnUmar = narrator(16, "ابن عمر", companion)
    private val abdAllahIbnUmar = narrator(36, "عبد الله بن عمر", companion)
    private val ibnAbbas = narrator(42, "ابن عباس", companion)
    private val abdAllahIbnAbbas = narrator(58, "عبد الله بن عباس", companion)
    private val nafi = narrator(5, "نافع", "راوٍ")
    private val salim = narrator(9, "سالم", "راوٍ")

    private val hadiths = FakeHadithRepository(
        listOf(
            hadith(1, listOf(ibnUmar), level = 1),
            hadith(2, listOf(abdAllahIbnUmar), level = 2),
            hadith(4, listOf(nafi, ibnAbbas), level = 3),
            hadith(5, listOf(salim, abdAllahIbnAbbas), level = 3),
            hadith(6, listOf(nafi, ibnUmar), level = 3),
        ),
    )
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

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

    private fun reviewVm(mode: ReviewMode, targetId: Long? = null) = ReviewViewModel(
        mode, targetId,
        GetDueHadithsUseCase(hadiths, reviews, scheduler) { now },
        GetUnseenHadithsUseCase(hadiths),
        hadiths,
        SubmitReviewUseCase(hadiths, reviews, scheduler) { now },
        GetNarratorChainHeatmapUseCase(hadiths, reviews),
        GetLayerStatusesUseCase(reviews, scheduler),
    )

    private fun ReviewViewModel.session(): ReviewSession = (state.value as UiState.Success).data

    @Test
    fun theCardNamesTheCompanionByHisCanonicalName() {
        core(1)
        core(2)

        val cards = reviewVm(ReviewMode.DUE).session().queue.associateBy { it.hadithId }

        assertEquals("عبد الله بن عمر", cards.getValue(1).sahabi)
        assertEquals("عبد الله بن عمر", cards.getValue(2).sahabi)
    }

    @Test
    fun oneCompanionGivesTheSameHintInEveryBook() {
        core(1)
        core(2)
        val vm = reviewVm(ReviewMode.DUE)

        val hints = vm.session().queue.map { card -> companionHint(card.sahabi) }

        // «ابن عمر» used to start with «ا» and «عبد الله بن عمر» with «ع».
        assertEquals(listOf("عـ…", "عـ…"), hints)
    }

    @Test
    fun theHintStepsGoThroughTheCanonicalName() {
        core(1)
        val vm = reviewVm(ReviewMode.DUE)

        vm.onEvent(ReviewEvent.Hint)

        assertEquals(listOf(HintKind.COMPANION), vm.session().shownHints)
        assertEquals("عـ…", companionHint(vm.session().current!!.sahabi))
        assertEquals(CardStage.RECALL, vm.session().stage)
    }

    @Test
    fun aReviewThroughAManCoversHadithsOfAllHisSpellings() {
        tree(4)
        tree(5)

        listOf(42L, 58L).forEach { target ->
            val queue = reviewVm(ReviewMode.NARRATOR, targetId = target).session().queue

            assertEquals("through $target", setOf(4L, 5L), queue.map { it.hadithId }.toSet())
        }
    }

    @Test
    fun aReviewThroughAManLeavesOutTheOtherMen() {
        tree(4)
        tree(5)
        tree(6)

        val ibnAbbasQueue = reviewVm(ReviewMode.NARRATOR, targetId = 58).session().queue.map { it.hadithId }.toSet()
        val ibnUmarQueue = reviewVm(ReviewMode.NARRATOR, targetId = 36).session().queue.map { it.hadithId }.toSet()

        assertEquals(setOf(4L, 5L), ibnAbbasQueue)
        assertEquals(setOf(6L), ibnUmarQueue)
    }

    @Test
    fun aReviewThroughAnUnknownNarratorIsEmptyNotAFailure() {
        tree(4)

        val session = reviewVm(ReviewMode.NARRATOR, targetId = 404).session()

        assertEquals(0, session.queue.size)
    }
}

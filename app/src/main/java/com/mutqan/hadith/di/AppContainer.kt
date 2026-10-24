package com.mutqan.hadith.di

import android.content.Context
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.repository.RoomHadithRepository
import com.mutqan.hadith.data.repository.RoomNarratorRepository
import com.mutqan.hadith.data.repository.RoomReviewRepository
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.NarratorRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import com.mutqan.hadith.domain.usecase.SubmitLinkDrillUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase

/** Hand-rolled dependency graph: one instance per process, owned by the Application. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database: MutqanDatabase by lazy { MutqanDatabase.getInstance(appContext) }

    val hadithRepository: HadithRepository by lazy { RoomHadithRepository(database) }
    val reviewRepository: ReviewRepository by lazy { RoomReviewRepository(database) }
    val narratorRepository: NarratorRepository by lazy { RoomNarratorRepository(database) }
    val scheduler: SpacedRepetitionScheduler = DualDecayScheduler()

    val observeStudyDashboard by lazy { ObserveStudyDashboardUseCase(hadithRepository, reviewRepository, scheduler) }
    val getDueHadiths by lazy { GetDueHadithsUseCase(hadithRepository, reviewRepository, scheduler) }
    val getUnseenHadiths by lazy { GetUnseenHadithsUseCase(hadithRepository) }
    val submitReview by lazy { SubmitReviewUseCase(hadithRepository, reviewRepository, scheduler) }
    val submitLinkDrill by lazy { SubmitLinkDrillUseCase(hadithRepository, reviewRepository, scheduler) }
    val getNarratorChainHeatmap by lazy { GetNarratorChainHeatmapUseCase(hadithRepository, reviewRepository) }
    val getNarratorGraph by lazy { GetNarratorGraphUseCase(hadithRepository, reviewRepository) }
    val getHadithsByNarrator by lazy { GetHadithsByNarratorUseCase(hadithRepository, reviewRepository) }
}

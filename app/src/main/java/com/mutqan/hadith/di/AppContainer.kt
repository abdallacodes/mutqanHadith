package com.mutqan.hadith.di

import android.content.Context
import com.mutqan.hadith.data.backup.ContentResolverBackupFileStore
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.repository.RoomBackupRepository
import com.mutqan.hadith.data.repository.RoomCollectionRepository
import com.mutqan.hadith.data.repository.RoomHadithRepository
import com.mutqan.hadith.data.repository.RoomNarratorRepository
import com.mutqan.hadith.data.repository.RoomReviewRepository
import com.mutqan.hadith.data.settings.SharedPreferencesStudyPreferences
import com.mutqan.hadith.domain.backup.BackupFileStore
import com.mutqan.hadith.domain.backup.BackupRepository
import com.mutqan.hadith.domain.backup.ExportUserDataUseCase
import com.mutqan.hadith.domain.backup.ImportUserDataUseCase
import com.mutqan.hadith.domain.backup.ResetProgressUseCase
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.repository.CollectionRepository
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.NarratorRepository
import com.mutqan.hadith.domain.repository.ReviewRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler
import com.mutqan.hadith.domain.settings.StudyPreferences
import com.mutqan.hadith.domain.usecase.CreateCollectionUseCase
import com.mutqan.hadith.domain.usecase.DeleteCollectionUseCase
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetLibraryPageUseCase
import com.mutqan.hadith.domain.usecase.GetMasteryAnalyticsUseCase
import com.mutqan.hadith.domain.usecase.CheckSanadDrillAccessUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.ObserveCollectionDetailUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import com.mutqan.hadith.domain.usecase.RenameCollectionUseCase
import com.mutqan.hadith.domain.usecase.SetCollectionMembershipUseCase
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.usecase.SubmitLinkDrillUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.study.ReaderSettings
import com.mutqan.hadith.ui.study.SharedPreferencesReaderSettings

/** Hand-rolled dependency graph: one instance per process, owned by the Application. */
class AppContainer(
    context: Context,
    /** Where the data lives; a test hands in an in-memory database. */
    openDatabase: (Context) -> MutqanDatabase = MutqanDatabase::getInstance,
) {
    private val appContext = context.applicationContext

    private val database: MutqanDatabase by lazy { openDatabase(appContext) }

    val hadithRepository: HadithRepository by lazy { RoomHadithRepository(database) }
    val reviewRepository: ReviewRepository by lazy { RoomReviewRepository(database) }
    val narratorRepository: NarratorRepository by lazy { RoomNarratorRepository(database) }
    val collectionRepository: CollectionRepository by lazy { RoomCollectionRepository(database) }
    val backupRepository: BackupRepository by lazy { RoomBackupRepository(database) }
    val backupFiles: BackupFileStore by lazy { ContentResolverBackupFileStore(appContext.contentResolver) }
    val studyPreferences: StudyPreferences by lazy { SharedPreferencesStudyPreferences(appContext) }
    val dailyPacing: DailyPacing by lazy { DailyPacing(studyPreferences) }
    val scheduler: SpacedRepetitionScheduler by lazy { DualDecayScheduler(unlockAllLayers = studyPreferences.unlockAllLayers) }
    val readerSettings: ReaderSettings by lazy { SharedPreferencesReaderSettings(appContext) }

    val observeStudyDashboard by lazy { ObserveStudyDashboardUseCase(hadithRepository, reviewRepository, scheduler) }
    val getDueHadiths by lazy { GetDueHadithsUseCase(hadithRepository, reviewRepository, scheduler) }
    val getUnseenHadiths by lazy { GetUnseenHadithsUseCase(hadithRepository) }
    val getLibraryPage by lazy { GetLibraryPageUseCase(hadithRepository, reviewRepository, scheduler) }
    val getHadithStudy by lazy { GetHadithStudyUseCase(hadithRepository, reviewRepository, scheduler) }
    val startMemorizing by lazy { StartMemorizingUseCase(hadithRepository, reviewRepository, dailyPacing) }
    val submitReview by lazy { SubmitReviewUseCase(hadithRepository, reviewRepository, scheduler) }
    val submitLinkDrill by lazy { SubmitLinkDrillUseCase(hadithRepository, reviewRepository, scheduler) }
    val checkSanadDrillAccess by lazy { CheckSanadDrillAccessUseCase(reviewRepository, scheduler) }
    val getNarratorChainHeatmap by lazy { GetNarratorChainHeatmapUseCase(hadithRepository, reviewRepository) }
    val getLayerStatuses by lazy { GetLayerStatusesUseCase(reviewRepository, scheduler) }
    val getNarratorGraph by lazy { GetNarratorGraphUseCase(hadithRepository, reviewRepository) }
    val getHadithsByNarrator by lazy { GetHadithsByNarratorUseCase(hadithRepository, reviewRepository) }
    val getMasteryAnalytics by lazy { GetMasteryAnalyticsUseCase(hadithRepository, narratorRepository, reviewRepository, scheduler) }
    val createCollection by lazy { CreateCollectionUseCase(collectionRepository) }
    val renameCollection by lazy { RenameCollectionUseCase(collectionRepository) }
    val deleteCollection by lazy { DeleteCollectionUseCase(collectionRepository) }
    val setCollectionMembership by lazy { SetCollectionMembershipUseCase(collectionRepository) }
    val observeCollectionDetail by lazy { ObserveCollectionDetailUseCase(collectionRepository, reviewRepository, scheduler) }
    val exportUserData by lazy { ExportUserDataUseCase(backupRepository) }
    val importUserData by lazy { ImportUserDataUseCase(backupRepository) }
    val resetProgress by lazy { ResetProgressUseCase(backupRepository) }
}

package com.mutqan.hadith.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mutqan.hadith.di.AppContainer
import com.mutqan.hadith.ui.collections.CollectionDetailViewModel
import com.mutqan.hadith.ui.collections.CollectionPracticeViewModel
import com.mutqan.hadith.ui.collections.CollectionsViewModel
import com.mutqan.hadith.ui.analytics.AnalyticsViewModel
import com.mutqan.hadith.ui.details.HadithDetailsViewModel
import com.mutqan.hadith.ui.graph.NarratorGraphViewModel
import com.mutqan.hadith.ui.home.HomeViewModel
import com.mutqan.hadith.ui.insights.InsightsViewModel
import com.mutqan.hadith.ui.library.LibraryViewModel
import com.mutqan.hadith.ui.missinglink.MissingLinkViewModel
import com.mutqan.hadith.ui.orderchain.OrderChainViewModel
import com.mutqan.hadith.ui.placement.PlacementViewModel
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderViewModel
import com.mutqan.hadith.ui.settings.SettingsViewModel
import com.mutqan.hadith.ui.study.HadithStudyViewModel

fun AppContainer.homeViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { HomeViewModel(observeStudyDashboard) }
}

fun AppContainer.reviewViewModelFactory(mode: ReviewMode, targetId: Long?): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        ReviewViewModel(mode, targetId, getDueHadiths, getUnseenHadiths, hadithRepository, submitReview, getNarratorChainHeatmap, getLayerStatuses)
    }
}

fun AppContainer.sanadBuilderViewModelFactory(hadithId: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer { SanadBuilderViewModel(hadithId, hadithRepository, submitReview, checkSanadDrillAccess) }
}

fun AppContainer.orderChainViewModelFactory(hadithId: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer { OrderChainViewModel(hadithId, hadithRepository, submitReview, checkSanadDrillAccess) }
}

fun AppContainer.missingLinkViewModelFactory(hadithId: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer { MissingLinkViewModel(hadithId, hadithRepository, narratorRepository, submitLinkDrill, checkSanadDrillAccess) }
}

fun AppContainer.narratorGraphViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { NarratorGraphViewModel(getNarratorGraph, getHadithsByNarrator) }
}

fun AppContainer.hadithDetailsViewModelFactory(hadithId: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer { HadithDetailsViewModel(hadithId, hadithRepository, getNarratorChainHeatmap, getLayerStatuses, startMemorizing, dailyPacing) }
}

fun AppContainer.libraryViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { LibraryViewModel(getLibraryPage) }
}

fun AppContainer.hadithStudyViewModelFactory(hadithId: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer { HadithStudyViewModel(hadithId, getHadithStudy, startMemorizing, readerSettings, dailyPacing) }
}

fun AppContainer.collectionsViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { CollectionsViewModel(collectionRepository, createCollection, setCollectionMembership) }
}

fun AppContainer.collectionDetailViewModelFactory(collectionId: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer { CollectionDetailViewModel(collectionId, observeCollectionDetail, renameCollection, deleteCollection, setCollectionMembership) }
}

fun AppContainer.collectionPracticeViewModelFactory(collectionId: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer { CollectionPracticeViewModel(collectionId, collectionRepository, hadithRepository, getLayerStatuses) }
}

fun AppContainer.analyticsViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { AnalyticsViewModel(getMasteryAnalytics) }
}

fun AppContainer.insightsViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { InsightsViewModel(getMasteryAnalytics) }
}

fun AppContainer.settingsViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { SettingsViewModel(exportUserData, importUserData, resetProgress, backupFiles, studyPreferences) }
}

fun AppContainer.placementViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { PlacementViewModel(placement, hadithRepository, studyPreferences.startingPoint.value) }
}

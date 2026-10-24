package com.mutqan.hadith.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mutqan.hadith.di.AppContainer
import com.mutqan.hadith.ui.details.HadithDetailsViewModel
import com.mutqan.hadith.ui.graph.NarratorGraphViewModel
import com.mutqan.hadith.ui.home.HomeViewModel
import com.mutqan.hadith.ui.missinglink.MissingLinkViewModel
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderViewModel

fun AppContainer.homeViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { HomeViewModel(observeStudyDashboard) }
}

fun AppContainer.reviewViewModelFactory(mode: ReviewMode, targetId: Long?): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        ReviewViewModel(mode, targetId, getDueHadiths, getUnseenHadiths, hadithRepository, submitReview, getNarratorChainHeatmap)
    }
}

fun AppContainer.sanadBuilderViewModelFactory(hadithId: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer { SanadBuilderViewModel(hadithId, hadithRepository, submitReview) }
}

fun AppContainer.missingLinkViewModelFactory(hadithId: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer { MissingLinkViewModel(hadithId, hadithRepository, narratorRepository, submitLinkDrill) }
}

fun AppContainer.narratorGraphViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { NarratorGraphViewModel(getNarratorGraph, getHadithsByNarrator) }
}

fun AppContainer.hadithDetailsViewModelFactory(hadithId: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer { HadithDetailsViewModel(hadithId, hadithRepository, getNarratorChainHeatmap) }
}

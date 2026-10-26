package com.mutqan.hadith.ui.common

import androidx.annotation.StringRes
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.domain.model.StartingPoint

/** The word for a band on screen: ضعيف، جيد، جيد جداً، ممتاز. */
@get:StringRes
val RecallBand.labelRes: Int
    get() = when (this) {
        RecallBand.WEAK -> R.string.band_weak
        RecallBand.GOOD -> R.string.band_good
        RecallBand.VERY_GOOD -> R.string.band_very_good
        RecallBand.EXCELLENT -> R.string.band_excellent
    }

@get:StringRes
val StartingPoint.labelRes: Int
    get() = when (this) {
        StartingPoint.BEGINNER -> R.string.placement_beginner
        StartingPoint.INTERMEDIATE -> R.string.placement_intermediate
        StartingPoint.EXPERT -> R.string.placement_expert
    }

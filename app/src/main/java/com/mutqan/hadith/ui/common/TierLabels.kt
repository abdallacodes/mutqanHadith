package com.mutqan.hadith.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.graph.TierRank

/**
 * The narrator's generation worded in the app's current language. The dataset stores it as Arabic text;
 * that value is only used to recognise the generation, never rewritten, and is shown as is when it is
 * none of the four the app knows.
 */
@Composable
fun tierLabel(tier: String): String {
    @StringRes val label = when (TierRank.of(tier)) {
        0 -> R.string.tier_companion
        1 -> R.string.tier_successor
        2 -> R.string.tier_successor_followers
        3 -> R.string.tier_collector_teacher
        else -> return tier
    }
    return stringResource(label)
}

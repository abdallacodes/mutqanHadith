package com.mutqan.hadith.ui.study

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mutqan.hadith.R
import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.ui.common.ChainNode
import com.mutqan.hadith.ui.common.ChainNodeState

/**
 * The chain as a read-only timeline: the compiler at the head, then each narrator down to the Companion with
 * the term he used to transmit. Pivot narrators (the مدار) are set apart. Nothing here reacts to a tap.
 */
@Composable
fun SanadTimeline(collector: String, links: List<SanadLink>, modifier: Modifier = Modifier) {
    val compiler = stringResource(R.string.study_sanad_compiler)
    val pivot = stringResource(R.string.narrator_pivot_badge)
    Column(modifier) {
        if (collector.isNotBlank()) {
            ChainNode(
                position = null,
                name = collector,
                fullName = null,
                tier = compiler,
                term = null,
                isFirst = true,
                isLast = links.isEmpty(),
                state = ChainNodeState.Normal,
            )
        }
        links.forEachIndexed { i, link ->
            ChainNode(
                position = i + 1,
                name = link.narrator.primaryName,
                fullName = link.narrator.fullName,
                tier = link.narrator.tier,
                term = link.transmissionTerm,
                isFirst = i == 0 && collector.isBlank(),
                isLast = i == links.lastIndex,
                state = if (link.narrator.isPivot) ChainNodeState.Highlighted else ChainNodeState.Normal,
                badge = if (link.narrator.isPivot) pivot else null,
            )
        }
    }
}

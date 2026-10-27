package com.mutqan.hadith.ui.graph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost

@Composable
fun NarratorGraphRoute(
    viewModel: NarratorGraphViewModel,
    onClose: () -> Unit,
    onOpenNarrator: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NarratorGraphScreen(state, viewModel::onEvent, viewModel::load, onClose, onOpenNarrator)
}

@Composable
fun NarratorGraphScreen(
    state: UiState<NarratorGraphUi>,
    onEvent: (NarratorGraphEvent) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    /** A tap on a narrator opens his page; the network keeps him highlighted for when the user comes back. */
    onOpenNarrator: (Long) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MutqanTopBar(stringResource(R.string.graph_title), onBack = onClose) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            UiStateHost(state, onRetry) { ui ->
                GraphContent(ui, onEvent, onOpenNarrator)
            }
        }
    }
}

@Composable
private fun GraphContent(ui: NarratorGraphUi, onEvent: (NarratorGraphEvent) -> Unit, onOpenNarrator: (Long) -> Unit) {
    var fitRequest by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !ui.studiedOnly,
                onClick = { onEvent(NarratorGraphEvent.SetStudiedOnly(false)) },
                label = { Text(stringResource(R.string.graph_filter_all)) },
            )
            FilterChip(
                selected = ui.studiedOnly,
                onClick = { onEvent(NarratorGraphEvent.SetStudiedOnly(true)) },
                label = { Text(stringResource(R.string.graph_filter_studied)) },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.graph.nodes.isEmpty()) {
                Text(
                    stringResource(if (ui.studiedOnly) R.string.graph_empty_studied else R.string.graph_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                NarratorGraphCanvas(
                    graph = ui.graph,
                    layout = ui.layout,
                    selectedId = ui.selectedId,
                    onNodeClick = { onEvent(NarratorGraphEvent.SelectNode(it)); onOpenNarrator(it) },
                    onBackgroundClick = { onEvent(NarratorGraphEvent.ClearSelection) },
                    modifier = Modifier.fillMaxSize(),
                    fitRequest = fitRequest,
                )
                GraphLegend(Modifier.align(Alignment.BottomStart).padding(12.dp))
                FilledTonalButton(
                    onClick = { fitRequest += 1 },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Text(stringResource(R.string.graph_reset_view)) }
            }
        }
    }
}

@Composable
private fun GraphLegend(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh.copy(alpha = 0.92f),
        contentColor = scheme.onSurfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = scheme.secondary, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.graph_legend_pivot), style = MaterialTheme.typography.labelMedium)
                }
                Text(stringResource(R.string.graph_legend_normal), style = MaterialTheme.typography.labelMedium)
            }
            Text(stringResource(R.string.graph_legend_edge), style = MaterialTheme.typography.labelSmall)
        }
    }
}

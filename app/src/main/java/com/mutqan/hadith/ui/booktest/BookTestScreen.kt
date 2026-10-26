package com.mutqan.hadith.ui.booktest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.labelRes
import com.mutqan.hadith.ui.placement.BandAnswerButtons
import com.mutqan.hadith.ui.placement.CheckCardView

@Composable
fun BookTestRoute(viewModel: BookTestViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BookTestScreen(
        state = state,
        actions = BookTestActions(
            onBack = onClose,
            onReveal = viewModel::reveal,
            onAnswer = viewModel::answer,
            onContinue = viewModel::continueTest,
            onStop = viewModel::stop,
            onRetry = viewModel::load,
        ),
    )
}

data class BookTestActions(
    val onBack: () -> Unit,
    val onReveal: () -> Unit,
    val onAnswer: (RecallBand?) -> Unit,
    val onContinue: () -> Unit,
    val onStop: () -> Unit,
    val onRetry: () -> Unit,
)

@Composable
fun BookTestScreen(state: UiState<BookTestUi>, actions: BookTestActions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MutqanTopBar(stringResource(R.string.booktest_title), onBack = actions.onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            UiStateHost(state, actions.onRetry, Modifier.contentWidth()) { ui ->
                Column(
                    Modifier.contentWidth().fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when {
                        ui.finished -> ResultStep(ui, actions.onBack)
                        ui.resting -> RestStep(ui, actions)
                        else -> QuestionStep(ui, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionStep(ui: BookTestUi, actions: BookTestActions) {
    val card = ui.card ?: return
    LinearProgressIndicator(progress = { ui.index.toFloat() / ui.cards.size }, modifier = Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.placement_check_progress, ui.index + 1, ui.cards.size),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CheckCardView(card, ui.revealed)
    if (!ui.revealed) {
        Button(onClick = actions.onReveal, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.placement_check_reveal)) }
    } else {
        BandAnswerButtons(ui.isBusy, actions.onAnswer)
    }
}

/** Between two sittings: what was done is kept, and the rest can wait for another day. */
@Composable
private fun RestStep(ui: BookTestUi, actions: BookTestActions) {
    val left = ui.cards.size - ui.index
    Text(
        stringResource(R.string.booktest_rest_title, ui.answered),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(R.string.booktest_rest_body, left), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = actions.onContinue, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.booktest_continue)) }
    OutlinedButton(onClick = actions.onStop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.booktest_stop)) }
}

@Composable
private fun ResultStep(ui: BookTestUi, onClose: () -> Unit) {
    val result = ui.result
    Text(stringResource(R.string.booktest_result_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    if (result.total == 0) {
        Text(stringResource(R.string.booktest_result_none), style = MaterialTheme.typography.bodyLarge)
    } else {
        Text(
            stringResource(R.string.booktest_score, result.percent),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.booktest_result_of, result.total, ui.cards.size), style = MaterialTheme.typography.bodyLarge)
        RecallBand.entries.reversed().forEach { band ->
            val count = result.tally[band] ?: return@forEach
            Text(stringResource(R.string.placement_done_band, stringResource(band.labelRes), count), style = MaterialTheme.typography.titleMedium)
        }
        if (result.notMemorized > 0) {
            Text(
                stringResource(R.string.placement_done_band, stringResource(R.string.placement_check_none), result.notMemorized),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(stringResource(R.string.booktest_result_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.placement_done_button)) }
}

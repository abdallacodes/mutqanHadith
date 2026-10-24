package com.mutqan.hadith.ui.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.usecase.ChainHeatmap
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.heatmap.ChainHeatmapView
import com.mutqan.hadith.ui.theme.MatnCompactTextStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HadithDetailsUi(
    val hadithId: Long,
    val hadithNumber: Int,
    val sourceBook: String,
    val chapter: String,
    val grade: String,
    val matn: String,
    val heatmap: ChainHeatmap,
)

class HadithDetailsViewModel(
    private val hadithId: Long,
    private val hadiths: HadithRepository,
    private val getHeatmap: GetNarratorChainHeatmapUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<HadithDetailsUi>>(UiState.Loading)
    val state: StateFlow<UiState<HadithDetailsUi>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val hadith = hadiths.getWithSanad(hadithId)
                if (hadith == null) {
                    UiState.Error("Hadith $hadithId not found")
                } else {
                    UiState.Success(
                        HadithDetailsUi(
                            hadithId = hadithId,
                            hadithNumber = hadith.hadith.hadithNumber,
                            sourceBook = hadith.hadith.sourceBook,
                            chapter = hadith.hadith.chapter,
                            grade = hadith.hadith.grade,
                            matn = hadith.hadith.matnWithTashkeel,
                            heatmap = getHeatmap(hadithId),
                        ),
                    )
                }
            } catch (e: Exception) {
                UiState.Error(e.message.orEmpty())
            }
        }
    }
}

@Composable
fun HadithDetailsRoute(viewModel: HadithDetailsViewModel, onClose: () -> Unit, onReview: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HadithDetailsContent(state, viewModel::load, onClose, onReview)
}

/** The body of the details dialog: the Hadith, its Matn and the heatmap of its chain. */
@Composable
fun HadithDetailsContent(
    state: UiState<HadithDetailsUi>,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onReview: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.heightIn(max = 720.dp)) {
            Text(
                stringResource(R.string.details_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            )
            Box(Modifier.weight(1f, fill = false)) {
                UiStateHost(state, onRetry, Modifier.heightIn(min = 160.dp)) { ui ->
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            InfoChip(ui.sourceBook, container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurfaceVariant)
                            InfoChip(
                                stringResource(R.string.hadith_number, ui.hadithNumber),
                                container = MaterialTheme.colorScheme.primaryContainer,
                                content = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            InfoChip(ui.grade, container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Text(ui.matn, style = MatnCompactTextStyle, modifier = Modifier.padding(16.dp))
                        }
                        Text(stringResource(R.string.heatmap_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.heatmap_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ChainHeatmapView(ui.heatmap)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val loaded = (state as? UiState.Success)?.data
                Button(onClick = { loaded?.let { onReview(it.hadithId) } }, enabled = loaded != null, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sheet_review_this))
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.details_close))
                }
            }
        }
    }
}

package com.mutqan.hadith.ui.study

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.data.text.ArabicNormalizer
import com.mutqan.hadith.domain.drill.SanadDrillPolicy
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.narrator.NarratorAliases
import com.mutqan.hadith.domain.usecase.HadithStudy
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.LayerBadge
import com.mutqan.hadith.ui.common.LayerLockSheet
import com.mutqan.hadith.ui.common.LockedHint
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.SanadDrillButtons
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.descriptionRes
import com.mutqan.hadith.ui.common.masteryColor
import com.mutqan.hadith.ui.theme.MatnTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun HadithStudyRoute(
    viewModel: HadithStudyViewModel,
    onClose: () -> Unit,
    /** Called once the hadith has been taken into memorization, to open the learning flow for it. */
    onStartLearning: (Long) -> Unit,
    onReview: (Long) -> Unit,
    onSanadBuilder: (Long) -> Unit = {},
    onMissingLink: (Long) -> Unit = {},
    onOrderChain: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HadithStudyScreen(
        state = state,
        onRetry = viewModel::retry,
        onClose = onClose,
        onSmaller = viewModel::decreaseText,
        onLarger = viewModel::increaseText,
        onStart = { viewModel.startMemorizing(onStartLearning) },
        onReview = onReview,
        onSanadBuilder = onSanadBuilder,
        onMissingLink = onMissingLink,
        onOrderChain = onOrderChain,
        onToggleTashkeel = viewModel::toggleTashkeel,
        onConfirmPacing = { viewModel.confirmPacing(onStartLearning) },
        onDismissPacing = viewModel::dismissPacing,
    )
}

/**
 * The app bar's switch between the matn with its tashkeel and without it. It shows the current state ("بَ" or "ب") and
 * says what it will do: hide the tashkeel while it is shown, show it while it is hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TashkeelAction(showTashkeel: Boolean, onToggle: () -> Unit) {
    val label = stringResource(if (showTashkeel) R.string.study_tashkeel_hide else R.string.study_tashkeel_show)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.semantics { contentDescription = label }) {
            Text(
                if (showTashkeel) GLYPH_WITH_TASHKEEL else GLYPH_WITHOUT_TASHKEEL,
                modifier = Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (showTashkeel) FontWeight.Bold else FontWeight.Normal,
                color = if (showTashkeel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A calm page for reading one hadith: its source, its matn at a comfortable size, its chain, and what to do next. */
@Composable
fun HadithStudyScreen(
    state: UiState<HadithStudyUi>,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onSmaller: () -> Unit,
    onLarger: () -> Unit,
    onStart: () -> Unit,
    onReview: (Long) -> Unit,
    /** Opens the Sanad drills for the hadith; offered once its Sanad tree is open. */
    onSanadBuilder: (Long) -> Unit = {},
    onMissingLink: (Long) -> Unit = {},
    onOrderChain: (Long) -> Unit = {},
    onToggleTashkeel: () -> Unit = {},
    /** The user chose to go on although enough new hadiths were started today. */
    onConfirmPacing: () -> Unit = {},
    /** The user left the hadith for another day. */
    onDismissPacing: () -> Unit = {},
) {
    (state as? UiState.Success)?.data?.takeIf { it.pacingPrompt }?.let { PacingDialog(onConfirmPacing, onDismissPacing) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MutqanTopBar(
                title = stringResource(R.string.study_title),
                onBack = onClose,
                actions = {
                    (state as? UiState.Success)?.data?.let { TashkeelAction(it.showTashkeel, onToggleTashkeel) }
                },
            )
        },
        bottomBar = {
            (state as? UiState.Success)?.data?.let { ui -> StudyFooter(ui, onStart, onReview) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            UiStateHost(state, onRetry, Modifier.contentWidth()) { ui ->
                StudyContent(ui, onSmaller, onLarger, Modifier.contentWidth(), onSanadBuilder, onMissingLink, onOrderChain)
            }
        }
    }
}

@Composable
private fun StudyContent(
    ui: HadithStudyUi,
    onSmaller: () -> Unit,
    onLarger: () -> Unit,
    modifier: Modifier = Modifier,
    onSanadBuilder: (Long) -> Unit = {},
    onMissingLink: (Long) -> Unit = {},
    onOrderChain: (Long) -> Unit = {},
) {
    val hadith = ui.study.hadith.hadith
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StudyHeader(hadith)
        ui.study.layer?.let { LayerCard(hadith.id, it, ui.study.hadith.sanad, onSanadBuilder, onMissingLink, onOrderChain) }
        MatnCard(hadith, ui.textScale, ui.showTashkeel, onSmaller, onLarger)
        SanadCard(ui.study)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudyHeader(hadith: HadithEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(hadith.sourceBook, style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoChip(
                stringResource(R.string.hadith_number, hadith.hadithNumber),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            InfoChip(
                stringResource(R.string.level_badge, hadith.level),
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hadith.takhrij.isNotBlank()) InfoChip(stringResource(R.string.study_takhrij, hadith.takhrij))
            if (hadith.grade.isNotBlank()) {
                InfoChip(
                    stringResource(R.string.study_grade, hadith.grade),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hadith.sahabi.isNotBlank()) {
            Text(stringResource(R.string.sahabi_label, NarratorAliases.canonicalName(hadith.sahabi)), style = MaterialTheme.typography.titleMedium)
        }
        if (hadith.chapter.isNotBlank()) {
            Text(
                stringResource(R.string.study_chapter, hadith.chapter),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where the user stands with a hadith in study: its layer and what is next, with the Sanad drills once they are open. */
@Composable
private fun LayerCard(
    hadithId: Long,
    status: LayerStatus,
    chain: List<SanadLink>,
    onSanadBuilder: (Long) -> Unit,
    onMissingLink: (Long) -> Unit,
    onOrderChain: (Long) -> Unit,
) {
    var explainingLock by rememberSaveable { mutableStateOf(false) }
    if (explainingLock) LayerLockSheet(onDismiss = { explainingLock = false })
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.study_layer_title), style = MaterialTheme.typography.titleLarge)
            LayerBadge(status)
            Text(
                stringResource(status.layer.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.layer.opensSanad) {
                Text(stringResource(R.string.home_drills), style = MaterialTheme.typography.titleMedium)
                SanadDrillButtons(hadithId, SanadDrillPolicy.openDrills(status, chain), onSanadBuilder, onMissingLink, onOrderChain)
            } else {
                LockedHint(stringResource(R.string.study_drills_locked), onClick = { explainingLock = true })
            }
        }
    }
}

/** Asked before a third new hadith in a day is taken up: calm, and the user's word is final. */
@Composable
private fun PacingDialog(onProceed: () -> Unit, onDefer: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDefer,
        title = { Text(stringResource(R.string.pacing_title)) },
        text = { Text(stringResource(R.string.pacing_body), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { TextButton(onClick = onProceed) { Text(stringResource(R.string.pacing_proceed)) } },
        dismissButton = { Button(onClick = onDefer) { Text(stringResource(R.string.pacing_defer)) } },
    )
}

@Composable
private fun MatnCard(hadith: HadithEntity, scale: Float, showTashkeel: Boolean, onSmaller: () -> Unit, onLarger: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FOR_MS)
            copied = false
        }
    }

    val citation = buildString {
        append(stringResource(R.string.study_copy_citation, hadith.sourceBook, hadith.hadithNumber))
        if (hadith.takhrij.isNotBlank()) append(stringResource(R.string.list_separator)).append(hadith.takhrij)
    }
    // What is on screen is what is selected and copied.
    val matn = if (showTashkeel) hadith.matnWithTashkeel else ArabicNormalizer.stripDiacritics(hadith.matnWithTashkeel)
    val copyText = matn + "\n\n" + citation
    val smaller = stringResource(R.string.study_text_smaller)
    val larger = stringResource(R.string.study_text_larger)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.study_matn_title), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(
                    onClick = onSmaller,
                    enabled = MatnScale.canShrink(scale),
                    modifier = Modifier.semantics { contentDescription = smaller },
                ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Text(
                    stringResource(R.string.study_text_size, (scale * 100).roundToInt()),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
                OutlinedIconButton(
                    onClick = onLarger,
                    enabled = MatnScale.canGrow(scale),
                    modifier = Modifier.semantics { contentDescription = larger },
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                Box(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(hadith.sourceBook, copyText)))
                            copied = true
                        }
                    },
                ) { Text(stringResource(if (copied) R.string.study_copied else R.string.study_copy)) }
            }
            // The matn follows the text size; the line height follows it too, so stacked tashkeel never touches the line above.
            SelectionContainer {
                Text(
                    matn,
                    style = MatnTextStyle.copy(fontSize = (MATN_BASE_SP * scale).sp, lineHeight = (MATN_BASE_LINE_SP * scale).sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SanadCard(study: HadithStudy) {
    val hadith = study.hadith
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.study_sanad_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.study_sanad_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SanadTimeline(collector = hadith.hadith.collector, links = hadith.sanad)
            if (hadith.sanad.size == 1) {
                Text(
                    stringResource(R.string.study_sanad_short_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What to do with the hadith next: start memorizing it, or review it with the current retention in view. */
@Composable
private fun StudyFooter(ui: HadithStudyUi, onStart: () -> Unit, onReview: (Long) -> Unit) {
    val study = ui.study
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!study.isInStudy) {
                Button(onClick = onStart, enabled = !ui.isStarting, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (ui.isStarting) R.string.study_starting else R.string.study_start), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    stringResource(if (ui.startFailed) R.string.study_start_error else R.string.study_start_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.startFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                RetentionSummary(study)
                Button(onClick = { onReview(study.hadith.hadith.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.study_review_now), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** The chance of recalling the hadith right now, as one number, with the matn and the sanad behind it. */
@Composable
private fun RetentionSummary(study: HadithStudy) {
    val retention = study.retention ?: return
    val layer = study.layer?.layer ?: MemorizationLayer.LAYER_3_TRANSMISSION_TERMS
    // The weaker component is what decides how urgent a review is, so it is the headline number;
    // while the chain is not part of the memorization yet only the matn counts.
    val overall = retention.weakestIn(layer)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.study_retention_title), style = MaterialTheme.typography.labelLarge)
            Text(
                if (layer.opensSanad) {
                    stringResource(
                        R.string.study_retention_detail,
                        (retention.matn * 100).roundToInt(),
                        (retention.sanad * 100).roundToInt(),
                    )
                } else {
                    stringResource(R.string.study_retention_detail_matn, (retention.matn * 100).roundToInt())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (study.isDue) {
            InfoChip(
                stringResource(R.string.badge_due),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            stringResource(R.string.mastery_percent, (overall * 100).roundToInt()),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = masteryColor(overall),
        )
    }
}

private const val GLYPH_WITH_TASHKEEL = "\u0628\u064E" // ba with a fatha
private const val GLYPH_WITHOUT_TASHKEEL = "\u0628" // ba bare
private const val MATN_BASE_SP = 24f
private const val MATN_BASE_LINE_SP = 46f
private const val COPIED_FOR_MS = 2_000L

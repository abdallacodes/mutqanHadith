package com.mutqan.hadith.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mutqan.hadith.R
import com.mutqan.hadith.data.text.ArabicNormalizer
import com.mutqan.hadith.domain.drill.SanadDrillPolicy
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.usecase.ChainHeatmap
import com.mutqan.hadith.ui.common.ChainNode
import com.mutqan.hadith.ui.common.ChainNodeState
import com.mutqan.hadith.ui.common.companionOpener
import com.mutqan.hadith.ui.common.narratorOpener
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.LayerBadge
import com.mutqan.hadith.ui.common.LockedHint
import com.mutqan.hadith.ui.common.MukhrijCard
import com.mutqan.hadith.ui.common.SahabiCard
import com.mutqan.hadith.ui.common.SanadDrillButtons
import com.mutqan.hadith.ui.heatmap.ChainHeatmapView
import com.mutqan.hadith.ui.study.SanadTimeline
import com.mutqan.hadith.ui.theme.MatnTextStyle

// The card of a review, shared with the practice of a collection: the recall prompt, the matn with its cues, and the chain.

/** Everything the card area needs; a value type so [AnimatedContent] can freeze the outgoing card. */
internal data class CardFrame(
    val card: ReviewCard,
    val stage: CardStage,
    val missed: Set<Long>,
    val heatmap: ChainHeatmap?,
    val hideTashkeel: Boolean,
    val termsRevealed: Boolean,
    /** The hints given so far for a layer 1 card, and whether another can be asked for. */
    val hints: List<HintKind>,
    val canHint: Boolean,
    /** A collection practice card: its chain is read, not marked, and it offers no drills (they record reviews). */
    val practice: Boolean = false,
)

@Composable
internal fun CardPane(
    frame: CardFrame,
    onReveal: () -> Unit,
    onToggleMissed: (Long) -> Unit,
    onToggleTashkeel: () -> Unit,
    onRevealTerms: () -> Unit,
    onHint: () -> Unit,
    onExplainLock: () -> Unit,
    onSanadBuilder: (Long) -> Unit,
    onMissingLink: (Long) -> Unit,
    onOrderChain: (Long) -> Unit,
) {
    val card = frame.card
    val stage = frame.stage
    val layer = card.activeLayer
    val core = !layer.opensSanad
    val matn = if (core && frame.hideTashkeel) ArabicNormalizer.stripDiacritics(card.matn) else card.matn
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoChip(card.sourceBook)
            InfoChip(
                stringResource(R.string.hadith_number, card.hadithNumber),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (card.grade.isNotBlank()) {
                InfoChip(card.grade, container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!frame.practice) LayerBadge(card.layer)
        // At layer 1 the Companion and the takhrij are cues for the matn: given as hints while it is being recalled,
        // and in full once it is on screen.
        if (core) {
            CoreCues(card, stage, frame.hints, frame.canHint, onHint)
        } else if (stage != CardStage.RECALL && card.sahabi.isNotBlank()) {
            // As in the reader: the Companion first. Held back while the matn is recalled.
            SahabiCard(card.sahabi, onOpen = companionOpener(card.hadithId))
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = stage != card.finalStage, onClick = onReveal),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(stage == CardStage.RECALL, enter = fadeIn(), exit = fadeOut() + shrinkVertically()) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.review_recall_prompt),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                AnimatedVisibility(stage != CardStage.RECALL, enter = fadeIn() + expandVertically(), exit = fadeOut()) {
                    Text(
                        matn,
                        style = MatnTextStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AnimatedVisibility(stage == CardStage.MATN && !core, enter = fadeIn(), exit = fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.review_reveal_sanad),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                AnimatedVisibility(stage == CardStage.SANAD && !core, enter = fadeIn() + expandVertically(), exit = fadeOut()) {
                    SanadBlock(card, frame, onToggleMissed, onRevealTerms)
                }
            }
        }

        // And the collectors last, after the matn, once it is on screen.
        if (stage != CardStage.RECALL && card.takhrij.isNotBlank()) MukhrijCard(card.takhrij)

        if (core) {
            if (stage != CardStage.RECALL) TashkeelToggle(frame.hideTashkeel, onToggleTashkeel)
            SanadLockedPanel(onExplainLock, card, canPeek = stage != CardStage.RECALL)
        } else if (stage == CardStage.SANAD && !frame.practice) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.home_drills), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.review_drills_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SanadDrillButtons(card.hadithId, SanadDrillPolicy.openDrills(card.layer, card.links), onSanadBuilder, onMissingLink, onOrderChain)
            }
        }
    }
}

/**
 * The Companion and the takhrij of a hadith. While the matn is being recalled they are held back and given one hint at
 * a time, the Companion's first letter and then the takhrij, without turning the card over; once the matn is on screen
 * they are shown in full, to check against.
 */
@Composable
internal fun CoreCues(card: ReviewCard, stage: CardStage, hints: List<HintKind>, canHint: Boolean, onHint: () -> Unit) {
    if (stage == CardStage.RECALL) {
        HintPanel(card, hints, canHint, onHint)
        return
    }
    if (card.sahabi.isNotBlank()) SahabiCard(card.sahabi, onOpen = companionOpener(card.hadithId))
}

/** The hint button of a layer 1 card and the hints it has given so far. */
@Composable
internal fun HintPanel(card: ReviewCard, hints: List<HintKind>, canHint: Boolean, onHint: () -> Unit) {
    if (card.hintKinds.isEmpty()) return
    val description = stringResource(R.string.review_hint_description)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            hints.forEach { kind ->
                when (kind) {
                    HintKind.COMPANION -> Text(
                        stringResource(R.string.sahabi_label, companionHint(card.sahabi)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    HintKind.TAKHRIJ -> Text(
                        stringResource(R.string.study_takhrij, card.takhrij),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (canHint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onHint, modifier = Modifier.semantics { contentDescription = description }) {
                        Text(stringResource(R.string.review_hint))
                    }
                    if (hints.isEmpty()) {
                        Text(
                            stringResource(R.string.review_hint_prompt),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Layer 1 hides the tashkeel on request, to recall how the words are vowelled too, and shows it again to check. */
@Composable
internal fun TashkeelToggle(hidden: Boolean, onToggle: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onToggle) {
            Text(stringResource(if (hidden) R.string.review_tashkeel_show else R.string.review_tashkeel_hide))
        }
        if (hidden) {
            Text(
                stringResource(R.string.review_tashkeel_hint),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The chain at layer 1. It is not recalled or rated yet, so it is folded away as in the reader and can be opened to look
 * at once the matn is on screen; a tap on the hint says why it is not asked for yet.
 */
@Composable
internal fun SanadLockedPanel(onExplain: () -> Unit, card: ReviewCard, canPeek: Boolean) {
    var shown by rememberSaveable(card.hadithId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.layer_2_name), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (canPeek && card.links.isNotEmpty()) {
                    TextButton(onClick = { shown = !shown }) {
                        Text(stringResource(if (shown) R.string.study_sanad_hide else R.string.study_sanad_show))
                    }
                }
            }
            if (shown && canPeek) SanadTimeline(collector = card.collector, links = card.links)
            LockedHint(stringResource(R.string.layer_sanad_locked_hint), onClick = onExplain)
        }
    }
}

/**
 * The chain of a hadith whose Sanad tree is open. Its transmission terms are left out until the last layer, where they
 * are asked for: shown as blanks until the user has tried to recall them.
 */
@Composable
internal fun SanadBlock(card: ReviewCard, frame: CardFrame, onToggleMissed: (Long) -> Unit, onRevealTerms: () -> Unit) {
    val terms = card.activeLayer.opensTerms
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.review_sanad_title), style = MaterialTheme.typography.titleLarge)
        if (!frame.practice) {
            Text(
                stringResource(R.string.review_sanad_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (terms) {
            Text(stringResource(R.string.review_terms_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.review_terms_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!frame.termsRevealed) {
                OutlinedButton(onClick = onRevealTerms, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_terms_reveal))
                }
            }
        }
        val masked = stringResource(R.string.review_terms_masked)
        card.links.forEachIndexed { i, link ->
            ChainNode(
                position = i + 1,
                name = link.narrator.primaryName,
                fullName = link.narrator.fullName,
                tier = link.narrator.tier,
                term = when {
                    !terms -> null
                    frame.termsRevealed -> link.transmissionTerm
                    else -> masked
                },
                isFirst = i == 0,
                isLast = i == card.links.lastIndex,
                state = if (link.narrator.id in frame.missed) ChainNodeState.Missed else ChainNodeState.Normal,
                onOpenNarrator = narratorOpener(link.narrator.id),
                onClick = if (frame.practice) null else ({ onToggleMissed(link.narrator.id) }),
            )
        }
        if (!terms) {
            LockedHint(stringResource(R.string.layer_terms_locked_hint), Modifier.padding(top = 4.dp))
        }
        if (frame.heatmap != null) {
            HorizontalDivider(Modifier.padding(top = 6.dp))
            Text(stringResource(R.string.heatmap_title), style = MaterialTheme.typography.titleMedium)
            ChainHeatmapView(frame.heatmap, compact = true)
        }
    }
}

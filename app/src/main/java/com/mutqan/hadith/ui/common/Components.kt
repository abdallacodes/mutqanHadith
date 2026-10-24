package com.mutqan.hadith.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mutqan.hadith.R
import com.mutqan.hadith.ui.theme.MutqanTheme
import com.mutqan.hadith.ui.theme.NarratorTextStyle
import kotlin.math.roundToInt

/** Screens stay readable on tablets and landscape: content is capped and centered. */
fun Modifier.contentWidth(): Modifier = this.widthIn(max = 640.dp)

@Composable
fun masteryColor(mastery: Float): Color = with(MutqanTheme.colors) {
    when {
        mastery < 0.34f -> masteryLow
        mastery < 0.67f -> masteryMid
        else -> masteryHigh
    }
}

@Composable
fun MasteryRing(
    progress: Float,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 3.dp,
) {
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = strokeWidth.toPx() / 2
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        if (progress > 0f) {
            drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
    }
}

/** A small pill with a progress ring: "[ring] المتن 72٪". A null [mastery] means not learned yet. */
@Composable
fun MasteryBadge(label: String, mastery: Float?, modifier: Modifier = Modifier) {
    val color = if (mastery == null) MaterialTheme.colorScheme.outline else masteryColor(mastery)
    val value = if (mastery == null) {
        stringResource(R.string.mastery_none)
    } else {
        stringResource(R.string.mastery_percent, (mastery * 100).roundToInt())
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MasteryRing(progress = mastery ?: 0f, color = color, size = 18.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MutqanTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

/** Renders Loading / Error uniformly and hands the loaded data to [content]. */
@Composable
fun <T> UiStateHost(
    state: UiState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
            }
        }
        is UiState.Error -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.error_generic),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
        is UiState.Success -> content(state.data)
    }
}

enum class ChainNodeState { Normal, Missed, Masked, Correct }

/**
 * One narrator in a chain drawn as a timeline step: numbered dot, connector line, and a card with
 * the narrator's name, tier and the transmission term by which the next link received from him.
 * A null [name] draws the masked placeholder of the missing-link drill.
 */
@Composable
fun ChainNode(
    position: Int,
    name: String?,
    fullName: String?,
    tier: String?,
    term: String?,
    isFirst: Boolean,
    isLast: Boolean,
    state: ChainNodeState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = MutqanTheme.colors
    val scheme = MaterialTheme.colorScheme
    val accent = when (state) {
        ChainNodeState.Normal -> scheme.outlineVariant
        ChainNodeState.Missed -> colors.wrong
        ChainNodeState.Masked -> scheme.primary
        ChainNodeState.Correct -> colors.correct
    }
    val container = when (state) {
        ChainNodeState.Missed -> colors.wrongContainer.copy(alpha = 0.55f)
        ChainNodeState.Correct -> colors.correctContainer.copy(alpha = 0.55f)
        ChainNodeState.Masked -> scheme.primaryContainer.copy(alpha = 0.45f)
        ChainNodeState.Normal -> scheme.surfaceContainerLowest
    }
    val line = scheme.outlineVariant

    Row(modifier = modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(10.dp).background(if (isFirst) Color.Transparent else line))
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(if (state == ChainNodeState.Normal) scheme.primary else accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    position.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onPrimary,
                )
            }
            Box(Modifier.width(2.dp).weight(1f).background(if (isLast) Color.Transparent else line))
        }

        val shape = RoundedCornerShape(14.dp)
        Surface(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
                .then(if (onClick != null) Modifier.clip(shape).clickable(role = Role.Button, onClick = onClick) else Modifier),
            shape = shape,
            color = container,
            border = androidx.compose.foundation.BorderStroke(if (state == ChainNodeState.Normal) 1.dp else 2.dp, accent),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (name == null) {
                    Text(
                        stringResource(R.string.missing_masked),
                        style = NarratorTextStyle,
                        color = scheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(name, style = NarratorTextStyle, color = scheme.onSurface)
                    if (!fullName.isNullOrBlank() && fullName != name) {
                        Text(fullName, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!tier.isNullOrBlank()) InfoChip(tierLabel(tier), container = scheme.surfaceVariant, content = scheme.onSurfaceVariant)
                    if (!term.isNullOrBlank()) InfoChip(term)
                    if (state == ChainNodeState.Missed) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.review_missed),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.wrong,
                        )
                    }
                }
            }
        }
    }
}

/** A short horizontal shake each time [trigger] changes while [active] is true. */
@Composable
fun Modifier.shakeOn(trigger: Int, active: Boolean): Modifier {
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current.density
    LaunchedEffect(trigger, active) {
        if (active && trigger > 0) {
            for (target in listOf(-9f, 9f, -6f, 6f, -3f, 0f)) offset.animateTo(target, tween(durationMillis = 45))
        }
    }
    return this.graphicsLayer { translationX = offset.value * density }
}

/** Describes a drill state for screen readers where color alone carries the meaning. */
fun Modifier.stateText(text: String?): Modifier =
    if (text == null) this else this.semantics { stateDescription = text }

/** A number over its caption, for the stat rows of the narrator and heatmap sheets. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = valueColor)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

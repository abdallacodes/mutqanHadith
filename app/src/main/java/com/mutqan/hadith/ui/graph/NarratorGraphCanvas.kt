package com.mutqan.hadith.ui.graph

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.graph.GraphLayout
import com.mutqan.hadith.domain.graph.NarratorGraph
import com.mutqan.hadith.domain.graph.NarratorNode
import com.mutqan.hadith.domain.graph.NodePlacement
import com.mutqan.hadith.domain.usecase.ChainHealth
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.tierLabel
import com.mutqan.hadith.ui.heatmap.color
import com.mutqan.hadith.ui.theme.NarratorTextStyle
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_SCALE = 2.5f
private const val READABLE_SCALE = 0.6f
private const val PAN_SLACK_PX = 120f

private enum class Emphasis { Normal, Selected, Neighbor, Dimmed }

/**
 * The narrator network on a pannable, zoomable surface. Bands hold the generations, edges run from
 * teacher to student (thicker = more shared Hadiths) and narrators are tappable pills.
 * With a [selectedId] its edges and neighbours stay lit while the rest fades.
 *
 * The surface is laid out left-to-right whatever the app direction: a graph has no reading order,
 * and one coordinate space keeps pan, zoom and hit-testing simple. Text inside keeps its own direction.
 */
@Composable
fun NarratorGraphCanvas(
    graph: NarratorGraph,
    layout: GraphLayout,
    selectedId: Long?,
    onNodeClick: (Long) -> Unit,
    onBackgroundClick: () -> Unit,
    modifier: Modifier = Modifier,
    fitRequest: Int = 0,
) {
    val density = LocalDensity.current.density
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var cameraFor by remember { mutableIntStateOf(0) }

    val contentW = layout.width * density
    val contentH = layout.height * density
    val fitScale = if (viewport == IntSize.Zero || contentW == 0f) 1f else min(viewport.width / contentW, viewport.height / contentH)
    val minScale = min(fitScale, READABLE_SCALE).coerceAtLeast(0.05f)

    fun clamp(o: Offset, s: Float): Offset {
        val w = contentW * s
        val h = contentH * s
        val x = if (w <= viewport.width) (viewport.width - w) / 2 else o.x.coerceIn(viewport.width - w - PAN_SLACK_PX, PAN_SLACK_PX)
        val y = if (h <= viewport.height) (viewport.height - h) / 2 else o.y.coerceIn(viewport.height - h - PAN_SLACK_PX, PAN_SLACK_PX)
        return Offset(x, y)
    }

    // Start at a readable zoom on the top centre; a fit request shows the whole network instead.
    LaunchedEffect(layout, viewport, fitRequest) {
        if (viewport == IntSize.Zero || layout.placements.isEmpty()) return@LaunchedEffect
        val wantFit = fitRequest != 0 && fitRequest != cameraFor
        val heightFit = viewport.height / contentH
        val start = maxOf(READABLE_SCALE, heightFit).coerceAtMost(1f)
        val s = (if (wantFit) fitScale else start).coerceIn(minScale, MAX_SCALE)
        scale = s
        offset = clamp(Offset((viewport.width - contentW * s) / 2, 0f), s)
        cameraFor = fitRequest
    }

    val nodes = remember(graph) { graph.nodes.associateBy { it.id } }
    val neighbors = remember(graph, selectedId) { selectedId?.let { graph.neighborsOf(it) }.orEmpty() }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier
                .clipToBounds()
                .onSizeChanged { viewport = it }
                // minScale follows the viewport, so the detector must restart when it changes.
                .pointerInput(layout, minScale) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(minScale, MAX_SCALE)
                        val ratio = newScale / scale
                        // Keep the point under the fingers fixed while zooming, then follow the pan.
                        offset = clamp((offset - centroid) * ratio + centroid + pan, newScale)
                        scale = newScale
                    }
                }
                .pointerInput(layout) { detectTapGestures { onBackgroundClick() } },
            contentAlignment = Alignment.TopStart,
        ) {
            // requiredSize alone would centre a surface larger than the viewport; unbounded wrapContentSize
            // keeps its top-left corner at the origin, which is what the camera maths assumes.
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(layout.width.dp, layout.height.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                Bands(layout)
                EdgeLayer(graph, layout, selectedId)
                layout.placements.values.forEach { placement ->
                    val node = nodes[placement.id] ?: return@forEach
                    val emphasis = when {
                        selectedId == null -> Emphasis.Normal
                        node.id == selectedId -> Emphasis.Selected
                        node.id in neighbors -> Emphasis.Neighbor
                        else -> Emphasis.Dimmed
                    }
                    NodePill(node, placement, emphasis) { onNodeClick(node.id) }
                }
            }
            // Tier labels live outside the zoomed surface and stick to the right edge, so they stay
            // readable however far the graph is panned or zoomed.
            layout.bands.forEach { band ->
                InfoChip(
                    tierLabel(band.tier),
                    modifier = Modifier.align(Alignment.TopEnd).offset {
                        val bandTop = offset.y + band.top * density * scale
                        val bandBottom = offset.y + band.bottom * density * scale
                        val pinned = bandTop.coerceAtLeast(0f).coerceAtMost(bandBottom - 36.dp.toPx())
                        IntOffset(-8.dp.roundToPx(), (maxOf(pinned, bandTop) + 6.dp.toPx()).roundToInt())
                    },
                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Bands(layout: GraphLayout) {
    layout.bands.forEach { band ->
        Box(
            Modifier
                .offset(8.dp, band.top.dp)
                .requiredSize((layout.width - 16f).dp, (band.bottom - band.top).dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        )
    }
}

@Composable
private fun EdgeLayer(graph: NarratorGraph, layout: GraphLayout, selectedId: Long?) {
    val scheme = MaterialTheme.colorScheme
    val base = scheme.outline
    val lit = scheme.primary
    Canvas(Modifier.requiredSize(layout.width.dp, layout.height.dp)) {
        // Lit edges are drawn last so they stay above the faded ones.
        val ordered = graph.edges.sortedBy { selectedId != null && (it.fromNarratorId == selectedId || it.toNarratorId == selectedId) }
        for (edge in ordered) {
            val from = layout.placements[edge.fromNarratorId] ?: continue
            val to = layout.placements[edge.toNarratorId] ?: continue
            val incident = selectedId != null && (edge.fromNarratorId == selectedId || edge.toNarratorId == selectedId)
            val color = when {
                selectedId == null -> base.copy(alpha = 0.6f)
                incident -> lit
                else -> base.copy(alpha = 0.12f)
            }
            // Heavier lines for steps shared by more Hadiths, capped so a hub does not become a bar.
            val width = (1.5f + 1.6f * (edge.sharedHadithCount - 1)).coerceAtMost(8f) + if (incident) 1f else 0f
            drawEdge(from, to, color, width.dp.toPx())
        }
    }
}

private fun DrawScope.drawEdge(from: NodePlacement, to: NodePlacement, color: Color, strokeWidth: Float) {
    val start = Offset(from.x.dp.toPx(), (from.y + from.height / 2).dp.toPx())
    val end = Offset(to.x.dp.toPx(), (to.y - to.height / 2).dp.toPx())
    val path = Path().apply {
        moveTo(start.x, start.y)
        val midY = (start.y + end.y) / 2
        cubicTo(start.x, midY, end.x, midY, end.x, end.y)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
    // A small arrowhead on the student's side shows which way the report travelled.
    val tip = end
    val head = 5.dp.toPx() + strokeWidth / 2
    val arrow = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - head, tip.y - head * 1.6f)
        lineTo(tip.x + head, tip.y - head * 1.6f)
        close()
    }
    drawPath(arrow, color)
}

@Composable
private fun NodePill(node: NarratorNode, placement: NodePlacement, emphasis: Emphasis, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (node.isPivot) 20.dp else 16.dp)
    val health = ChainHealth.of(node.errorCount, node.successCount)
    val description = stringResource(if (node.isPivot) R.string.graph_node_pivot_cd else R.string.graph_node_cd, node.name, tierLabel(node.tier))

    val container = when {
        node.isPivot -> scheme.secondaryContainer
        else -> scheme.surfaceContainerLowest
    }
    val borderColor = when {
        emphasis == Emphasis.Selected -> scheme.primary
        node.isPivot -> scheme.secondary
        else -> scheme.outlineVariant
    }
    val borderWidth = when {
        emphasis == Emphasis.Selected -> 3.dp
        node.isPivot -> 2.dp
        else -> 1.dp
    }

    Box(
        Modifier
            .offset { IntOffset(placement.left.dp.roundToPx(), placement.top.dp.roundToPx()) }
            .size(placement.width.dp, placement.height.dp)
            .alpha(if (emphasis == Emphasis.Dimmed) 0.35f else 1f),
    ) {
        Surface(
            modifier = Modifier
                .size(placement.width.dp, placement.height.dp)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description },
            shape = shape,
            color = container,
            contentColor = scheme.onSurface,
            shadowElevation = if (emphasis == Emphasis.Selected) 6.dp else 0.dp,
            border = BorderStroke(borderWidth, borderColor),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                if (node.isPivot) Icon(Icons.Filled.Star, contentDescription = null, tint = scheme.secondary, modifier = Modifier.size(16.dp))
                Text(
                    node.name,
                    style = NarratorTextStyle.copy(fontSize = if (node.isPivot) 17.sp else 15.sp, lineHeight = if (node.isPivot) 24.sp else 21.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // How reliably the user places this narrator, as a dot on the pill's corner.
        if (health != ChainHealth.UNTESTED) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(2.dp, (-2).dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(scheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(health.color()),
            )
        }
    }
}

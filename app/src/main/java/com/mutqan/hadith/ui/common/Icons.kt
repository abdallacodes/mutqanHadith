package com.mutqan.hadith.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A teacher linked to two students: the "network of chains" glyph, which the core icon set lacks. */
@Composable
fun NetworkIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val top = Offset(w * 0.5f, h * 0.2f)
        val left = Offset(w * 0.2f, h * 0.8f)
        val right = Offset(w * 0.8f, h * 0.8f)
        val line = w * 0.075f
        drawLine(tint, top, left, strokeWidth = line, cap = StrokeCap.Round)
        drawLine(tint, top, right, strokeWidth = line, cap = StrokeCap.Round)
        drawLine(tint, left, right, strokeWidth = line * 0.7f, cap = StrokeCap.Round)
        drawCircle(tint, radius = w * 0.15f, center = top)
        drawCircle(tint, radius = w * 0.12f, center = left)
        drawCircle(tint, radius = w * 0.12f, center = right)
    }
}

/** Three rising bars: the "statistics" glyph, which the core icon set lacks. */
@Composable
fun AnalyticsIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barWidth = w * 0.2f
        val corner = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.3f)
        // Left to right: short, medium, tall, all standing on the same baseline.
        listOf(0.5f to 0.15f, 0.7f to 0.4f, 0.95f to 0.65f).forEach { (height, start) ->
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * start, h * (0.9f - height * 0.8f)),
                size = androidx.compose.ui.geometry.Size(barWidth, h * height * 0.8f),
                cornerRadius = corner,
            )
        }
    }
}

/** Four books standing on a shelf: the "library" glyph, which the core icon set lacks. */
@Composable
fun LibraryIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val corner = androidx.compose.ui.geometry.CornerRadius(w * 0.04f)
        val shelf = h * 0.8f
        // Left to right: start, width and height of each spine, all standing on the shelf.
        listOf(
            Triple(0.12f, 0.16f, 0.6f),
            Triple(0.32f, 0.16f, 0.72f),
            Triple(0.52f, 0.16f, 0.52f),
            Triple(0.72f, 0.16f, 0.66f),
        ).forEach { (start, width, height) ->
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * start, shelf - h * height),
                size = androidx.compose.ui.geometry.Size(w * width, h * height),
                cornerRadius = corner,
            )
        }
        drawLine(tint, Offset(w * 0.08f, h * 0.88f), Offset(w * 0.92f, h * 0.88f), strokeWidth = h * 0.07f, cap = StrokeCap.Round)
    }
}

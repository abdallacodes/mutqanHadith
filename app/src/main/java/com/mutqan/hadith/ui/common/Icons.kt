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

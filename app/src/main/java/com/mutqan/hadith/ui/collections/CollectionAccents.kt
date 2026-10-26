package com.mutqan.hadith.ui.collections

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.mutqan.hadith.domain.model.CollectionAccent

/**
 * The tones a collection can wear, quiet ones that sit with the emerald and gold of the app: emerald, gold, deep blue,
 * rose clay, indigo and olive. Each has a deeper form for the light theme and a lighter one for the dark.
 */
internal val LightTones = listOf(
    Color(0xFF0B6B53), Color(0xFF9A7418), Color(0xFF2F6F8F), Color(0xFF9A4A57), Color(0xFF4F5BA6), Color(0xFF6D6A2F),
)
internal val DarkTones = listOf(
    Color(0xFF7FD6B8), Color(0xFFE6C36A), Color(0xFF8CC4E0), Color(0xFFE5A3AE), Color(0xFFAFB6EA), Color(0xFFC9C47A),
)

/** The color of accent [index] in the current theme; an index outside the palette gets the first tone. */
@Composable
fun collectionAccentColor(index: Int): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tones = if (dark) DarkTones else LightTones
    return tones[CollectionAccent.coerce(index)]
}

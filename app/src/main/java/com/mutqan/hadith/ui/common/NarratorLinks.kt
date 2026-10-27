package com.mutqan.hadith.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration

/**
 * Opens the page of a narrator, given the id of any of his rows. The app's navigation provides it, so a name can be
 * tappable on any screen without every screen being handed a callback; where nothing is provided (a screen shown on its
 * own, a test) names are plain text.
 */
val LocalOpenNarrator = staticCompositionLocalOf<((Long) -> Unit)?> { null }

/** The tap that opens the page of narrator [narratorId], or null when there is nowhere to open it or no narrator to open. */
@Composable
fun narratorOpener(narratorId: Long?): (() -> Unit)? {
    val open = LocalOpenNarrator.current ?: return null
    if (narratorId == null) return null
    return { open(narratorId) }
}

/**
 * Opens the page of the Companion of hadith [hadithId], for the places that know a hadith and show only the Companion's
 * name (a library card, the next hadith on Home). The navigation looks up the end of the chain and opens it.
 */
val LocalOpenCompanion = staticCompositionLocalOf<((Long) -> Unit)?> { null }

@Composable
fun companionOpener(hadithId: Long): (() -> Unit)? {
    val open = LocalOpenCompanion.current ?: return null
    return { open(hadithId) }
}

/** A narrator's name; with [onOpen] it is underlined and a tap on it opens his page, whatever else a tap on its surroundings does. */
@Composable
fun NarratorNameText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    if (onOpen == null) {
        Text(text, modifier, color = color, style = style)
    } else {
        Text(
            text,
            modifier.clickable(role = Role.Button, onClick = onOpen),
            color = color,
            style = style.copy(textDecoration = TextDecoration.Underline),
        )
    }
}

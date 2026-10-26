package com.mutqan.hadith.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mutqan.hadith.R

/**
 * Asked before a third new hadith in a day is taken up, wherever the hadith is started from: calm, and the user's word
 * is final. Leaving it ([onDefer], also by tapping outside) is the same as saving the hadith for tomorrow.
 */
@Composable
fun PacingDialog(onProceed: () -> Unit, onDefer: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDefer,
        title = { Text(stringResource(R.string.pacing_title)) },
        text = { Text(stringResource(R.string.pacing_body), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { TextButton(onClick = onProceed) { Text(stringResource(R.string.pacing_proceed)) } },
        dismissButton = { Button(onClick = onDefer) { Text(stringResource(R.string.pacing_defer)) } },
    )
}

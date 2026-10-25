package com.mutqan.hadith.ui.common

import com.mutqan.hadith.domain.drill.DrillAccess

/** Loading state of a screen whose content is [T]. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    data class Success<T>(val data: T) : UiState<T>

    /** [message] is a technical detail for logs/diagnostics; screens show their own friendly text. */
    data class Error(val message: String) : UiState<Nothing>

    /** A drill that cannot be played on this hadith now; [access] says why, and the screen says it kindly. */
    data class Blocked(val access: DrillAccess.Closed) : UiState<Nothing>
}

inline fun <T> UiState<T>.updateSuccess(transform: (T) -> T): UiState<T> =
    if (this is UiState.Success) UiState.Success(transform(data)) else this

package com.mutqan.hadith.domain.model

import kotlin.math.round

/**
 * A long list of due reviews is worked through a few at a time, so that coming back after a break meets a short
 * task and not a wall: the most urgent [BATCH_SIZE] first, the rest in later batches.
 */
object ReviewBatching {
    const val BATCH_SIZE = 5

    /** What one hadith costs on average, from the recall to the rating. */
    const val MINUTES_PER_HADITH = 1.5

    /** [items] in batches of [BATCH_SIZE], in their order; the last batch holds what is left. */
    fun <T> batches(items: List<T>): List<List<T>> = items.chunked(BATCH_SIZE)

    /** Whether [dueCount] is more than one sitting's worth. */
    fun isBacklog(dueCount: Int): Boolean = dueCount > BATCH_SIZE

    /** How many hadiths the next sitting takes. */
    fun nextBatchSize(dueCount: Int): Int = dueCount.coerceIn(0, BATCH_SIZE)

    /** About how long the next sitting takes, in whole minutes; halves round to the even minute (3 hadiths: 4 minutes). */
    fun estimatedMinutes(dueCount: Int): Int = round(nextBatchSize(dueCount) * MINUTES_PER_HADITH).toInt()
}

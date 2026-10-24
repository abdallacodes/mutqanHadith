package com.mutqan.hadith.domain.analytics

import java.time.Instant
import java.time.ZoneId

/**
 * @property current consecutive days with a review, counting back from today, or from yesterday when
 * today has none yet: the streak only breaks once a whole day passes without studying.
 * @property longest the best run of consecutive study days so far.
 * @property studyDays the number of distinct days with a review.
 * @property lastSevenDays whether each of the last seven days, oldest first and ending today, had a review.
 */
data class StudyStreak(
    val current: Int,
    val longest: Int,
    val studiedToday: Boolean,
    val studyDays: Int,
    val lastSevenDays: List<Boolean>,
) {
    companion object {
        val None = StudyStreak(0, 0, false, 0, List(7) { false })
    }
}

object StudyStreakCalculator {
    /**
     * Days are calendar days in [zone]. Timestamps after [now] (a clock set back, or an edited
     * backup) are ignored so they can neither extend a streak nor make it look like today was studied.
     */
    fun compute(reviewTimestamps: Collection<Long>, now: Long, zone: ZoneId): StudyStreak {
        val today = dayOf(now, zone)
        val days = reviewTimestamps.asSequence().map { dayOf(it, zone) }.filter { it <= today }.toSortedSet()
        if (days.isEmpty()) return StudyStreak.None

        val studiedToday = today in days
        var current = 0
        var day = if (studiedToday) today else today - 1
        while (day in days) {
            current++
            day--
        }

        var longest = 0
        var run = 0
        var previous = Long.MIN_VALUE
        for (d in days) {
            run = if (d == previous + 1) run + 1 else 1
            longest = maxOf(longest, run)
            previous = d
        }

        return StudyStreak(
            current = current,
            longest = longest,
            studiedToday = studiedToday,
            studyDays = days.size,
            lastSevenDays = (today - 6..today).map { it in days },
        )
    }

    private fun dayOf(epochMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay()
}

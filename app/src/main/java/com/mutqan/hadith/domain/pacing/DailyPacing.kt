package com.mutqan.hadith.domain.pacing

import com.mutqan.hadith.domain.settings.StudyPreferences
import java.time.Instant
import java.time.ZoneId

/**
 * A gentle guard against starting too many new hadiths in one day, which piles up reviews and ends in giving up.
 * It never forbids anything: [isOverLimit] says when the app should ask before taking one more hadith into study.
 * The day is the local calendar day.
 */
class DailyPacing(
    private val preferences: StudyPreferences,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun startedToday(): Int = preferences.newStartsOn(today())

    /** True once [DAILY_LIMIT] new hadiths were started today: the next one is the third, and is asked about. */
    fun isOverLimit(): Boolean = startedToday() >= DAILY_LIMIT

    fun recordStart() = preferences.recordNewStart(today())

    private fun today(): Long = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toEpochDay()

    companion object {
        /** One or two new hadiths a day is what keeps the review load bearable. */
        const val DAILY_LIMIT = 2
    }
}

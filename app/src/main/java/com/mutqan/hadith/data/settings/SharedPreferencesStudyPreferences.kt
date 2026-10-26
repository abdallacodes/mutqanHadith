package com.mutqan.hadith.data.settings

import android.content.Context
import com.mutqan.hadith.domain.model.StartingPoint
import com.mutqan.hadith.domain.settings.StudyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [StudyPreferences] in the app's private shared preferences. Only today's count of new hadiths is kept, as the day it
 * was counted on: a count from an earlier day reads as zero, so there is nothing to reset at midnight.
 */
class SharedPreferencesStudyPreferences(context: Context) : StudyPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val unlockAll = MutableStateFlow(preferences.getBoolean(KEY_UNLOCK_ALL, false))

    private val point = MutableStateFlow(
        preferences.getString(KEY_STARTING_POINT, null)?.let { name -> StartingPoint.entries.firstOrNull { it.name == name } },
    )

    override val unlockAllLayers: StateFlow<Boolean> = unlockAll.asStateFlow()

    override val startingPoint: StateFlow<StartingPoint?> = point.asStateFlow()

    override fun setStartingPoint(point: StartingPoint) {
        preferences.edit().putString(KEY_STARTING_POINT, point.name).apply()
        this.point.value = point
    }

    override fun setUnlockAllLayers(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_UNLOCK_ALL, enabled).apply()
        unlockAll.value = enabled
    }

    override fun newStartsOn(epochDay: Long): Int =
        if (preferences.getLong(KEY_STARTS_DAY, NO_DAY) == epochDay) preferences.getInt(KEY_STARTS_COUNT, 0) else 0

    override fun recordNewStart(epochDay: Long) {
        preferences.edit()
            .putLong(KEY_STARTS_DAY, epochDay)
            .putInt(KEY_STARTS_COUNT, newStartsOn(epochDay) + 1)
            .apply()
    }

    private companion object {
        const val PREFERENCES = "mutqan_study"
        const val KEY_UNLOCK_ALL = "unlock_all_layers"
        const val KEY_STARTING_POINT = "starting_point"
        const val KEY_STARTS_DAY = "new_starts_day"
        const val KEY_STARTS_COUNT = "new_starts_count"
        const val NO_DAY = Long.MIN_VALUE
    }
}

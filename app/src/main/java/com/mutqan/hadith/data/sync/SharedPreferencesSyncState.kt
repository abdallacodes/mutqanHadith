package com.mutqan.hadith.data.sync

import android.content.Context
import com.mutqan.hadith.domain.model.StartingPoint
import com.mutqan.hadith.domain.sync.SyncState
import com.mutqan.hadith.domain.sync.SyncStateStore

/** [SyncStateStore] in the app's private shared preferences. */
class SharedPreferencesSyncState(context: Context) : SyncStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): SyncState = SyncState(
        uid = preferences.getString(KEY_UID, null),
        eventCursor = preferences.getLong(KEY_EVENT_CURSOR, 0),
        pushedUntil = preferences.getLong(KEY_PUSHED_UNTIL, 0),
        startingPoint = preferences.getString(KEY_STARTING_POINT, null)?.let { name -> StartingPoint.entries.firstOrNull { it.name == name } },
        lastSyncedAt = if (preferences.contains(KEY_LAST_SYNCED)) preferences.getLong(KEY_LAST_SYNCED, 0) else null,
        replacePending = preferences.getBoolean(KEY_REPLACE_PENDING, false),
    )

    override fun write(state: SyncState) {
        preferences.edit()
            .putString(KEY_UID, state.uid)
            .putLong(KEY_EVENT_CURSOR, state.eventCursor)
            .putLong(KEY_PUSHED_UNTIL, state.pushedUntil)
            .putString(KEY_STARTING_POINT, state.startingPoint?.name)
            .apply { if (state.lastSyncedAt != null) putLong(KEY_LAST_SYNCED, state.lastSyncedAt) else remove(KEY_LAST_SYNCED) }
            .putBoolean(KEY_REPLACE_PENDING, state.replacePending)
            .apply()
    }

    private companion object {
        const val PREFERENCES = "mutqan_sync"
        const val KEY_UID = "uid"
        const val KEY_EVENT_CURSOR = "event_cursor"
        const val KEY_PUSHED_UNTIL = "pushed_until"
        const val KEY_STARTING_POINT = "starting_point"
        const val KEY_LAST_SYNCED = "last_synced_at"
        const val KEY_REPLACE_PENDING = "replace_pending"
    }
}

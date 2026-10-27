package com.mutqan.hadith.data.catalog

import android.content.Context
import com.mutqan.hadith.domain.catalog.CatalogSyncState
import com.mutqan.hadith.domain.catalog.CatalogSyncStateStore

/** [CatalogSyncStateStore] in the app's private shared preferences. */
class SharedPreferencesCatalogState(context: Context) : CatalogSyncStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): CatalogSyncState = CatalogSyncState(
        revision = long(KEY_REVISION),
        cursor = preferences.getLong(KEY_CURSOR, 0),
        checkedAt = long(KEY_CHECKED_AT),
        updatedAt = long(KEY_UPDATED_AT),
    )

    override fun write(state: CatalogSyncState) {
        preferences.edit()
            .putNullable(KEY_REVISION, state.revision)
            .putLong(KEY_CURSOR, state.cursor)
            .putNullable(KEY_CHECKED_AT, state.checkedAt)
            .putNullable(KEY_UPDATED_AT, state.updatedAt)
            .apply()
    }

    private fun long(key: String): Long? = if (preferences.contains(key)) preferences.getLong(key, 0) else null

    private fun android.content.SharedPreferences.Editor.putNullable(key: String, value: Long?) =
        if (value != null) putLong(key, value) else remove(key)

    private companion object {
        const val PREFERENCES = "mutqan_catalog"
        const val KEY_REVISION = "revision"
        const val KEY_CURSOR = "cursor"
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_UPDATED_AT = "updated_at"
    }
}

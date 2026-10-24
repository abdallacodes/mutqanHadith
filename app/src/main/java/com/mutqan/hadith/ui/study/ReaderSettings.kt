package com.mutqan.hadith.ui.study

import android.content.Context

/** How big the matn is set, relative to its default size. */
object MatnScale {
    val STEPS = listOf(0.85f, 1f, 1.2f, 1.4f, 1.7f)
    const val DEFAULT = 1f

    /** The nearest step to [value], so a stored value that is no longer a step still lands on one. */
    fun snap(value: Float): Float = STEPS.minBy { kotlin.math.abs(it - value) }

    fun larger(current: Float): Float = STEPS.firstOrNull { it > snap(current) } ?: STEPS.last()

    fun smaller(current: Float): Float = STEPS.lastOrNull { it < snap(current) } ?: STEPS.first()

    fun canGrow(current: Float): Boolean = snap(current) < STEPS.last()

    fun canShrink(current: Float): Boolean = snap(current) > STEPS.first()
}

/** The reader's own preferences, kept from one visit to the next. */
interface ReaderSettings {
    var matnScale: Float
}

class SharedPreferencesReaderSettings(context: Context) : ReaderSettings {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override var matnScale: Float
        get() = MatnScale.snap(preferences.getFloat(KEY_MATN_SCALE, MatnScale.DEFAULT))
        set(value) {
            preferences.edit().putFloat(KEY_MATN_SCALE, MatnScale.snap(value)).apply()
        }

    private companion object {
        const val PREFERENCES = "mutqan_reader"
        const val KEY_MATN_SCALE = "matn_scale"
    }
}

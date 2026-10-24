package com.mutqan.hadith.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.mutqan.hadith.R
import java.util.Locale

/**
 * The languages the app is translated into. [tag] is the app locale handed to Android; [nameRes] is the
 * language's own name, the same whichever language the app is showing.
 */
enum class AppLanguage(val tag: String, @StringRes val nameRes: Int) {
    /** The default. `nu-latn` keeps Latin digits, so numbers read the same in both languages. */
    ARABIC("ar-u-nu-latn", R.string.language_name_ar),
    ENGLISH("en", R.string.language_name_en),
    ;

    companion object {
        /** The app language a locale resolves to: English only for an English locale, Arabic for everything else. */
        fun of(locale: Locale?): AppLanguage = if (locale?.language == "en") ENGLISH else ARABIC
    }
}

/**
 * Reads and changes the per-app language through AppCompat, which hands the choice to the platform
 * on Android 13+ and keeps it itself before that. Changing it recreates the running activity, so
 * every screen re-reads its resources and Compose picks the layout direction up from the new locale.
 *
 * AppCompat ignores calls on Android 13+ while no AppCompat activity exists, so use this from one.
 */
class AppLanguageController(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The language in use: the chosen app language, or the one the resources resolved to when none was chosen. */
    fun current(): AppLanguage {
        val chosen = AppCompatDelegate.getApplicationLocales()
        val locale = if (chosen.isEmpty) context.resources.configuration.locales[0] else chosen[0]
        return AppLanguage.of(locale)
    }

    fun select(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }

    /**
     * Makes Arabic the app language the first time the app runs, so a phone set to English does not
     * silently turn the app English. A language chosen before, in the app or in the system's per-app
     * language settings, is left alone. Returns whether it changed anything.
     */
    fun applyDefaultOnFirstRun(): Boolean {
        if (preferences.getBoolean(KEY_INITIALIZED, false)) return false
        preferences.edit().putBoolean(KEY_INITIALIZED, true).apply()
        if (!AppCompatDelegate.getApplicationLocales().isEmpty) return false
        select(AppLanguage.ARABIC)
        return true
    }

    private companion object {
        const val PREFERENCES = "mutqan_language"
        const val KEY_INITIALIZED = "initialized"
    }
}

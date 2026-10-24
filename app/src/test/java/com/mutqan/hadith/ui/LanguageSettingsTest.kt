package com.mutqan.hadith.ui

import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import com.mutqan.hadith.ui.settings.AppLanguage
import com.mutqan.hadith.ui.settings.AppLanguageController
import com.mutqan.hadith.ui.settings.SettingsActions
import com.mutqan.hadith.ui.settings.SettingsScreen
import com.mutqan.hadith.ui.settings.SettingsUi
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Language row in Settings and the controller behind it, with the app in its default language, Arabic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class LanguageSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val selected = mutableListOf<AppLanguage>()

    private fun showSettings(language: AppLanguage = AppLanguage.ARABIC) = compose.setContent {
        MutqanHadithTheme {
            val actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, onLanguageSelected = { selected += it })
            SettingsScreen(SettingsUi(), "1.0", actions, language)
        }
    }

    @After
    fun clearAppLocales() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    // -- The row and its dialog ------------------------------------------------------------

    @Test
    fun theLanguageRowIsBilingualAndNamesTheCurrentLanguage() {
        showSettings()

        compose.onNodeWithText("اللغة / Language").assertExists()
        compose.onNodeWithText("العربية (الافتراضية)").assertExists()
    }

    @Test
    fun theDialogOffersArabicAndEnglishWithTheCurrentOneSelected() {
        showSettings(AppLanguage.ARABIC)

        compose.onNodeWithText("اللغة / Language").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("اختر اللغة").assertExists()
        compose.onNode(hasText("English") and isSelectable()).assertIsNotSelected()
        compose.onNode(hasText("العربية (الافتراضية)") and isSelectable()).assertIsSelected()
    }

    @Test
    fun choosingEnglishHandsItOnAndClosesTheDialog() {
        showSettings(AppLanguage.ARABIC)

        compose.onNodeWithText("اللغة / Language").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("English").performClick()
        compose.waitForIdle()

        assertEquals(listOf(AppLanguage.ENGLISH), selected)
        compose.onNodeWithText("اختر اللغة").assertDoesNotExist()
    }

    @Test
    fun choosingTheLanguageAlreadyInUseChangesNothing() {
        showSettings(AppLanguage.ARABIC)

        compose.onNodeWithText("اللغة / Language").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("العربية (الافتراضية)") and isSelectable()).performClick()
        compose.waitForIdle()

        assertTrue(selected.isEmpty())
        compose.onNodeWithText("اختر اللغة").assertDoesNotExist()
    }

    // -- AppLanguage -----------------------------------------------------------------------

    @Test
    fun onlyAnEnglishLocaleResolvesToEnglish() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of(Locale.forLanguageTag("en")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of(Locale.forLanguageTag("en-GB")))
        assertEquals(AppLanguage.ARABIC, AppLanguage.of(Locale.forLanguageTag("ar")))
        assertEquals(AppLanguage.ARABIC, AppLanguage.of(Locale.forLanguageTag("ar-u-nu-latn")))
        assertEquals(AppLanguage.ARABIC, AppLanguage.of(Locale.forLanguageTag("fr")))
        assertEquals(AppLanguage.ARABIC, AppLanguage.of(null))
    }

    @Test
    fun arabicKeepsLatinDigits() {
        assertEquals("latn", Locale.forLanguageTag(AppLanguage.ARABIC.tag).getUnicodeLocaleType("nu"))
    }

    // -- AppLanguageController -------------------------------------------------------------

    private fun activity(): AppCompatActivity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()

    @Test
    fun theControllerReadsTheLanguageTheAppResolvedTo() {
        assertEquals(AppLanguage.ARABIC, AppLanguageController(activity()).current())
    }

    @Test
    fun selectingALanguageChangesTheAppLocale() {
        val controller = AppLanguageController(activity())

        controller.select(AppLanguage.ENGLISH)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        assertEquals(AppLanguage.ENGLISH, controller.current())

        controller.select(AppLanguage.ARABIC)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppLanguage.ARABIC, controller.current())
        assertEquals("ar", AppCompatDelegate.getApplicationLocales()[0]?.language)
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13AppCompatKeepsTheLanguageItself() {
        val controller = AppLanguageController(activity())

        controller.select(AppLanguage.ENGLISH)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        assertEquals(AppLanguage.ENGLISH, controller.current())
    }

    @Test
    fun firstRunMakesArabicTheAppLanguageOnce() {
        val controller = AppLanguageController(activity())

        assertTrue("the first run sets the default", controller.applyDefaultOnFirstRun())
        assertEquals("ar", AppCompatDelegate.getApplicationLocales()[0]?.language)

        controller.select(AppLanguage.ENGLISH)
        assertFalse("later runs leave the user's choice alone", controller.applyDefaultOnFirstRun())
        assertEquals(AppLanguage.ENGLISH, controller.current())
    }

    @Test
    fun firstRunLeavesALanguageChosenInSystemSettingsAlone() {
        val activity = activity()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))

        assertFalse(AppLanguageController(activity).applyDefaultOnFirstRun())
        assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }
}

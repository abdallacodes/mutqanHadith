package com.mutqan.hadith.ui

import com.mutqan.hadith.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The collections screens in English; the behavior is in [CollectionsScreensBase], the same as in Arabic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h1600dp-xxhdpi")
class CollectionsEnglishScreensTest : CollectionsScreensBase() {

    @Test
    fun theLanguageUnderTestIsEnglish() {
        assertEquals("My collections", str(R.string.library_section_collections))
        assertEquals("Custom practice", str(R.string.practice_title))
    }
}

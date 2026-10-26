package com.mutqan.hadith.ui

import com.mutqan.hadith.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The collections screens in Arabic, the app's own language; the behavior is in [CollectionsScreensBase]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class CollectionsScreensTest : CollectionsScreensBase() {

    @Test
    fun theLanguageUnderTestIsArabic() {
        assertEquals("مجموعاتي", str(R.string.library_section_collections))
        assertEquals("ورد مخصص", str(R.string.practice_title))
    }
}

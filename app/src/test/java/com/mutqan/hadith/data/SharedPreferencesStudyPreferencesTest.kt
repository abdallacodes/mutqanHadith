package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.settings.SharedPreferencesStudyPreferences
import com.mutqan.hadith.domain.pacing.DailyPacing
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the study preferences keep in the app's shared preferences: the advanced student's switch and the day's count of
 * new hadiths. Each test builds the preferences again over the same storage, as a fresh start of the app does, and reads
 * the values back from there, not from the object that wrote them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPreferencesStudyPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // 2023-11-14T22:13:20Z
    private var clock = 1_700_000_000_000L
    private val utc = ZoneOffset.UTC

    @Before
    fun startFromEmptyStorage() {
        context.getSharedPreferences("mutqan_study", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun stored() = SharedPreferencesStudyPreferences(context)

    private fun pacingOver(preferences: SharedPreferencesStudyPreferences, zone: ZoneOffset = utc) =
        DailyPacing(preferences, { zone }, { clock })

    @Test
    fun advancedStudentToggle_persistsAcrossInstanceRecreation() {
        assertFalse("off until the student turns it on", stored().unlockAllLayers.value)

        stored().setUnlockAllLayers(true)

        assertTrue(stored().unlockAllLayers.value)

        stored().setUnlockAllLayers(false)

        assertFalse("and taking it back is remembered too", stored().unlockAllLayers.value)
    }

    @Test
    fun dailyPacingCount_persistsAcrossInstanceRecreationForSameDay() {
        val first = pacingOver(stored())
        assertEquals(0, first.startedToday())
        first.recordStart()
        first.recordStart()

        val afterRestart = pacingOver(stored())

        assertEquals(2, afterRestart.startedToday())
        assertTrue("the third of the day is the one asked about", afterRestart.isOverLimit())

        afterRestart.recordStart()

        assertEquals(3, pacingOver(stored()).startedToday())
    }

    @Test
    fun dailyPacingCount_resetsOnNewLocalCalendarDay() {
        stored().setUnlockAllLayers(true)
        val today = pacingOver(stored())
        today.recordStart()
        today.recordStart()
        assertTrue(today.isOverLimit())

        clock += 24 * 60 * 60 * 1000L
        val tomorrow = pacingOver(stored())

        assertEquals("a count from an earlier day reads as zero", 0, tomorrow.startedToday())
        assertFalse(tomorrow.isOverLimit())
        assertTrue("and the other preferences are not wiped", stored().unlockAllLayers.value)

        tomorrow.recordStart()

        assertEquals("the new day counts from one", 1, pacingOver(stored()).startedToday())
        assertTrue(stored().unlockAllLayers.value)
    }

    @Test
    fun dailyPacingCount_followsTheLocalMidnightNotTheUtcOne() {
        val plusThree = ZoneOffset.ofHours(3)
        // 23:59 local is 20:59 UTC: two minutes later it is the next day at home and still the same day in UTC.
        clock = LocalDateTime.of(2026, 10, 25, 23, 59).toInstant(plusThree).toEpochMilli()
        val beforeMidnight = pacingOver(stored(), plusThree)
        beforeMidnight.recordStart()
        beforeMidnight.recordStart()
        assertTrue(beforeMidnight.isOverLimit())

        clock += 2 * 60 * 1000L

        assertEquals("a new day at home", 0, pacingOver(stored(), plusThree).startedToday())
        assertEquals("the same day in UTC, which is not the one the app counts by", 2, pacingOver(stored(), utc).startedToday())
    }
}

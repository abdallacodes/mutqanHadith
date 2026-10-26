package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.seed.SeedLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseSeedingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seed = SeedLoader.load(context)
    private val hadiths = seed.hadiths.size
    private val narrators = seed.narrators.size

    private fun open(name: String) = MutqanDatabase.builder(context, name).allowMainThreadQueries().build()

    @Test
    fun freshDatabase_isSeededOnce_andReopeningDoesNotDuplicate() = runBlocking {
        val first = open("fresh.db")
        assertEquals(hadiths, first.hadithDao().count())
        assertEquals(narrators, first.narratorDao().getAll().size)
        first.close()

        val second = open("fresh.db")
        assertEquals(hadiths, second.hadithDao().count())
        assertEquals(narrators, second.narratorDao().getAll().size)
        assertEquals(hadiths, second.hadithDao().getAllWithSanad().count { it.sanad.size == 1 })
        second.close()
    }

    @Test
    fun destructiveMigration_reseedsTheRecreatedTables() = runBlocking {
        // An older install: a different schema, at a lower version, holding junk and a user row.
        context.openOrCreateDatabase("legacy.db", Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL("CREATE TABLE hadith (id INTEGER PRIMARY KEY, junk TEXT)")
            legacy.execSQL("INSERT INTO hadith VALUES (1, 'stale')")
            legacy.execSQL("CREATE TABLE mastery_record (hadithId INTEGER PRIMARY KEY)")
            legacy.version = 2
        }

        val db = open("legacy.db")

        assertEquals(hadiths, db.hadithDao().count())
        assertEquals(hadiths, db.hadithDao().getAllWithSanad().size)
        assertEquals(hadiths, db.hadithDao().getUnseenWithSanad().size)
        assertEquals("الأربعون النووية", db.hadithDao().getHadithWithSanad(11)!!.hadith.sourceBook)
        db.close()
    }

    @Test
    fun interruptedSeed_isRepairedOnNextOpen() = runBlocking {
        val db = open("interrupted.db")
        assertEquals(hadiths, db.hadithDao().count())
        db.openHelper.writableDatabase.execSQL("DELETE FROM hadith") // as if the first seed never landed
        db.close()

        val reopened = open("interrupted.db")
        assertEquals(hadiths, reopened.hadithDao().count())
        reopened.close()
    }
}

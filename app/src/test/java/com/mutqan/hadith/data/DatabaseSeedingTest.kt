package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
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

    private fun open(name: String) = MutqanDatabase.builder(context, name).allowMainThreadQueries().build()

    @Test
    fun freshDatabase_isSeededOnce_andReopeningDoesNotDuplicate() = runBlocking {
        val first = open("fresh.db")
        assertEquals(10, first.hadithDao().count())
        assertEquals(39, first.narratorDao().getAll().size)
        first.close()

        val second = open("fresh.db")
        assertEquals(10, second.hadithDao().count())
        assertEquals(39, second.narratorDao().getAll().size)
        assertEquals(10, second.hadithDao().getAllWithSanad().count { it.sanad.size >= 4 })
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

        assertEquals(10, db.hadithDao().count())
        assertEquals(10, db.hadithDao().getAllWithSanad().size)
        assertEquals(10, db.hadithDao().getUnseenWithSanad().size)
        assertEquals("صحيح البخاري", db.hadithDao().getHadithWithSanad(1)!!.hadith.sourceBook)
        db.close()
    }

    @Test
    fun interruptedSeed_isRepairedOnNextOpen() = runBlocking {
        val db = open("interrupted.db")
        assertEquals(10, db.hadithDao().count())
        db.openHelper.writableDatabase.execSQL("DELETE FROM hadith") // as if the first seed never landed
        db.close()

        val reopened = open("interrupted.db")
        assertEquals(10, reopened.hadithDao().count())
        reopened.close()
    }
}

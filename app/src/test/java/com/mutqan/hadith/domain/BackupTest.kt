package com.mutqan.hadith.domain

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.backup.ContentResolverBackupFileStore
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.repository.RoomBackupRepository
import com.mutqan.hadith.data.repository.RoomHadithRepository
import com.mutqan.hadith.data.repository.RoomReviewRepository
import com.mutqan.hadith.data.seed.SeedLoader
import com.mutqan.hadith.domain.backup.BackupCodec
import com.mutqan.hadith.domain.backup.BackupException
import com.mutqan.hadith.domain.backup.ExportUserDataUseCase
import com.mutqan.hadith.domain.backup.ImportUserDataUseCase
import com.mutqan.hadith.domain.backup.ResetProgressUseCase
import com.mutqan.hadith.domain.backup.UserData
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.time.ZoneOffset

/**
 * Backup, restore and reset, end to end against a real (in-memory) Room database seeded with the
 * Bukhari library. org.json needs the Android runtime, hence Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seed = SeedLoader.load(context)
    private val seededCounts = seed.hadiths.size to seed.narrators.size
    private lateinit var db: MutqanDatabase
    private lateinit var backup: RoomBackupRepository
    private var clock = 1_760_000_000_000L

    private lateinit var export: ExportUserDataUseCase
    private lateinit var importer: ImportUserDataUseCase
    private lateinit var reset: ResetProgressUseCase

    @Before
    fun setUp() {
        db = MutqanDatabase.builder(context, name = null).allowMainThreadQueries().build()
        backup = RoomBackupRepository(db)
        export = ExportUserDataUseCase(backup, { clock }, { ZoneOffset.UTC })
        importer = ImportUserDataUseCase(backup)
        reset = ResetProgressUseCase(backup)
    }

    @After
    fun tearDown() = db.close()

    /** Values chosen to expose rounding: floats that are not exact in decimal, longs past 2^53, nulls, extremes. */
    private val awkwardMastery = listOf(
        MasteryRecordEntity(1, 0.1f, 365f, 1_700_000_000_123L, null, 1_700_086_400_000L, 7),
        MasteryRecordEntity(2, 0f, 1.0E-4f, null, 1_700_000_000_000L, 0L, 0),
        MasteryRecordEntity(3, 3.3333333f, 12.345679f, Long.MAX_VALUE - 1, 9_007_199_254_740_993L, Long.MAX_VALUE, Int.MAX_VALUE),
        MasteryRecordEntity(10, 89.99999f, 0.20000002f, 1L, 2L, 3L, 1),
    )

    private fun events(narrators: List<Long>) = listOf(
        NarratorReviewEventEntity(id = 7, hadithId = 1, narratorId = narrators[0], reviewedAt = 1_700_000_000_123L, succeeded = true),
        NarratorReviewEventEntity(id = 8, hadithId = 1, narratorId = narrators[1], reviewedAt = 1_700_000_000_123L, succeeded = false),
        NarratorReviewEventEntity(id = 1_000, hadithId = 3, narratorId = narrators[2], reviewedAt = 9_007_199_254_740_993L, succeeded = false),
        NarratorReviewEventEntity(id = 4_000_000_000L, hadithId = 10, narratorId = narrators[0], reviewedAt = Long.MAX_VALUE, succeeded = true),
    )

    private fun narratorIds() = runBlocking { db.narratorDao().getAll().map { it.id } }

    private fun seedUserData(): UserData = runBlocking {
        db.masteryDao().upsertAll(awkwardMastery)
        db.reviewHistoryDao().insertAll(events(narratorIds()))
        backup.readUserData()
    }

    private fun counts() = runBlocking { db.hadithDao().count() to db.narratorDao().getAll().size }

    // -- Round trip -------------------------------------------------------------------------

    @Test
    fun exportThenImportRestoresEveryFieldExactly() = runBlocking {
        val before = seedUserData()
        assertEquals(4, before.mastery.size)
        assertEquals(4, before.events.size)

        val exported = export()
        assertEquals(4, exported.masteryCount)
        assertEquals(4, exported.eventCount)

        reset()
        assertEquals(UserData.Empty, backup.readUserData())

        val result = importer(exported.json)

        assertEquals(4, result.masteryCount)
        assertEquals(4, result.eventCount)
        val after = backup.readUserData()
        assertEquals(before, after)
        // Spelled out for the fields the format is most likely to corrupt.
        before.mastery.zip(after.mastery).forEach { (a, b) ->
            assertEquals(a.matnStability.toRawBits(), b.matnStability.toRawBits())
            assertEquals(a.sanadStability.toRawBits(), b.sanadStability.toRawBits())
            assertEquals(a.matnLastReviewedAt, b.matnLastReviewedAt)
            assertEquals(a.sanadLastReviewedAt, b.sanadLastReviewedAt)
            assertEquals(a.nextReviewDue, b.nextReviewDue)
        }
        assertEquals(before.events.map { it.id }, after.events.map { it.id })
        assertEquals(before.events.map { it.reviewedAt }, after.events.map { it.reviewedAt })
    }

    @Test
    fun theBackupIsAVersionedDocumentWithDeterministicContent() = runBlocking {
        seedUserData()

        val first = export()
        val root = JSONObject(first.json)

        assertEquals("mutqan-hadith-backup", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertEquals(clock, root.getLong("exportedAt"))
        assertEquals(4, root.getJSONArray("masteryRecords").length())
        assertEquals(4, root.getJSONArray("narratorReviewEvents").length())
        assertTrue(root.getJSONArray("masteryRecords").getJSONObject(0).isNull("sanadLastReviewedAt"))
        assertEquals(first.json, export().json)
    }

    @Test
    fun anEmptyBackupRoundTripsToAnEmptyState() = runBlocking {
        val exported = export()
        assertEquals(0, exported.masteryCount)

        seedUserData()
        importer(exported.json)

        assertEquals(UserData.Empty, backup.readUserData())
    }

    @Test
    fun restoreReplacesTheCurrentDataInsteadOfMergingIt() = runBlocking {
        val saved = seedUserData()
        val exported = export().json

        // Progress made after the backup was taken.
        db.masteryDao().upsert(MasteryRecordEntity(5, 9f, 9f, 5L, 5L, 5L, 5))
        db.masteryDao().upsert(awkwardMastery[0].copy(repetitionCount = 99))
        db.reviewHistoryDao().insertAll(listOf(NarratorReviewEventEntity(hadithId = 5, narratorId = narratorIds()[0], reviewedAt = 5L, succeeded = false)))

        importer(exported)

        assertEquals(saved, backup.readUserData())
    }

    @Test
    fun theLibraryIsUntouchedByRestoreAndReset() = runBlocking {
        val library = RoomHadithRepository(db).getAll()
        seedUserData()
        val exported = export().json

        reset()
        assertEquals(seededCounts, counts())
        assertEquals(library, RoomHadithRepository(db).getAll())

        importer(exported)
        assertEquals(seededCounts, counts())
        assertEquals(library, RoomHadithRepository(db).getAll())
    }

    // -- Reset ------------------------------------------------------------------------------

    @Test
    fun resetReturnsEveryHadithToUnseen() = runBlocking {
        seedUserData()
        assertEquals(seed.hadiths.size - awkwardMastery.size, RoomHadithRepository(db).getUnseen().size)

        reset()

        assertEquals(UserData.Empty, backup.readUserData())
        assertEquals(seed.hadiths.size, RoomHadithRepository(db).getUnseen().size)
        assertTrue(RoomReviewRepository(db).getNarratorOutcomeTotals().isEmpty())
        assertTrue(RoomReviewRepository(db).getReviewTimestamps().isEmpty())
    }

    @Test
    fun resetOnAFreshInstallIsHarmless() = runBlocking {
        reset()
        assertEquals(UserData.Empty, backup.readUserData())
        assertEquals(seededCounts, counts())
    }

    // -- Validation and atomicity -----------------------------------------------------------

    @Test
    fun aBackupOfUnknownHadithsIsRefusedAndNothingChanges() = runBlocking {
        val before = seedUserData()
        val foreign = BackupCodec.encode(UserData(listOf(awkwardMastery[0].copy(hadithId = 999)), emptyList()), clock)

        val error = assertThrows(BackupException.UnknownContent::class.java) { runBlocking { importer(foreign) } }

        assertEquals(1, error.missingHadiths)
        assertEquals(0, error.missingNarrators)
        assertEquals(before, backup.readUserData())
    }

    @Test
    fun aBackupOfUnknownNarratorsIsRefusedAndNothingChanges() = runBlocking {
        val before = seedUserData()
        val foreign = BackupCodec.encode(
            UserData(emptyList(), listOf(NarratorReviewEventEntity(id = 1, hadithId = 1, narratorId = 424_242, reviewedAt = 1L, succeeded = true))),
            clock,
        )

        val error = assertThrows(BackupException.UnknownContent::class.java) { runBlocking { importer(foreign) } }

        assertEquals(0, error.missingHadiths)
        assertEquals(1, error.missingNarrators)
        assertEquals(before, backup.readUserData())
    }

    @Test
    fun preparingABackupChangesNothingUntilItIsApplied() = runBlocking {
        val saved = seedUserData()
        val json = export().json
        reset()

        val prepared = importer.prepare(json)

        assertEquals(clock, prepared.exportedAt)
        assertEquals(4, prepared.masteryCount)
        assertEquals(4, prepared.eventCount)
        assertEquals(UserData.Empty, backup.readUserData())

        importer.apply(prepared)
        assertEquals(saved, backup.readUserData())
    }

    @Test
    fun aFailureWhileWritingRollsTheWholeRestoreBack() = runBlocking {
        val before = seedUserData()
        // Bypasses the use case's checks on purpose: the second table's insert violates a foreign key
        // after the first table was already replaced, which only a transaction can undo.
        val broken = UserData(
            mastery = listOf(MasteryRecordEntity(2, 5f, 5f, 5L, 5L, 5L, 5)),
            events = listOf(NarratorReviewEventEntity(id = 1, hadithId = 999, narratorId = narratorIds()[0], reviewedAt = 1L, succeeded = true)),
        )

        runCatching { backup.replaceUserData(broken) }.also { assertTrue("the bad insert must fail", it.isFailure) }

        assertEquals(before, backup.readUserData())
    }

    @Test
    fun theLibraryCheckAlsoGuardsApplyingAPreparedBackup() = runBlocking {
        val before = seedUserData()
        val prepared = com.mutqan.hadith.domain.backup.PreparedBackup(1L, UserData(listOf(awkwardMastery[0].copy(hadithId = 77)), emptyList()))

        assertThrows(BackupException.UnknownContent::class.java) { runBlocking { importer.apply(prepared) } }

        assertEquals(before, backup.readUserData())
    }

    // -- Codec ------------------------------------------------------------------------------

    private fun sampleJson(): JSONObject = JSONObject(BackupCodec.encode(UserData(awkwardMastery, events(listOf(1, 2, 3))), clock))

    private fun JSONObject.mastery(i: Int = 0): JSONObject = getJSONArray("masteryRecords").getJSONObject(i)

    private fun JSONObject.event(i: Int = 0): JSONObject = getJSONArray("narratorReviewEvents").getJSONObject(i)

    private inline fun <reified E : BackupException> assertRejected(mutate: JSONObject.() -> Unit) {
        val json = sampleJson().apply(mutate).toString()
        assertThrows(E::class.java) { BackupCodec.decode(json) }
    }

    @Test
    fun decodeAcceptsWhatEncodeWrites_inAnyListOrder() {
        val data = UserData(awkwardMastery.reversed(), events(listOf(1, 2, 3)).reversed())
        val decoded = BackupCodec.decode(BackupCodec.encode(data, 42L))

        assertEquals(1, decoded.formatVersion)
        assertEquals(42L, decoded.exportedAt)
        assertEquals(awkwardMastery.sortedBy { it.hadithId }, decoded.data.mastery)
        assertEquals(events(listOf(1, 2, 3)).sortedBy { it.id }, decoded.data.events)
    }

    @Test
    fun decodeToleratesAByteOrderMark() {
        val text = "\uFEFF" + BackupCodec.encode(UserData.Empty, 1L)
        assertEquals(UserData.Empty, BackupCodec.decode(text).data)
    }

    @Test
    fun textThatIsNotOurBackupIsRecognisedAsSuch() {
        listOf("", "   ", "hello", "[1,2,3]", "{}", """{"format":"something-else","version":1}""", """{"version":1}""").forEach { text ->
            assertThrows("'$text'", BackupException.NotABackup::class.java) { BackupCodec.decode(text) }
        }
    }

    @Test
    fun aNewerFormatIsRefusedWithBothVersions() {
        val error = assertThrows(BackupException.UnsupportedVersion::class.java) {
            BackupCodec.decode(sampleJson().put("version", 2).toString())
        }
        assertEquals(2, error.found)
        assertEquals(1, error.supported)
    }

    @Test
    fun malformedFieldsAreRejectedInsteadOfCoerced() {
        assertRejected<BackupException.Corrupt> { put("version", "1") }
        assertRejected<BackupException.Corrupt> { put("version", 0) }
        assertRejected<BackupException.Corrupt> { put("exportedAt", -1) }
        assertRejected<BackupException.Corrupt> { remove("masteryRecords") }
        assertRejected<BackupException.Corrupt> { put("narratorReviewEvents", "none") }
        assertRejected<BackupException.Corrupt> { getJSONArray("masteryRecords").put(7) }

        assertRejected<BackupException.Corrupt> { mastery().put("hadithId", "1") }
        assertRejected<BackupException.Corrupt> { mastery().put("hadithId", 1.5) }
        assertRejected<BackupException.Corrupt> { mastery().remove("hadithId") }
        assertRejected<BackupException.Corrupt> { mastery().put("matnStability", -0.5) }
        assertRejected<BackupException.Corrupt> { mastery().put("sanadStability", 1e60) }
        assertRejected<BackupException.Corrupt> { mastery().put("matnStability", "3") }
        assertRejected<BackupException.Corrupt> { mastery().put("nextReviewDue", -5) }
        assertRejected<BackupException.Corrupt> { mastery().put("matnLastReviewedAt", -1) }
        assertRejected<BackupException.Corrupt> { mastery().remove("sanadLastReviewedAt") }
        assertRejected<BackupException.Corrupt> { mastery().put("repetitionCount", -1) }
        assertRejected<BackupException.Corrupt> { mastery().put("repetitionCount", Int.MAX_VALUE.toLong() + 1) }

        assertRejected<BackupException.Corrupt> { event().put("succeeded", "true") }
        assertRejected<BackupException.Corrupt> { event().put("succeeded", 1) }
        assertRejected<BackupException.Corrupt> { event().put("id", -3) }
        assertRejected<BackupException.Corrupt> { event().remove("narratorId") }
        assertRejected<BackupException.Corrupt> { event().put("reviewedAt", -1) }
    }

    @Test
    fun duplicateKeysAreRejected() {
        assertRejected<BackupException.Corrupt> { mastery(1).put("hadithId", mastery(0).getLong("hadithId")) }
        assertRejected<BackupException.Corrupt> { event(1).put("id", event(0).getLong("id")) }
    }

    @Test
    fun aNullLastReviewIsKeptNull() {
        val decoded = BackupCodec.decode(sampleJson().toString())
        assertNull(decoded.data.mastery.first { it.hadithId == 1L }.sanadLastReviewedAt)
        assertNull(decoded.data.mastery.first { it.hadithId == 2L }.matnLastReviewedAt)
    }

    // -- File names and files ---------------------------------------------------------------

    @Test
    fun theSuggestedFileNameCarriesTheLocalDate() {
        assertEquals("mutqan-hadith-backup-2025-10-09.json", export.suggestedFileName())

        // One minute before UTC midnight is already the next day in Riyadh (UTC+3).
        clock = 1_760_054_400_000L - 60_000 // 2025-10-09T23:59:00Z
        assertEquals("mutqan-hadith-backup-2025-10-09.json", export.suggestedFileName())
        val riyadh = ExportUserDataUseCase(backup, { clock }, { java.time.ZoneId.of("Asia/Riyadh") })
        assertEquals("mutqan-hadith-backup-2025-10-10.json", riyadh.suggestedFileName())
    }

    @Test
    fun theFileStoreWritesAndReadsThroughTheContentResolver() = runBlocking {
        val file = File(context.cacheDir, "picked.json").apply { writeText("x".repeat(500)) }
        val uri = Uri.fromFile(file).toString()
        val store = ContentResolverBackupFileStore(context.contentResolver)
        val text = BackupCodec.encode(UserData(awkwardMastery, emptyList()), clock)

        store.write(uri, text)

        assertEquals("a shorter file must replace, not overlay, the old content", text, file.readText())
        assertEquals(text, store.read(uri))
    }

    @Test
    fun theFileStoreRefusesAnOversizedFileAndReportsAMissingOne() = runBlocking {
        val file = File(context.cacheDir, "big.json").apply { writeText("y".repeat(100)) }
        val small = ContentResolverBackupFileStore(context.contentResolver, maxBytes = 50)

        assertThrows(BackupException.TooLarge::class.java) { runBlocking { small.read(Uri.fromFile(file).toString()) } }
        assertThrows(FileNotFoundException::class.java) {
            runBlocking { ContentResolverBackupFileStore(context.contentResolver).read(Uri.fromFile(File(context.cacheDir, "nope.json")).toString()) }
        }
        Unit
    }
}

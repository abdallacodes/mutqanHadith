package com.mutqan.hadith.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.analytics.StudyStreak
import com.mutqan.hadith.domain.backup.BackupFileStore
import com.mutqan.hadith.domain.backup.BackupRepository
import com.mutqan.hadith.domain.backup.ExportUserDataUseCase
import com.mutqan.hadith.domain.backup.ImportUserDataUseCase
import com.mutqan.hadith.domain.backup.LibraryIds
import com.mutqan.hadith.domain.backup.PreparedBackup
import com.mutqan.hadith.domain.backup.ResetProgressUseCase
import com.mutqan.hadith.domain.backup.UserData
import com.mutqan.hadith.domain.usecase.ComponentRetention
import com.mutqan.hadith.domain.usecase.DualRetention
import com.mutqan.hadith.domain.usecase.LibraryStatus
import com.mutqan.hadith.domain.usecase.MasteryAnalytics
import com.mutqan.hadith.domain.usecase.WeakNarrator
import com.mutqan.hadith.ui.analytics.AnalyticsScreen
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.settings.AboutDialog
import com.mutqan.hadith.ui.settings.ImportProblem
import com.mutqan.hadith.ui.settings.SettingsActions
import com.mutqan.hadith.ui.settings.SettingsMessage
import com.mutqan.hadith.ui.settings.SettingsScreen
import com.mutqan.hadith.ui.settings.SettingsUi
import com.mutqan.hadith.ui.settings.SettingsViewModel
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Smoke tests of the analytics and settings screens, and of the settings screen wired to its ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class SettingsAnalyticsScreensTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    // -- Analytics --------------------------------------------------------------------------

    private val analytics = MasteryAnalytics(
        streak = StudyStreak(current = 5, longest = 9, studiedToday = true, studyDays = 20, lastSevenDays = listOf(true, true, false, true, true, true, true)),
        retention = DualRetention(ComponentRetention(0.82f, 12.34f), ComponentRetention(0.64f, 5f)),
        status = LibraryStatus(total = 9, unseen = 4, learning = 2, mastered = 3),
        weakestNarrators = listOf(
            WeakNarrator(3, "الزهري", "تابعي", isPivot = true, errorCount = 3, attempts = 4),
            WeakNarrator(7, "عروة", "تابعي", isPivot = false, errorCount = 2, attempts = 5),
        ),
    )

    private fun showAnalytics(
        state: UiState<MasteryAnalytics> = UiState.Success(analytics),
        onDrill: (Long) -> Unit = {},
        onRetry: () -> Unit = {},
    ) = compose.setContent { MutqanHadithTheme { AnalyticsScreen(state, onRetry, onClose = {}, onOpenNarrator = onDrill) } }

    @Test
    fun analytics_showsEveryCardWithItsFigures() {
        showAnalytics()

        compose.onNodeWithText("إحصائيات الإتقان").assertExists()
        // Streak
        compose.onNodeWithText("سلسلة المراجعة").assertExists()
        compose.onNodeWithText("5").assertExists()
        compose.onNodeWithText("راجعت اليوم، أحسنت").assertExists()
        compose.onNodeWithText("أطول سلسلة: 9").assertExists()
        compose.onNodeWithText("أيام المراجعة: 20").assertExists()
        // Dual mastery
        compose.onNodeWithText("مؤشر الإتقان المزدوج").assertExists()
        compose.onNodeWithText("المتن").assertExists()
        compose.onNodeWithText("السند").assertExists()
        compose.onNodeWithText("82٪").assertExists()
        compose.onNodeWithText("64٪").assertExists()
        compose.onNodeWithText("متوسط الثبات: 12.3 يوم").assertExists()
        compose.onNodeWithText("متوسط الثبات: 5 يوم").assertExists()
        // Library status
        compose.onNodeWithText("حالة المحفوظات").assertExists()
        compose.onNodeWithText("3 من 9 متقن").assertExists()
        compose.onNodeWithText("متقن").assertExists()
        compose.onNodeWithText("قيد التثبيت").assertExists()
        compose.onNodeWithText("جديد").assertExists()
        // Weakest narrators
        compose.onNodeWithText("أكثر الرواة تفلّتًا").assertExists()
        compose.onNodeWithText("الزهري").assertExists()
        compose.onNodeWithText("أخطأت 3 من 4").assertExists()
        compose.onNodeWithText("75٪").assertExists()
        compose.onNodeWithText("عروة").assertExists()
        compose.onNodeWithText("40٪").assertExists()
    }

    @Test
    fun analytics_tappingAWeakNarratorStartsADrillOnThem() {
        var drilled: Long? = null
        showAnalytics(onDrill = { drilled = it })

        compose.onNodeWithText("عروة").performClick()

        assertEquals(7L, drilled)
    }

    @Test
    fun analytics_aSanadWeakerThanTheMatnSuggestsSanadDrills() {
        showAnalytics(UiState.Success(analytics.copy(retention = DualRetention(ComponentRetention(0.9f, 10f), ComponentRetention(0.6f, 4f)))))

        compose.onNodeWithText("سندك أضعف من متنك، فخصّص وقتًا لتدريبات السند").assertExists()
    }

    @Test
    fun analytics_aMatnWeakerThanTheSanadSuggestsRecall() {
        showAnalytics(UiState.Success(analytics.copy(retention = DualRetention(ComponentRetention(0.5f, 3f), ComponentRetention(0.8f, 9f)))))

        compose.onNodeWithText("متنك أضعف من سندك، فأعد استحضار المتون").assertExists()
    }

    @Test
    fun analytics_balancedRetentionIsSaidToBeBalanced() {
        showAnalytics(UiState.Success(analytics.copy(retention = DualRetention(ComponentRetention(0.8f, 9f), ComponentRetention(0.78f, 9f)))))

        compose.onNodeWithText("المتن والسند متقاربان في الثبات").assertExists()
    }

    @Test
    fun analytics_aStreakNotYetExtendedTodayNudgesTheUser() {
        showAnalytics(UiState.Success(analytics.copy(streak = analytics.streak.copy(studiedToday = false))))

        compose.onNodeWithText("راجع اليوم لتحافظ على سلسلتك").assertExists()
        compose.onNodeWithText("راجعت اليوم، أحسنت").assertDoesNotExist()
    }

    @Test
    fun analytics_aFreshInstallInvitesTheFirstReviewAndShowsEmptyFigures() {
        val empty = MasteryAnalytics(
            streak = StudyStreak.None,
            retention = DualRetention(null, null),
            status = LibraryStatus(total = 10, unseen = 10, learning = 0, mastered = 0),
            weakestNarrators = emptyList(),
        )
        showAnalytics(UiState.Success(empty))

        compose.onNodeWithText("ابدأ أول مراجعة لتظهر هنا إحصائياتك").assertExists()
        compose.onNodeWithText("ابدأ سلسلتك اليوم").assertExists()
        compose.onAllNodesWithText("لم يُراجع بعد").assertCountEquals(2)
        compose.onNodeWithText("0 من 10 متقن").assertExists()
        compose.onNodeWithText("لا يوجد بعد راوٍ تكرّر خطؤك فيه. ستظهر هنا أكثر الأسماء تفلّتًا من ذاكرتك بعد بضع مراجعات.").assertExists()
    }

    @Test
    fun analytics_aFailedLoadOffersARetry() {
        var retried = false
        showAnalytics(UiState.Error("boom"), onRetry = { retried = true })

        compose.onNodeWithText("إعادة المحاولة").performClick()

        assertTrue(retried)
    }

    // -- Settings ---------------------------------------------------------------------------

    private class RecordedActions {
        val calls = mutableListOf<String>()
        val actions = SettingsActions(
            onBack = { calls += "back" },
            onExport = { calls += "export" },
            onImport = { calls += "import" },
            onConfirmImport = { calls += "confirmImport" },
            onCancelImport = { calls += "cancelImport" },
            onConfirmReset = { calls += "reset" },
            onMessageShown = { calls += "shown" },
        )
    }

    private val recorded = RecordedActions()

    private fun showSettings(ui: SettingsUi = SettingsUi()) =
        compose.setContent { MutqanHadithTheme { SettingsScreen(ui, appVersion = "1.0", actions = recorded.actions) } }

    @Test
    fun settings_listsTheBackupDataAndAboutRows() {
        showSettings()

        compose.onNodeWithText("الإعدادات").assertExists()
        compose.onNodeWithText("تصدير نسخة احتياطية").assertExists()
        compose.onNodeWithText("استعادة نسخة احتياطية").assertExists()
        compose.onNodeWithText("إعادة تعيين التقدم").assertExists()
        compose.onNodeWithText("عن متقن - حديث").assertExists()
        compose.onNodeWithText("الإصدار 1.0").assertExists()
    }

    @Test
    fun settings_exportAndImportRowsAskForTheFilePicker() {
        showSettings()

        compose.onNodeWithText("تصدير نسخة احتياطية").performClick()
        compose.onNodeWithText("استعادة نسخة احتياطية").performClick()

        assertEquals(listOf("export", "import"), recorded.calls)
    }

    @Test
    fun settings_resetAsksForConfirmationBeforeDoingAnything() {
        showSettings()

        compose.onNodeWithText("إعادة تعيين التقدم").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("إعادة تعيين التقدم؟").assertExists()
        assertTrue("nothing happens before the confirmation", recorded.calls.isEmpty())

        compose.onNodeWithText("إلغاء").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("إعادة تعيين التقدم؟").assertDoesNotExist()
        assertTrue(recorded.calls.isEmpty())

        compose.onNodeWithText("إعادة تعيين التقدم").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("إعادة التعيين").performClick()
        compose.waitForIdle()

        assertEquals(listOf("reset"), recorded.calls)
        compose.onNodeWithText("إعادة تعيين التقدم؟").assertDoesNotExist()
    }

    @Test
    fun settings_aboutDialogCarriesTheSuiteVisionAndMethod() {
        showSettings()

        compose.onNodeWithText("عن متقن - حديث").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("ضمن سلسلة متقن").assertExists()
        compose.onNodeWithText("رؤية السلسلة").assertExists()
        compose.onNodeWithText("المنهج").assertExists()
        compose.onNodeWithText("• المذاكرة: استحضر من ذاكرتك قبل أن ترى الجواب، فالتذكّر النشط أبلغ أثرًا من إعادة القراءة.").assertExists()

        compose.onNodeWithText("حسنًا").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("رؤية السلسلة").assertDoesNotExist()
    }

    @Test
    fun about_showsTheAppNameAndVersion() {
        compose.setContent { MutqanHadithTheme { AboutDialog("2.3", onDismiss = {}) } }

        compose.onNodeWithText("متقن - حديث").assertExists()
        compose.onNodeWithText("الإصدار 2.3").assertExists()
    }

    @Test
    fun settings_aPendingBackupIsSummarisedAndNeedsConfirmation() {
        val prepared = PreparedBackup(exportedAt = 1_760_000_000_000L, data = UserData(listOf(MasteryRecordEntity(1, 1f, 1f, 1L, 1L, 1L, 1)), emptyList()))
        showSettings(SettingsUi(pendingImport = prepared))

        compose.onNodeWithText("استعادة النسخة؟").assertExists()
        compose.onNodeWithText("أحاديث بدأ حفظها: 1", substring = true).assertExists()
        compose.onNodeWithText("سجلّات الرواة: 0", substring = true).assertExists()
        compose.onNodeWithText("تاريخ النسخة: 2025-10-09", substring = true).assertExists()

        compose.onNodeWithText("استعادة").performClick()
        compose.onNodeWithText("إلغاء").performClick()

        assertEquals(listOf("confirmImport", "cancelImport"), recorded.calls)
    }

    @Test
    fun settings_rowsAreDisabledWhileSomethingIsRunning() {
        showSettings(SettingsUi(isBusy = true))

        compose.onNodeWithText("تصدير نسخة احتياطية").assertIsNotEnabled()
        compose.onNodeWithText("استعادة نسخة احتياطية").assertIsNotEnabled()
        compose.onNodeWithText("إعادة تعيين التقدم").assertIsNotEnabled()
        compose.onNodeWithText("عن متقن - حديث").assertIsEnabled()
    }

    @Test
    fun settings_aMessageAppearsInASnackbarAndIsReportedShownOnceItGoes() {
        showSettings(SettingsUi(message = SettingsMessage.Exported))
        compose.waitForIdle()
        compose.onNodeWithText("تم حفظ النسخة الاحتياطية").assertExists()
        assertTrue(recorded.calls.isEmpty())

        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()

        assertEquals(listOf("shown"), recorded.calls)
        compose.onNodeWithText("تم حفظ النسخة الاحتياطية").assertDoesNotExist()
    }

    @Test
    fun settings_everyRejectionHasReadableArabicText() {
        val texts = mapOf(
            ImportProblem.NotABackup to "هذا الملف ليس نسخة احتياطية من متقن - حديث",
            ImportProblem.Corrupt to "ملف النسخة تالف أو معدَّل ولا يمكن قراءته",
            ImportProblem.NewerVersion(4) to "أُنشئت هذه النسخة بإصدار أحدث من التطبيق (الصيغة 4)، حدّث التطبيق ثم أعد المحاولة",
            ImportProblem.UnknownContent to "تشير النسخة إلى أحاديث أو رواة غير موجودين في هذا الإصدار، حدّث التطبيق إلى أحدث إصدار",
            ImportProblem.TooLarge to "الملف أكبر من أن يكون نسخة احتياطية",
            ImportProblem.Unreadable to "تعذّرت قراءة الملف",
            ImportProblem.RestoreFailed to "تعذّرت الاستعادة، ولم يتغيّر شيء من بياناتك",
        )
        var message by mutableStateOf<SettingsMessage?>(null)
        compose.setContent { MutqanHadithTheme { SettingsScreen(SettingsUi(message = message), "1.0", recorded.actions) } }

        texts.forEach { (problem, text) ->
            compose.runOnIdle { message = SettingsMessage.ImportRejected(problem) }
            compose.waitForIdle()
            compose.onNodeWithText(text).assertExists()
        }
    }

    // -- Settings with the real ViewModel ---------------------------------------------------

    private class MemoryBackup : BackupRepository {
        var data = UserData(listOf(MasteryRecordEntity(1, 2f, 2f, 1L, 1L, 2L, 1)), emptyList())
        override suspend fun readUserData() = data
        override suspend fun libraryIds() = LibraryIds(setOf(1), setOf(10))
        override suspend fun replaceUserData(data: UserData) { this.data = data }
        override suspend fun clearUserData() { data = UserData.Empty }
    }

    private class NoFiles : BackupFileStore {
        override suspend fun write(target: String, text: String) = Unit
        override suspend fun read(source: String): String = ""
    }

    @Test
    fun settings_resetThroughTheViewModelClearsTheDataAndConfirmsInASnackbar() {
        val backup = MemoryBackup()
        val vm = SettingsViewModel(ExportUserDataUseCase(backup), ImportUserDataUseCase(backup), ResetProgressUseCase(backup), NoFiles())
        compose.setContent {
            MutqanHadithTheme {
                val ui by vm.state.collectAsState()
                SettingsScreen(
                    ui, "1.0",
                    SettingsActions({}, {}, {}, vm::confirmImport, vm::cancelImport, vm::reset, vm::messageShown),
                )
            }
        }

        compose.onNodeWithText("إعادة تعيين التقدم").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("إعادة التعيين").performClick()
        compose.waitForIdle()

        assertEquals(UserData.Empty, backup.data)
        compose.onNodeWithText("أُعيد تعيين التقدم").assertExists()
    }
}

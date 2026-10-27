package com.mutqan.hadith.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mutqan.hadith.domain.account.AccountUser
import com.mutqan.hadith.domain.account.AuthRepository
import com.mutqan.hadith.domain.account.NoGoogleAccount
import com.mutqan.hadith.domain.account.SignInCancelled
import com.mutqan.hadith.domain.backup.BackupFileStore
import com.mutqan.hadith.domain.backup.BackupRepository
import com.mutqan.hadith.domain.backup.ExportUserDataUseCase
import com.mutqan.hadith.domain.backup.ImportUserDataUseCase
import com.mutqan.hadith.domain.backup.LibraryIds
import com.mutqan.hadith.domain.backup.ResetProgressUseCase
import com.mutqan.hadith.domain.backup.UserData
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.sync.AccountSync
import com.mutqan.hadith.domain.sync.FirstSyncChoice
import com.mutqan.hadith.domain.sync.SyncStatus
import com.mutqan.hadith.ui.settings.AppLanguage
import com.mutqan.hadith.ui.settings.SettingsActions
import com.mutqan.hadith.ui.settings.SettingsMessage
import com.mutqan.hadith.ui.settings.SettingsScreen
import com.mutqan.hadith.ui.settings.SettingsUi
import com.mutqan.hadith.ui.settings.SettingsViewModel
import com.mutqan.hadith.ui.theme.MutqanHadithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Signing in with Google from Settings: who is shown, what each way out of the account chooser says. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "+w411dp-h1600dp-xxhdpi")
class AccountSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val sara = AccountUser("uid-1", "سارة", "sara@example.com")

    private class FakeAuth : AuthRepository {
        private val current = MutableStateFlow<AccountUser?>(null)
        override val user: StateFlow<AccountUser?> = current
        var failWith: Exception? = null
        val tokens = mutableListOf<String>()
        var account = AccountUser("uid-1", "سارة", "sara@example.com")

        override suspend fun signInWithGoogle(idToken: String) {
            failWith?.let { throw it }
            tokens += idToken
            current.value = account
        }

        override fun signOut() {
            current.value = null
        }
    }

    private class EmptyBackup : BackupRepository {
        override suspend fun readUserData() = UserData.Empty
        override suspend fun libraryIds() = LibraryIds(emptySet(), emptySet())
        override suspend fun replaceUserData(data: UserData) = Unit
        override suspend fun clearUserData() = Unit
    }

    private class NoFiles : BackupFileStore {
        override suspend fun write(target: String, text: String) = Unit
        override suspend fun read(source: String): String = ""
    }

    private class FakeSync : AccountSync {
        val currentStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        val syncedAt = MutableStateFlow<Long?>(null)
        override val status: StateFlow<SyncStatus> = currentStatus
        override val lastSyncedAt: StateFlow<Long?> = syncedAt
        var syncNows = 0
        var replaced = 0
        val choices = mutableListOf<FirstSyncChoice>()

        override fun syncNow() {
            syncNows++
        }

        override fun choose(choice: FirstSyncChoice) {
            choices += choice
        }

        override fun replaceAccountWithThisPhone() {
            replaced++
        }
    }

    private val auth = FakeAuth()
    private val sync = FakeSync()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): SettingsViewModel {
        val backup = EmptyBackup()
        return SettingsViewModel(
            ExportUserDataUseCase(backup), ImportUserDataUseCase(backup), ResetProgressUseCase(backup), NoFiles(),
            InMemoryStudyPreferences(), auth, sync,
        )
    }

    // -- The ViewModel ---------------------------------------------------------------------------

    @Test
    fun nobodyIsSignedInAtFirst() {
        assertNull(viewModel().state.value.account)
    }

    @Test
    fun signingInPassesTheChosenTokenToTheAuthServiceAndShowsTheAccount() {
        val vm = viewModel()

        vm.signIn { "token-123" }

        assertEquals(listOf("token-123"), auth.tokens)
        assertEquals(sara, vm.state.value.account)
        assertNull(vm.state.value.message)
    }

    @Test
    fun leavingTheAccountChooserSaysNothing() {
        val vm = viewModel()

        vm.signIn { throw SignInCancelled() }

        assertNull(vm.state.value.account)
        assertNull(vm.state.value.message)
        assertEquals(false, vm.state.value.isBusy)
    }

    @Test
    fun aDeviceWithoutAGoogleAccountIsToldSo() {
        val vm = viewModel()

        vm.signIn { throw NoGoogleAccount() }

        assertEquals(SettingsMessage.NoGoogleAccountOnDevice, vm.state.value.message)
    }

    @Test
    fun aFailedSignInIsReportedAndLeavesNobodySignedIn() {
        auth.failWith = RuntimeException("network")
        val vm = viewModel()

        vm.signIn { "token" }

        assertEquals(SettingsMessage.SignInFailed, vm.state.value.message)
        assertNull(vm.state.value.account)
        assertEquals(false, vm.state.value.isBusy)
    }

    @Test
    fun signingOutClearsTheAccountAndSaysSo() {
        val vm = viewModel()
        vm.signIn { "token" }

        vm.signOut()

        assertNull(vm.state.value.account)
        assertEquals(SettingsMessage.SignedOut, vm.state.value.message)
    }

    @Test
    fun aResetAsksTheAccountToFollowThePhone() {
        val vm = viewModel()

        vm.reset()

        assertEquals(1, sync.replaced)
    }

    @Test
    fun theSyncStatusReachesTheScreenState() {
        val vm = viewModel()

        sync.currentStatus.value = SyncStatus.Failed
        sync.syncedAt.value = 1_700_000_000_000

        assertEquals(SyncStatus.Failed, vm.state.value.syncStatus)
        assertEquals(1_700_000_000_000, vm.state.value.lastSyncedAt)
    }

    // -- The screen ------------------------------------------------------------------------------

    private fun showSettings(language: AppLanguage = AppLanguage.ARABIC, vm: SettingsViewModel = viewModel()) {
        compose.setContent {
            MutqanHadithTheme {
                val ui by vm.state.collectAsState()
                SettingsScreen(
                    ui, "1.0",
                    SettingsActions(
                        {}, {}, {}, {}, {}, vm::reset, vm::messageShown,
                        onSignIn = { vm.signIn { "token" } },
                        onSignOut = vm::signOut,
                        onSyncNow = vm::syncNow,
                        onChooseSync = vm::chooseSync,
                    ),
                    language,
                )
            }
        }
    }

    @Test
    fun signedOutTheScreenInvitesToSignInAndSaysTheAppWorksWithoutOne() {
        showSettings()

        compose.onNodeWithText("الحساب").assertExists()
        compose.onNodeWithText("تسجيل الدخول بحساب Google").assertExists()
        compose.onNodeWithText("احفظ تقدّمك في حسابك. التطبيق يعمل كاملًا دون حساب.").assertExists()
    }

    @Test
    fun signingInFromTheScreenShowsWhoIsSignedInAndSigningOutGoesBack() {
        showSettings()

        compose.onNodeWithText("تسجيل الدخول بحساب Google").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("سارة").assertExists()
        compose.onNodeWithText("مسجَّل بالحساب sara@example.com. اضغط لتسجيل الخروج.").assertExists()
        compose.onNodeWithText("تسجيل الدخول بحساب Google").assertDoesNotExist()

        compose.onNodeWithText("سارة").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("تسجيل الدخول بحساب Google").assertExists()
        compose.onNodeWithText("تم تسجيل الخروج. تبقى بياناتك على هذا الجهاز.").assertExists()
    }

    private fun signedInScreen(): SettingsViewModel {
        val vm = viewModel()
        vm.signIn { "token" }
        showSettings(vm = vm)
        return vm
    }

    @Test
    fun noSyncRowWhileSignedOut() {
        showSettings()

        compose.onNodeWithText("مزامنة الآن").assertDoesNotExist()
    }

    @Test
    fun signedInTheSyncRowSaysNothingWasSavedYetAndSyncsOnTap() {
        signedInScreen()

        compose.onNodeWithText("لم يُحفظ تقدّمك في الحساب بعد. اضغط للمزامنة.").assertExists()
        compose.onNodeWithText("مزامنة الآن").performClick()

        assertEquals(1, sync.syncNows)
    }

    @Test
    fun theSyncRowShowsWhenTheProgressWasLastSaved() {
        signedInScreen()

        sync.syncedAt.value = 1_700_000_000_000
        compose.waitForIdle()

        compose.onNodeWithText("آخر مزامنة", substring = true).assertExists()
    }

    @Test
    fun theSyncRowShowsWhileSyncingAndAfterAFailure() {
        signedInScreen()

        sync.currentStatus.value = SyncStatus.Syncing
        compose.waitForIdle()
        compose.onNodeWithText("جارٍ المزامنة…").assertExists()

        sync.currentStatus.value = SyncStatus.Failed
        compose.waitForIdle()
        compose.onNodeWithText("تعذّرت المزامنة. سنعيد المحاولة لاحقًا، أو اضغط للمحاولة الآن.").assertExists()
    }

    @Test
    fun whenPhoneAndAccountBothHoldProgressTheLearnerChoosesHowToCombineThem() {
        signedInScreen()

        sync.currentStatus.value = SyncStatus.NeedsChoice(onPhone = 12, inAccount = 30)
        compose.waitForIdle()

        compose.onNodeWithText("تقدّم على الجهاز وفي الحساب").assertExists()
        compose.onNodeWithText("على هذا الجهاز تقدّم في 12 حديثًا، وفي حسابك تقدّم في 30 حديثًا. كيف نجمعهما؟").assertExists()
        compose.onNodeWithText("استخدام ما في الحساب").performClick()

        assertEquals(listOf(FirstSyncChoice.USE_ACCOUNT), sync.choices)
    }

    @Test
    fun theChoiceCanWaitAndTheSyncRowAsksItAgain() {
        signedInScreen()
        sync.currentStatus.value = SyncStatus.NeedsChoice(1, 2)
        compose.waitForIdle()

        compose.onNodeWithText("لاحقًا").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("تقدّم على الجهاز وفي الحساب").assertDoesNotExist()

        compose.onNodeWithText("مزامنة الآن").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("تقدّم على الجهاز وفي الحساب").assertExists()
        assertEquals(0, sync.syncNows)
    }

    @Test
    fun resettingWhileSignedInSaysTheAccountIsErasedToo() {
        signedInScreen()

        compose.onNodeWithText("إعادة تعيين التقدم").performClick()

        compose.onNodeWithText("وبما أنك مسجَّل الدخول، يُمسح تقدّمك المحفوظ في حسابك أيضًا.").assertExists()
    }

    @Test
    fun resettingWhileSignedOutSaysNothingAboutAnAccount() {
        showSettings()

        compose.onNodeWithText("إعادة تعيين التقدم").performClick()

        compose.onNodeWithText("وبما أنك مسجَّل الدخول، يُمسح تقدّمك المحفوظ في حسابك أيضًا.").assertDoesNotExist()
    }
}

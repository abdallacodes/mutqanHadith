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

    private val auth = FakeAuth()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): SettingsViewModel {
        val backup = EmptyBackup()
        return SettingsViewModel(
            ExportUserDataUseCase(backup), ImportUserDataUseCase(backup), ResetProgressUseCase(backup), NoFiles(),
            InMemoryStudyPreferences(), auth,
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

    // -- The screen ------------------------------------------------------------------------------

    private fun showSettings(language: AppLanguage = AppLanguage.ARABIC, vm: SettingsViewModel = viewModel()) {
        compose.setContent {
            MutqanHadithTheme {
                val ui by vm.state.collectAsState()
                SettingsScreen(
                    ui, "1.0",
                    SettingsActions(
                        {}, {}, {}, {}, {}, {}, vm::messageShown,
                        onSignIn = { vm.signIn { "token" } },
                        onSignOut = vm::signOut,
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
}

package com.mutqan.hadith.domain.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The learner's Google account, as far as the app needs it: who is signed in. */
data class AccountUser(val uid: String, val displayName: String, val email: String)

/** The user left the Google account chooser without choosing; nothing is wrong and nothing is shown. */
class SignInCancelled : Exception("Sign-in was cancelled")

/** There is no Google account on the device to sign in with. */
class NoGoogleAccount : Exception("No Google account on this device")

/**
 * Who is signed in. Signing in is optional: the app is whole without it, and signing in only adds what needs an account
 * (progress saved online, the admin's editing).
 */
interface AuthRepository {
    /** The signed-in account; null when nobody is. */
    val user: StateFlow<AccountUser?>

    /** Signs in with the ID token Google's account chooser gave. */
    suspend fun signInWithGoogle(idToken: String)

    fun signOut()
}

/** An [AuthRepository] with nobody in it and no way in; the default for code that is not given the real one. */
class SignedOutAuth : AuthRepository {
    override val user: StateFlow<AccountUser?> = MutableStateFlow<AccountUser?>(null).asStateFlow()

    override suspend fun signInWithGoogle(idToken: String) = throw UnsupportedOperationException("No account service")

    override fun signOut() = Unit
}

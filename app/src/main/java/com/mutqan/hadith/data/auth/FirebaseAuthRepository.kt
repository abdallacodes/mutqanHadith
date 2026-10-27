package com.mutqan.hadith.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.mutqan.hadith.domain.account.AccountUser
import com.mutqan.hadith.domain.account.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** [AuthRepository] over Firebase Authentication, which keeps the session between launches by itself. */
class FirebaseAuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) : AuthRepository {
    private val current = MutableStateFlow(auth.currentUser?.toAccount())

    override val user: StateFlow<AccountUser?> = current.asStateFlow()

    init {
        auth.addAuthStateListener { current.value = it.currentUser?.toAccount() }
    }

    override suspend fun signInWithGoogle(idToken: String) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    override fun signOut() = auth.signOut()

    override suspend fun reauthenticate(idToken: String) {
        val user = checkNotNull(auth.currentUser) { "Nobody is signed in" }
        user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    override suspend fun deleteAccount() {
        checkNotNull(auth.currentUser) { "Nobody is signed in" }.delete().await()
    }

    private fun FirebaseUser.toAccount() = AccountUser(uid, displayName.orEmpty(), email.orEmpty())
}

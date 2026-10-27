package com.mutqan.hadith.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mutqan.hadith.domain.account.NoGoogleAccount
import com.mutqan.hadith.domain.account.SignInCancelled

/**
 * Asks the person which Google account to use, through the system's account chooser, and returns the ID token that
 * proves it. [webClientId] is the project's web OAuth client (`default_web_client_id`), whose audience Firebase checks.
 * The chooser needs an Activity context.
 *
 * @throws SignInCancelled when the chooser is dismissed.
 * @throws NoGoogleAccount when the device has no Google account to offer.
 */
class GoogleSignInHelper(private val webClientId: String) {

    suspend fun idToken(activityContext: Context): String {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        } catch (e: GetCredentialCancellationException) {
            throw SignInCancelled()
        } catch (e: NoCredentialException) {
            throw NoGoogleAccount()
        }
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw IllegalStateException("Unexpected credential type: ${credential.type}")
    }

    /** Forgets the chosen account, so the next sign-in asks again. */
    suspend fun forget(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}

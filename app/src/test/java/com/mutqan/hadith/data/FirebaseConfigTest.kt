package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app is wired to the Firebase project: `google-services.json` is read into the default app at start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirebaseConfigTest {

    @Test
    fun theDefaultFirebaseAppPointsAtTheMutqanProjectAndPackage() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)

        assertEquals("mutqan-hadith", app!!.options.projectId)
        assertEquals("com.mutqan.hadith", context.packageName)
    }
}

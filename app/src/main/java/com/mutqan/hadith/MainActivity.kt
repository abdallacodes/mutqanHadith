package com.mutqan.hadith

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mutqan.hadith.data.sync.BackgroundSyncWorker
import com.mutqan.hadith.ui.navigation.MutqanNavHost
import com.mutqan.hadith.ui.settings.AppLanguageController
import com.mutqan.hadith.ui.theme.MutqanHadithTheme

/** An AppCompat activity: that is what lets the in-app language switch work on Android 11 and 12 as well. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First run only: Arabic is the app's language even on a phone set to another one. The activity is
        // recreated by the change when it takes effect, so there is nothing to wait for here.
        AppLanguageController(this).applyDefaultOnFirstRun()
        enableEdgeToEdge()
        val container = (application as MutqanApplication).container
        BackgroundSyncWorker.schedule(this)
        // Asked once, before anything else; a launch that comes after the answer goes straight to Home.
        val askStartingPoint = container.studyPreferences.startingPoint.value == null
        setContent {
            MutqanHadithTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MutqanNavHost(container, askStartingPoint = askStartingPoint)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Coming back to the app: a newer library is looked for, whatever was reviewed on another device is picked up and
        // what was reviewed here is sent. Progress is a no-op when nobody is signed in.
        (application as MutqanApplication).container.syncCoordinator.onForeground()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app: what was just reviewed goes out now, not at the next start.
        (application as MutqanApplication).container.syncCoordinator.onBackground()
    }
}

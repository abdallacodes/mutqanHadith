package com.mutqan.hadith

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
}

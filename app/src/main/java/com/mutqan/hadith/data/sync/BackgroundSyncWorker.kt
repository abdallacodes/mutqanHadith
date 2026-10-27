package com.mutqan.hadith.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mutqan.hadith.MutqanApplication
import java.util.concurrent.TimeUnit

/**
 * Keeps the library and the learner's progress up to date while the app is closed: once about every twelve hours, when
 * there is a network, it checks for a newer library and, if someone is signed in, syncs their progress. The same
 * code as when the app opens, so there is one way to sync, not two.
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MutqanApplication).container
        val library = container.catalogSyncer.refreshAndAwait(force = true)
        val progress = container.progressSyncer.syncAndAwait()
        // A failed run is tried again later by WorkManager, with a growing wait.
        return if (library && progress) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "background-sync"

        /** Plans the periodic run; asking again while one is planned changes nothing. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

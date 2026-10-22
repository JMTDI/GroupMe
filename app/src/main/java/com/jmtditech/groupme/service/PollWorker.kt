package com.jmtditech.groupme.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jmtditech.groupme.data.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * "Every 15 minutes" background mode: no foreground service and no persistent notification. Android runs this
 * about every 15 minutes (it may be later in battery saver / Doze), fetches new messages and notifies.
 */
class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val sl = ServiceLocator
        if (sl.prefs.token.isNullOrBlank()) return Result.success()
        return try {
            for (key in sl.repo.pollOnce().distinct()) Notifier.maybeNotify(applicationContext, key)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "poll-messages"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
        }
    }
}

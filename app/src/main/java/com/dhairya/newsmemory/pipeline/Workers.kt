package com.dhairya.newsmemory.pipeline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dhairya.newsmemory.App
import java.time.Instant
import java.time.ZoneId

/**
 * Runs the pipeline for one window. Unique name = window_id with KEEP policy +
 * the pipeline's own idempotency check: double-runs are harmless (EDD §5.3).
 * On completion the next slot's alarm is chained.
 */
class DigestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val windowId = inputData.getString(KEY_WINDOW_ID) ?: return Result.failure()
        val app = applicationContext as App
        return try {
            app.container.digestPipeline.runForWindow(windowId)
            DigestAlarmScheduler.scheduleNext(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else {
                DigestAlarmScheduler.scheduleNext(applicationContext)
                Result.failure()
            }
        }
    }

    companion object {
        private const val KEY_WINDOW_ID = "window_id"

        fun enqueue(context: Context, windowId: String) {
            val request = OneTimeWorkRequestBuilder<DigestWorker>()
                .setInputData(workDataOf(KEY_WINDOW_ID to windowId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                windowId, ExistingWorkPolicy.KEEP, request
            )
        }
    }
}

/**
 * Hourly safety net (EDD §5.3): enumerate windows that closed in the trailing two days
 * and run any that have no digest row. Late > lost. Also re-arms the alarm chain
 * (covers reboots and force-stops).
 */
class CatchupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val times = app.container.settingsStore.digestTimesSnapshot()
        val zone = ZoneId.systemDefault()
        val now = Instant.now()

        val closed = WindowCalculator.windowIdsBetween(
            now.minusSeconds(2 * 24 * 3600), now, times, zone
        )
        for (windowId in closed) {
            if (app.container.database.digestDao().digest(windowId) == null) {
                app.container.digestPipeline.runForWindow(windowId)
            }
        }
        DigestAlarmScheduler.scheduleNext(applicationContext)
        return Result.success()
    }
}

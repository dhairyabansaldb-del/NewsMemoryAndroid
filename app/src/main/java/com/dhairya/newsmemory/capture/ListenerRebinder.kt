package com.dhairya.newsmemory.capture

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dhairya.newsmemory.App
import com.dhairya.newsmemory.util.Permissions
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * One UI survival (EDD §4.3, the project's #1 technical risk).
 *
 * Every 30 minutes: if notification access is granted but the heartbeat is stale,
 * the listener binding is presumed zombie. The documented workaround: toggle the
 * component's enabled-state, then ask the OS to rebind.
 */
object RebinderLogic {

    /** Stale = more than two missed 15-minute heartbeats. */
    const val STALE_AFTER_MS = 35L * 60 * 1000

    fun isListenerDead(accessGranted: Boolean, lastAlive: Long?, now: Long): Boolean {
        if (!accessGranted) return false          // nothing to rebind without the grant
        if (lastAlive == null) return true        // granted but never heartbeat → dead
        return now - lastAlive > STALE_AFTER_MS
    }
}

class ListenerRebinderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val granted = Permissions.hasNotificationAccess(applicationContext)
        val lastAlive = app.container.settingsStore.lastAlive.first()

        if (RebinderLogic.isListenerDead(granted, lastAlive, System.currentTimeMillis())) {
            val component = ComponentName(applicationContext, NewsListenerService::class.java)
            val pm = applicationContext.packageManager
            // Toggle enabled-state to clear the zombie binding, then request rebind.
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            NotificationListenerService.requestRebind(component)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "listener-rebinder"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ListenerRebinderWorker>(
                30, TimeUnit.MINUTES
            )
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

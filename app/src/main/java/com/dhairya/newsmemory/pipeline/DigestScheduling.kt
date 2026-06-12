package com.dhairya.newsmemory.pipeline

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dhairya.newsmemory.App
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Scheduling mechanics (EDD §5.3): one exact-while-idle alarm for the NEXT slot only;
 * alarm → receiver → expedited unique DigestWorker (KEEP) → reschedule next slot.
 * BootReceiver re-arms after reboot; an hourly catch-up worker closes missed windows —
 * late digest beats lost digest, idempotency makes double-runs harmless.
 */
object DigestAlarmScheduler {

    private const val REQUEST_CODE = 4001

    suspend fun scheduleNext(context: Context) {
        val app = context.applicationContext as App
        val times = app.container.settingsStore.digestTimesSnapshot()
        val zone = ZoneId.systemDefault()
        val windowId = WindowCalculator.nextWindowId(Instant.now(), times, zone)
        val fireAt = WindowCalculator.deliveryTime(windowId, times, zone)

        val intent = Intent(context, DigestAlarmReceiver::class.java)
            .putExtra(EXTRA_WINDOW_ID, windowId)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
        } else {
            // Grant revoked: inexact alarm + hourly catch-up still deliver, just less punctually.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
        }
    }

    const val EXTRA_WINDOW_ID = "window_id"
}

class DigestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val windowId = intent.getStringExtra(DigestAlarmScheduler.EXTRA_WINDOW_ID) ?: return
        DigestWorker.enqueue(context, windowId)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Catch-up worker runs promptly and the alarm chain re-arms via DigestWorker.
            CatchupScheduler.scheduleNow(context)
        }
    }
}

object CatchupScheduler {

    private const val PERIODIC_NAME = "digest-catchup"

    /** Hourly safety net (EDD §5.3): any window that closed without a digest row gets run. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CatchupWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun scheduleNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<CatchupWorker>().build()
        )
    }
}

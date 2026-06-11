package com.dhairya.newsmemory.util

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.dhairya.newsmemory.capture.NewsListenerService

/** Snapshot of every grant onboarding cares about (EDD §2). */
data class GrantState(
    val notificationAccess: Boolean,
    val postNotifications: Boolean,
    val exactAlarms: Boolean,
    val batteryUnrestricted: Boolean
) {
    /** The two hard requirements; battery/alarms can be acknowledged past (EDD §2 step 3). */
    val coreGranted: Boolean get() = notificationAccess && postNotifications
    val allGranted: Boolean get() = coreGranted && exactAlarms && batteryUnrestricted
}

object Permissions {

    fun grantState(context: Context): GrantState = GrantState(
        notificationAccess = hasNotificationAccess(context),
        postNotifications = hasPostNotifications(context),
        exactAlarms = canScheduleExactAlarms(context),
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
    )

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun hasPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    fun canScheduleExactAlarms(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // --- Deep links to the exact settings screens (EDD §2) ---

    fun notificationAccessIntent(context: Context): Intent {
        val component = ComponentName(context, NewsListenerService::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                component.flattenToString()
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    fun exactAlarmIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${context.packageName}"))

    /** Direct request dialog; if OEM blocks it, fall back to App Info → Battery. */
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
}

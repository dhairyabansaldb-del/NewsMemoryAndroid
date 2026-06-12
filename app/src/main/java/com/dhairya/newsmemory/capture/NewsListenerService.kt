package com.dhairya.newsmemory.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dhairya.newsmemory.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Capture layer (EDD §4.1). Dumb and fast by design: allowlist gate first,
 * extract, hash, insert, return. No network, no LLM, no clustering here.
 *
 * Heartbeat (EDD §4.3): last_alive written on every captured post and on a
 * 15-minute internal timer, so the rebinder and the health panel can tell a
 * live listener from a zombie one.
 */
class NewsListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val container get() = (application as App).container

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch { container.settingsStore.heartbeat() }
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                container.settingsStore.heartbeat()
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // FIRST LINE: non-allowlisted content is never touched (EDD §4.1).
        val allowlist = container.settingsStore.allowlistSnapshot()
        if (sbn.packageName !in allowlist) return
        if (sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (title.isNullOrBlank() && body.isNullOrBlank()) return

        val postTime = sbn.postTime
        val packageName = sbn.packageName
        scope.launch {
            container.notificationRepository.insertRaw(packageName, title, body, postTime)
            container.settingsStore.heartbeat()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 15L * 60 * 1000
    }
}

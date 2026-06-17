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
 * Capture layer (EDD §4.1, reworked in Phase A/B). Dumb and fast: allowlist gate first,
 * classify the notification shape, resolve real headlines, persist, then INTERCEPT
 * (cancel) so the notification leaves the system shade and lives only in News Memory.
 *
 * Heartbeat (EDD §4.3): last_alive on every capture, on connect, and on a 15-min timer.
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

        val input = buildInput(sbn) ?: return
        val items = NotificationExtractor.extract(input)
        if (items.isEmpty()) return

        val packageName = sbn.packageName
        val postTime = sbn.postTime
        val key = sbn.key

        scope.launch {
            val result = container.notificationRepository.insertExtracted(packageName, items, postTime)
            if (result.anyUnparseable) container.settingsStore.flagLimitedSupport(packageName)
            container.settingsStore.heartbeat()
        }

        // INTERCEPT (Phase B): pull it from the shade now that it's captured. Global for all
        // allowlisted apps. Can't pre-empt the post, so a brief blip is possible.
        runCatching { cancelNotification(key) }
    }

    /** Pull every text-bearing extra out of the notification for the classifier. */
    private fun buildInput(sbn: StatusBarNotification): NotificationExtractor.Input? {
        val extras = sbn.notification.extras
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString() } ?: emptyList()
        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        val input = NotificationExtractor.Input(
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString(),
            summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),
            textLines = lines,
            template = extras.getString(Notification.EXTRA_TEMPLATE)
        )

        // Nothing usable at all → don't even create a row.
        val empty = input.title.isNullOrBlank() && input.text.isNullOrBlank() &&
            input.bigText.isNullOrBlank() && lines.isEmpty()
        return if (empty) null else input
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 15L * 60 * 1000
    }
}

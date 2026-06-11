package com.dhairya.newsmemory.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Phase 2 stub: declared so the Notification Access toggle exists in system settings.
 * Phase 3 adds the allowlist gate, extraction, hashing, insert path, and heartbeat (EDD §4).
 */
class NewsListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Phase 3: allowlist gate FIRST, then extract → hash → insert. Nothing yet.
    }
}

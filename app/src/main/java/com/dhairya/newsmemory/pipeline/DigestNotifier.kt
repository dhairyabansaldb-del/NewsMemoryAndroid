package com.dhairya.newsmemory.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dhairya.newsmemory.MainActivity
import com.dhairya.newsmemory.R
import com.dhairya.newsmemory.data.db.Digest

/**
 * Stage 5 (EDD §5.2): exactly one push per digest.
 * "Morning Digest — 12 stories from 4 apps"; 1–3 items → "Quiet window".
 */
class DigestNotifier(private val context: Context) {

    init {
        val channel = NotificationChannel(
            CHANNEL_ID, "Digests", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Three daily digest notifications" }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun postDigestReady(digest: Digest) {
        val slot = DigestSlot.valueOf(digest.slot)
        val title = "${slot.label} Digest"
        val text = if (digest.itemCount <= 3) {
            "Quiet window — ${digest.itemCount} ${if (digest.itemCount == 1) "story" else "stories"}"
        } else {
            "${digest.itemCount} stories from ${digest.sourceCount} apps"
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DIGEST_ID, digest.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            digest.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(digest.id.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "digests"
        const val EXTRA_DIGEST_ID = "digest_id"
    }
}

package com.dhruvaxtechnologies.govjobradar.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dhruvaxtechnologies.govjobradar.R
import com.dhruvaxtechnologies.govjobradar.ui.MainActivity

object Notifier {
    const val CH_NEW = "new_jobs"
    const val CH_REMIND = "reminders"
    const val EXTRA_JOB = "job_id"
    const val EXTRA_TAB = "tab"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_NEW, "New jobs (morning summary)",
            NotificationManager.IMPORTANCE_DEFAULT).apply { description = "One summary each morning when new vacancies match you" })
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Last-date reminders",
            NotificationManager.IMPORTANCE_HIGH).apply { description = "Reminders before the last date of saved jobs" })
    }

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun open(context: Context, jobId: String?, tab: String?, req: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            jobId?.let { putExtra(EXTRA_JOB, it) }
            tab?.let { putExtra(EXTRA_TAB, it) }
        }
        return PendingIntent.getActivity(context, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Returns false when notifications are off (not an error). */
    @Suppress("MissingPermission")
    fun post(context: Context, id: Int, channel: String, title: String, text: String, lines: List<String> = emptyList(),
             jobId: String? = null, tab: String? = null): Boolean {
        if (!allowed(context)) return false
        val style = NotificationCompat.InboxStyle().also { s -> lines.take(5).forEach { s.addLine(it) } }
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(if (lines.isEmpty()) NotificationCompat.BigTextStyle().bigText(text) else style)
            .setContentIntent(open(context, jobId, tab, id))
            .setAutoCancel(true)
            .setColor(0xFF0B5563.toInt())
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
        return true
    }
}

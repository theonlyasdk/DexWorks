package com.asdk.tools.dexworks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object WorkNotifications {

    const val CHANNEL_ID = "work_done"
    private const val NOTIF_ID_ZIP = 1001
    private const val NOTIF_ID_ZIP_PROGRESS = 1002

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_work),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_work_desc)
                setSound(null, null)
            }
        )
    }

    fun areEnabled(context: Context): Boolean {
        val prefsOn = AppPrefs.notifications(context)
        if (!prefsOn) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notifyZipProgress(context: Context, zipName: String, progress1000: Int) {
        if (!areEnabled(context)) return
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tool_extract)
            .setContentTitle(context.getString(R.string.notif_zip_progress_title, zipName))
            .setProgress(1000, progress1000.coerceIn(0, 1000), false)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_ZIP_PROGRESS, notification)
        } catch (e: SecurityException) {
            // Permission was revoked between check and post; dialog already covers it.
        }
    }

    fun cancelZipProgress(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID_ZIP_PROGRESS)
        } catch (e: SecurityException) {
        }
    }

    private var hintShownThisSession = false

    fun maybeShowDisabledHint(context: Context) {
        if (hintShownThisSession) return
        val prefsOn = AppPrefs.notifications(context)
        if (!prefsOn) return
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        hintShownThisSession = true
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.notif_off_title)
            .setMessage(R.string.notif_off_message)
            .setPositiveButton(R.string.action_open_settings) { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    fun notifyZipDone(context: Context, success: Boolean, zipName: String) {
        if (!areEnabled(context)) return
        createChannel(context)
        cancelZipProgress(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tool_extract)
            .setContentTitle(
                context.getString(
                    if (success) R.string.notif_zip_done_title else R.string.notif_zip_failed_title
                )
            )
            .setContentText(
                if (success) context.getString(R.string.notif_zip_done_text, zipName)
                else context.getString(R.string.notif_zip_failed_text)
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_ZIP, notification)
        } catch (e: SecurityException) {
            // Permission was revoked between check and post; Snackbar already covers it.
        }
    }
}

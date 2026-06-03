package com.joasasso.paperlink.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "photo_detection_channel"
    private const val CHANNEL_NAME = "Detección de Fotos"
    private const val NOTIFICATION_ID = 1001

    fun showPhotoCodeNotification(context: Context, code: String, latencyMs: Long) {
        val tag = "NotificationHelper"
        Log.d(tag, "Preparing notification for code: $code")
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existingChannel == null) {
                Log.d(tag, "Creating new notification channel: $CHANNEL_ID")
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notificaciones rápidas para códigos de PaperLink"
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
            } else {
                Log.d(tag, "Using existing notification channel. Importance: ${existingChannel.importance}")
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Código PaperLink: $code")
            .setContentText("Detectado en ${latencyMs}ms")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.i(tag, "Notification displayed successfully for ID: $NOTIFICATION_ID")
        } catch (e: Exception) {
            Log.e(tag, "Failed to display notification", e)
        }
    }
}

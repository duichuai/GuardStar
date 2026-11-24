package com.guardstar.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.guardstar.app.MainActivity
import com.guardstar.app.R

object Notifications {

    const val CHANNEL_WARD_LOCATION = "ward_location"
    const val ID_WARD_LOCATION = 1001

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_WARD_LOCATION) == null) {
            val channel = NotificationChannel(
                CHANNEL_WARD_LOCATION,
                "位置共享",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "被守护端正在共享位置"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun locationNotification(context: Context): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_WARD_LOCATION)
            .setContentTitle("守护星")
            .setContentText("正在共享位置给你的监护人")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}

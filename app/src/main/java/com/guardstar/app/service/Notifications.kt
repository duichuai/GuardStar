package com.guardstar.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.guardstar.app.MainActivity
import com.guardstar.app.R
import com.guardstar.app.data.event.EventItem
import com.guardstar.app.data.model.RelationSummary
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Notifications {

    const val CHANNEL_WARD_LOCATION = "ward_location"
    const val ID_WARD_LOCATION = 1001
    private const val CHANNEL_GUARDIAN_SOS = "guardian_sos"
    private const val CHANNEL_GUARDIAN_ALERT = "guardian_alert"
    private const val CHANNEL_GUARDIAN_STATUS = "guardian_status"
    private const val ID_WARD_BATTERY = 1002
    private const val PREFS_NAME = "guardstar_notifications"
    private const val KEY_NOTIFIED_EVENTS = "notified_events"
    private const val KEY_WARD_BATTERY_LEVEL = "ward_battery_level"
    private const val EXTRA_RELATION_ID = "guardstar.relation_id"
    private const val EXTRA_EVENT_ID = "guardstar.event_id"

    data class EventTarget(val relationId: String, val eventId: String)

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
        listOf(
            NotificationChannel(CHANNEL_GUARDIAN_SOS, "SOS 紧急求助", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "被守护人发起的紧急求助"
                enableVibration(true)
            },
            NotificationChannel(CHANNEL_GUARDIAN_ALERT, "围栏与定位提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "围栏越界与定位权限变化"
            },
            NotificationChannel(CHANNEL_GUARDIAN_STATUS, "设备状态提醒", NotificationManager.IMPORTANCE_LOW).apply {
                description = "低电量、离线与恢复在线"
            },
        ).forEach { channel ->
            if (nm.getNotificationChannel(channel.id) == null) nm.createNotificationChannel(channel)
        }
    }

    fun locationNotification(context: Context, contentText: String = "正在共享位置给你的监护人"): android.app.Notification {
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
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    /** 更新前台服务通知文案（如待上报数）。 */
    fun updateLocationNotification(context: Context, contentText: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(ID_WARD_LOCATION, locationNotification(context, contentText))
    }

    /** 为尚未提醒过的事件发送系统通知；同一事件在同一安装内不重复提醒。 */
    @android.annotation.SuppressLint("MissingPermission")
    fun notifyNewEvents(context: Context, relation: RelationSummary, events: List<EventItem>) {
        if (events.isEmpty()) return
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(KEY_NOTIFIED_EVENTS, null).orEmpty().toMutableSet()
        var changed = false
        // 接口按时间倒序返回，提醒时按时间正序展示。
        for (event in events.asReversed()) {
            if (event.eventId in notified) continue
            val channel = when (event.level) {
                "p0" -> CHANNEL_GUARDIAN_SOS
                "p1" -> CHANNEL_GUARDIAN_ALERT
                else -> CHANNEL_GUARDIAN_STATUS
            }
            val priority = when (event.level) {
                "p0" -> NotificationCompat.PRIORITY_HIGH
                "p1" -> NotificationCompat.PRIORITY_DEFAULT
                else -> NotificationCompat.PRIORITY_LOW
            }
            val nickname = relation.peerNickname.ifBlank { "被守护人" }
            val body = "${eventNotificationText(event)} · ${formatEventTime(event.occurredAt)}"
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("$nickname · ${eventNotificationTitle(event)}")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(priority)
                .setCategory(
                    if (event.level == "p0") NotificationCompat.CATEGORY_ALARM
                    else NotificationCompat.CATEGORY_EVENT
                )
                .setWhen(eventTimeMillis(event.occurredAt))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroup("guardian_events_${relation.relationId}")
                .setContentIntent(eventPendingIntent(context, relation.relationId, event.eventId))
                .build()
            try {
                manager.notify(event.eventId.hashCode(), notification)
                notified += event.eventId
                changed = true
            } catch (_: SecurityException) {
                break
            }
        }
        if (changed) prefs.edit().putStringSet(KEY_NOTIFIED_EVENTS, notified).apply()
    }

    fun cancelEvent(context: Context, eventId: String) {
        NotificationManagerCompat.from(context).cancel(eventId.hashCode())
    }

    fun eventTarget(intent: Intent?): EventTarget? {
        val relationId = intent?.getStringExtra(EXTRA_RELATION_ID).orEmpty()
        val eventId = intent?.getStringExtra(EXTRA_EVENT_ID).orEmpty()
        return if (relationId.isNotBlank() && eventId.isNotBlank()) EventTarget(relationId, eventId) else null
    }

    /** 被守护端本地低电量提示：20% 和 10% 各一次，充电或恢复至 20% 以上后重置。 */
    @android.annotation.SuppressLint("MissingPermission")
    fun notifyWardLowBattery(context: Context, battery: Int, charging: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manager = NotificationManagerCompat.from(context)
        if (charging || battery > 20) {
            manager.cancel(ID_WARD_BATTERY)
            if (prefs.getInt(KEY_WARD_BATTERY_LEVEL, 0) != 0) {
                prefs.edit().putInt(KEY_WARD_BATTERY_LEVEL, 0).apply()
            }
            return
        }
        if (!manager.areNotificationsEnabled()) return
        val threshold = if (battery <= 10) 10 else 20
        val previous = prefs.getInt(KEY_WARD_BATTERY_LEVEL, 0)
        // 已进入 10% 档后，电量小幅回升到 20% 档不重复提醒。
        if (previous != 0 && threshold >= previous) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            ID_WARD_BATTERY,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_GUARDIAN_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("设备电量低，请充电")
            .setContentText("当前电量 $battery%，保持充电以维持位置共享")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        try {
            manager.notify(ID_WARD_BATTERY, notification)
            prefs.edit().putInt(KEY_WARD_BATTERY_LEVEL, threshold).apply()
        } catch (_: SecurityException) {
            // 通知权限在检查后被关闭时，等待下一轮状态检测。
        }
    }

    private fun eventPendingIntent(context: Context, relationId: String, eventId: String): PendingIntent {
        val uri = Uri.Builder()
            .scheme("guardstar")
            .authority("events")
            .appendPath(relationId)
            .appendPath(eventId)
            .build()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_RELATION_ID, relationId)
            putExtra(EXTRA_EVENT_ID, eventId)
        }
        return PendingIntent.getActivity(
            context,
            eventId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun eventNotificationTitle(event: EventItem): String = when (event.type) {
        "sos_created" -> "SOS 紧急求助"
        "low_battery" -> "设备电量低"
        "device_offline" -> "设备已离线"
        "device_online" -> "设备恢复在线"
        "location_disabled" -> "定位权限已关闭"
        "fence_exit" -> "离开围栏"
        "fence_enter" -> "进入围栏"
        "reporting_paused" -> "位置共享已暂停"
        "sos_enabled" -> "SOS 高频定位已开启"
        else -> "守护提醒"
    }

    private fun eventNotificationText(event: EventItem): String {
        val payload = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(event.payload).jsonObject
        }.getOrNull()
        fun field(name: String): String? = payload?.get(name)?.jsonPrimitive?.content
        return when (event.type) {
            "sos_created" -> "收到紧急求助，请尽快查看位置并处理"
            "low_battery" -> "当前电量 ${field("battery") ?: "未知"}%，请提醒对方充电"
            "device_offline" -> "已连续 ${field("offlineAfterMin") ?: "30"} 分钟未收到上报"
            "device_online" -> "已重新收到设备上报"
            "location_disabled" -> "定位权限被关闭，位置共享可能已中断"
            "fence_exit", "fence_enter" -> "围栏：${field("fenceName") ?: "未命名"}"
            "reporting_paused" -> "被守护端已暂停位置上报"
            "sos_enabled" -> "设备已进入 30 秒高频定位模式"
            else -> "点击查看事件与最新位置"
        }
    }

    private fun eventTimeMillis(occurredAt: String): Long = runCatching {
        Instant.parse(occurredAt).toEpochMilli()
    }.getOrElse { System.currentTimeMillis() }

    private fun formatEventTime(occurredAt: String): String = runCatching {
        Instant.parse(occurredAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault(occurredAt)
}

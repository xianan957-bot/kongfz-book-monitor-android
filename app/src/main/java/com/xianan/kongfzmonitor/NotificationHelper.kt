package com.xianan.kongfzmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager

object NotificationHelper {
    const val MONITOR_NOTIFICATION_ID = 1001

    private const val MONITOR_CHANNEL_ID = "monitor_status"
    private const val ITEM_CHANNEL_ID = "new_items"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val monitorChannel = NotificationChannel(
            MONITOR_CHANNEL_ID,
            "监控运行状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示孔夫子商品监控是否正在运行"
            setSound(null, null)
        }

        val notificationSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        val itemChannel = NotificationChannel(
            ITEM_CHANNEL_ID,
            "符合条件的新商品",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "发现符合监控条件的新上架商品时提醒"
            enableVibration(true)
            setSound(notificationSound, audioAttributes)
        }

        manager.createNotificationChannels(listOf(monitorChannel, itemChannel))
    }

    fun buildMonitorNotification(context: Context): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(context, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("孔夫子商品监控运行中")
            .setContentText("正在按设定间隔检查新上架商品")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun notifyNewItem(context: Context, item: KongfzItem) {
        val intent = Intent(context, ItemWebViewActivity::class.java).apply {
            putExtra(ItemWebViewActivity.EXTRA_ITEM_URL, item.itemUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            item.itemId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val summary = buildList {
            item.price?.let { add("¥${formatPrice(it)}") }
            if (item.condition.isNotBlank()) add(item.condition)
            if (item.shop.isNotBlank()) add(item.shop)
        }.joinToString(" · ").ifBlank { "点击查看商品" }

        val notification = Notification.Builder(context, ITEM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(item.title)
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(item.itemId.hashCode(), notification)
    }

    private fun formatPrice(price: Double): String {
        return if (price % 1.0 == 0.0) {
            price.toLong().toString()
        } else {
            "%.2f".format(price).trimEnd('0').trimEnd('.')
        }
    }
}

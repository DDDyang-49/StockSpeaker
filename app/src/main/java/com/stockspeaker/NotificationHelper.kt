package com.stockspeaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ID = "stock_monitor"
    const val ALERT_CHANNEL_ID = "stock_alert"
    const val NOTIFICATION_ID = 1
    const val ALERT_NOTIFICATION_ID = 2
    const val ACTION_PAUSE = "com.stockspeaker.PAUSE"
    const val ACTION_RESUME = "com.stockspeaker.RESUME"
    const val ACTION_DISMISS_ALERT = "com.stockspeaker.DISMISS_ALERT"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "盯盘服务", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "摸鱼听盘后台播报通知" })
        nm.createNotificationChannel(NotificationChannel(
            ALERT_CHANNEL_ID, "异动提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "大单/涨速异动实时提醒" })
    }

    private fun baseBuilder(context: Context) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ))

    private fun pauseAction(context: Context) =
        NotificationCompat.Action.Builder(
            0, "⏸ 暂停",
            PendingIntent.getService(context, 1,
                Intent(context, StockMonitorService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ).build()

    private fun resumeAction(context: Context) =
        NotificationCompat.Action.Builder(
            0, "▶ 继续",
            PendingIntent.getService(context, 2,
                Intent(context, StockMonitorService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ).build()

    fun build(context: Context, code: String, paused: Boolean = false) =
        baseBuilder(context)
            .setContentTitle(if (paused) "摸鱼听盘 ⏸" else "摸鱼听盘")
            .setContentText(if (paused) "已暂停播报" else "正在监控 $code...")
            .apply { addAction(if (paused) resumeAction(context) else pauseAction(context)) }
            .build()

    fun buildWithData(context: Context, name: String, price: Double, changePct: Double, paused: Boolean): NotificationCompat.Builder {
        val st = when { changePct > 0 -> "涨"; changePct < 0 -> "跌"; else -> "平" }
        val content = "$name $price ($st${Math.abs(changePct)}%)"
        return baseBuilder(context)
            .setContentTitle(if (paused) "摸鱼听盘 ⏸" else "摸鱼听盘")
            .setContentText(if (paused) "已暂停播报" else content)
            .apply { addAction(if (paused) resumeAction(context) else pauseAction(context)) }
    }

    fun notify(context: Context, builder: NotificationCompat.Builder) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, builder.build())
    }

    /** 异动提醒通知：点击"关闭提醒"后发送 DISMISS_ALERT 动作到 Service */
    fun buildAlert(context: Context, text: String): NotificationCompat.Builder {
        val dismissIntent = PendingIntent.getService(context, 3,
            Intent(context, StockMonitorService::class.java).setAction(ACTION_DISMISS_ALERT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("异动提醒")
            .setContentText(text.take(80))
            .setAutoCancel(true)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "关闭提醒", dismissIntent)
            .setContentIntent(PendingIntent.getActivity(
                context, 4, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    fun notifyAlert(context: Context, builder: NotificationCompat.Builder) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ALERT_NOTIFICATION_ID, builder.build())
    }

    fun cancelAlert(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(ALERT_NOTIFICATION_ID)
    }
}

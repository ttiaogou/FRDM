package com.freedom.stability

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.freedom.MainActivity
import com.freedom.R
import com.freedom.accessibility.FreedomAccessibilityService
import com.freedom.adapters.android.MonitorActions
import com.freedom.app.FreedomApplication

/** Records a pending boot restore and notifies the user; the system reconnects AccessibilityService. */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val repository = FreedomApplication.root(context).monitorRepository
        if (!repository.isRunRequested()) {
            repository.setBootRecoveryPending(false)
            return
        }
        if (!repository.isBootCheckEnabled()) {
            repository.setBootRecoveryPending(false)
            return
        }

        val accessibilityEnabled = isAccessibilityEnabled(context)

        val message = if (!accessibilityEnabled) {
            repository.setBootRecoveryPending(false)
            "设备已重启，但 Freedom 无障碍授权未开启。请打开应用检查授权。"
        } else {
            // Do not start an AccessibilityService or foreground service from this receiver.
            // Android owns AccessibilityService binding; the pending marker is acknowledged by
            // the service after its normal persisted-session reconciliation completes.
            repository.setBootRecoveryPending(true)
            context.sendBroadcast(
                Intent(MonitorActions.ACTION_BOOT_RECOVERY).setPackage(context.packageName)
            )
            "设备已重启。Freedom 已登记恢复请求，正在等待系统重新连接无障碍服务；服务连接后会按原运行模式恢复。"
        }

        postBootCheckNotification(context, message)
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, FreedomAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        return enabled.split(':').any { flattened ->
            val component = ComponentName.unflattenFromString(flattened)
            component != null &&
                component.packageName == expected.packageName &&
                component.className == expected.className
        }
    }

    private fun postBootCheckNotification(context: Context, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return

        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Freedom 启动检查",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "设备重启后检查 Freedom 的运行授权"
                }
            )
        }

        val openIntent = PendingIntent.getActivity(
            context,
            102,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
            .setSmallIcon(R.drawable.ic_freedom)
            .setContentTitle("Freedom 启动检查")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(BOOT_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "freedom_boot_check"
        private const val BOOT_NOTIFICATION_ID = 7102
    }
}

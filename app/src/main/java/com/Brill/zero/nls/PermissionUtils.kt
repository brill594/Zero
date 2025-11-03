package com.brill.zero.nls

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object PermissionUtils {

    /**
     * 检查我们的 Service 是否已被用户在 "通知使用权" 中勾选
     */
    fun isNotificationServiceEnabled(context: Context): Boolean {
        // [!] 这是检查 "通知使用权" 的唯一正确方法
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
        val myPackageName = context.packageName
        return enabledListeners.contains(myPackageName)
    }

    /**
     * 打开 "通知使用权" 的系统设置页面，让用户手动开启
     */
    fun openNotificationAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
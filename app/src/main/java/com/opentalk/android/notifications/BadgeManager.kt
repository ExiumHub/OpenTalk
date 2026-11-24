package com.opentalk.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors

/**
 * Universal Android App Icon Badge Manager.
 * Dispatches all OEM launcher broadcasts & ContentProvider IPC asynchronously off the Main Thread.
 */
object BadgeManager {

    private const val TAG = "BadgeManager"
    private const val MAIN_ACTIVITY = "com.opentalk.android.MainActivity"
    private const val FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000

    private val badgeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BadgeDispatchThread").apply { isDaemon = true }
    }

    fun setBadge(context: Context, count: Int) {
        val appCtx = context.applicationContext
        val safeCount = maxOf(0, count)
        badgeExecutor.execute {
            dispatchBadgeInternal(appCtx, safeCount)
        }
    }

    @SuppressLint("WrongConstant")
    private fun sendOemBroadcast(context: Context, intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND or FLAG_RECEIVER_INCLUDE_BACKGROUND)
            val resolved = context.packageManager.queryBroadcastReceivers(intent, 0)
            if (resolved.isNotEmpty()) {
                for (ri in resolved) {
                    val explicit = Intent(intent).apply {
                        setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    }
                    try { context.sendBroadcast(explicit) } catch (_: Exception) {}
                }
            } else {
                context.sendBroadcast(intent)
            }
        } catch (_: Exception) {}
    }

    private fun dispatchBadgeInternal(context: Context, safeCount: Int) {
        val pkgName = context.packageName

        // 1. Transsion / MediaTek (Infinix XOS & Tecno HiOS)
        sendOemBroadcast(context, Intent("com.mediatek.action.UNREAD_CHANGED").apply {
            putExtra("com.mediatek.intent.extra.UNREAD_COMPONENT", ComponentName(context, MAIN_ACTIVITY))
            putExtra("com.mediatek.intent.extra.UNREAD_NUMBER", safeCount)
            putExtra("userId", 0)
        })

        sendOemBroadcast(context, Intent("com.transsion.action.BADGE_COUNT_UPDATE").apply {
            putExtra("badge_count", safeCount)
            putExtra("badge_count_package_name", pkgName)
            putExtra("badge_count_class_name", MAIN_ACTIVITY)
        })

        // Transsion XOS Launcher Unread Provider
        try {
            val transsionBundle = Bundle().apply {
                putString("package", pkgName)
                putString("class", MAIN_ACTIVITY)
                putInt("badgenumber", safeCount)
                putLong("userId", 0L)
            }
            context.contentResolver.call(
                Uri.parse("content://com.transsion.XOSLauncher.unreadprovider"),
                "change_badge",
                null,
                transsionBundle
            )
        } catch (_: Exception) {}

        // Transsion system unread settings (only when WRITE_SETTINGS granted, and write 0 when clearing!)
        try {
            if (Settings.System.canWrite(context)) {
                val flag = if (safeCount > 0) 1 else 0
                val sanitizedPkg = pkgName.replace('.', '_')
                Settings.System.putInt(context.contentResolver, "settings_unread_badge_${sanitizedPkg}_0", flag)
                if (flag == 1) {
                    Settings.System.putInt(context.contentResolver, "settings_app_unread", 1)
                }
            }
        } catch (_: Exception) {}

        // 2. Universal Android Launcher Broadcast
        sendOemBroadcast(context, Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
            putExtra("badge_count", safeCount)
            putExtra("badge_count_package_name", pkgName)
            putExtra("badge_count_class_name", MAIN_ACTIVITY)
        })

        // 3. Samsung OneUI / TouchWiz ContentProvider
        try {
            val uri = Uri.parse("content://com.sec.badge/apps")
            val cv = ContentValues().apply {
                put("package", pkgName)
                put("class", MAIN_ACTIVITY)
                put("badgecount", safeCount)
            }
            val updated = context.contentResolver.update(uri, cv, "package=?", arrayOf(pkgName))
            if (updated <= 0) {
                context.contentResolver.insert(uri, cv)
            }
        } catch (_: Exception) {}

        // 4. Huawei / Honor EMUI
        try {
            val bundle = Bundle().apply {
                putString("package", pkgName)
                putString("class", MAIN_ACTIVITY)
                putInt("badgenumber", safeCount)
            }
            context.contentResolver.call(
                Uri.parse("content://com.huawei.android.launcher.settings/badge/"),
                "change_badge",
                null,
                bundle
            )
        } catch (_: Exception) {}

        // 5. Vivo FuntouchOS
        sendOemBroadcast(context, Intent("launcher.action.CHANGE_APPLICATION_NOTIFICATION_NUM").apply {
            putExtra("packageName", pkgName)
            putExtra("className", MAIN_ACTIVITY)
            putExtra("notificationNum", safeCount)
        })

        // 6. Oppo / Realme ColorOS
        try {
            val oppoBundle = Bundle().apply {
                putInt("app_badge_count", safeCount)
            }
            context.contentResolver.call(
                Uri.parse("content://com.android.badge/badge"),
                "setAppBadgeCount",
                null,
                oppoBundle
            )
        } catch (_: Exception) {}

        sendOemBroadcast(context, Intent("com.oppo.unsettledevent").apply {
            putExtra("pName", pkgName)
            putExtra("number", safeCount)
            putExtra("upgradeNumber", safeCount)
        })

        Log.d(TAG, "Badge counter dispatched: count=$safeCount for $pkgName")
    }

    fun clearBadge(context: Context) {
        setBadge(context, 0)
    }

    fun applyXiaomiBadge(notification: Notification, count: Int) {
        try {
            val safeCount = maxOf(0, count)
            val field = notification.javaClass.getDeclaredField("extraNotification")
            val extraNotification = field.get(notification) ?: return
            val method = extraNotification.javaClass.getDeclaredMethod("setMessageCount", Int::class.javaPrimitiveType)
            method.invoke(extraNotification, safeCount)
        } catch (_: Exception) {}
    }
}

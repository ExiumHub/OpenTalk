package com.opentalk.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.opentalk.android.CallActivity
import com.opentalk.android.MainActivity
import com.opentalk.android.R
import com.opentalk.android.receivers.SipWatchdogReceiver
import com.opentalk.android.utils.PhoneNumberHelper

/**
 * 24/7 Professional Missed Call Notification & App Icon Badge Manager.
 */
object MissedCallNotifier {

    private const val TAG = "MissedCallNotifier"
    const val CHANNEL_MISSED_CALLS = "sip_missed_calls_channel"
    const val NOTIFICATION_ID_MISSED = 2001
    const val ACTION_DISMISS_MISSED = "com.opentalk.android.action.DISMISS_MISSED_CALL"
    private const val PREFS_NAME = "dial_missed_calls_prefs"
    private const val KEY_MISSED_COUNT = "unviewed_missed_count"
    private const val LEGACY_KEY_LAST_CALLER_NUM = "last_caller_number"
    private const val LEGACY_KEY_LAST_CALLER_NAME = "last_caller_name"

    /**
     * Always stores non-sensitive missed call counter in Device-Protected Storage so
     * Direct Boot and Unlocked states share ONE single source of truth (zero split-brain).
     */
    private fun getSharedCounterPrefs(context: Context): android.content.SharedPreferences {
        val deCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }
        return deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        createChannel(context)
        val unviewedCount = getMissedCount(context)
        if (unviewedCount > 0 &&
            com.opentalk.android.storage.SoundSettingsManager.isMissedCallNotificationEnabled(context) &&
            canPostNotifications(context)
        ) {
            BadgeManager.setBadge(context, unviewedCount)
        }
    }

    @Synchronized
    fun getMissedCount(context: Context): Int {
        return try {
            getSharedCounterPrefs(context).getInt(KEY_MISSED_COUNT, 0)
        } catch (_: Exception) {
            0
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (_: Exception) {
            true
        }
    }

    @Synchronized
    fun postMissedCall(context: Context, callerNumber: String, callerName: String, increment: Int = 1) {
        try {
            val appCtx = context.applicationContext
            val prefs = getSharedCounterPrefs(appCtx)
            val currentCount = prefs.getInt(KEY_MISSED_COUNT, 0)
            val newCount = currentCount + maxOf(1, increment)

            val canonicalNumber = PhoneNumberHelper.toCanonicalE164(callerNumber).ifBlank { callerNumber.trim() }
            val displayNumber = PhoneNumberHelper.formatForDisplay(canonicalNumber)
            val rawName = callerName.trim()
            val hasContactName = rawName.isNotBlank() &&
                !PhoneNumberHelper.looksLikePhoneNumber(rawName) &&
                !PhoneNumberHelper.areSameNumber(rawName, canonicalNumber)
            val cleanName = if (hasContactName) rawName else displayNumber

            // Never persist unencrypted caller PII in Device-Protected Storage
            prefs.edit()
                .putInt(KEY_MISSED_COUNT, newCount)
                .remove(LEGACY_KEY_LAST_CALLER_NUM)
                .remove(LEGACY_KEY_LAST_CALLER_NAME)
                .apply()

            if (!com.opentalk.android.storage.SoundSettingsManager.isMissedCallNotificationEnabled(appCtx)) {
                Log.d(TAG, "Missed call notifications disabled by user; skipping shade alert.")
                return
            }

            if (!canPostNotifications(appCtx)) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted/enabled; updated missed count ($newCount) without posting notification.")
                return
            }

            createChannel(appCtx)

            val notificationManager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Tap Notification -> Open MainActivity on Recents Tab with Missed filter
            val contentIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("select_tab", "recents")
                putExtra("filter_missed", true)
            }
            val contentPendingIntent = PendingIntent.getActivity(
                appCtx,
                301,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Direct One-Tap Callback Action Button (signals CallActivity to clear missed badge)
            val callBackIntent = Intent(appCtx, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("number", canonicalNumber)
                putExtra("name", cleanName)
                putExtra("is_incoming", false)
                putExtra("clear_missed", true)
            }
            val callBackPendingIntent = PendingIntent.getActivity(
                appCtx,
                302,
                callBackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Swipe-to-dismiss -> reset missed counter & OEM launcher badge
            val deleteIntent = Intent(appCtx, SipWatchdogReceiver::class.java).apply {
                action = ACTION_DISMISS_MISSED
            }
            val deletePendingIntent = PendingIntent.getBroadcast(
                appCtx,
                303,
                deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (newCount <= 1) "Missed Call" else "$newCount Missed Calls"
            val text = if (newCount <= 1) {
                if (hasContactName) "$cleanName ($displayNumber)" else displayNumber
            } else {
                if (hasContactName) "$cleanName and other calls" else "$displayNumber ($newCount calls)"
            }

            val soundUri = com.opentalk.android.storage.SoundSettingsManager.getNotificationSoundUriOrNull(appCtx)
            val activeChannelId = getActiveChannelId(appCtx)

            val builder = NotificationCompat.Builder(appCtx, activeChannelId)
                .setSmallIcon(R.drawable.ic_call_missed)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText("Missed")
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(contentPendingIntent)
                .setDeleteIntent(deletePendingIntent)
                .setAutoCancel(true)
                .setNumber(newCount)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .setColor(0xFFFF3B30.toInt())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(soundUri)
                .addAction(
                    R.drawable.ic_phone,
                    "Call Back",
                    callBackPendingIntent
                )
                .addAction(
                    R.drawable.ic_call_missed,
                    "Mark as Read",
                    deletePendingIntent
                )

            // Rich multi-caller InboxStyle breakdown when 2+ missed calls accumulate
            if (newCount > 1 && androidx.core.os.UserManagerCompat.isUserUnlocked(appCtx)) {
                try {
                    val recentMissed = com.opentalk.android.storage.CallHistoryDbHelper
                        .getInstance(appCtx)
                        .getCalls()
                        .filter { it.type == com.opentalk.android.models.CallType.MISSED }
                        .take(minOf(newCount, 5))
                    if (recentMissed.isNotEmpty()) {
                        val inboxStyle = NotificationCompat.InboxStyle()
                            .setBigContentTitle(title)
                            .setSummaryText(if (newCount > 5) "+${newCount - 5} more missed calls" else "Tap to view call log")
                        for (rec in recentMissed) {
                            val formattedRecNum = PhoneNumberHelper.formatForDisplay(rec.remoteNumber)
                            val recHasName = rec.displayName.isNotBlank() &&
                                !PhoneNumberHelper.looksLikePhoneNumber(rec.displayName) &&
                                !PhoneNumberHelper.areSameNumber(rec.displayName, rec.remoteNumber)
                            val who = if (recHasName) {
                                "${rec.displayName} ($formattedRecNum)"
                            } else {
                                formattedRecNum
                            }
                            inboxStyle.addLine("$who • ${rec.formattedTime}")
                        }
                        builder.setStyle(inboxStyle)
                    }
                } catch (_: Exception) {}
            }

            val notification = builder.build()
            BadgeManager.applyXiaomiBadge(notification, newCount)

            notificationManager.notify(NOTIFICATION_ID_MISSED, notification)
            BadgeManager.setBadge(appCtx, newCount)

            Log.i(TAG, "Posted Missed Call Notification for $cleanName ($canonicalNumber), total missed: $newCount")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post missed call notification: ${e.message}")
        }
    }

    @Synchronized
    fun clearMissedCalls(context: Context) {
        try {
            val appCtx = context.applicationContext
            getSharedCounterPrefs(appCtx).edit()
                .putInt(KEY_MISSED_COUNT, 0)
                .remove(LEGACY_KEY_LAST_CALLER_NUM)
                .remove(LEGACY_KEY_LAST_CALLER_NAME)
                .apply()

            val notificationManager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID_MISSED)

            BadgeManager.clearBadge(appCtx)
            Log.d(TAG, "Missed calls cleared, badge counter reset to 0.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear missed call notification: ${e.message}")
        }
    }

    private fun getActiveChannelId(context: Context): String {
        val ver = com.opentalk.android.storage.SoundSettingsManager.getNotificationChannelVersion(context)
        return "${CHANNEL_MISSED_CALLS}_v$ver"
    }

    @Synchronized
    fun refreshNotificationSoundChannel(context: Context) {
        var deletedOldChannel = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val currentId = getActiveChannelId(context)
                for (ch in manager.notificationChannels) {
                    if (ch.id != currentId && ch.id.startsWith(CHANNEL_MISSED_CALLS)) {
                        try {
                            manager.deleteNotificationChannel(ch.id)
                            deletedOldChannel = true
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
        // Deleting an old channel cancels any active shade notification; reset counter BEFORE creating new channel
        if (deletedOldChannel && getMissedCount(context) > 0) {
            clearMissedCalls(context)
        }
        createChannel(context)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val activeId = getActiveChannelId(context)
                // Remove legacy unversioned dead channel if present
                try {
                    if (activeId != CHANNEL_MISSED_CALLS && manager.getNotificationChannel(CHANNEL_MISSED_CALLS) != null) {
                        manager.deleteNotificationChannel(CHANNEL_MISSED_CALLS)
                    }
                } catch (_: Exception) {}

                // Fast-path: Android 8+ ignores sound changes on existing channels; avoid Main-Thread ContentProvider IPC on cold start!
                if (manager.getNotificationChannel(activeId) != null) return

                val soundUri = com.opentalk.android.storage.SoundSettingsManager.getNotificationSoundUriOrNull(context)
                val channel = NotificationChannel(
                    activeId,
                    "Missed Calls",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications and app icon badge counts for missed audio calls"
                    setShowBadge(true)
                    enableLights(true)
                    lightColor = 0xFFFF3B30.toInt()
                    enableVibration(true)
                    if (soundUri != null) {
                        val attrs = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        setSound(soundUri, attrs)
                    } else {
                        setSound(null, null)
                    }
                }
                try {
                    manager.createNotificationChannel(channel)
                } catch (soundErr: Exception) {
                    Log.w(TAG, "createNotificationChannel with custom sound failed (${soundErr.message}); retrying with default sound.")
                    val fallback = NotificationChannel(
                        activeId,
                        "Missed Calls",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Notifications and app icon badge counts for missed audio calls"
                        setShowBadge(true)
                        enableLights(true)
                        lightColor = 0xFFFF3B30.toInt()
                        enableVibration(true)
                    }
                    manager.createNotificationChannel(fallback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "createChannel failed safely: ${e.message}")
            }
        }
    }
}

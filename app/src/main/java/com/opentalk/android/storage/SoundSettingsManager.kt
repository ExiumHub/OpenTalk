package com.opentalk.android.storage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.UserManagerCompat

/**
 * Ringtone, Notification Tone & Vibration Settings Manager for OpenTalk.
 * Uses Device-Protected (DE) storage as the single source of truth so Direct Boot
 * and Unlocked states never suffer CE/DE split-brain.
 */
object SoundSettingsManager {
    private const val PREFS_NAME = "dial_sound_settings_prefs"
    private const val KEY_CE_MIGRATED = "_ce_migrated_to_de_v1"
    private const val KEY_CALL_RINGTONE_URI = "call_ringtone_uri"
    private const val KEY_CALL_RINGTONE_NAME = "call_ringtone_name"
    private const val KEY_NOTIF_SOUND_URI = "notif_sound_uri"
    private const val KEY_NOTIF_SOUND_NAME = "notif_sound_name"
    private const val KEY_VIBRATE_ON_CALL = "vibrate_on_call"
    private const val KEY_LOW_DATA_MODE = "low_data_weak_network_mode"
    private const val KEY_MISSED_CALL_NOTIF = "missed_call_notifications_enabled"
    private const val KEY_DARK_MODE = "dark_mode_enabled"
    private const val KEY_NOTIF_CHANNEL_VER = "missed_notif_channel_ver"
    private const val SILENT_URI_SENTINEL = "SILENT"

    private val prefsLock = Any()

    @Volatile
    private var migratedChecked = false

    private fun getStorageContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext
        val deCtx = getStorageContext(appCtx)
        val dePrefs = deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!migratedChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && UserManagerCompat.isUserUnlocked(appCtx)) {
            synchronized(prefsLock) {
                if (!migratedChecked && UserManagerCompat.isUserUnlocked(appCtx)) {
                    try {
                        if (!dePrefs.getBoolean(KEY_CE_MIGRATED, false)) {
                            val cePrefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            val all = cePrefs.all
                            if (all.isNotEmpty()) {
                                val editor = dePrefs.edit()
                                for ((k, v) in all) {
                                    // Preserve any key already written to DE while locked
                                    if (!dePrefs.contains(k)) {
                                        when (v) {
                                            is Boolean -> editor.putBoolean(k, v)
                                            is Int -> editor.putInt(k, v)
                                            is Long -> editor.putLong(k, v)
                                            is Float -> editor.putFloat(k, v)
                                            is String -> editor.putString(k, v)
                                        }
                                    }
                                }
                                editor.putBoolean(KEY_CE_MIGRATED, true).apply()
                            } else {
                                dePrefs.edit().putBoolean(KEY_CE_MIGRATED, true).apply()
                            }
                        }
                        migratedChecked = true
                    } catch (_: Exception) {
                        // Retry on next unlocked access
                    }
                }
            }
        }
        return dePrefs
    }

    private fun isUriAccessible(context: Context, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "android.resource") return true
        // External MediaStore / SAF URIs are unavailable in Direct Boot; fall back safely
        if (!UserManagerCompat.isUserUnlocked(context) &&
            (uri.authority?.contains("media") == true || uri.authority?.contains("documents") == true)
        ) {
            return false
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            // System settings ringtone URIs (content://settings/system/ringtone) may not open directly via openAssetFileDescriptor
            uri.authority == "settings"
        }
    }

    private fun resolveToneUri(context: Context, rawUri: String?, defaultType: Int): Uri? {
        if (rawUri == SILENT_URI_SENTINEL) return null
        val fallback = RingtoneManager.getDefaultUri(defaultType)
        if (rawUri.isNullOrBlank()) return fallback
        return try {
            val parsed = Uri.parse(rawUri)
            if (isUriAccessible(context, parsed)) parsed else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun persistUriPermissionQuietly(context: Context, uri: Uri?) {
        if (uri == null) return
        try {
            context.applicationContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
    }

    fun getCallRingtoneUriOrNull(context: Context): Uri? {
        val uriStr = getPrefs(context).getString(KEY_CALL_RINGTONE_URI, null)
        return resolveToneUri(context, uriStr, RingtoneManager.TYPE_RINGTONE)
    }

    fun getCallRingtoneUri(context: Context): Uri? {
        return getCallRingtoneUriOrNull(context)
    }

    fun getCallRingtoneName(context: Context): String {
        val prefs = getPrefs(context)
        val rawUri = prefs.getString(KEY_CALL_RINGTONE_URI, null)
        if (rawUri == SILENT_URI_SENTINEL) return "Silent"
        val storedName = prefs.getString(KEY_CALL_RINGTONE_NAME, null)
        if (!rawUri.isNullOrBlank() && !storedName.isNullOrBlank()) {
            val stillAccessible = try {
                isUriAccessible(context, Uri.parse(rawUri))
            } catch (_: Exception) {
                false
            }
            if (stillAccessible) return storedName
        }
        val uri = getCallRingtoneUriOrNull(context) ?: return "Silent"
        return try {
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.getTitle(context) ?: "Default Ringtone"
        } catch (_: Exception) {
            "Default Ringtone"
        }
    }

    fun setCallRingtone(context: Context, uri: Uri?, name: String) {
        persistUriPermissionQuietly(context, uri)
        getPrefs(context).edit()
            .putString(KEY_CALL_RINGTONE_URI, uri?.toString() ?: SILENT_URI_SENTINEL)
            .putString(KEY_CALL_RINGTONE_NAME, if (uri == null) "Silent" else name)
            .apply()
    }

    fun getNotificationSoundUriOrNull(context: Context): Uri? {
        val uriStr = getPrefs(context).getString(KEY_NOTIF_SOUND_URI, null)
        return resolveToneUri(context, uriStr, RingtoneManager.TYPE_NOTIFICATION)
    }

    fun getNotificationSoundUri(context: Context): Uri? {
        return getNotificationSoundUriOrNull(context)
    }

    fun getNotificationSoundName(context: Context): String {
        val prefs = getPrefs(context)
        val rawUri = prefs.getString(KEY_NOTIF_SOUND_URI, null)
        if (rawUri == SILENT_URI_SENTINEL) return "Silent"
        val storedName = prefs.getString(KEY_NOTIF_SOUND_NAME, null)
        if (!rawUri.isNullOrBlank() && !storedName.isNullOrBlank()) {
            val stillAccessible = try {
                isUriAccessible(context, Uri.parse(rawUri))
            } catch (_: Exception) {
                false
            }
            if (stillAccessible) return storedName
        }
        val uri = getNotificationSoundUriOrNull(context) ?: return "Silent"
        return try {
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.getTitle(context) ?: "Default Notification"
        } catch (_: Exception) {
            "Default Notification"
        }
    }

    fun setNotificationSound(context: Context, uri: Uri?, name: String) {
        persistUriPermissionQuietly(context, uri)
        val prefs = getPrefs(context)
        val nextVer = prefs.getInt(KEY_NOTIF_CHANNEL_VER, 1) + 1
        prefs.edit()
            .putString(KEY_NOTIF_SOUND_URI, uri?.toString() ?: SILENT_URI_SENTINEL)
            .putString(KEY_NOTIF_SOUND_NAME, if (uri == null) "Silent" else name)
            .putInt(KEY_NOTIF_CHANNEL_VER, nextVer)
            .apply()
    }

    fun getNotificationChannelVersion(context: Context): Int {
        return getPrefs(context).getInt(KEY_NOTIF_CHANNEL_VER, 1)
    }

    fun isVibrateOnCallEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VIBRATE_ON_CALL, true)
    }

    fun setVibrateOnCall(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VIBRATE_ON_CALL, enabled).apply()
    }

    fun isLowDataModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LOW_DATA_MODE, false)
    }

    fun setLowDataMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LOW_DATA_MODE, enabled).apply()
    }

    fun isMissedCallNotificationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MISSED_CALL_NOTIF, true)
    }

    fun setMissedCallNotificationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MISSED_CALL_NOTIF, enabled).apply()
    }

    fun hasExplicitThemePreference(context: Context): Boolean {
        return getPrefs(context).contains(KEY_DARK_MODE)
    }

    fun isDarkModeEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_DARK_MODE)) {
            return prefs.getBoolean(KEY_DARK_MODE, false)
        }
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    fun setDarkModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DARK_MODE, enabled).apply()
        applySavedTheme(context)
    }

    fun applySavedTheme(context: Context) {
        val prefs = getPrefs(context)
        val targetMode = if (prefs.contains(KEY_DARK_MODE)) {
            if (prefs.getBoolean(KEY_DARK_MODE, false)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        } else {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }
    }
}

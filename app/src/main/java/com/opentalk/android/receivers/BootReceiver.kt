package com.opentalk.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.os.UserManagerCompat
import com.opentalk.android.services.SipVoipService
import com.opentalk.android.storage.SecureAccountManager
import com.opentalk.android.sync.CdrSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Direct Boot & Power-On Receiver.
 * Automatically restarts SipVoipService in Standby mode upon phone boot (even before screen unlock),
 * guaranteeing 24/7 background availability for incoming calls without requiring the user
 * to manually launch the app or unlock the device.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isUnlocked = UserManagerCompat.isUserUnlocked(context)
        Log.i(TAG, "BootReceiver triggered with action: $action (userUnlocked=$isUnlocked)")

        val isQuickBootAction = action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (isQuickBootAction) {
            // Unprotected OEM quick-boot actions can be spoofed by third-party apps post-boot;
            // only accept them near device startup and when the standby service isn't already live.
            if (SystemClock.elapsedRealtime() >= 180_000L || SipVoipService.isRunning) {
                Log.w(TAG, "Ignoring late/duplicate $action (uptime=${SystemClock.elapsedRealtime()}ms, running=${SipVoipService.isRunning})")
                return
            }
        }

        val isBootAction = action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            isQuickBootAction ||
            action == "android.intent.action.REBOOT"

        if (isBootAction) {
            // Only auto-start standby service & alarms when a SIP account is actually configured!
            if (!SecureAccountManager.hasConfiguredAccount(context)) {
                Log.i(TAG, "BootReceiver ($action): No SIP account configured; skipping standby startup.")
                SipVoipService.cancelWatchdog(context)
                return
            }

            Log.i(TAG, "Boot auto-start triggered ($action). Starting SipVoipService synchronously on main thread...")
            // CRITICAL: Must be called SYNCHRONOUSLY on the main thread directly inside onReceive()
            // to strictly adhere to Android's BOOT_COMPLETED foreground service start exemption window.
            try {
                SipVoipService.startStandby(context)
                SipVoipService.scheduleImmediateWatchdog(context, 1500L)
                SipVoipService.rescheduleWatchdog(context)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start SipVoipService synchronously on boot: ${e.message}")
            }

            // Asynchronous background operations (e.g. CDR sync upon user unlock)
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val accountManager = SecureAccountManager(context)
                    val account = accountManager.getAccount()
                    Log.i(TAG, "Boot verified account: ${account.username}@${account.server} (unlocked=$isUnlocked)")

                    // If user just unlocked the device after reboot, trigger offline CDR sync
                    if (action == Intent.ACTION_BOOT_COMPLETED && isUnlocked) {
                        Log.i(TAG, "User unlocked device after boot. Triggering offline CDR sync...")
                        CdrSyncManager.triggerSync(context)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Error in BootReceiver async tasks: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

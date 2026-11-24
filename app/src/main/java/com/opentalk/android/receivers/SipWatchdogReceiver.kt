package com.opentalk.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.opentalk.android.engine.SipEngine
import com.opentalk.android.notifications.MissedCallNotifier
import com.opentalk.android.services.SipVoipService
import com.opentalk.android.storage.SecureAccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 24/7 Hardware Watchdog Alarm Receiver & Internal Missed-Call Dismiss Handler.
 */
class SipWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SipWatchdogReceiver"
        const val ACTION_WATCHDOG_TICK = "com.opentalk.android.action.SIP_WATCHDOG_TICK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        // Handle missed-call notification swipe-dismiss
        if (action == MissedCallNotifier.ACTION_DISMISS_MISSED) {
            MissedCallNotifier.clearMissedCalls(context)
            return
        }

        if (action != ACTION_WATCHDOG_TICK) return

        // Fast Direct-Boot-safe check BEFORE starting standby service so logged-out devices never spin up!
        if (!SecureAccountManager.hasConfiguredAccount(context)) {
            Log.d(TAG, "Watchdog tick: no SIP account configured; cancelling alarms.")
            SipVoipService.cancelWatchdog(context)
            return
        }

        // Start standby service synchronously inside onReceive() if not running (respects Android 12+ FGS window)
        if (!SipVoipService.isRunning) {
            try {
                SipVoipService.startStandby(context)
            } catch (e: Exception) {
                Log.w(TAG, "Watchdog sync startStandby deferred: ${e.message}")
            }
        }

        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenTalk:WatchdogWakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(6_000)
        }

        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            // Default true because line 39 already verified a configured account exists;
            // a transient KeyStore/IO exception must never permanently kill the 24/7 watchdog!
            var shouldKeepWatchdog = true
            try {
                val accountManager = SecureAccountManager(appCtx)
                val account = accountManager.getAccount()
                val hasValidAccount = account.username.isNotBlank() && account.server.isNotBlank()
                shouldKeepWatchdog = hasValidAccount
                if (hasValidAccount) {
                    // Await actual UDP keepalive / reconnect check BEFORE releasing wakeLock & pendingResult!
                    withTimeoutOrNull(4_500L) {
                        SipEngine.onWatchdogPingSuspend(appCtx)
                    }
                    com.opentalk.android.sync.CdrSyncManager.maybeTriggerPeriodicSync(appCtx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog tick error (preserving watchdog schedule): ${e.message}")
            } finally {
                // Only cancel hardware alarm when account lookup succeeded and confirmed logged out
                if (shouldKeepWatchdog) {
                    SipVoipService.rescheduleWatchdog(appCtx)
                } else {
                    SipVoipService.cancelWatchdog(appCtx)
                }
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (_: Exception) {}
                pendingResult.finish()
            }
        }
    }
}

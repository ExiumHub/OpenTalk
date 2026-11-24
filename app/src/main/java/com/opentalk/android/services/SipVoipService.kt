package com.opentalk.android.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.opentalk.android.CallActivity
import com.opentalk.android.IncomingCallActivity
import com.opentalk.android.MainActivity
import com.opentalk.android.R
import com.opentalk.android.engine.CallState
import com.opentalk.android.engine.RegistrationStatus
import com.opentalk.android.engine.SipEngine
import com.opentalk.android.storage.SecureAccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 24/7 Persistent Background VoIP Foreground Service.
 *
 * Keeps UDP socket listeners alive, handles mobile NAT keepalives, monitors network handoffs
 * (Wi-Fi <-> Cellular), and triggers FullScreenIntent notifications for incoming calls.
 */
class SipVoipService : Service() {

    companion object {
        private const val TAG = "SipVoipService"

        // Notification Channels
        const val CHANNEL_STANDBY = "sip_standby_channel_v2"
        const val CHANNEL_INCOMING = "sip_incoming_channel"
        const val CHANNEL_CALL = "sip_call_channel"

        // Notification IDs
        const val NOTIFICATION_ID_STANDBY = 1001
        const val NOTIFICATION_ID_CALL = 1002
        const val NOTIFICATION_ID_INCOMING = 1003

        // Actions
        const val ACTION_START_STANDBY = "com.opentalk.android.action.START_STANDBY"
        const val ACTION_START_CALL = "com.opentalk.android.action.START_CALL"
        const val ACTION_CALL_ENDED = "com.opentalk.android.action.CALL_ENDED"
        const val ACTION_STOP_SERVICE = "com.opentalk.android.action.STOP_SERVICE"
        const val ACTION_SHOW_INCOMING_CALL = "com.opentalk.android.action.SHOW_INCOMING_CALL"
        const val ACTION_SHOW_SILENT_INCOMING_CALL = "com.opentalk.android.action.SHOW_SILENT_INCOMING_CALL"
        const val ACTION_SILENCE_INCOMING_NOTIFICATION = "com.opentalk.android.action.SILENCE_INCOMING_NOTIFICATION"
        const val ACTION_DISMISS_INCOMING_CALL = "com.opentalk.android.action.DISMISS_INCOMING_CALL"
        const val ACTION_ANSWER_CALL = "com.opentalk.android.action.ANSWER_CALL"
        const val ACTION_DECLINE_CALL = "com.opentalk.android.action.DECLINE_CALL"
        const val ACTION_HANG_UP_ACTIVE_CALL = "com.opentalk.android.action.HANG_UP_ACTIVE_CALL"

        @Volatile
        var isRunning: Boolean = false

        // Static Client Helpers
        fun startStandby(context: Context) {
            val intent = Intent(context, SipVoipService::class.java).apply {
                action = ACTION_START_STANDBY
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start standby service: ${e.message}")
            }
        }

        fun startCall(context: Context, number: String, name: String) {
            val intent = Intent(context, SipVoipService::class.java).apply {
                action = ACTION_START_CALL
                putExtra("number", number)
                putExtra("name", name)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start call service: ${e.message}")
            }
        }

        fun callEnded(context: Context) {
            val intent = Intent(context, SipVoipService::class.java).apply {
                action = ACTION_CALL_ENDED
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun stopCall(context: Context) {
            // Legacy alias - reverts to standby instead of killing service
            callEnded(context)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SipVoipService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun showIncomingCall(context: Context, number: String, name: String, isTest: Boolean = false) {
            val intent = Intent(context, SipVoipService::class.java).apply {
                action = ACTION_SHOW_INCOMING_CALL
                putExtra("number", number)
                putExtra("name", name)
                putExtra("is_test", isTest)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show incoming call notification: ${e.message}")
            }
        }

        fun showSilentIncomingNotification(context: Context, number: String, name: String, isTest: Boolean = false) {
            val intent = Intent(context, SipVoipService::class.java).apply {
                action = ACTION_SHOW_SILENT_INCOMING_CALL
                putExtra("number", number)
                putExtra("name", name)
                putExtra("is_test", isTest)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show silent minimized incoming notification: ${e.message}")
            }
        }

        fun silenceIncomingNotification(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID_INCOMING)
            } catch (_: Exception) {}
            val intent = Intent(context, SipVoipService::class.java).apply {
                action = ACTION_SILENCE_INCOMING_NOTIFICATION
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun dismissIncomingCall(context: Context) {
            // Immediately & synchronously cancel the incoming notification on the calling thread
            // so notification ringtone/vibration stops with ZERO service-queue delay!
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID_INCOMING)
            } catch (_: Exception) {}

            val intent = Intent(context, SipVoipService::class.java).apply {
                action = ACTION_DISMISS_INCOMING_CALL
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        const val WATCHDOG_INTERVAL_MS = 28_000L // 28 seconds hardware wake interval (BD Mobile NAT Pin-hole Friendly)

        private const val WATCHDOG_IMMEDIATE_REQ_CODE = 887
        private const val WATCHDOG_PERIODIC_REQ_CODE = 888

        private fun canUseExactAlarms(alarmManager: AlarmManager): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    alarmManager.canScheduleExactAlarms()
                } catch (_: Exception) {
                    false
                }
            } else {
                true
            }
        }

        fun scheduleImmediateWatchdog(context: Context, delayMs: Long = 1500L) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, com.opentalk.android.receivers.SipWatchdogReceiver::class.java).apply {
                    action = com.opentalk.android.receivers.SipWatchdogReceiver.ACTION_WATCHDOG_TICK
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    WATCHDOG_IMMEDIATE_REQ_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = SystemClock.elapsedRealtime() + delayMs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (canUseExactAlarms(alarmManager)) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                }
                Log.d(TAG, "Immediate watchdog alarm armed for +${delayMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule immediate watchdog: ${e.message}")
            }
        }

        fun rescheduleWatchdog(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, com.opentalk.android.receivers.SipWatchdogReceiver::class.java).apply {
                    action = com.opentalk.android.receivers.SipWatchdogReceiver.ACTION_WATCHDOG_TICK
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    WATCHDOG_PERIODIC_REQ_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (canUseExactAlarms(alarmManager)) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule watchdog: ${e.message}")
            }
        }

        fun cancelWatchdog(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                for (reqCode in intArrayOf(WATCHDOG_IMMEDIATE_REQ_CODE, WATCHDOG_PERIODIC_REQ_CODE)) {
                    val intent = Intent(context, com.opentalk.android.receivers.SipWatchdogReceiver::class.java).apply {
                        action = com.opentalk.android.receivers.SipWatchdogReceiver.ACTION_WATCHDOG_TICK
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        reqCode,
                        intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pendingIntent != null) {
                        alarmManager.cancel(pendingIntent)
                        pendingIntent.cancel()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeLockGuard = Any()
    private val networkStateLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var incomingWakeLock: PowerManager.WakeLock? = null
    private var incomingNotificationTimeoutJob: Job? = null
    private var warmupJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var physicalNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastActiveNetwork: Network? = null
    @Volatile private var lastKnownLocalIp: String? = null
    @Volatile private var lastPhysicalSignature: String? = null

    @Volatile private var isCallActive = false
    @Volatile private var currentActiveNumber = ""
    @Volatile private var currentActiveName = ""

    // Stored listener refs so cleanup() removes them cleanly (zero Service Context leak!)
    private var statusListenerRef: ((RegistrationStatus, String) -> Unit)? = null
    private var incomingCancelledListenerRef: (() -> Unit)? = null
    private var callStateListenerRef: ((CallState, String) -> Unit)? = null

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Screen wake/unlock detected. Ensuring 24/7 VoIP registration is Ready...")
                    acquireLocks()
                    val account = SecureAccountManager(this@SipVoipService).getAccount()
                    if (account.username.isNotBlank() && account.server.isNotBlank()) {
                        if (!SipEngine.isRegistered() && SipEngine.currentStatus != RegistrationStatus.REGISTERING) {
                            SipEngine.triggerRegister(account)
                        } else {
                            SipEngine.onWatchdogPing(this@SipVoipService)
                        }
                    }
                    if (!isCallActive && !SipEngine.isInCall()) {
                        updateStandbyNotification(SipEngine.currentStatus, "")
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF detected: Reinforcing background VoIP wake/wifi locks & sending keepalive...")
                    acquireLocks()
                    SipEngine.sendKeepAlivePing(forceOptions = false)
                    rescheduleWatchdog(this@SipVoipService)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "SipVoipService onCreate()")
        createNotificationChannels()
        showStandbyNotification()
        SipEngine.initialize(applicationContext)
        acquireLocks()
        setupNetworkMonitoring()
        setupSipListeners()
        rescheduleWatchdog(this)

        val screenFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_STANDBY
        Log.d(TAG, "onStartCommand action=$action")
        rescheduleWatchdog(this)

        when (action) {
            ACTION_START_STANDBY -> {
                acquireLocks()
                // Never downgrade an active call's microphone FGS to standby mid-call!
                if (SipEngine.isInCall() || isCallActive) {
                    val activeNum = SipEngine.activeCallNumber ?: currentActiveNumber.ifBlank { "Unknown" }
                    val activeName = SipEngine.activeCallName ?: currentActiveName.ifBlank { activeNum }
                    showCallNotification(activeName, activeNum)
                    return START_STICKY
                }
                isCallActive = false
                // Always force startForeground() on ACTION_START_STANDBY so a rapid stop->start
                // never skips startForeground() and triggers ForegroundServiceDidNotStartInTimeException!
                showStandbyNotification(force = true)
                ensureRegistered()

                // Cancel any previous warmup retry loop before starting a fresh one
                warmupJob?.cancel()
                warmupJob = serviceScope.launch {
                    val intervals = listOf(
                        1000L, 2000L, 3500L, 5000L, 8000L, 12000L, 18000L, 25000L, 35000L, 50000L, 70000L, 90000L
                    )
                    for (delayMs in intervals) {
                        delay(delayMs)
                        if (SipEngine.isRegistered()) {
                            Log.i(TAG, "Warmup registration successful! Active standby ready.")
                            break
                        }
                        if (SipEngine.currentStatus != RegistrationStatus.REGISTERING) {
                            if (SipEngine.isNetworkConnected(this@SipVoipService)) {
                                Log.i(TAG, "Boot registration check (${delayMs / 1000}s) with active internet. Registering...")
                                ensureRegistered()
                            }
                        }
                    }
                }
            }
            ACTION_START_CALL -> {
                isCallActive = true
                isIncomingNotifForeground = false
                lastStandbySignature = ""
                currentActiveNumber = intent?.getStringExtra("number") ?: "Unknown"
                currentActiveName = intent?.getStringExtra("name") ?: currentActiveNumber
                showCallNotification(currentActiveName, currentActiveNumber)
            }
            ACTION_CALL_ENDED -> {
                // Guard against stale queued ACTION_CALL_ENDED from a previous call clobbering a newly started call!
                if (SipEngine.isInCall()) {
                    Log.w(TAG, "Ignoring stale ACTION_CALL_ENDED because a live call is currently active (${SipEngine.currentCallState}).")
                    if (!SipEngine.hasActiveIncomingCall()) {
                        isCallActive = true
                        currentActiveNumber = SipEngine.activeCallNumber ?: currentActiveNumber
                        currentActiveName = SipEngine.activeCallName ?: currentActiveNumber
                        showCallNotification(currentActiveName, currentActiveNumber)
                    }
                    return START_STICKY
                }
                isCallActive = false
                com.opentalk.android.engine.CallProximityManager.forceRelease("SipVoipService ACTION_CALL_ENDED")
                dismissIncomingNotification()
                com.opentalk.android.engine.AudioRouteManager.resetToNormal(this)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID_CALL)
                showStandbyNotification(force = true)
            }
            ACTION_SHOW_INCOMING_CALL -> {
                val number = intent?.getStringExtra("number") ?: "Unknown"
                val name = intent?.getStringExtra("name") ?: number
                val isTest = intent?.getBooleanExtra("is_test", false) ?: false
                postIncomingCallNotification(number, name, isTest, silentMinimized = false)
            }
            ACTION_SHOW_SILENT_INCOMING_CALL -> {
                val number = intent?.getStringExtra("number") ?: SipEngine.activeCallNumber ?: "Unknown"
                val name = intent?.getStringExtra("name") ?: SipEngine.activeCallName ?: number
                val isTest = intent?.getBooleanExtra("is_test", false) ?: false
                postIncomingCallNotification(number, name, isTest, silentMinimized = true)
            }
            ACTION_SILENCE_INCOMING_NOTIFICATION -> {
                // Full-screen IncomingCallActivity is visibly ringing on screen -> hand foreground back to standby
                // and remove NOTIFICATION_ID_INCOMING so there is ZERO double ringtone!
                if (!isCallActive) {
                    showStandbyNotification(force = true)
                }
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.cancel(NOTIFICATION_ID_INCOMING)
                } catch (_: Exception) {}
            }
            ACTION_DISMISS_INCOMING_CALL -> {
                if (!SipEngine.hasActiveIncomingCall()) {
                    dismissIncomingNotification()
                }
            }
            ACTION_ANSWER_CALL -> {
                val number = intent?.getStringExtra("number") ?: "Unknown"
                val name = intent?.getStringExtra("name") ?: number
                dismissIncomingNotification()
                IncomingCallActivity.stopRingtoneOnly()
                serviceScope.launch {
                    if (!SipEngine.hasActiveIncomingCall() && !SipEngine.isAnsweringIncoming() &&
                        SipEngine.currentCallState != CallState.CONNECTED) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(applicationContext, "Call ended by caller", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        IncomingCallActivity.stopRingingAndDismissImmediately()
                        return@launch
                    }
                    val connected = SipEngine.answerIncomingCall()
                    if (connected && SipEngine.currentCallState == CallState.CONNECTED) {
                        val callIntent = Intent(this@SipVoipService, CallActivity::class.java).apply {
                            putExtra("number", number)
                            putExtra("name", name)
                            putExtra("is_incoming", true)
                            putExtra("auto_answer", false)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(callIntent)
                    } else {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(applicationContext, "Call ended by caller", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        IncomingCallActivity.stopRingingAndDismissImmediately()
                    }
                }
            }
            ACTION_DECLINE_CALL -> {
                dismissIncomingNotification()
                SipEngine.declineIncomingCall()
            }
            ACTION_HANG_UP_ACTIVE_CALL -> {
                Log.i(TAG, "Notification Hang Up action triggered. Ending call...")
                com.opentalk.android.engine.CallProximityManager.forceRelease("SipVoipService ACTION_HANG_UP_ACTIVE_CALL")
                SipEngine.hangUp()
            }
            ACTION_STOP_SERVICE -> {
                cancelWatchdog(this)
                lastStandbySignature = ""
                isIncomingNotifForeground = false
                isCallActive = false
                com.opentalk.android.engine.CallProximityManager.forceRelease("SipVoipService ACTION_STOP_SERVICE")
                dismissIncomingNotification()
                releaseLocks()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // START_STICKY ensures Android restarts this VoIP service if killed under memory pressure
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "App swiped from Recents.")

        if (SipEngine.isInCall() || isCallActive) {
            Log.i(TAG, "Active call in progress while app was swiped from Recents! Maintaining active call in foreground...")
            showCallNotification(currentActiveName, currentActiveNumber)
            return
        }

        if (SecureAccountManager.hasConfiguredAccount(this)) {
            Log.d(TAG, "Maintaining 24/7 background standby via foreground service + broadcast watchdog...")
            // Never schedule PendingIntent.getForegroundService() here (crashes with
            // ForegroundServiceStartNotAllowedException on Android 12-15); stopWithTask="false" +
            // START_STICKY + SipWatchdogReceiver broadcast alarm maintain persistence safely.
            rescheduleWatchdog(this)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "SipVoipService onDestroy()")
        isRunning = false
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        // Do NOT cancel watchdog in onDestroy() so OS low-memory kills can self-heal via SipWatchdogReceiver
        // (explicit logout already cancels the watchdog in ACTION_STOP_SERVICE).
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================================
    // LOCKS & PERSISTENCE
    // =========================================================================

    private fun acquireLocks() {
        synchronized(wakeLockGuard) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                val existingWake = wakeLock
                if (existingWake == null) {
                    wakeLock = powerManager?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "OpenTalk:VoipStandbyWakeLock"
                    )?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                } else if (!existingWake.isHeld) {
                    existingWake.acquire()
                }

                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val existingWifi = wifiLock
                if (existingWifi == null) {
                    val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wifiManager?.createWifiLock(
                        lockMode,
                        "OpenTalk:VoipStandbyWifiLock"
                    )?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                } else if (!existingWifi.isHeld) {
                    existingWifi.acquire()
                }
                Unit
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring wake/wifi locks: ${e.message}")
            }
        }
    }

    private fun releaseLocks() {
        synchronized(wakeLockGuard) {
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                wifiLock?.let { if (it.isHeld) it.release() }
                wifiLock = null
                incomingWakeLock?.let { if (it.isHeld) it.release() }
                incomingWakeLock = null
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing locks: ${e.message}")
            }
        }
    }

    // =========================================================================
    // NETWORK HANDOFF MONITORING (Wi-Fi <-> Mobile Data)
    // =========================================================================

    private var networkDebounceJob: Job? = null
    private var connectivityReceiver: BroadcastReceiver? = null
    @Volatile private var isCurrentlyOnline: Boolean = true

    private fun setupNetworkMonitoring() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

            isCurrentlyOnline = SipEngine.isNetworkConnected(this)
            if (!isCurrentlyOnline) {
                Log.w(TAG, "Initial check: Device is OFFLINE")
                SipEngine.onNetworkLost()
                updateStandbyNotification(RegistrationStatus.FAILED, "No Internet Connection")
            } else {
                Log.i(TAG, "Initial check: Device is ONLINE. Ensuring registered...")
                ensureRegistered()
            }

            lastActiveNetwork = connectivityManager?.activeNetwork
            lastKnownLocalIp = SipEngine.getNetworkFingerprint(this)
            lastPhysicalSignature = SipEngine.getActivePhysicalNetworkSignature(this)
            var previousNetwork: Network? = null
            var pendingHandover = false

            fun isValidFingerprint(sig: String?): Boolean {
                return SipEngine.isValidNetworkFingerprint(sig)
            }

            fun scheduleUnifiedRecovery(
                delayMs: Long,
                forceHandover: Boolean,
                reason: String
            ) {
                synchronized(networkStateLock) {
                    if (forceHandover) pendingHandover = true
                    networkDebounceJob?.cancel()
                    networkDebounceJob = serviceScope.launch {
                        delay(delayMs)
                        if (!SipEngine.isNetworkConnected(this@SipVoipService)) {
                            // Wait up to 1.8s (4 x 450ms) in case Mobile Data / VPN route takes an extra moment to come online
                            var becameOnline = false
                            for (retry in 1..4) {
                                delay(450L)
                                if (SipEngine.isNetworkConnected(this@SipVoipService)) {
                                    becameOnline = true
                                    break
                                }
                            }
                            if (!becameOnline) {
                                Log.d(TAG, "Deferred recovery ($reason) skipped: no underlying internet yet.")
                                return@launch
                            }
                        }
                        val freshSig = SipEngine.getNetworkFingerprint(this@SipVoipService)
                        val freshPhy = SipEngine.getActivePhysicalNetworkSignature(this@SipVoipService)
                        val doHandover = synchronized(networkStateLock) {
                            val h = pendingHandover || forceHandover || SipEngine.isInCall()
                            pendingHandover = false
                            if (isValidFingerprint(freshSig)) lastKnownLocalIp = freshSig
                            if (freshPhy != "none") lastPhysicalSignature = freshPhy
                            isCurrentlyOnline = true
                            h
                        }
                        Log.i(TAG, "Executing network recovery ($reason, handover=$doHandover, sig=$freshSig)")
                        if (doHandover) {
                            SipEngine.onNetworkHandover(this@SipVoipService)
                        } else {
                            SipEngine.onNetworkAvailable(this@SipVoipService)
                        }
                    }
                }
            }

            fun scheduleLossGraceMonitor(lostNetwork: Network?, reason: String) {
                if (SipEngine.isInCall()) {
                    SipEngine.onCallNetworkInterrupted()
                }
                synchronized(networkStateLock) {
                    pendingHandover = true
                    networkDebounceJob?.cancel()
                    networkDebounceJob = serviceScope.launch {
                        if (SipEngine.isInCall()) {
                            for (tick in 1..50) {
                                delay(300L)
                                val activeNet = connectivityManager?.activeNetwork
                                val netRecovered = activeNet != null &&
                                    (lostNetwork == null || activeNet != lostNetwork || tick >= 2) &&
                                    SipEngine.isNetworkConnected(this@SipVoipService)
                                if (netRecovered) {
                                    // Extra stabilization if VPN tunnel is active across physical switch
                                    if (SipEngine.isVpnActive(this@SipVoipService)) delay(450L)
                                    Log.i(TAG, "Mid-call network ready ($activeNet, reason=$reason) after ${tick * 300}ms! Triggering seamless call handover...")
                                    val newIp = SipEngine.getNetworkFingerprint(this@SipVoipService)
                                    val newPhy = SipEngine.getActivePhysicalNetworkSignature(this@SipVoipService)
                                    synchronized(networkStateLock) {
                                        lastActiveNetwork = activeNet
                                        if (isValidFingerprint(newIp)) lastKnownLocalIp = newIp
                                        if (newPhy != "none") lastPhysicalSignature = newPhy
                                        isCurrentlyOnline = true
                                        pendingHandover = false
                                    }
                                    SipEngine.onNetworkHandover(this@SipVoipService)
                                    return@launch
                                }
                                if (!SipEngine.isInCall()) return@launch
                            }
                            if (!SipEngine.isNetworkConnected(this@SipVoipService) && isActive) {
                                Log.w(TAG, "Device remained OFFLINE for 15s during active call ($reason). Ending call...")
                                synchronized(networkStateLock) {
                                    isCurrentlyOnline = false
                                    pendingHandover = false
                                }
                                SipEngine.onNetworkLost()
                                if (!isCallActive) {
                                    updateStandbyNotification(RegistrationStatus.FAILED, "No Internet Connection")
                                }
                            }
                        } else {
                            delay(if (SipEngine.isVpnActive(this@SipVoipService)) 850L else 450L)
                            val activeNet = connectivityManager?.activeNetwork
                            if (activeNet != null && SipEngine.isNetworkConnected(this@SipVoipService)) {
                                Log.i(TAG, "Network switched to $activeNet after loss ($reason). Triggering handover...")
                                val newIp = SipEngine.getNetworkFingerprint(this@SipVoipService)
                                val newPhy = SipEngine.getActivePhysicalNetworkSignature(this@SipVoipService)
                                synchronized(networkStateLock) {
                                    lastActiveNetwork = activeNet
                                    if (isValidFingerprint(newIp)) lastKnownLocalIp = newIp
                                    if (newPhy != "none") lastPhysicalSignature = newPhy
                                    isCurrentlyOnline = true
                                    pendingHandover = false
                                }
                                SipEngine.onNetworkHandover(this@SipVoipService)
                            } else {
                                delay(2500L)
                                if (!SipEngine.isNetworkConnected(this@SipVoipService) && isActive) {
                                    Log.w(TAG, "Device confirmed OFFLINE ($reason). Switching app to OFFLINE mode...")
                                    synchronized(networkStateLock) {
                                        isCurrentlyOnline = false
                                        pendingHandover = false
                                    }
                                    SipEngine.onNetworkLost()
                                    if (!isCallActive) {
                                        updateStandbyNotification(RegistrationStatus.FAILED, "No Internet Connection")
                                    }
                                } else if (SipEngine.isNetworkConnected(this@SipVoipService) && isActive) {
                                    scheduleUnifiedRecovery(250L, forceHandover = true, reason = "recovered-after-loss:$reason")
                                }
                            }
                        }
                    }
                }
            }

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val needsHandover: Boolean
                    val currentIp = SipEngine.getNetworkFingerprint(this@SipVoipService)
                    val currentPhy = SipEngine.getActivePhysicalNetworkSignature(this@SipVoipService)
                    synchronized(networkStateLock) {
                        val isHandover = (lastActiveNetwork != null && lastActiveNetwork != network) ||
                            (previousNetwork != null && previousNetwork != network)
                        previousNetwork = null
                        lastActiveNetwork = network
                        val ipChanged = isValidFingerprint(lastKnownLocalIp) && isValidFingerprint(currentIp) && lastKnownLocalIp != currentIp
                        val phyChanged = lastPhysicalSignature != null && currentPhy != "none" && lastPhysicalSignature != currentPhy
                        if (isValidFingerprint(currentIp)) lastKnownLocalIp = currentIp
                        if (currentPhy != "none") lastPhysicalSignature = currentPhy
                        needsHandover = isHandover || ipChanged || phyChanged || pendingHandover
                        if (needsHandover) pendingHandover = true
                        isCurrentlyOnline = true
                        Log.i(TAG, "Network onAvailable: $network (isHandover=$isHandover, ipChanged=$ipChanged, phyChanged=$phyChanged, needsHandover=$needsHandover, netSig=$currentIp)")
                    }
                    val waitMs = when {
                        SipEngine.isInCall() -> 150L
                        SipEngine.isVpnActive(this@SipVoipService) -> 700L
                        needsHandover -> 400L
                        else -> 150L
                    }
                    scheduleUnifiedRecovery(waitMs, forceHandover = needsHandover, reason = "default-onAvailable")
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Network onLost: $network (inCall=${SipEngine.isInCall()})")
                    synchronized(networkStateLock) {
                        previousNetwork = network
                        if (lastActiveNetwork == network) {
                            lastActiveNetwork = null
                        }
                        pendingHandover = true
                    }
                    scheduleLossGraceMonitor(network, "default-onLost")
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    val newSig = SipEngine.getNetworkFingerprint(this@SipVoipService, linkProperties)
                    val changed = synchronized(networkStateLock) {
                        if (isValidFingerprint(newSig) &&
                            (!isValidFingerprint(lastKnownLocalIp) || lastKnownLocalIp != newSig)) {
                            Log.i(TAG, "Link properties changed: network signature changed from $lastKnownLocalIp to $newSig. Triggering handover...")
                            lastKnownLocalIp = newSig
                            pendingHandover = true
                            true
                        } else {
                            false
                        }
                    }
                    if (changed) {
                        val waitMs = when {
                            SipEngine.isInCall() -> 150L
                            SipEngine.isVpnActive(this@SipVoipService) -> 750L
                            else -> 400L
                        }
                        scheduleUnifiedRecovery(waitMs, forceHandover = true, reason = "default-linkChanged")
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        SipEngine.isNetworkConnected(this@SipVoipService)
                    val shouldRecover = synchronized(networkStateLock) {
                        Log.d(TAG, "Network capabilities changed: internet=$hasInternet, pendingHandover=$pendingHandover")
                        if (!hasInternet) {
                            false
                        } else if (!pendingHandover && (!isCurrentlyOnline || SipEngine.currentStatus == RegistrationStatus.UNREGISTERED)) {
                            // Only trigger recovery on offline->online transition or UNREGISTERED
                            isCurrentlyOnline = true
                            true
                        } else {
                            // Never cancel an already pending handover from VPN/physical/default switch!
                            false
                        }
                    }
                    if (!hasInternet) {
                        scheduleLossGraceMonitor(null, "capabilities-no-internet")
                    } else if (shouldRecover) {
                        scheduleUnifiedRecovery(350L, forceHandover = false, reason = "capabilities-online")
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, networkCallback!!)

                @Suppress("DEPRECATION")
                val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
                connectivityReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val online = SipEngine.isNetworkConnected(this@SipVoipService)
                        Log.d(TAG, "CONNECTIVITY_ACTION received. Online = $online (was $isCurrentlyOnline)")
                        if (!online && isCurrentlyOnline) {
                            networkDebounceJob?.cancel()
                            networkDebounceJob = serviceScope.launch {
                                delay(7000)
                                val stillOnline = SipEngine.isNetworkConnected(this@SipVoipService)
                                if (!stillOnline && isActive) {
                                    isCurrentlyOnline = false
                                    SipEngine.onNetworkLost()
                                    updateStandbyNotification(RegistrationStatus.FAILED, "No Internet Connection")
                                }
                            }
                        } else if (online && (!isCurrentlyOnline || SipEngine.currentStatus != RegistrationStatus.REGISTERED)) {
                            isCurrentlyOnline = true
                            networkDebounceJob?.cancel()
                            networkDebounceJob = serviceScope.launch {
                                delay(600)
                                SipEngine.onNetworkAvailable(this@SipVoipService)
                            }
                        }
                    }
                }
                ContextCompat.registerReceiver(this, connectivityReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            }

            // Dedicated 24/7 VPN transport watcher (instantaneous VPN connect/disconnect detection!)
            try {
                var lastVpnActive = SipEngine.isVpnActive(this)
                val vpnRequest = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val currentSig = SipEngine.getNetworkFingerprint(this@SipVoipService)
                        val skipSticky: Boolean
                        val wasVpnActive: Boolean
                        synchronized(networkStateLock) {
                            wasVpnActive = lastVpnActive
                            lastVpnActive = true
                            val sameSig = (lastKnownLocalIp != null && currentSig == lastKnownLocalIp)
                            skipSticky = wasVpnActive && sameSig &&
                                (SipEngine.isRegistered() || SipEngine.currentStatus == RegistrationStatus.REGISTERING)
                            if (!skipSticky) {
                                pendingHandover = true
                            }
                        }
                        val vpnActiveForApp = SipEngine.isVpnActive(this@SipVoipService)
                        if (skipSticky) {
                            Log.d(TAG, "VPN initial sticky onAvailable ($network, vpnForApp=$vpnActiveForApp, sig=$currentSig): already active & registered.")
                            SipEngine.notifyVpnStateChanged(vpnActiveForApp)
                            return
                        }
                        Log.i(TAG, "VPN connected ($network, vpnForApp=$vpnActiveForApp, wasVpnActive=$wasVpnActive, sig=$currentSig)")
                        SipEngine.notifyVpnStateChanged(vpnActiveForApp)
                        // Allow ~750ms for VPN tunnel handshake & routing table before fresh STUN + SIP REGISTER
                        scheduleUnifiedRecovery(750L, forceHandover = true, reason = "vpn-connected")
                    }

                    override fun onLost(network: Network) {
                        val stillHasVpn = SipEngine.isVpnActive(this@SipVoipService, excludeNetwork = network)
                        synchronized(networkStateLock) {
                            lastVpnActive = stillHasVpn
                            if (!stillHasVpn) {
                                pendingHandover = true
                            }
                        }
                        Log.w(TAG, "VPN lost ($network, stillHasVpn=$stillHasVpn)")
                        SipEngine.notifyVpnStateChanged(stillHasVpn)
                        if (!stillHasVpn) {
                            // Allow ~600ms for routes to revert cleanly to Wi-Fi/Mobile Data before re-registering
                            scheduleUnifiedRecovery(600L, forceHandover = true, reason = "vpn-disconnected")
                        }
                    }
                }
                connectivityManager?.registerNetworkCallback(vpnRequest, vpnNetworkCallback!!)
            } catch (e: Exception) {
                Log.w(TAG, "VPN NetworkCallback registration warning: ${e.message}")
            }

            // Dedicated 24/7 Underlying Physical Network Watcher (Wi-Fi <-> Mobile Data <-> Hotspot):
            // Crucial when VPN (e.g. Cloudflare WARP / WireGuard) is active, because defaultNetworkCallback
            // stays pinned to tun0 (same 172.16.0.2 IP) and NEVER fires on Wi-Fi <-> Cellular switches!
            try {
                val physicalRequest = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                physicalNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                    private fun handlePhysicalChange(reason: String) {
                        val vpnNow = SipEngine.isVpnActive(this@SipVoipService)
                        val newPhy = SipEngine.getActivePhysicalNetworkSignature(this@SipVoipService)
                        val newSig = SipEngine.getNetworkFingerprint(this@SipVoipService)
                        var triggerHandover = false
                        var triggerReconnect = false
                        synchronized(networkStateLock) {
                            val phyChanged = newPhy != "none" && lastPhysicalSignature != null && lastPhysicalSignature != newPhy
                            val sigChanged = isValidFingerprint(newSig) && isValidFingerprint(lastKnownLocalIp) && lastKnownLocalIp != newSig
                            if (newPhy != "none") lastPhysicalSignature = newPhy
                            if (isValidFingerprint(newSig)) lastKnownLocalIp = newSig
                            if (phyChanged || sigChanged) {
                                pendingHandover = true
                                triggerHandover = true
                            } else if (!pendingHandover &&
                                networkDebounceJob?.isActive != true &&
                                !SipEngine.isRegistered() &&
                                SipEngine.currentStatus != RegistrationStatus.REGISTERING &&
                                SipEngine.isNetworkConnected(this@SipVoipService)
                            ) {
                                triggerReconnect = true
                            }
                        }
                        if (triggerHandover) {
                            Log.i(TAG, "Underlying physical network changed ($reason, vpn=$vpnNow, phy=$newPhy). Scheduling handover...")
                            // When VPN is active, wait ~900ms so WireGuard/WARP completes its outer-tunnel re-handshake!
                            val delayMs = if (vpnNow) {
                                if (SipEngine.isInCall()) 500L else 900L
                            } else {
                                if (SipEngine.isInCall()) 200L else 400L
                            }
                            scheduleUnifiedRecovery(delayMs, forceHandover = true, reason = "physical-$reason")
                        } else if (triggerReconnect) {
                            Log.i(TAG, "Underlying physical network online while unregistered ($reason, vpn=$vpnNow). Scheduling reconnect...")
                            scheduleUnifiedRecovery(350L, forceHandover = false, reason = "physical-reconnect-$reason")
                        }
                    }

                    override fun onAvailable(network: Network) {
                        handlePhysicalChange("onAvailable:$network")
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        handlePhysicalChange("onLinkChanged:$network")
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            handlePhysicalChange("onCapsChanged:$network")
                        }
                    }

                    override fun onLost(network: Network) {
                        val stillHasOnline = SipEngine.isNetworkConnected(this@SipVoipService)
                        val vpnNow = SipEngine.isVpnActive(this@SipVoipService)
                        Log.w(TAG, "Physical network lost: $network (stillOnline=$stillHasOnline, vpn=$vpnNow)")
                        if (!stillHasOnline) {
                            // Even if tun0 is still up, ALL underlying physical networks are gone!
                            scheduleLossGraceMonitor(network, "physical-all-lost")
                        } else if (vpnNow) {
                            // Another physical network (e.g. Mobile Data) is already up underneath the VPN!
                            handlePhysicalChange("onLostSwitch:$network")
                        }
                    }
                }
                connectivityManager?.registerNetworkCallback(physicalRequest, physicalNetworkCallback!!)
            } catch (e: Exception) {
                Log.w(TAG, "Physical NetworkCallback registration warning: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network monitoring: ${e.message}")
        }
    }

    private fun ensureRegistered() {
        val accountManager = SecureAccountManager(this)
        val account = accountManager.getAccount()
        if (account.username.isNotBlank() && account.server.isNotBlank()) {
            if (!SipEngine.isNetworkConnected(this)) {
                Log.d(TAG, "Cannot register: Device is offline")
                if (SipEngine.currentStatus != RegistrationStatus.FAILED) {
                    SipEngine.onNetworkLost()
                }
                return
            }
            if (!SipEngine.isRegistered() && SipEngine.currentStatus != RegistrationStatus.REGISTERING) {
                Log.d(TAG, "Ensuring SIP registration for ${account.username}@${account.server}")
                SipEngine.triggerRegister(account)
            }
        }
    }

    private fun setupSipListeners() {
        val statusCb: (RegistrationStatus, String) -> Unit = { status, message ->
            if (!isCallActive) {
                updateStandbyNotification(status, message)
            }
        }
        val cancelCb: () -> Unit = {
            dismissIncomingNotification()
        }
        val stateCb: (CallState, String) -> Unit = { state, _ ->
            when (state) {
                CallState.CONNECTING, CallState.RINGING -> {
                    if (!SipEngine.hasActiveIncomingCall()) {
                        isCallActive = true
                        currentActiveNumber = SipEngine.activeCallNumber ?: "Unknown"
                        currentActiveName = SipEngine.activeCallName ?: currentActiveNumber
                        showCallNotification(currentActiveName, currentActiveNumber)
                    }
                }
                CallState.CONNECTED -> {
                    isCallActive = true
                    currentActiveNumber = SipEngine.activeCallNumber ?: currentActiveNumber
                    currentActiveName = SipEngine.activeCallName ?: currentActiveNumber
                    dismissIncomingNotification()
                    showCallNotification(currentActiveName, currentActiveNumber)
                }
                CallState.ENDED, CallState.FAILED, CallState.IDLE -> {
                    com.opentalk.android.engine.CallProximityManager.forceRelease("SipVoipService CallState.$state")
                    dismissIncomingNotification()
                    if (isCallActive) {
                        isCallActive = false
                        com.opentalk.android.engine.AudioRouteManager.resetToNormal(this)
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        nm?.cancel(NOTIFICATION_ID_CALL)
                        showStandbyNotification(force = true)
                    }
                }
            }
        }
        statusListenerRef = statusCb
        incomingCancelledListenerRef = cancelCb
        callStateListenerRef = stateCb
        SipEngine.addStatusListener(statusCb)
        SipEngine.addIncomingCallCancelledListener(cancelCb)
        SipEngine.addCallStateListener(stateCb)
    }

    // =========================================================================
    // NOTIFICATIONS & FULL SCREEN INCOMING CALLS
    // =========================================================================

    @Volatile private var lastStandbySignature: String = ""
    @Volatile private var isIncomingNotifForeground: Boolean = false

    private fun showStandbyNotification(force: Boolean = false) {
        val status = SipEngine.currentStatus
        val sig = "$status|${isIncomingNotifForeground}|$isCallActive"
        if (!force && !isIncomingNotifForeground && sig == lastStandbySignature) {
            return
        }
        isIncomingNotifForeground = false
        lastStandbySignature = "$status|false|false"
        val notification = buildStandbyNotification(status)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Strictly use FOREGROUND_SERVICE_TYPE_PHONE_CALL in standby (never manifest-all / microphone!)
                startForeground(NOTIFICATION_ID_STANDBY, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else {
                startForeground(NOTIFICATION_ID_STANDBY, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "showStandbyNotification startForeground error: ${e.message}")
            // Update notification via NotificationManager instead of throwing SecurityException via 2-arg manifest-all FGS
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID_STANDBY, notification)
            } catch (_: Exception) {}
        }
    }

    private fun updateStandbyNotification(status: RegistrationStatus, message: String) {
        if (isCallActive || isIncomingNotifForeground) return
        val sig = "$status|$message"
        if (sig == lastStandbySignature) return
        lastStandbySignature = sig
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notification = buildStandbyNotification(status, message)
        manager?.notify(NOTIFICATION_ID_STANDBY, notification)
    }

    private fun buildStandbyNotification(
        status: RegistrationStatus = SipEngine.currentStatus,
        message: String = ""
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val accountManager = SecureAccountManager(this)
        val account = accountManager.getAccount()
        val customName = if (account.displayName.isNotBlank() && account.displayName != "OpenTalk" && account.displayName != "BanglaCalls" && account.displayName != "Sarker Communication" && account.displayName != "Sarker") {
            account.displayName
        } else if (account.username.isNotBlank()) {
            account.username
        } else {
            ""
        }

        val isConnected = status == RegistrationStatus.REGISTERED
        val statusIcon = if (isConnected) {
            R.drawable.ic_stat_phone_connected
        } else {
            R.drawable.ic_stat_phone_disconnected
        }

        val statusText = when (status) {
            RegistrationStatus.REGISTERED -> "Connected"
            RegistrationStatus.REGISTERING -> "Connecting..."
            RegistrationStatus.FAILED -> {
                when {
                    message.contains("Internet", ignoreCase = true) || !SipEngine.isNetworkConnected(this) -> "Offline • No Internet"
                    else -> "Offline"
                }
            }
            RegistrationStatus.UNREGISTERED -> "Offline"
        }

        val title = if (customName.isNotBlank()) "OpenTalk • $customName" else "OpenTalk"
        return NotificationCompat.Builder(this, CHANNEL_STANDBY)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(statusIcon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private fun showCallNotification(name: String, number: String) {
        val activityOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }.toBundle()
        } else null

        val launchIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("number", number)
            putExtra("name", name)
            putExtra("is_incoming", SipEngine.isCurrentCallIncoming)
            putExtra("return_to_call", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            activityOptions
        )

        val hangUpIntent = Intent(this, SipVoipService::class.java).apply {
            action = ACTION_HANG_UP_ACTIVE_CALL
        }
        val hangUpPendingIntent = PendingIntent.getService(
            this,
            2,
            hangUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callState = SipEngine.currentCallState
        val isConnected = callState == CallState.CONNECTED
        val formattedNumber = com.opentalk.android.utils.PhoneNumberHelper.formatForDisplay(number)
        val hasDistinctName = name.isNotBlank() &&
            !com.opentalk.android.utils.PhoneNumberHelper.looksLikePhoneNumber(name) &&
            !com.opentalk.android.utils.PhoneNumberHelper.areSameNumber(name, number)
        val displayName = if (hasDistinctName) name else formattedNumber.ifBlank { number }
        val subText = when (callState) {
            CallState.CONNECTED -> "Active Voice Call"
            CallState.RINGING -> "Ringing..."
            else -> "Calling..."
        }
        val contentText = when (callState) {
            CallState.CONNECTED -> "Tap to return to call • End Call available"
            CallState.RINGING -> "Ringing • Tap to view call"
            else -> "Calling • Tap to view call"
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_CALL)
            .setContentTitle(displayName)
            .setContentText(contentText)
            .setSubText(subText)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_phone, "Open Call", pendingIntent)
            .addAction(R.drawable.ic_phone_hangup, "End Call", hangUpPendingIntent)

        if (isConnected && SipEngine.callConnectedStartTime > 0L) {
            val startTime = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - SipEngine.callConnectedStartTime)
            notificationBuilder.setUsesChronometer(true)
            notificationBuilder.setWhen(startTime)
        }

        val notification = notificationBuilder.build()
        // Invalidate standby signature so returning from active call to standby ALWAYS promotes FGS cleanly
        lastStandbySignature = ""
        isIncomingNotifForeground = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            try {
                startForeground(NOTIFICATION_ID_CALL, notification, fgType)
            } catch (e: Exception) {
                Log.w(TAG, "Call FGS with microphone restricted (${e.message}), falling back safely to phoneCall FGS...")
                try {
                    startForeground(NOTIFICATION_ID_CALL, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                } catch (e2: Exception) {
                    Log.e(TAG, "Call FGS phoneCall fallback error: ${e2.message}")
                    try {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        nm?.notify(NOTIFICATION_ID_CALL, notification)
                    } catch (_: Exception) {}
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID_CALL, notification)
            } catch (e: Exception) {
                Log.e(TAG, "Legacy startForeground call error: ${e.message}")
            }
        }
    }

    private fun postIncomingCallNotification(
        number: String,
        name: String,
        isTest: Boolean = false,
        silentMinimized: Boolean = false
    ) {
        if (!isTest && !SipEngine.hasActiveIncomingCall()) {
            Log.i(TAG, "Skipping incoming call notification: call was already cancelled/ended before display.")
            dismissIncomingNotification()
            // Satisfy startForegroundService() contract even if incoming call vanished before display
            if (!silentMinimized) {
                if (isCallActive) {
                    showCallNotification(currentActiveName, currentActiveNumber)
                } else {
                    showStandbyNotification(force = true)
                }
            }
            return
        }

        // 1. Wake screen up safely ONLY on fresh arrival (never flash screen when user minimized via Back/Home)
        if (!silentMinimized) {
            synchronized(wakeLockGuard) {
                try {
                    incomingWakeLock?.let { if (it.isHeld) it.release() }
                    incomingWakeLock = null
                    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    @Suppress("DEPRECATION")
                    incomingWakeLock = powerManager?.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                        "OpenTalk:IncomingCallWakeLock"
                    )?.apply {
                        setReferenceCounted(false)
                        acquire(30_000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring incoming wake lock: ${e.message}")
                }
            }
        }

        val activityOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }.toBundle()
        } else null

        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("number", number)
            putExtra("name", name)
            putExtra("is_test", isTest)
            putExtra("return_to_incoming", silentMinimized)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            201,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            activityOptions
        )

        // Check ACTUAL Android 10+ Background Activity Launch (BAL) eligibility:
        // Direct Service.startActivity() ONLY succeeds on Android 10-15 when SYSTEM_ALERT_WINDOW is granted
        // or the app is already in foreground (or on Android 8-9). NEVER assume it succeeded when BAL blocks it!
        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else true
        val isForegroundProcess = try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.runningAppProcesses?.any {
                it.pid == android.os.Process.myPid() &&
                    it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } == true
        } catch (_: Exception) { false }
        val canDirectLaunch = !silentMinimized &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || canOverlay || isForegroundProcess)

        var directActivityLaunched = false
        if (canDirectLaunch) {
            try {
                val directIntent = Intent(this, IncomingCallActivity::class.java).apply {
                    putExtra("number", number)
                    putExtra("name", name)
                    putExtra("is_test", isTest)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
                startActivity(directIntent)
                directActivityLaunched = true
                Log.i(TAG, "Direct full-screen IncomingCallActivity launched (overlay=$canOverlay, fg=$isForegroundProcess).")
            } catch (e: Exception) {
                Log.w(TAG, "Direct incoming activity launch failed: ${e.message}")
            }
        } else if (!silentMinimized) {
            Log.i(TAG, "Background incoming call (overlay=$canOverlay, fg=$isForegroundProcess): posting audible CallStyle + FullScreenIntent notification.")
        }

        val declineIntent = Intent(this, SipVoipService::class.java).apply {
            action = ACTION_DECLINE_CALL
        }
        val declinePendingIntent = PendingIntent.getService(
            this,
            202,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerCallIntent = Intent(this, CallActivity::class.java).apply {
            putExtra("number", number)
            putExtra("name", name)
            putExtra("is_incoming", true)
            putExtra("auto_answer", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val answerPendingIntent = PendingIntent.getActivity(
            this,
            203,
            answerCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            activityOptions
        )

        val ringtoneUri = com.opentalk.android.storage.SoundSettingsManager.getCallRingtoneUriOrNull(this)
        val vibrateOn = com.opentalk.android.storage.SoundSettingsManager.isVibrateOnCallEnabled(this)
        val formattedNumber = com.opentalk.android.utils.PhoneNumberHelper.formatForDisplay(number)
        val hasDistinctName = name.isNotBlank() &&
            !com.opentalk.android.utils.PhoneNumberHelper.looksLikePhoneNumber(name) &&
            !com.opentalk.android.utils.PhoneNumberHelper.areSameNumber(name, number)
        val displayCaller = if (hasDistinctName) name else formattedNumber.ifBlank { "Incoming Caller" }
        val callerPerson = androidx.core.app.Person.Builder()
            .setName(displayCaller)
            .setImportant(true)
            .build()

        val notifBuilder = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setContentTitle(displayCaller)
            .setContentText(
                if (silentMinimized) "Incoming Call • Tap to view"
                else "Incoming Audio Call • $formattedNumber"
            )
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPendingIntent)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    callerPerson,
                    declinePendingIntent,
                    answerPendingIntent
                )
            )

        if (!silentMinimized) {
            notifBuilder.setFullScreenIntent(fullScreenPendingIntent, true)
        }

        if (silentMinimized || directActivityLaunched || (ringtoneUri == null && !vibrateOn)) {
            notifBuilder.setSilent(true)
        } else {
            if (ringtoneUri != null) {
                notifBuilder.setSound(ringtoneUri)
            } else {
                notifBuilder.setSound(null)
            }
            if (vibrateOn) {
                notifBuilder.setVibrate(longArrayOf(0, 1000, 1000, 1000))
            } else {
                notifBuilder.setVibrate(longArrayOf(0L))
            }
        }

        val notification = notifBuilder.build().apply {
            if (!silentMinimized && !directActivityLaunched) {
                flags = flags or Notification.FLAG_INSISTENT
            }
        }

        // Promote fresh incoming call notification to phoneCall Foreground Service so CallStyle
        // gets top SystemUI heads-up + full-screen launch priority and is NEVER rate-limit demoted!
        var postedAsForeground = false
        if (!silentMinimized && !directActivityLaunched) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID_INCOMING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                } else {
                    startForeground(NOTIFICATION_ID_INCOMING, notification)
                }
                isIncomingNotifForeground = true
                postedAsForeground = true
            } catch (e: Exception) {
                Log.w(TAG, "Incoming CallStyle startForeground fallback: ${e.message}")
            }
        }
        if (!postedAsForeground) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID_INCOMING, notification)
            // Guarantee startForeground() is ALWAYS called for startForegroundService(ACTION_SHOW_INCOMING_CALL)
            if (!silentMinimized) {
                if (isCallActive) {
                    showCallNotification(currentActiveName, currentActiveNumber)
                } else {
                    showStandbyNotification(force = true)
                }
            }
        }

        if (!silentMinimized) {
            incomingNotificationTimeoutJob?.cancel()
            incomingNotificationTimeoutJob = serviceScope.launch {
                delay(30_500L)
                Log.i(TAG, "Autonomous incoming call notification watchdog reached (30.5s). Dismissing notification.")
                dismissIncomingNotification()
            }
        }
    }

    private fun dismissIncomingNotification() {
        incomingNotificationTimeoutJob?.cancel()
        incomingNotificationTimeoutJob = null
        if (isIncomingNotifForeground) {
            isIncomingNotifForeground = false
            if (!isCallActive) {
                showStandbyNotification(force = true)
            }
        }
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID_INCOMING)
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing incoming notification: ${e.message}")
        }
        synchronized(wakeLockGuard) {
            try {
                incomingWakeLock?.let { if (it.isHeld) it.release() }
            } catch (_: Exception) {}
            incomingWakeLock = null
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            try {
                manager.deleteNotificationChannel("sip_standby_channel")
            } catch (_: Exception) {}

            val standbyChannel = NotificationChannel(
                CHANNEL_STANDBY,
                "OpenTalk Background Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps OpenTalk active in the background for 24/7 incoming calls"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(standbyChannel)

            val incomingChannel = NotificationChannel(
                CHANNEL_INCOMING,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming VoIP call alerts"
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000, 1000)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(incomingChannel)

            val callChannel = NotificationChannel(
                CHANNEL_CALL,
                "Active Voice Call",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Active voice call indicator"
                setSound(null, null)
            }
            manager.createNotificationChannel(callChannel)

            // Delegate Missed Calls channel creation exclusively to MissedCallNotifier (eliminates duplicate dead channel!)
            com.opentalk.android.notifications.MissedCallNotifier.init(this)
        }
    }

    private fun cleanup() {
        com.opentalk.android.engine.CallProximityManager.forceRelease("SipVoipService cleanup/onDestroy")
        // Remove all global SipEngine listeners so destroyed SipVoipService is NEVER retained or invoked!
        statusListenerRef?.let { SipEngine.removeStatusListener(it) }
        statusListenerRef = null
        incomingCancelledListenerRef?.let { SipEngine.removeIncomingCallCancelledListener(it) }
        incomingCancelledListenerRef = null
        callStateListenerRef?.let { SipEngine.removeCallStateListener(it) }
        callStateListenerRef = null

        try { warmupJob?.cancel(); warmupJob = null } catch (_: Exception) {}
        isIncomingNotifForeground = false
        dismissIncomingNotification()
        try {
            serviceScope.cancel()
        } catch (_: Exception) {}
        try {
            connectivityReceiver?.let { unregisterReceiver(it) }
            connectivityReceiver = null
        } catch (_: Exception) {}
        try {
            synchronized(networkStateLock) {
                networkDebounceJob?.cancel()
                networkDebounceJob = null
            }
            connectivityManager?.let { cm ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
                }
                networkCallback?.let { cb -> cm.unregisterNetworkCallback(cb) }
                vpnNetworkCallback?.let { cb -> try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {} }
                physicalNetworkCallback?.let { cb -> try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {} }
            }
            networkCallback = null
            vpnNetworkCallback = null
            physicalNetworkCallback = null
        } catch (_: Exception) {}
        releaseLocks()
    }
}

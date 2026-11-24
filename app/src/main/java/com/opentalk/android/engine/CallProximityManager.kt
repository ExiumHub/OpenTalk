package com.opentalk.android.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Centralized Proximity Sensor Screen-Off Controller for active voice calls.
 */
object CallProximityManager {
    private const val TAG = "CallProximityManager"
    private const val PROXIMITY_LOCK_TAG = "OpenTalk:CallProximityWakeLock"
    private const val CPU_LOCK_TAG = "OpenTalk:CallAudioCpuWakeLock"
    private const val MAX_CALL_WAKELOCK_MS = 4L * 60L * 60L * 1000L // 4-hour safety cap

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var proximitySensorListener: SensorEventListener? = null
    private var usingHardwareWakeLock: Boolean = false

    @Volatile
    var isProximityActive: Boolean = false
        private set

    @Volatile
    var isNearEar: Boolean = false
        private set

    @Volatile
    var isFallbackNearEar: Boolean = false
        private set

    @Volatile
    var onFallbackNearChanged: ((Boolean) -> Unit)? = null

    private val fallbackNotifyRunnable = Runnable {
        onFallbackNearChanged?.invoke(isFallbackNearEar)
    }

    fun isScreenInteractive(context: Context): Boolean {
        return try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
    }

    fun updateState(
        context: Context,
        isCallConnected: Boolean,
        isUiForeground: Boolean,
        isSpeakerOn: Boolean,
        isExternalHeadsetConnected: Boolean,
        isOnHold: Boolean
    ) {
        val shouldEnable = isCallConnected &&
            isUiForeground &&
            !isSpeakerOn &&
            !isExternalHeadsetConnected &&
            !isOnHold

        if (shouldEnable) {
            acquireLocked(context.applicationContext)
        } else {
            releaseInternal(
                reason = "connected=$isCallConnected fg=$isUiForeground speaker=$isSpeakerOn extHeadset=$isExternalHeadsetConnected hold=$isOnHold"
            )
        }
    }

    fun forceRelease(reason: String = "forceRelease") {
        releaseInternal(reason)
    }

    private fun acquireLocked(appContext: Context) {
        synchronized(lock) {
            try {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

                // Acquire proximity mechanism FIRST so devices without any proximity sensor
                // never hold an unnecessary 4-hour PARTIAL_WAKE_LOCK!
                val acquiredProximity: Boolean = if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    usingHardwareWakeLock = true
                    if (proximityWakeLock == null) {
                        proximityWakeLock = pm.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                            PROXIMITY_LOCK_TAG
                        ).apply {
                            setReferenceCounted(false)
                        }
                    }
                    if (proximityWakeLock?.isHeld == false) {
                        proximityWakeLock?.acquire(MAX_CALL_WAKELOCK_MS)
                        Log.i(TAG, "Proximity screen-off ENABLED (hardware WakeLock acquired)")
                    }
                    val held = proximityWakeLock?.isHeld == true
                    if (held) {
                        // Also monitor Sensor.TYPE_PROXIMITY so CallActivity knows live near-ear state
                        // on OEMs where mSuspendWhenScreenOffDueToProximityConfig=true pauses the Activity!
                        registerProximitySensorLocked(appContext, driveFallbackOverlay = false)
                    }
                    held
                } else {
                    usingHardwareWakeLock = false
                    val registered = registerProximitySensorLocked(appContext, driveFallbackOverlay = true)
                    if (registered && !isProximityActive) {
                        Log.i(TAG, "Proximity screen-off ENABLED (Sensor.TYPE_PROXIMITY fallback)")
                    }
                    registered
                }

                if (!acquiredProximity) {
                    isProximityActive = false
                    return
                }

                // Pair with PARTIAL_WAKE_LOCK so CPU, AudioRecord, and AudioTrack
                // never suspend when OEM mSuspendWhenScreenOffDueToProximityConfig=true.
                if (cpuWakeLock == null) {
                    cpuWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        CPU_LOCK_TAG
                    ).apply {
                        setReferenceCounted(false)
                    }
                }
                if (cpuWakeLock?.isHeld == false) {
                    cpuWakeLock?.acquire(MAX_CALL_WAKELOCK_MS)
                }
                isProximityActive = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acquire proximity lock: ${e.message}")
                try { proximityWakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
                try { cpuWakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
                unregisterProximitySensorLocked()
                isNearEar = false
                isProximityActive = false
            }
        }
    }

    private fun releaseInternal(reason: String) {
        var notifyFallbackClear = false
        synchronized(lock) {
            val wasActive = isProximityActive ||
                (proximityWakeLock?.isHeld == true) ||
                (cpuWakeLock?.isHeld == true) ||
                (proximitySensorListener != null)

            try {
                proximityWakeLock?.let { wl ->
                    if (wl.isHeld) {
                        wl.release()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release proximity WakeLock: ${e.message}")
            }

            try {
                cpuWakeLock?.let { wl ->
                    if (wl.isHeld) {
                        wl.release()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release call CPU WakeLock: ${e.message}")
            }

            unregisterProximitySensorLocked()
            isNearEar = false

            if (isFallbackNearEar) {
                isFallbackNearEar = false
                notifyFallbackClear = true
            }
            isProximityActive = false

            if (wasActive) {
                Log.i(TAG, "Proximity screen-off DISABLED ($reason)")
            }
        }

        mainHandler.removeCallbacks(fallbackNotifyRunnable)
        if (notifyFallbackClear) {
            mainHandler.post(fallbackNotifyRunnable)
        }
    }

    private fun registerProximitySensorLocked(appContext: Context, driveFallbackOverlay: Boolean): Boolean {
        if (proximitySensorListener != null) return true
        return try {
            val sm = (sensorManager ?: appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                .also { sensorManager = it } ?: return false
            val proxSensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return false
            val nearThreshold = minOf(proxSensor.maximumRange, 5f)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val distance = event?.values?.firstOrNull() ?: return
                    val isNear = distance < nearThreshold
                    val shouldNotifyFallback = synchronized(lock) {
                        if (proximitySensorListener !== this) return
                        if (isNear != isNearEar) {
                            isNearEar = isNear
                            Log.d(TAG, "Proximity sensor changed: near=$isNear (dist=$distance, threshold=$nearThreshold, hwLock=$usingHardwareWakeLock)")
                        }
                        if (driveFallbackOverlay && isNear != isFallbackNearEar) {
                            isFallbackNearEar = isNear
                            true
                        } else {
                            false
                        }
                    }
                    if (shouldNotifyFallback) {
                        mainHandler.removeCallbacks(fallbackNotifyRunnable)
                        mainHandler.post(fallbackNotifyRunnable)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val registered = sm.registerListener(listener, proxSensor, SensorManager.SENSOR_DELAY_NORMAL)
            if (registered) {
                proximitySensorListener = listener
            }
            registered
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register proximity sensor: ${e.message}")
            false
        }
    }

    private fun unregisterProximitySensorLocked() {
        try {
            proximitySensorListener?.let { listener ->
                sensorManager?.unregisterListener(listener)
            }
        } catch (_: Exception) {}
        proximitySensorListener = null
    }
}

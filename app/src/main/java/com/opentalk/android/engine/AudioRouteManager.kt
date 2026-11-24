package com.opentalk.android.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class AudioRouteManager(context: Context) {
    companion object {
        private const val TAG = "AudioRouteManager"

        // Shared singleton audio-focus & mute state across CallActivity recreations so
        // returning to a live background call NEVER requests a duplicate AudioFocusRequest
        // (which would deliver AUDIOFOCUS_LOSS_TRANSIENT to a stale listener and mute the mic!).
        private val focusLock = Any()
        private var sharedFocusRequest: AudioFocusRequest? = null
        @Volatile private var sharedIsFocusActive = false
        @Volatile private var sharedUserMuted = false
        @Volatile private var sharedMutedByAudioFocus = false
        @Volatile private var sharedDesiredSpeakerOn = false
        @Volatile private var sharedAudioManager: AudioManager? = null

        private val sharedFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            synchronized(focusLock) {
                if (!sharedIsFocusActive) return@OnAudioFocusChangeListener
                val am = sharedAudioManager
                when (focusChange) {
                    // Only mute on true focus loss (e.g. incoming GSM call); ignore CAN_DUCK from notifications/navigation!
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (!sharedUserMuted) {
                            sharedMutedByAudioFocus = true
                            SipEngine.setMute(true)
                            try { am?.isMicrophoneMute = true } catch (_: Exception) {}
                        }
                        Log.w(TAG, "VoIP audio focus lost ($focusChange). Focus-muted=$sharedMutedByAudioFocus, userMuted=$sharedUserMuted")
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (sharedMutedByAudioFocus && !sharedUserMuted) {
                            sharedMutedByAudioFocus = false
                            SipEngine.setMute(false)
                            try { am?.isMicrophoneMute = false } catch (_: Exception) {}
                            Log.i(TAG, "VoIP audio focus regained. Unmuted focus-muted mic.")
                        } else {
                            sharedMutedByAudioFocus = false
                            Log.i(TAG, "VoIP audio focus regained. Preserving user manual mute=$sharedUserMuted.")
                        }
                    }
                }
            }
        }

        private fun abandonSharedFocusLocked(am: AudioManager) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sharedFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                    sharedFocusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(sharedFocusListener)
                }
            } catch (_: Exception) {}
        }

        /**
         * Forcefully and synchronously reset Android audio mode to MODE_NORMAL and abandon focus.
         * Guarantees the Android status-bar green microphone privacy indicator is dismissed immediately.
         */
        fun resetToNormal(context: Context) {
            synchronized(focusLock) {
                sharedIsFocusActive = false
                sharedUserMuted = false
                sharedMutedByAudioFocus = false
                sharedDesiredSpeakerOn = false
                try {
                    val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        am.clearCommunicationDevice()
                    } else {
                        @Suppress("DEPRECATION")
                        try {
                            if (am.isBluetoothScoOn) {
                                am.stopBluetoothSco()
                                am.isBluetoothScoOn = false
                            }
                        } catch (_: Exception) {}
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = false
                    }
                    am.isMicrophoneMute = false
                    abandonSharedFocusLocked(am)
                    am.mode = AudioManager.MODE_NORMAL
                    Log.i(TAG, "Audio mode forcefully reset to MODE_NORMAL (Mic chip OFF)")
                } catch (e: Exception) {
                    Log.w(TAG, "resetToNormal failed: ${e.message}")
                }
            }
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var deviceCallback: Any? = null
    private var commDeviceListener: Any? = null
    private var audioRouteReceiver: BroadcastReceiver? = null

    /**
     * Optional listener notified whenever speaker state or connected audio devices
     * (Bluetooth headset, wired/USB headset, earpiece) change during a call.
     */
    @Volatile var onAudioRouteChanged: (() -> Unit)? = null

    private fun notifyAudioRouteChanged() {
        val cb = onAudioRouteChanged ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb()
        } else {
            mainHandler.post { onAudioRouteChanged?.invoke() }
        }
    }

    @Synchronized
    fun acquireVoipAudioFocus(preserveCurrentRouting: Boolean = false): Boolean {
        detachDeviceCallback()

        val focusGranted: Boolean
        synchronized(focusLock) {
            sharedAudioManager = audioManager
            val alreadyHadFocus = sharedIsFocusActive && sharedFocusRequest != null
            sharedIsFocusActive = true
            if (!preserveCurrentRouting) {
                sharedMutedByAudioFocus = false
                sharedUserMuted = false
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Ensure in-call speech volume has a safe, clearly audible default (at least 70% of max)
            try {
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val minAudibleVol = (maxVol * 0.70f).toInt().coerceAtLeast(1)
                if (currentVol < minAudibleVol) {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, minAudibleVol, 0)
                }
            } catch (_: Exception) {}

            // Reuse existing active focus request on Activity recreation instead of requesting a conflicting second one
            focusGranted = if (preserveCurrentRouting && alreadyHadFocus) {
                true
            } else {
                abandonSharedFocusLocked(audioManager)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val playbackAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(playbackAttributes)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(sharedFocusListener)
                        .build()
                    sharedFocusRequest = req
                    audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                } else {
                    @Suppress("DEPRECATION")
                    val result = audioManager.requestAudioFocus(
                        sharedFocusListener,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                    result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
            }
        }

        if (preserveCurrentRouting) {
            setSpeakerphone(isEffectiveSpeakerOn())
        } else {
            setSpeakerphone(false)
        }

        // Register dynamic audio device change callback (auto-routes wired/Bluetooth headsets during call)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val callback = object : android.media.AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                        val hasExternalChange = addedDevices?.any { isExternalCallDeviceType(it.type) } == true
                        if (hasExternalChange && sharedIsFocusActive && !sharedDesiredSpeakerOn) {
                            setSpeakerphone(false)
                        } else {
                            notifyAudioRouteChanged()
                        }
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                        val hasExternalChange = removedDevices?.any { isExternalCallDeviceType(it.type) } == true
                        if (hasExternalChange && sharedIsFocusActive && !sharedDesiredSpeakerOn) {
                            setSpeakerphone(false)
                        } else {
                            notifyAudioRouteChanged()
                        }
                    }
                }
                audioManager.registerAudioDeviceCallback(callback, mainHandler)
                deviceCallback = callback
            } catch (_: Exception) {}
        }

        // Android 12+ (API 31+): observe async communication device transitions (Speaker <-> Earpiece <-> BT/USB)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val listener = AudioManager.OnCommunicationDeviceChangedListener {
                    notifyAudioRouteChanged()
                }
                audioManager.addOnCommunicationDeviceChangedListener(
                    ContextCompat.getMainExecutor(appContext),
                    listener
                )
                commDeviceListener = listener
            } catch (_: Exception) {}
        }

        // Cross-OEM fallback receiver for wired 3.5mm plug/unplug and Bluetooth SCO state updates
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    notifyAudioRouteChanged()
                }
            }
            val filter = IntentFilter().apply {
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            audioRouteReceiver = receiver
        } catch (_: Exception) {}

        return focusGranted
    }

    /**
     * Unregisters Activity-scoped device callbacks without dropping active call audio focus
     * (used when CallActivity is destroyed while the VoIP call continues in background).
     */
    @Synchronized
    fun detachDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                (deviceCallback as? android.media.AudioDeviceCallback)?.let {
                    audioManager.unregisterAudioDeviceCallback(it)
                }
            } catch (_: Exception) {}
            deviceCallback = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                (commDeviceListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                    audioManager.removeOnCommunicationDeviceChangedListener(it)
                }
            } catch (_: Exception) {}
            commDeviceListener = null
        }
        try {
            audioRouteReceiver?.let { appContext.unregisterReceiver(it) }
        } catch (_: Exception) {}
        audioRouteReceiver = null
    }

    @Synchronized
    fun releaseVoipAudioFocus() {
        detachDeviceCallback()
        synchronized(focusLock) {
            sharedIsFocusActive = false
            sharedUserMuted = false
            sharedMutedByAudioFocus = false
            sharedDesiredSpeakerOn = false
            try {
                audioManager.isMicrophoneMute = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    try {
                        if (audioManager.isBluetoothScoOn) {
                            audioManager.stopBluetoothSco()
                            audioManager.isBluetoothScoOn = false
                        }
                    } catch (_: Exception) {}
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }

                abandonSharedFocusLocked(audioManager)
                audioManager.mode = AudioManager.MODE_NORMAL
            } catch (_: Exception) {}
        }
    }

    private fun isLegacyBluetoothScoConnected(): Boolean {
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun setSpeakerphone(enabled: Boolean) {
        synchronized(focusLock) {
            // Never activate Bluetooth SCO or communication routing after call audio focus was released/reset!
            if (!sharedIsFocusActive) return
            sharedDesiredSpeakerOn = enabled
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    // Note: AudioManager.availableCommunicationDevices & setCommunicationDevice() only require
                    // MODIFY_AUDIO_SETTINGS (NOT runtime BLUETOOTH_CONNECT), so BT headsets route seamlessly on Android 12+!
                    val devices = audioManager.availableCommunicationDevices
                    if (enabled) {
                        val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (speaker != null) {
                            val success = audioManager.setCommunicationDevice(speaker)
                            Log.i(TAG, "Audio routed to speaker: ${speaker.type} (success=$success)")
                        } else {
                            audioManager.clearCommunicationDevice()
                        }
                    } else {
                        // Try candidates in priority order (BT -> Wired/USB -> Earpiece) so a rejected/stale BT
                        // device seamlessly falls back to wired headset or built-in earpiece instead of leaving audio broken!
                        val candidates = listOfNotNull(
                            devices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
                            },
                            devices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                            },
                            devices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                            }
                        )
                        var routed = false
                        for (candidate in candidates) {
                            val ok = audioManager.setCommunicationDevice(candidate)
                            Log.i(TAG, "Audio route candidate ${candidate.type} -> success=$ok")
                            if (ok) {
                                routed = true
                                break
                            }
                        }
                        if (!routed) {
                            audioManager.clearCommunicationDevice()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setCommunicationDevice failed: ${e.message}")
                }
            } else {
                // Android 8 - 11 (API 26 - 30): Full Bluetooth SCO + Earpiece/Speaker routing
                @Suppress("DEPRECATION")
                try {
                    if (enabled) {
                        if (audioManager.isBluetoothScoOn) {
                            audioManager.stopBluetoothSco()
                            audioManager.isBluetoothScoOn = false
                        }
                        audioManager.isSpeakerphoneOn = true
                    } else {
                        audioManager.isSpeakerphoneOn = false
                        if (isLegacyBluetoothScoConnected() && audioManager.isBluetoothScoAvailableOffCall) {
                            audioManager.startBluetoothSco()
                            audioManager.isBluetoothScoOn = true
                            Log.i(TAG, "Legacy API <31: Routed call audio to Bluetooth SCO headset")
                        } else if (audioManager.isBluetoothScoOn) {
                            audioManager.stopBluetoothSco()
                            audioManager.isBluetoothScoOn = false
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Legacy audio route update failed: ${e.message}")
                }
            }
            Unit
        }
        notifyAudioRouteChanged()
    }

    fun setMicrophoneMute(muted: Boolean) {
        synchronized(focusLock) {
            sharedUserMuted = muted
            if (!muted) sharedMutedByAudioFocus = false
            audioManager.isMicrophoneMute = muted
        }
    }

    fun isSpeakerphoneOn(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevice = audioManager.communicationDevice
            if (commDevice != null) {
                return commDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        }
        @Suppress("DEPRECATION")
        return audioManager.isSpeakerphoneOn
    }

    /**
     * Authoritative speaker state during an active call.
     * Avoids Android 12+ async setCommunicationDevice() stale-read race when switching Speaker -> Earpiece.
     */
    fun isEffectiveSpeakerOn(): Boolean {
        return if (sharedIsFocusActive) sharedDesiredSpeakerOn else isSpeakerphoneOn()
    }

    /**
     * Returns true when any call-capable Bluetooth headset/earbuds, wired 3.5mm headset/headphones,
     * USB-C headset/audio device, or hearing aid is connected (excludes media-only A2DP/BLE speakers
     * so earpiece calls don't lose proximity screen-off just because a car stereo is paired).
     */
    fun isExternalAudioDeviceConnected(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commType = audioManager.communicationDevice?.type
                if (commType != null && isExternalCallDeviceType(commType)) {
                    return true
                }
                if (audioManager.availableCommunicationDevices.any { isExternalCallDeviceType(it.type) }) {
                    return true
                }
            }
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            if (outputs.any { isExternalCallDeviceType(it.type) }) {
                return true
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn || audioManager.isWiredHeadsetOn) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "isExternalAudioDeviceConnected check failed: ${e.message}")
        }
        return false
    }

    private fun isExternalCallDeviceType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            type == AudioDeviceInfo.TYPE_HEARING_AID
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {
            return true
        }
        return false
    }

    fun isMicrophoneMute(): Boolean = sharedUserMuted || audioManager.isMicrophoneMute
}

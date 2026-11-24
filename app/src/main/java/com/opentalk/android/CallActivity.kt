package com.opentalk.android

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.provider.ContactsContract
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.opentalk.android.databinding.ActivityCallBinding
import com.opentalk.android.databinding.DialogAddCallBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.opentalk.android.engine.AudioRouteManager
import com.opentalk.android.engine.CallProximityManager
import com.opentalk.android.engine.CallRecorder
import com.opentalk.android.engine.CallState
import com.opentalk.android.engine.SipEngine
import com.opentalk.android.models.CallRecord
import com.opentalk.android.models.CallType
import com.opentalk.android.services.SipVoipService
import com.opentalk.android.storage.CallHistoryDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCallBinding
    private lateinit var audioRouteManager: AudioRouteManager
    private lateinit var dbHelper: CallHistoryDbHelper

    private var remoteNumber: String = ""
    private var remoteName: String = ""
    private var isMuted = false
    private var isSpeaker = false
    private var isHeld = false
    private var durationSeconds = 0
    private var startTime = System.currentTimeMillis()
    private var isCallConnected = false
    private var isCallFailed = false
    private val isEnding = java.util.concurrent.atomic.AtomicBoolean(false)
    private var isExplicitUserHangup = false
    private var isSwitchingCall = false
    private var switchingPriorCallGen = -1L
    private var isReconnecting = false
    private var connectedElapsedRealtime = 0L
    private var lastQualityLatency = 24L
    private var lastQualityLevel = 2
    private var isIncoming = false

    private var toneGenerator: ToneGenerator? = null
    private var isActivityForeground = false
    private var isUserMinimizingCall = false
    private var fallbackProximityOverlay: View? = null
    private var savedScreenBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var activeDialog: android.app.Dialog? = null
    private var avatarHaloAnimator: android.animation.AnimatorSet? = null
    private var callStateListener: ((CallState, String) -> Unit)? = null
    private var callQualityListenerRef: ((Long, Int) -> Unit)? = null
    private var hasCallInitiated = false
    private var addCallNumberBuffer: StringBuilder? = null
    private var tvAddCallDigitsRef: TextView? = null
    private var pendingAddCallNumber: String? = null

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val contactUri = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.query(
                    contactUri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val num = if (numIdx != -1) cursor.getString(numIdx) else ""
                        val clean = num.replace("[^0-9+*#]".toRegex(), "")
                        if (clean.isNotBlank()) {
                            pendingAddCallNumber = clean
                            addCallNumberBuffer?.setLength(0)
                            addCallNumberBuffer?.append(clean)
                            tvAddCallDigitsRef?.text = clean
                            if (activeDialog == null || !(activeDialog?.isShowing ?: false)) {
                                showAddCallSheet()
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private val timerHandler = Handler(Looper.getMainLooper())
    private val failedFinishRunnable = Runnable {
        endCall()
    }

    /**
     * After a call ends (even if OpenTalk was closed or minimized when the call arrived),
     * brings MainActivity straight to the Recents tab so the user immediately sees the latest call log.
     */
    private fun openRecentsAfterCallEnd() {
        try {
            val recentsIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("select_tab", "recents")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(recentsIntent)
        } catch (_: Exception) {}
    }
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isCallConnected && !isEnding.get()) {
                if (connectedElapsedRealtime > 0L) {
                    durationSeconds = maxOf(0L, (SystemClock.elapsedRealtime() - connectedElapsedRealtime) / 1000).toInt()
                }
                val minutes = durationSeconds / 60
                val seconds = durationSeconds % 60
                val hours = minutes / 60
                val timeStr = if (hours > 0) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, minutes % 60, seconds)
                } else {
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
                }
                if (!isHeld && !isReconnecting) {
                    binding.tvCallState.text = timeStr
                    binding.tvCallState.setTextColor(ContextCompat.getColor(this@CallActivity, R.color.incall_text_primary))
                }
                binding.tvCallDuration.text = timeStr
            }
            timerHandler.removeCallbacks(this)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private fun onCallConnected() {
        isReconnecting = false
        try { toneGenerator?.stopTone() } catch (_: Exception) {}
        // Re-apply pre-connect Mute state so mic stays muted if user tapped Mute during ringing!
        if (isMuted) {
            audioRouteManager.setMicrophoneMute(true)
            SipEngine.setMute(true)
        }
        if (isCallConnected && connectedElapsedRealtime > 0L) {
            if (!isHeld) {
                binding.tvCallState.text = "Connected"
                binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.registered_green))
            }
            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.post(timerRunnable)
            updateProximityState()
            return
        }
        isCallConnected = true
        if (connectedElapsedRealtime == 0L) {
            connectedElapsedRealtime = if (SipEngine.callConnectedStartTime > 0L) {
                SipEngine.callConnectedStartTime
            } else {
                SystemClock.elapsedRealtime()
            }
        }
        startTime = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - connectedElapsedRealtime)

        // Dismiss incoming call notification immediately
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(SipVoipService.NOTIFICATION_ID_INCOMING)
            SipVoipService.dismissIncomingCall(this)
        } catch (_: Exception) {}

        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)

        if (!isHeld) {
            binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_text_primary))
            binding.tvCallState.text = "00:00"
        }
        binding.tvCallDuration.visibility = View.GONE
        binding.layoutQualityIndicator.visibility = View.VISIBLE
        updateProximityState()
    }

    private fun updateRemoteCallerUi(number: String, rawName: String) {
        remoteNumber = com.opentalk.android.utils.PhoneNumberHelper.toCanonicalE164(number).ifBlank { number.trim() }
        val targetNumber = remoteNumber
        val displayNumber = com.opentalk.android.utils.PhoneNumberHelper.formatForDisplay(targetNumber)
        val cleanName = com.opentalk.android.utils.ContactResolver.stripOperatorName(rawName).trim()

        fun renderRemote(phonebookName: String?) {
            remoteName = when {
                !phonebookName.isNullOrBlank() -> phonebookName
                targetNumber == "121" && cleanName.isNotBlank() -> cleanName
                else -> targetNumber
            }
            val hasDistinctName = !phonebookName.isNullOrBlank() ||
                (targetNumber == "121" && cleanName.isNotBlank() &&
                    !com.opentalk.android.utils.PhoneNumberHelper.looksLikePhoneNumber(cleanName))

            if (hasDistinctName) {
                binding.tvRemoteName.text = remoteName
                binding.tvRemoteNumber.text = displayNumber
                binding.tvRemoteNumber.visibility = View.VISIBLE
            } else {
                binding.tvRemoteName.text = displayNumber
                binding.tvRemoteNumber.visibility = View.GONE
            }

            // Configure dynamic avatar initial or vector phone icon (1-2 initials matching IncomingCallActivity)
            val hasLetter = remoteName.any { it.isLetter() }
            if (hasLetter) {
                val initials = remoteName
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .take(2)
                    .mapNotNull { part -> part.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
                    .joinToString("")
                val initial = initials.ifBlank {
                    remoteName.trim().firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "D"
                }
                binding.tvAvatarInitials.text = initial
                binding.tvAvatarInitials.visibility = View.VISIBLE
                binding.ivAvatarIcon.visibility = View.GONE
            } else {
                binding.tvAvatarInitials.visibility = View.GONE
                binding.ivAvatarIcon.visibility = View.VISIBLE
            }
        }

        // Instant 0ms render from in-memory contact cache (zero Main-Thread IPC stall),
        // followed by live Android Phonebook refresh on Dispatchers.IO!
        val cachedPhonebook = com.opentalk.android.utils.ContactResolver.getCachedContactName(targetNumber)
        renderRemote(cachedPhonebook)

        val appCtx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val livePhonebook = com.opentalk.android.utils.ContactResolver.resolveContactName(appCtx, targetNumber)
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed && remoteNumber == targetNumber) {
                    val prevName = remoteName
                    renderRemote(livePhonebook)
                    // Keep SipEngine & ongoing Foreground Call Notification in sync when live lookup resolves a contact name!
                    if (remoteName != prevName && !isEnding.get() && SipEngine.isInCall()) {
                        SipEngine.activeCallName = remoteName
                        SipVoipService.startCall(this@CallActivity, remoteNumber, remoteName)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.opentalk.android.storage.SoundSettingsManager.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Bind physical volume keys directly to in-call speech stream for loud, crystal-clear volume control
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        // Set adaptive Light/Dark status bar & navigation bar for calling screen
        val isDark = com.opentalk.android.storage.SoundSettingsManager.isDarkModeEnabled(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
        window.statusBarColor = ContextCompat.getColor(this, R.color.incall_bg_start)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.incall_bg_end)

        // Show cleanly over lock screen WITHOUT forcing PIN/Biometric prompt via requestDismissKeyguard()!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Subtle ambient breathing halo behind caller avatar (visual polish only)
        avatarHaloAnimator = android.animation.AnimatorSet().apply {
            val sx = android.animation.ObjectAnimator.ofFloat(binding.viewAvatarHalo, View.SCALE_X, 0.94f, 1.12f).apply {
                duration = 2000L
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
            }
            val sy = android.animation.ObjectAnimator.ofFloat(binding.viewAvatarHalo, View.SCALE_Y, 0.94f, 1.12f).apply {
                duration = 2000L
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
            }
            val alpha = android.animation.ObjectAnimator.ofFloat(binding.viewAvatarHalo, View.ALPHA, 0.55f, 1.0f).apply {
                duration = 2000L
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
            }
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            playTogether(sx, sy, alpha)
            start()
        }

        if (intent.getBooleanExtra("clear_missed", false)) {
            com.opentalk.android.notifications.MissedCallNotifier.clearMissedCalls(this)
        }

        audioRouteManager = AudioRouteManager(this).apply {
            onAudioRouteChanged = {
                if (!isFinishing && !isDestroyed) {
                    updateProximityState()
                }
            }
        }
        CallProximityManager.onFallbackNearChanged = { isNear ->
            if (!isFinishing && !isDestroyed) {
                applyFallbackProximityBlank(isNear)
            }
        }
        dbHelper = CallHistoryDbHelper.getInstance(this)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
        } catch (_: Exception) {}

        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val returnToCall = intent.getBooleanExtra("return_to_call", false) || launchedFromHistory
        val autoAnswer = intent.getBooleanExtra("auto_answer", false)
        val rawNum = intent.getStringExtra("number")?.trim()?.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: SipEngine.activeCallNumber?.takeIf { it.isNotBlank() }
            ?: "Unknown"
        val rawNm = intent.getStringExtra("name")?.trim()?.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: SipEngine.activeCallName?.takeIf { it.isNotBlank() }
            ?: rawNum

        // Guard stale/reopened return intents: NEVER redial or drop an incoming ringing call!
        if (returnToCall && !autoAnswer) {
            if (SipEngine.hasActiveIncomingCall()) {
                val incIntent = Intent(this, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("number", SipEngine.activeCallNumber ?: rawNum)
                    putExtra("name", SipEngine.activeCallName ?: rawNm)
                    putExtra("return_to_incoming", true)
                }
                isEnding.set(true)
                try { startActivity(incIntent) } catch (_: Exception) {}
                finish()
                return
            }
            if (!SipEngine.isInCall()) {
                Log.i("CallActivity", "Return-to-call opened after call already ended. Finishing cleanly without redial.")
                isEnding.set(true)
                finish()
                return
            }
        }

        val normIntentNum = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(rawNum)
        val normActiveNum = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(SipEngine.activeCallNumber ?: "")
        val isReturningToLiveCall = !autoAnswer && !SipEngine.hasActiveIncomingCall() && (
            returnToCall ||
            (SipEngine.isInCall() && (
                (normActiveNum.isNotBlank() && normActiveNum == normIntentNum) ||
                normIntentNum.isBlank() ||
                rawNum == "Unknown"
            ))
        )

        isIncoming = if (isReturningToLiveCall) {
            SipEngine.isCurrentCallIncoming
        } else {
            intent.getBooleanExtra("is_incoming", false)
        }
        updateRemoteCallerUi(rawNum, rawNm)

        if (SipEngine.currentCallState == CallState.CONNECTED) {
            binding.tvCallState.text = "00:00"
            binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_text_primary))
        } else {
            binding.tvCallState.text = if (isIncoming) "Connecting..." else "Calling..."
            binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_state_accent))
        }
        binding.tvCallDuration.visibility = View.GONE

        // Set call state listener
        val stateListener: (CallState, String) -> Unit = { state, message ->
            runOnUiThread {
                if (isEnding.get() || isFinishing) return@runOnUiThread
                // While switching from a prior call to a new outgoing call, suppress ONLY stale ENDED/IDLE
                // from the prior call generation; NEVER suppress new CONNECTING/RINGING/CONNECTED/FAILED!
                if (isSwitchingCall) {
                    val curGen = SipEngine.currentCallGeneration()
                    if (state == CallState.ENDED || state == CallState.IDLE) {
                        if (switchingPriorCallGen >= 0L && curGen <= switchingPriorCallGen + 1L) {
                            return@runOnUiThread
                        }
                    } else {
                        isSwitchingCall = false
                        switchingPriorCallGen = -1L
                    }
                }
                when (state) {
                    CallState.CONNECTING -> {
                        hasCallInitiated = true
                        isCallFailed = false
                        isReconnecting = false
                        // NEVER play local ringback tone during INVITE / 100 Trying / 183 Session Progress!
                        try { toneGenerator?.stopTone() } catch (_: Exception) {}
                        binding.tvCallState.text = "Calling..."
                        binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_state_accent))
                    }
                    CallState.RINGING -> {
                        hasCallInitiated = true
                        isCallFailed = false
                        isReconnecting = false
                        binding.tvCallState.text = if (isIncoming) "Connecting..." else "Ringing..."
                        binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_state_accent))
                        if (message.contains("Early Media") || SipEngine.hasEarlyMedia()) {
                            try { toneGenerator?.stopTone() } catch (_: Exception) {}
                        } else if (!isIncoming) {
                            try {
                                toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
                            } catch (_: Exception) {}
                        }
                    }
                    CallState.CONNECTED -> {
                        hasCallInitiated = true
                        isCallFailed = false
                        try { toneGenerator?.stopTone() } catch (_: Exception) {}
                        if (message.contains("Reconnecting", ignoreCase = true)) {
                            isReconnecting = true
                            binding.tvCallState.text = "Reconnecting..."
                            binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.registering_amber))
                        } else {
                            onCallConnected()
                        }
                    }
                    CallState.FAILED -> {
                        if (!hasCallInitiated && !isIncoming) return@runOnUiThread
                        isCallFailed = true
                        isReconnecting = false
                        try {
                            toneGenerator?.stopTone()
                            toneGenerator?.startTone(ToneGenerator.TONE_SUP_BUSY, 1500)
                        } catch (_: Exception) {}
                        val friendlyMessage = formatCallFailureMessage(message)
                        binding.tvCallState.text = friendlyMessage
                        binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.failed_red))
                        binding.tvCallDuration.visibility = View.GONE
                        binding.layoutQualityIndicator.visibility = View.GONE
                        CallProximityManager.forceRelease("CallState.FAILED")
                        timerHandler.removeCallbacks(timerRunnable)
                        timerHandler.removeCallbacks(failedFinishRunnable)
                        Toast.makeText(this, friendlyMessage, Toast.LENGTH_SHORT).show()
                        timerHandler.postDelayed(failedFinishRunnable, 2200)
                    }
                    CallState.ENDED, CallState.IDLE -> {
                        if (isCallFailed) return@runOnUiThread
                        if (hasCallInitiated || isIncoming || isCallConnected) {
                            try { toneGenerator?.stopTone() } catch (_: Exception) {}
                            binding.tvCallState.text = "Call Ended"
                            binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_text_secondary))
                            binding.layoutQualityIndicator.visibility = View.GONE
                            endCall()
                        }
                    }
                }
            }
        }
        callStateListener = stateListener
        SipEngine.addCallStateListener(stateListener)
        if (SipEngine.currentCallState == CallState.CONNECTED) {
            onCallConnected()
        } else if (SipEngine.currentCallState == CallState.RINGING) {
            binding.tvCallState.text = if (isIncoming) "Connecting..." else "Ringing..."
            binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_state_accent))
        } else if (SipEngine.currentCallState == CallState.CONNECTING) {
            binding.tvCallState.text = "Calling..."
            binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_state_accent))
        }

        // 1. Mute Action (Hook both button and container for 100% responsiveness)
        val muteAction = View.OnClickListener {
            toggleMute()
        }
        binding.btnMute.setOnClickListener(muteAction)
        binding.ibMute.setOnClickListener(muteAction)

        // 2. Speaker Action
        val speakerAction = View.OnClickListener {
            toggleSpeaker()
        }
        binding.btnSpeaker.setOnClickListener(speakerAction)
        binding.ibSpeaker.setOnClickListener(speakerAction)

        // 3. Hold Action
        val holdAction = View.OnClickListener {
            toggleHold()
        }
        binding.btnHold.setOnClickListener(holdAction)
        binding.ibHold.setOnClickListener(holdAction)

        // 4. In-Call Keypad (DTMF Dialpad)
        val keypadAction = View.OnClickListener {
            showInCallDtmfKeypad()
        }
        binding.btnKeypad.setOnClickListener(keypadAction)
        binding.ibKeypad.setOnClickListener(keypadAction)

        // 5. In-Call Voice Recording
        val recordAction = View.OnClickListener {
            toggleRecord()
        }
        binding.btnRecord.setOnClickListener(recordAction)
        binding.ibRecord.setOnClickListener(recordAction)

        // 6. In-Call Add Call Action
        val addCallAction = View.OnClickListener {
            showAddCallSheet()
        }
        binding.btnCallAdd.setOnClickListener(addCallAction)
        binding.ibCallAdd.setOnClickListener(addCallAction)

        // Intercept back button/gesture: NEVER cut a ringing, calling, or connected call — minimize safely instead!
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Ignore accidental cheek presses while fallback proximity shield is active
                if (fallbackProximityOverlay != null) return
                if (SipEngine.isInCall()) {
                    isUserMinimizingCall = true
                    isActivityForeground = false
                    CallProximityManager.forceRelease("CallActivity back minimize")
                    applyFallbackProximityBlank(false)
                    SipVoipService.startCall(this@CallActivity, remoteNumber, remoteName)
                    Toast.makeText(
                        this@CallActivity,
                        "Call running in background. Tap notification or app to return.",
                        Toast.LENGTH_SHORT
                    ).show()
                    moveTaskToBack(true)
                } else {
                    if (!isFinishing && !isDestroyed) finish()
                }
            }
        })

        // Proximity screen-off activates automatically once the call connects (onCallConnected / onResume)

        // 7. Hang Up Button
        binding.btnEndCall.setOnClickListener {
            isExplicitUserHangup = true
            endCall()
        }

        setupCallQualityIndicator()

        if (isReturningToLiveCall) {
            if (SipEngine.isInCall()) {
                audioRouteManager.acquireVoipAudioFocus(preserveCurrentRouting = true)
                SipVoipService.startCall(this, remoteNumber, remoteName)
                hasCallInitiated = true
                syncControlVisualsFromLiveState()
                syncUiWithCurrentEngineState()
            } else {
                isEnding.set(true)
                finish()
                return
            }
        } else if (isIncoming) {
            if (SipEngine.currentCallState == CallState.CONNECTED) {
                audioRouteManager.acquireVoipAudioFocus(preserveCurrentRouting = false)
                SipVoipService.startCall(this, remoteNumber, remoteName)
                hasCallInitiated = true
                onCallConnected()
            } else if (autoAnswer && (SipEngine.hasActiveIncomingCall() || SipEngine.isAnsweringIncoming())) {
                audioRouteManager.acquireVoipAudioFocus(preserveCurrentRouting = false)
                SipVoipService.startCall(this, remoteNumber, remoteName)
                hasCallInitiated = true
                IncomingCallActivity.stopRingtoneOnly()
                val answerDeferred = SipEngine.engineScope.async { SipEngine.answerIncomingCall() }
                lifecycleScope.launch {
                    val success = try { answerDeferred.await() } catch (_: Exception) { false }
                    if (success && SipEngine.currentCallState == CallState.CONNECTED) {
                        onCallConnected()
                    } else {
                        Toast.makeText(this@CallActivity, "Call ended by caller", Toast.LENGTH_SHORT).show()
                        endCall()
                    }
                }
            } else if (SipEngine.hasActiveIncomingCall()) {
                val incIntent = Intent(this, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("number", SipEngine.activeCallNumber ?: remoteNumber)
                    putExtra("name", SipEngine.activeCallName ?: remoteName)
                    putExtra("return_to_incoming", true)
                }
                isEnding.set(true)
                try { startActivity(incIntent) } catch (_: Exception) {}
                finish()
            } else {
                Log.w("CallActivity", "Incoming CallActivity opened but call is not CONNECTED (state=${SipEngine.currentCallState})")
                Toast.makeText(this, "Call ended", Toast.LENGTH_SHORT).show()
                isEnding.set(true)
                finish()
            }
        } else {
            audioRouteManager.acquireVoipAudioFocus(preserveCurrentRouting = false)
            SipVoipService.startCall(this, remoteNumber, remoteName)
            // Suppress ONLY stale ENDED/IDLE from the prior call generation while switching!
            val hadPreviousCall = SipEngine.isInCall()
            isSwitchingCall = hadPreviousCall
            switchingPriorCallGen = if (hadPreviousCall) SipEngine.currentCallGeneration() else -1L
            hasCallInitiated = true
            SipEngine.startOutgoingCall(remoteNumber)
            if (hadPreviousCall) {
                timerHandler.postDelayed({
                    isSwitchingCall = false
                    switchingPriorCallGen = -1L
                    syncUiWithCurrentEngineState()
                }, 400L)
            }
        }
    }

    /**
     * Reconciles CallActivity UI directly with SipEngine.currentCallState so no fast state transition
     * (RINGING, CONNECTED, ENDED, FAILED) can ever leave the screen stuck on "Calling...".
     */
    private fun syncUiWithCurrentEngineState() {
        if (isEnding.get() || isCallFailed || isFinishing || isDestroyed) return
        when (SipEngine.currentCallState) {
            CallState.CONNECTED -> onCallConnected()
            CallState.RINGING -> {
                binding.tvCallState.text = if (isIncoming) "Connecting..." else "Ringing..."
                binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_state_accent))
            }
            CallState.ENDED, CallState.IDLE -> {
                if (hasCallInitiated && !isSwitchingCall) {
                    endCall()
                }
            }
            CallState.FAILED -> {
                if (hasCallInitiated && !isSwitchingCall) {
                    isCallFailed = true
                    binding.tvCallState.text = "Call Failed"
                    binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.failed_red))
                    timerHandler.removeCallbacks(failedFinishRunnable)
                    timerHandler.postDelayed(failedFinishRunnable, 1800)
                }
            }
            CallState.CONNECTING -> {
                binding.tvCallState.text = "Calling..."
                binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_state_accent))
            }
        }
    }

    private fun syncControlVisualsFromLiveState() {
        val activeIconColor = ContextCompat.getColor(this, R.color.incall_ctrl_active_icon)
        val normalIconColor = ContextCompat.getColor(this, R.color.incall_ctrl_icon)
        val activeLabelColor = ContextCompat.getColor(this, R.color.incall_ctrl_active_label)
        val normalLabelColor = ContextCompat.getColor(this, R.color.incall_text_secondary)

        isMuted = SipEngine.isCallMuted || audioRouteManager.isMicrophoneMute()
        isSpeaker = audioRouteManager.isEffectiveSpeakerOn()
        isHeld = SipEngine.isCallOnHold
        binding.ibMute.setImageResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        binding.ibMute.setBackgroundResource(if (isMuted) R.drawable.bg_incall_control_button_active else R.drawable.bg_incall_control_button)
        binding.ibMute.setColorFilter(if (isMuted) activeIconColor else normalIconColor)
        binding.tvMuteLabel.text = if (isMuted) "Unmute" else "Mute"
        binding.tvMuteLabel.setTextColor(if (isMuted) activeLabelColor else normalLabelColor)

        binding.ibSpeaker.setBackgroundResource(if (isSpeaker) R.drawable.bg_incall_control_button_active else R.drawable.bg_incall_control_button)
        binding.ibSpeaker.setColorFilter(if (isSpeaker) activeIconColor else normalIconColor)
        binding.tvSpeakerLabel.text = if (isSpeaker) "Earpiece" else "Speaker"
        binding.tvSpeakerLabel.setTextColor(if (isSpeaker) activeLabelColor else normalLabelColor)

        binding.ibHold.setBackgroundResource(if (isHeld) R.drawable.bg_incall_control_button_active else R.drawable.bg_incall_control_button)
        binding.ibHold.setColorFilter(if (isHeld) activeIconColor else normalIconColor)
        binding.tvHoldLabel.text = if (isHeld) "Resume" else "Hold"
        binding.tvHoldLabel.setTextColor(if (isHeld) activeLabelColor else normalLabelColor)
        if (isHeld) {
            binding.tvCallState.text = "On Hold"
            binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.registering_amber))
        }

        val recActive = CallRecorder.isRecording
        binding.ibRecord.setBackgroundResource(if (recActive) R.drawable.bg_hangup_button else R.drawable.bg_incall_control_button)
        binding.ibRecord.setColorFilter(if (recActive) 0xFFFFFFFF.toInt() else normalIconColor)
        binding.tvRecordLabel.text = if (recActive) "Recording" else "Record"
        binding.tvRecordLabel.setTextColor(if (recActive) ContextCompat.getColor(this, R.color.failed_red) else normalLabelColor)

        updateProximityState()
    }

    private fun resetCallUiAndState(num: String, name: String, incoming: Boolean) {
        timerHandler.removeCallbacksAndMessages(null)
        try { activeDialog?.dismiss() } catch (_: Exception) {}
        activeDialog = null
        try { toneGenerator?.stopTone() } catch (_: Exception) {}
        CallProximityManager.forceRelease("resetCallUiAndState")
        applyFallbackProximityBlank(false)

        isEnding.set(false)
        isExplicitUserHangup = false
        isCallConnected = false
        isCallFailed = false
        isReconnecting = false
        connectedElapsedRealtime = 0L
        durationSeconds = 0
        startTime = System.currentTimeMillis()
        isIncoming = incoming
        hasCallInitiated = true

        isMuted = false
        isSpeaker = false
        isHeld = false
        audioRouteManager.setMicrophoneMute(false)
        SipEngine.setMute(false)
        val normalIconColor = ContextCompat.getColor(this, R.color.incall_ctrl_icon)
        val normalLabelColor = ContextCompat.getColor(this, R.color.incall_text_secondary)

        binding.ibMute.setImageResource(R.drawable.ic_mic)
        binding.ibMute.setBackgroundResource(R.drawable.bg_incall_control_button)
        binding.ibMute.setColorFilter(normalIconColor)
        binding.tvMuteLabel.text = "Mute"
        binding.tvMuteLabel.setTextColor(normalLabelColor)

        binding.ibSpeaker.setBackgroundResource(R.drawable.bg_incall_control_button)
        binding.ibSpeaker.setColorFilter(normalIconColor)
        binding.tvSpeakerLabel.text = "Speaker"
        binding.tvSpeakerLabel.setTextColor(normalLabelColor)

        binding.ibHold.setBackgroundResource(R.drawable.bg_incall_control_button)
        binding.ibHold.setColorFilter(normalIconColor)
        binding.tvHoldLabel.text = "Hold"
        binding.tvHoldLabel.setTextColor(normalLabelColor)

        binding.ibRecord.setBackgroundResource(R.drawable.bg_incall_control_button)
        binding.ibRecord.setColorFilter(normalIconColor)
        binding.tvRecordLabel.text = "Record"
        binding.tvRecordLabel.setTextColor(normalLabelColor)

        binding.ibCallAdd.setBackgroundResource(R.drawable.bg_incall_control_button)
        binding.ibCallAdd.setColorFilter(normalIconColor)

        updateRemoteCallerUi(num, name)
        binding.tvCallState.text = if (incoming) "Connecting..." else "Calling..."
        binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.incall_state_accent))
        binding.tvCallDuration.visibility = View.GONE
        binding.layoutQualityIndicator.visibility = View.GONE
    }

    override fun onStart() {
        super.onStart()
        try {
            if (avatarHaloAnimator?.isPaused == true) {
                avatarHaloAnimator?.resume()
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        isUserMinimizingCall = false
        isActivityForeground = true
        if (!isSwitchingCall && !isEnding.get()) {
            syncUiWithCurrentEngineState()
            if (SipEngine.isInCall() && !isCallFailed) {
                syncControlVisualsFromLiveState()
            }
        }
        updateProximityState()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Only treat as explicit user minimize (Home/Recents/App switch) when screen is awake and NOT at the ear!
        if (CallProximityManager.isScreenInteractive(this) && !CallProximityManager.isNearEar) {
            isUserMinimizingCall = true
            isActivityForeground = false
            CallProximityManager.forceRelease("CallActivity onUserLeaveHint")
            applyFallbackProximityBlank(false)
        }
    }

    override fun onPause() {
        // CRITICAL for OEMs with mSuspendWhenScreenOffDueToProximityConfig=true (e.g. Infinix/Transsion/Unisoc):
        // When PROXIMITY_SCREEN_OFF_WAKE_LOCK turns off the display at the ear, Android pauses/stops CallActivity!
        // We MUST keep proximity active while the screen is off due to proximity so the screen stays OFF at the ear.
        val keptOffByProximity = isCallConnected &&
            !isFinishing &&
            !isDestroyed &&
            !isEnding.get() &&
            !isUserMinimizingCall &&
            (!CallProximityManager.isScreenInteractive(this) || CallProximityManager.isNearEar)

        if (!keptOffByProximity) {
            isActivityForeground = false
            CallProximityManager.forceRelease("CallActivity onPause")
            applyFallbackProximityBlank(false)
        }
        super.onPause()
    }

    override fun onStop() {
        val keptOffByProximity = isCallConnected &&
            !isFinishing &&
            !isDestroyed &&
            !isEnding.get() &&
            !isUserMinimizingCall &&
            (!CallProximityManager.isScreenInteractive(this) || CallProximityManager.isNearEar)

        if (!keptOffByProximity) {
            isActivityForeground = false
            CallProximityManager.forceRelease("CallActivity onStop")
            applyFallbackProximityBlank(false)
        }
        try {
            if (avatarHaloAnimator?.isRunning == true) {
                avatarHaloAnimator?.pause()
            }
        } catch (_: Exception) {}
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        // Always cancel any pending 2.2s FAILED auto-finish timer immediately so a warm handoff can never be killed!
        timerHandler.removeCallbacks(failedFinishRunnable)
        if (intent.getBooleanExtra("clear_missed", false)) {
            com.opentalk.android.notifications.MissedCallNotifier.clearMissedCalls(this)
        }

        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val returnToCall = intent.getBooleanExtra("return_to_call", false) || launchedFromHistory
        val autoAnswer = intent.getBooleanExtra("auto_answer", false)
        val num = intent.getStringExtra("number")?.trim()?.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: SipEngine.activeCallNumber?.takeIf { it.isNotBlank() }
            ?: remoteNumber
        val name = intent.getStringExtra("name")?.trim()?.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: SipEngine.activeCallName?.takeIf { it.isNotBlank() }
            ?: num
        val normNum = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(num)
        val normActive = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(SipEngine.activeCallNumber ?: "")
        val normRemote = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(remoteNumber)
        val sameConnectedSession = isCallConnected &&
            (SipEngine.callConnectedStartTime == 0L || connectedElapsedRealtime == SipEngine.callConnectedStartTime)
        val sameRingingOrConnectingSession = !isCallConnected &&
            (SipEngine.currentCallState == CallState.CONNECTING || SipEngine.currentCallState == CallState.RINGING) &&
            ((normActive.isNotBlank() && (normActive == normNum || normActive == normRemote)) || returnToCall)
        val sameLiveSession = !isCallFailed && !isEnding.get() && (sameConnectedSession || sameRingingOrConnectingSession)

        // Explicit return-to-call (notification tap, MainActivity banner, or Recents):
        // NEVER hang up, NEVER redial, NEVER cut an active or incoming ringing call!
        if (returnToCall && !autoAnswer) {
            if (SipEngine.hasActiveIncomingCall()) {
                val incIntent = Intent(this, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("number", SipEngine.activeCallNumber ?: num)
                    putExtra("name", SipEngine.activeCallName ?: name)
                    putExtra("return_to_incoming", true)
                }
                isEnding.set(true)
                try { startActivity(incIntent) } catch (_: Exception) {}
                if (!isFinishing && !isDestroyed) finish()
                return
            }
            if (!SipEngine.isInCall()) {
                isEnding.set(true)
                if (!isFinishing && !isDestroyed) finish()
                return
            }
            if (sameLiveSession) {
                isEnding.set(false)
                hasCallInitiated = true
                isIncoming = SipEngine.isCurrentCallIncoming
                if (num.isNotBlank() && num != "Unknown") {
                    updateRemoteCallerUi(num, name)
                }
                syncControlVisualsFromLiveState()
                syncUiWithCurrentEngineState()
                return
            }
            // Stale/failed Activity instance being reused for a live call: re-initialize UI, focus & foreground service cleanly!
            setIntent(intent)
            resetCallUiAndState(num, name, incoming = SipEngine.isCurrentCallIncoming)
            audioRouteManager.acquireVoipAudioFocus(preserveCurrentRouting = true)
            SipVoipService.startCall(this, remoteNumber, remoteName)
            syncControlVisualsFromLiveState()
            syncUiWithCurrentEngineState()
            return
        }

        // Returning to the currently active incoming OR outgoing call by matching number: NEVER hang up!
        if (sameLiveSession && !autoAnswer && !SipEngine.hasActiveIncomingCall() && SipEngine.isInCall() &&
            ((normActive.isNotBlank() && normActive == normNum) || normNum.isBlank())) {
            isEnding.set(false)
            hasCallInitiated = true
            isIncoming = SipEngine.isCurrentCallIncoming
            syncControlVisualsFromLiveState()
            syncUiWithCurrentEngineState()
            return
        }

        setIntent(intent)
        val incoming = intent.getBooleanExtra("is_incoming", false)

        if (incoming) {
            if (!autoAnswer && SipEngine.hasActiveIncomingCall()) {
                val incIntent = Intent(this, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("number", SipEngine.activeCallNumber ?: num)
                    putExtra("name", SipEngine.activeCallName ?: name)
                    putExtra("return_to_incoming", true)
                }
                isEnding.set(true)
                try { startActivity(incIntent) } catch (_: Exception) {}
                if (!isFinishing && !isDestroyed) finish()
                return
            }
            val canEnterIncomingCall = SipEngine.currentCallState == CallState.CONNECTED ||
                (autoAnswer && (SipEngine.hasActiveIncomingCall() || SipEngine.isAnsweringIncoming()))
            if (!canEnterIncomingCall) {
                Toast.makeText(this, "Call ended", Toast.LENGTH_SHORT).show()
                isEnding.set(true)
                finish()
                return
            }
            resetCallUiAndState(num, name, incoming = true)
            audioRouteManager.acquireVoipAudioFocus()
            SipVoipService.startCall(this, remoteNumber, remoteName)
            if (SipEngine.currentCallState == CallState.CONNECTED) {
                onCallConnected()
            } else {
                IncomingCallActivity.stopRingtoneOnly()
                val answerDeferred = SipEngine.engineScope.async { SipEngine.answerIncomingCall() }
                lifecycleScope.launch {
                    val success = try { answerDeferred.await() } catch (_: Exception) { false }
                    if (success && SipEngine.currentCallState == CallState.CONNECTED) {
                        onCallConnected()
                    } else {
                        Toast.makeText(this@CallActivity, "Call ended by caller", Toast.LENGTH_SHORT).show()
                        endCall()
                    }
                }
            }
        } else {
            // Stop any active call recording from the previous call before switching
            if (CallRecorder.isRecording) {
                try { CallRecorder.stopRecording() } catch (_: Exception) {}
            }
            // Switching to a brand-new outgoing call: save any completed prior call off Main Thread first!
            if (!isEnding.get() && isCallConnected && connectedElapsedRealtime > 0L) {
                val dur = maxOf(0L, (SystemClock.elapsedRealtime() - connectedElapsedRealtime) / 1000).toInt()
                val prevRecord = CallRecord(
                    remoteNumber = remoteNumber,
                    displayName = remoteName,
                    type = if (isIncoming) CallType.INCOMING else CallType.OUTGOING,
                    timestamp = startTime,
                    durationSeconds = dur
                )
                val helper = dbHelper
                SipEngine.engineScope.launch(Dispatchers.IO) {
                    try { helper.insertCall(prevRecord) } catch (_: Exception) {}
                }
            }

            val hadPreviousCall = SipEngine.isInCall()
            isSwitchingCall = hadPreviousCall
            switchingPriorCallGen = if (hadPreviousCall) SipEngine.currentCallGeneration() else -1L
            try {
                if (hadPreviousCall) {
                    SipEngine.hangUp(sendByeOrCancel = true)
                }
                resetCallUiAndState(num, name, incoming = false)
                audioRouteManager.acquireVoipAudioFocus()
                SipVoipService.startCall(this, remoteNumber, remoteName)
                SipEngine.startOutgoingCall(remoteNumber)
            } finally {
                if (hadPreviousCall) {
                    timerHandler.postDelayed({
                        isSwitchingCall = false
                        switchingPriorCallGen = -1L
                        syncUiWithCurrentEngineState()
                    }, 400L)
                }
            }
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        audioRouteManager.setMicrophoneMute(isMuted)
        SipEngine.setMute(isMuted)
        if (isMuted) {
            binding.ibMute.setImageResource(R.drawable.ic_mic_off)
            binding.ibMute.setBackgroundResource(R.drawable.bg_incall_control_button_active)
            binding.ibMute.setColorFilter(ContextCompat.getColor(this, R.color.incall_ctrl_active_icon))
            binding.tvMuteLabel.text = "Unmute"
            binding.tvMuteLabel.setTextColor(ContextCompat.getColor(this, R.color.incall_ctrl_active_label))
            Toast.makeText(this, "Microphone muted", Toast.LENGTH_SHORT).show()
        } else {
            binding.ibMute.setImageResource(R.drawable.ic_mic)
            binding.ibMute.setBackgroundResource(R.drawable.bg_incall_control_button)
            binding.ibMute.setColorFilter(ContextCompat.getColor(this, R.color.incall_ctrl_icon))
            binding.tvMuteLabel.text = "Mute"
            binding.tvMuteLabel.setTextColor(ContextCompat.getColor(this, R.color.incall_text_secondary))
            Toast.makeText(this, "Microphone unmuted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSpeaker() {
        isSpeaker = !isSpeaker
        audioRouteManager.setSpeakerphone(isSpeaker)
        if (isSpeaker) {
            binding.ibSpeaker.setBackgroundResource(R.drawable.bg_incall_control_button_active)
            binding.ibSpeaker.setColorFilter(ContextCompat.getColor(this, R.color.incall_ctrl_active_icon))
            binding.tvSpeakerLabel.text = "Earpiece"
            binding.tvSpeakerLabel.setTextColor(ContextCompat.getColor(this, R.color.incall_ctrl_active_label))
            Toast.makeText(this, "Speaker ON", Toast.LENGTH_SHORT).show()
        } else {
            binding.ibSpeaker.setBackgroundResource(R.drawable.bg_incall_control_button)
            binding.ibSpeaker.setColorFilter(ContextCompat.getColor(this, R.color.incall_ctrl_icon))
            binding.tvSpeakerLabel.text = "Speaker"
            binding.tvSpeakerLabel.setTextColor(ContextCompat.getColor(this, R.color.incall_text_secondary))
            Toast.makeText(this, "Earpiece ON", Toast.LENGTH_SHORT).show()
        }
        updateProximityState()
    }

    /**
     * Evaluates all proximity sensor conditions:
     * - Active ONLY when call is connected, CallActivity is foreground, Speaker is OFF,
     *   no Bluetooth/wired/USB headset is connected, and call is not on hold.
     */
    private fun updateProximityState() {
        if (isEnding.get() || isFinishing || isDestroyed || isCallFailed) {
            CallProximityManager.forceRelease("CallActivity inactive/ending")
            return
        }
        val speakerActive = isSpeaker || audioRouteManager.isEffectiveSpeakerOn()
        val extHeadset = audioRouteManager.isExternalAudioDeviceConnected()
        CallProximityManager.updateState(
            context = this,
            isCallConnected = isCallConnected,
            isUiForeground = isActivityForeground,
            isSpeakerOn = speakerActive,
            isExternalHeadsetConnected = extHeadset,
            isOnHold = isHeld
        )
    }

    /**
     * Fallback touch-blocking dark screen shield used ONLY on rare devices where
     * PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK is unsupported by hardware/firmware.
     */
    private fun applyFallbackProximityBlank(nearEar: Boolean) {
        try {
            val contentRoot = findViewById<ViewGroup>(android.R.id.content) ?: return
            if (nearEar) {
                try { activeDialog?.dismiss() } catch (_: Exception) {}
                activeDialog = null
                if (fallbackProximityOverlay == null) {
                    savedScreenBrightness = window.attributes.screenBrightness
                    val overlay = View(this).apply {
                        setBackgroundColor(0xFF000000.toInt())
                        isClickable = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setOnTouchListener { _, _ -> true }
                        elevation = 1000f
                    }
                    contentRoot.addView(
                        overlay,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    fallbackProximityOverlay = overlay
                }
                window.attributes = window.attributes.apply {
                    screenBrightness = 0.01f
                }
            } else {
                fallbackProximityOverlay?.let { overlay ->
                    contentRoot.removeView(overlay)
                }
                fallbackProximityOverlay = null
                window.attributes = window.attributes.apply {
                    screenBrightness = savedScreenBrightness
                }
            }
        } catch (_: Exception) {}
    }

    private fun toggleHold() {
        if (!isCallConnected || SipEngine.currentCallState != CallState.CONNECTED) {
            Toast.makeText(this, "Hold available once connected", Toast.LENGTH_SHORT).show()
            return
        }
        isHeld = !isHeld
        SipEngine.setHold(isHeld)
        if (isHeld) {
            binding.ibHold.setBackgroundResource(R.drawable.bg_incall_control_button_active)
            binding.ibHold.setColorFilter(ContextCompat.getColor(this, R.color.incall_ctrl_active_icon))
            binding.tvHoldLabel.text = "Resume"
            binding.tvHoldLabel.setTextColor(ContextCompat.getColor(this, R.color.incall_ctrl_active_label))
            binding.tvCallState.text = "On Hold"
            binding.tvCallState.setTextColor(ContextCompat.getColor(this, R.color.registering_amber))
            Toast.makeText(this, "Call placed on hold", Toast.LENGTH_SHORT).show()
        } else {
            binding.ibHold.setBackgroundResource(R.drawable.bg_incall_control_button)
            binding.ibHold.setColorFilter(ContextCompat.getColor(this, R.color.incall_ctrl_icon))
            binding.tvHoldLabel.text = "Hold"
            binding.tvHoldLabel.setTextColor(ContextCompat.getColor(this, R.color.incall_text_secondary))
            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.post(timerRunnable)
            Toast.makeText(this, "Call resumed", Toast.LENGTH_SHORT).show()
        }
        updateProximityState()
    }

    private fun toggleRecord() {
        try {
            if (CallRecorder.isRecording) {
                val recordedFile = CallRecorder.stopRecording()
                binding.ibRecord.setBackgroundResource(R.drawable.bg_incall_control_button)
                binding.ibRecord.setColorFilter(ContextCompat.getColor(this, R.color.incall_ctrl_icon))
                binding.tvRecordLabel.text = "Record"
                binding.tvRecordLabel.setTextColor(ContextCompat.getColor(this, R.color.incall_text_secondary))
                if (recordedFile != null) {
                    Toast.makeText(this, "Recording saved:\n${recordedFile.name}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "No audio recorded", Toast.LENGTH_SHORT).show()
                }
            } else {
                val file = CallRecorder.startRecording(this, remoteNumber, remoteName)
                if (file != null) {
                    binding.ibRecord.setBackgroundResource(R.drawable.bg_hangup_button)
                    binding.ibRecord.setColorFilter(0xFFFFFFFF.toInt())
                    binding.tvRecordLabel.text = "Recording"
                    binding.tvRecordLabel.setTextColor(ContextCompat.getColor(this, R.color.failed_red))
                    Toast.makeText(this, "Call recording started...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("CallActivity", "Error toggling record: ${e.message}", e)
            Toast.makeText(this, "Recording error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInCallDtmfKeypad() {
        if (isFinishing || isDestroyed) return
        val dtmfBuffer = StringBuilder()
        val density = resources.displayMetrics.density

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dark_sheet)
            val hPad = (20 * density).toInt()
            val vPad = (14 * density).toInt()
            setPadding(hPad, vPad, hPad, (26 * density).toInt())
        }

        val handle = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@CallActivity, R.color.incall_text_tertiary))
            layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (14 * density).toInt()
            }
        }
        rootLayout.addView(handle)

        val title = TextView(this).apply {
            text = "In-Call Keypad (DTMF)"
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.incall_text_tertiary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        rootLayout.addView(title)

        val tvDtmfDigits = TextView(this).apply {
            text = ""
            hint = "Touch keys to send tones"
            setHintTextColor(ContextCompat.getColor(this@CallActivity, R.color.incall_text_tertiary))
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.incall_text_primary))
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (14 * density).toInt())
        }
        rootLayout.addView(tvDtmfDigits)

        val grid = GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        val size = (density * 64).toInt()
        val margin = (density * 8).toInt()
        val keyTextColor = ContextCompat.getColor(this, R.color.incall_text_primary)

        for (k in keys) {
            val btn = TextView(this).apply {
                text = k
                textSize = 24f
                setTextColor(keyTextColor)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_incall_control_button)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(margin, margin, margin, margin)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val charKey = k[0]
                    SipEngine.sendDtmf(charKey)
                    playDtmfTone(charKey)
                    dtmfBuffer.append(k)
                    tvDtmfDigits.text = dtmfBuffer.toString()
                }
            }
            grid.addView(btn)
        }
        rootLayout.addView(grid)

        try { activeDialog?.dismiss() } catch (_: Exception) {}
        val sheet = BottomSheetDialog(this)

        val btnDone = TextView(this).apply {
            text = "Done"
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.incall_text_primary))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dark_voice_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (46 * density).toInt()
            ).apply {
                topMargin = (14 * density).toInt()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { sheet.dismiss() }
        }
        rootLayout.addView(btnDone)

        sheet.setContentView(rootLayout)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.setOnDismissListener {
            if (activeDialog === sheet) activeDialog = null
        }
        activeDialog = sheet
        sheet.show()
    }

    private fun showAddCallSheet() {
        if (isFinishing || isDestroyed) return
        val sheet = BottomSheetDialog(this)
        val sheetBinding = DialogAddCallBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        val numberBuffer = StringBuilder()
        addCallNumberBuffer = numberBuffer
        tvAddCallDigitsRef = sheetBinding.tvAddCallDigits

        val initialNumber = pendingAddCallNumber
        if (!initialNumber.isNullOrBlank()) {
            numberBuffer.append(initialNumber)
            sheetBinding.tvAddCallDigits.text = initialNumber
            pendingAddCallNumber = null
        }

        val density = resources.displayMetrics.density
        val btnSize = (60 * density).toInt()
        val btnMargin = (6 * density).toInt()
        val keyTextColor = ContextCompat.getColor(this, R.color.incall_text_primary)
        val keySubColor = ContextCompat.getColor(this, R.color.incall_text_tertiary)

        val keyData = listOf(
            Triple("1", "", '1'),
            Triple("2", "ABC", '2'),
            Triple("3", "DEF", '3'),
            Triple("4", "GHI", '4'),
            Triple("5", "JKL", '5'),
            Triple("6", "MNO", '6'),
            Triple("7", "PQRS", '7'),
            Triple("8", "TUV", '8'),
            Triple("9", "WXYZ", '9'),
            Triple("*", "", '*'),
            Triple("0", "+", '0'),
            Triple("#", "", '#')
        )

        for (item in keyData) {
            val digit = item.first
            val sub = item.second
            val dtmfChar = item.third

            val keyContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_incall_control_button)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = btnSize
                    height = btnSize
                    setMargins(btnMargin, btnMargin, btnMargin, btnMargin)
                }
                isClickable = true
                isFocusable = true

                addView(TextView(this@CallActivity).apply {
                    text = digit
                    textSize = 21f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(keyTextColor)
                    gravity = Gravity.CENTER
                })

                if (sub.isNotBlank()) {
                    addView(TextView(this@CallActivity).apply {
                        text = sub
                        textSize = 8.5f
                        setTextColor(keySubColor)
                        gravity = Gravity.CENTER
                        letterSpacing = 0.08f
                    })
                }

                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    playDtmfTone(dtmfChar)
                    numberBuffer.append(digit)
                    sheetBinding.tvAddCallDigits.text = numberBuffer.toString()
                }

                if (digit == "0") {
                    setOnLongClickListener {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        numberBuffer.append("+")
                        sheetBinding.tvAddCallDigits.text = numberBuffer.toString()
                        true
                    }
                }
            }
            sheetBinding.gridAddCallKeypad.addView(keyContainer)
        }

        // Backspace button
        sheetBinding.btnAddCallBackspace.setOnClickListener {
            if (numberBuffer.isNotEmpty()) {
                numberBuffer.deleteCharAt(numberBuffer.length - 1)
                sheetBinding.tvAddCallDigits.text = numberBuffer.toString()
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
        sheetBinding.btnAddCallBackspace.setOnLongClickListener {
            if (numberBuffer.isNotEmpty()) {
                numberBuffer.setLength(0)
                sheetBinding.tvAddCallDigits.text = ""
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            true
        }

        // Close button
        sheetBinding.btnCloseAddCall.setOnClickListener {
            sheet.dismiss()
        }

        // Pick contact
        sheetBinding.btnAddCallPickContact.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                contactPickerLauncher.launch(intent)
            } catch (_: Exception) {
                Toast.makeText(this@CallActivity, "Contacts not accessible", Toast.LENGTH_SHORT).show()
            }
        }

        // Call button
        sheetBinding.btnAddCallDial.setOnClickListener {
            val target = numberBuffer.toString().trim()
            if (target.isBlank()) {
                Toast.makeText(this@CallActivity, "Please enter a phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sheet.dismiss()

            // Place current call on hold if connected & not already on hold
            if (isCallConnected && !isHeld) {
                toggleHold()
            }

            // Initiate new outgoing call
            val newCallIntent = Intent(this@CallActivity, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("number", target)
                putExtra("name", target)
                putExtra("is_incoming", false)
            }
            startActivity(newCallIntent)
        }

        // Transfer button
        sheetBinding.btnAddCallTransfer.setOnClickListener {
            val target = numberBuffer.toString().trim()
            if (target.isBlank()) {
                Toast.makeText(this@CallActivity, "Please enter a number to transfer to", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sheet.dismiss()
            val ok = SipEngine.transferCall(target)
            if (ok) {
                Toast.makeText(this@CallActivity, "Transferring call to $target...", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@CallActivity, "Transfer request sent for $target", Toast.LENGTH_SHORT).show()
            }
        }

        try { activeDialog?.dismiss() } catch (_: Exception) {}
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.setOnDismissListener {
            if (activeDialog === sheet) activeDialog = null
            addCallNumberBuffer = null
            tvAddCallDigitsRef = null
        }
        activeDialog = sheet
        sheet.show()
    }

    private fun playDtmfTone(key: Char) {
        val tone = when (key) {
            '0' -> ToneGenerator.TONE_DTMF_0
            '1' -> ToneGenerator.TONE_DTMF_1
            '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3
            '4' -> ToneGenerator.TONE_DTMF_4
            '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6
            '7' -> ToneGenerator.TONE_DTMF_7
            '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        try {
            toneGenerator?.startTone(tone, 120)
        } catch (_: Exception) {}
    }

    private fun endCall() {
        if (!isEnding.compareAndSet(false, true)) return
        try { toneGenerator?.stopTone() } catch (_: Exception) {}
        try { activeDialog?.dismiss() } catch (_: Exception) {}
        activeDialog = null
        CallProximityManager.forceRelease("CallActivity endCall")
        applyFallbackProximityBlank(false)
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.removeCallbacks(failedFinishRunnable)

        if (CallRecorder.isRecording) {
            val rec = CallRecorder.stopRecording()
            if (rec != null) {
                Toast.makeText(this, "Call recording saved:\n${rec.name}", Toast.LENGTH_SHORT).show()
            }
        }

        SipEngine.hangUp()
        audioRouteManager.releaseVoipAudioFocus()
        AudioRouteManager.resetToNormal(this)
        SipVoipService.stopCall(this)

        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(SipVoipService.NOTIFICATION_ID_INCOMING)
            SipVoipService.dismissIncomingCall(this)
        } catch (_: Exception) {}

        if (connectedElapsedRealtime > 0L) {
            val accurate = maxOf(0L, (SystemClock.elapsedRealtime() - connectedElapsedRealtime) / 1000).toInt()
            durationSeconds = maxOf(durationSeconds, accurate)
        }

        // Save call record to local SQLite off Main Thread (using engineScope so insert always completes after finish())
        if (isCallConnected || !isIncoming) {
            val recordToSave = CallRecord(
                remoteNumber = remoteNumber,
                displayName = remoteName,
                type = if (isIncoming) CallType.INCOMING else CallType.OUTGOING,
                timestamp = startTime,
                durationSeconds = durationSeconds
            )
            val helper = dbHelper
            SipEngine.engineScope.launch(Dispatchers.IO) {
                try { helper.insertCall(recordToSave) } catch (_: Exception) {}
            }
        }

        // Only open Recents if user was actively looking at CallActivity (never hijack another app or wake locked screen!)
        if ((hasCallInitiated || isCallConnected || isIncoming) &&
            isActivityForeground &&
            !isUserMinimizingCall &&
            CallProximityManager.isScreenInteractive(this)
        ) {
            openRecentsAfterCallEnd()
        }

        if (!isFinishing) finish()
    }

    private fun setupCallQualityIndicator() {
        binding.layoutQualityIndicator.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            val levelStr = when (lastQualityLevel) {
                2 -> "Excellent (HD Voice)"
                1 -> "Good (Stable)"
                else -> "Weak (High Jitter)"
            }
            try { activeDialog?.dismiss() } catch (_: Exception) {}
            val activeCodec = SipEngine.getActiveCodecName()
            val dialog = AlertDialog.Builder(this)
                .setTitle("Live Call Quality Stats")
                .setMessage(
                    "• Network Status: $levelStr\n" +
                    "• Latency Estimate: ${lastQualityLatency} ms\n" +
                    "• Negotiated Codec: $activeCodec\n" +
                    "• Audio Enhancement: Hardware Echo Cancellation & Noise Suppression Active\n" +
                    "• Packet Interval: 20 ms (Smooth Audio Stream)"
                )
                .setPositiveButton("OK", null)
                .create()
            dialog.setOnDismissListener {
                if (activeDialog === dialog) activeDialog = null
            }
            activeDialog = dialog
            dialog.show()
        }

        val qualityCb: (Long, Int) -> Unit = { latencyMs, qualityLevel ->
            runOnUiThread {
                if (isFinishing || isEnding.get()) return@runOnUiThread
                lastQualityLatency = latencyMs
                lastQualityLevel = qualityLevel
                binding.layoutQualityIndicator.visibility = View.VISIBLE
                when (qualityLevel) {
                    2 -> {
                        binding.viewQualityDot.backgroundTintList = ColorStateList.valueOf(0xFF10B981.toInt())
                        binding.tvQualityText.text = "HD Voice • ${latencyMs}ms"
                        binding.tvQualityText.setTextColor(ContextCompat.getColor(this@CallActivity, R.color.incall_quality_text))
                    }
                    1 -> {
                        binding.viewQualityDot.backgroundTintList = ColorStateList.valueOf(0xFFF59E0B.toInt())
                        binding.tvQualityText.text = "Good • ${latencyMs}ms"
                        binding.tvQualityText.setTextColor(0xFFD97706.toInt())
                    }
                    else -> {
                        binding.viewQualityDot.backgroundTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
                        binding.tvQualityText.text = "Weak • ${latencyMs}ms"
                        binding.tvQualityText.setTextColor(ContextCompat.getColor(this@CallActivity, R.color.call_red))
                    }
                }
            }
        }
        callQualityListenerRef = qualityCb
        SipEngine.setCallQualityListener(qualityCb)
    }

    private fun formatCallFailureMessage(message: String): String {
        val lower = message.lowercase()
        return when {
            lower.contains("404") || lower.contains("484") || lower.contains("not found") -> "Number Not Found"
            lower.contains("invalid") || lower.contains("নাম্বার") -> "Invalid Number"
            lower.contains("congestion") || lower.contains("network busy") -> "Network Busy"
            lower.contains("486") || lower.contains("600") || lower.contains("busy") || lower.contains("ব্যস্ত") -> "Busy"
            lower.contains("403") || lower.contains("forbidden") || lower.contains("balance") || lower.contains("restricted") || lower.contains("অনুমতি") -> "Call Restricted"
            lower.contains("rejected") -> "Call Rejected"
            lower.contains("480") || lower.contains("unavailable") || lower.contains("switched off") -> "Number Unavailable"
            lower.contains("not connected") || lower.contains("no connection") || lower.contains("408") || lower.contains("timeout") || lower.contains("unreachable") || lower.contains("সংযোগ") -> "Not Connected"
            lower.contains("500") || lower.contains("501") || lower.contains("502") || lower.contains("503") || lower.contains("server") || lower.contains("সার্ভার") -> "Server Error"
            lower.contains("no answer") || lower.contains("timed out") || lower.contains("উত্তর") -> "No Answer"
            lower.contains("internet") || lower.contains("network") || lower.contains("offline") || lower.contains("ইন্টারনেট") -> "No Internet"
            lower.contains("decline") || lower.contains("603") -> "Call Declined"
            lower.contains("cancelled") || lower.contains("canceled") || lower.contains("487") -> "Call Cancelled"
            lower.contains("ended") || lower.contains("idle") -> "Call Ended"
            message.isNotBlank() && !message.contains(Regex("""\(\d{3}\)|\b\d{3}\b""")) && message.length <= 25 -> message
            else -> "Call Failed"
        }
    }

    override fun onDestroy() {
        isActivityForeground = false
        CallProximityManager.onFallbackNearChanged = null
        CallProximityManager.forceRelease("CallActivity onDestroy")
        applyFallbackProximityBlank(false)
        callQualityListenerRef?.let { SipEngine.removeCallQualityListener(it) }
        callQualityListenerRef = null
        callStateListener?.let { SipEngine.removeCallStateListener(it) }
        callStateListener = null
        // Always detach AudioDeviceCallback & listener so destroyed Activity never leaks into AudioManager
        audioRouteManager.onAudioRouteChanged = null
        audioRouteManager.detachDeviceCallback()
        if (isExplicitUserHangup || !SipEngine.isInCall()) {
            if (CallRecorder.isRecording) {
                CallRecorder.stopRecording()
            }
            endCall()
            if (!SipEngine.isInCall()) {
                SipEngine.currentCallState = CallState.IDLE
            }
        } else {
            android.util.Log.i("CallActivity", "CallActivity destroyed while SIP call stays active in background service (keeping recording & audio focus alive).")
        }
        try { avatarHaloAnimator?.cancel() } catch (_: Exception) {}
        avatarHaloAnimator = null
        try { activeDialog?.dismiss() } catch (_: Exception) {}
        activeDialog = null
        CallProximityManager.forceRelease("CallActivity onDestroy final")
        timerHandler.removeCallbacksAndMessages(null)
        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null
        super.onDestroy()
    }
}
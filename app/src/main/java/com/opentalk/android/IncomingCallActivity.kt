package com.opentalk.android

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.opentalk.android.databinding.ActivityIncomingCallBinding
import com.opentalk.android.engine.SipEngine
import com.opentalk.android.services.SipVoipService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class IncomingCallActivity : AppCompatActivity() {
    companion object {
        private val ringtoneLock = Any()
        @Volatile
        private var activeInstance: java.lang.ref.WeakReference<IncomingCallActivity>? = null
        @Volatile
        private var globalRingtone: Ringtone? = null
        @Volatile
        private var globalVibrator: Vibrator? = null

        private fun stopGlobalRingtoneAndVibrate() {
            synchronized(ringtoneLock) {
                try { globalRingtone?.stop() } catch (_: Exception) {}
                globalRingtone = null
                try { globalVibrator?.cancel() } catch (_: Exception) {}
                globalVibrator = null
            }
        }

        /**
         * Direct, zero-latency kill-switch invoked synchronously from SipEngine whenever
         * CANCEL, BYE, Decline, or Timeout ends the incoming ringing dialog.
         */
        fun stopRingingAndDismissImmediately() {
            stopGlobalRingtoneAndVibrate()
            val act = activeInstance?.get() ?: return
            act.stopRingtoneAndVibrate()
            try {
                act.runOnUiThread {
                    act.stopRingtoneAndVibrate()
                    if (!act.isFinishing && !act.isDestroyed) {
                        act.finish()
                    }
                }
            } catch (_: Exception) {}
        }

        /**
         * Stops ringtone & vibration immediately without finishing the Activity
         * (used when answering so the UI can cleanly transition to CallActivity).
         */
        fun stopRingtoneOnly() {
            stopGlobalRingtoneAndVibrate()
            val act = activeInstance?.get() ?: return
            act.stopRingtoneAndVibrate()
        }
    }

    private lateinit var binding: ActivityIncomingCallBinding
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var isTestCall = false
    private var userSilencedRingtone = false
    private var callerNumber: String = "Unknown Caller"
    private var callerName: String = "Unknown Caller"
    private val actionTaken = java.util.concurrent.atomic.AtomicBoolean(false)

    private var callTimeoutHandler: android.os.Handler? = null
    private val callTimeoutRunnable = Runnable {
        if (actionTaken.get() || isFinishing || isDestroyed) return@Runnable
        if (!actionTaken.compareAndSet(false, true)) return@Runnable
        android.util.Log.i("IncomingCallActivity", "Incoming call ringing timed out (30s). Dismissing...")
        stopRingtoneAndVibrate()
        SipVoipService.dismissIncomingCall(this@IncomingCallActivity)
        if (!isTestCall) {
            SipEngine.handleIncomingCallMissed()
        }
        if (!isFinishing) finish()
    }

    // 350ms live state guard: guarantees ringing NEVER persists if CANCEL raced with Activity startup
    private val stateGuardRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || actionTaken.get()) return
            if (!isTestCall && !SipEngine.hasActiveIncomingCall()) {
                android.util.Log.i("IncomingCallActivity", "State guard detected incoming call ended. Stopping ringtone immediately.")
                stopRingtoneAndVibrate()
                SipVoipService.dismissIncomingCall(this@IncomingCallActivity)
                finish()
                return
            }
            callTimeoutHandler?.postDelayed(this, 350L)
        }
    }

    private var legacyRingtoneHandler: android.os.Handler? = null
    private val legacyRingtoneRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || actionTaken.get() || ringtone == null) return
            try {
                if (ringtone?.isPlaying == false) {
                    ringtone?.play()
                }
            } catch (_: Exception) {}
            if (!isFinishing && !isDestroyed && !actionTaken.get() && ringtone != null) {
                legacyRingtoneHandler?.postDelayed(this, 2500L)
            }
        }
    }

    private val cancelReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                stopRingtoneAndVibrate()
                SipVoipService.dismissIncomingCall(this@IncomingCallActivity)
                if (!isFinishing) finish()
            }
        }
    }

    private val cancelledCallback: () -> Unit = {
        runOnUiThread {
            stopRingtoneAndVibrate()
            SipVoipService.dismissIncomingCall(this@IncomingCallActivity)
            if (!isFinishing) finish()
        }
    }

    private val callStateCallback: (com.opentalk.android.engine.CallState, String) -> Unit = { state, _ ->
        if (state == com.opentalk.android.engine.CallState.CONNECTED) {
            if (!actionTaken.get()) {
                runOnUiThread {
                    stopRingtoneAndVibrate()
                    if (!isFinishing) finish()
                }
            }
        } else if (state == com.opentalk.android.engine.CallState.ENDED ||
            state == com.opentalk.android.engine.CallState.IDLE) {
            runOnUiThread {
                stopRingtoneAndVibrate()
                if (!isFinishing) finish()
            }
        }
    }

    private fun bindCallerFromIntent(targetIntent: Intent?) {
        isTestCall = when {
            targetIntent?.hasExtra("is_test") == true -> targetIntent.getBooleanExtra("is_test", false)
            targetIntent?.getBooleanExtra("return_to_incoming", false) == true -> isTestCall
            else -> false
        }

        val intentNum = targetIntent?.getStringExtra("number")?.trim().orEmpty()
        val num = when {
            intentNum.isNotBlank() && intentNum != "Unknown Caller" && intentNum != "Unknown" -> intentNum
            !SipEngine.activeCallNumber.isNullOrBlank() -> SipEngine.activeCallNumber!!
            callerNumber.isNotBlank() && callerNumber != "Unknown Caller" -> callerNumber
            else -> "Unknown Caller"
        }

        val canonicalNum = com.opentalk.android.utils.PhoneNumberHelper.toCanonicalE164(num).ifBlank { num }
        val displayNum = com.opentalk.android.utils.PhoneNumberHelper.formatForDisplay(canonicalNum)
        callerNumber = canonicalNum

        fun renderCallerUi(resolved: String?) {
            val hasContactName = !resolved.isNullOrBlank() &&
                !com.opentalk.android.utils.PhoneNumberHelper.looksLikePhoneNumber(resolved) &&
                !com.opentalk.android.utils.PhoneNumberHelper.areSameNumber(resolved, canonicalNum)
            callerName = if (hasContactName) resolved!! else displayNum

            if (hasContactName) {
                binding.tvIncomingName.text = callerName
                binding.tvIncomingNumber.text = displayNum
                binding.tvIncomingNumber.visibility = View.VISIBLE

                val initials = callerName
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .take(2)
                    .mapNotNull { part -> part.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
                    .joinToString("")
                if (initials.isNotBlank()) {
                    binding.tvIncomingAvatarInitials.text = initials
                    binding.tvIncomingAvatarInitials.visibility = View.VISIBLE
                    binding.ivIncomingAvatarIcon.visibility = View.GONE
                } else {
                    binding.tvIncomingAvatarInitials.visibility = View.GONE
                    binding.ivIncomingAvatarIcon.visibility = View.VISIBLE
                }
            } else {
                binding.tvIncomingName.text = displayNum
                binding.tvIncomingNumber.visibility = View.GONE
                binding.tvIncomingAvatarInitials.visibility = View.GONE
                binding.ivIncomingAvatarIcon.visibility = View.VISIBLE
            }
        }

        // Instant 0ms render from in-memory cache (no Main-Thread ContentResolver IPC stall),
        // followed by authoritative live Android Phonebook refresh on Dispatchers.IO!
        val cachedName = com.opentalk.android.utils.ContactResolver.getCachedContactName(canonicalNum)
            ?: targetIntent?.getStringExtra("name")?.trim()?.takeIf { it.isNotBlank() }
        renderCallerUi(cachedName)

        val appCtx = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val liveResolved = com.opentalk.android.utils.ContactResolver.resolveContactName(appCtx, canonicalNum)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isFinishing && !isDestroyed && callerNumber == canonicalNum) {
                    renderCallerUi(liveResolved)
                }
            }
        }
    }

    /**
     * Minimizes the incoming ringing screen safely WITHOUT declining/cutting the call.
     * Posts a silent ongoing notification so the user can tap to return, Accept, or Decline anytime.
     */
    private fun minimizeIncomingCall() {
        if (actionTaken.get() || isFinishing || isDestroyed) return
        if (!isTestCall && !SipEngine.hasActiveIncomingCall()) {
            finish()
            return
        }
        SipVoipService.showSilentIncomingNotification(this, callerNumber, callerName, isTestCall)
        android.widget.Toast.makeText(
            this,
            "Incoming call ringing in background. Tap notification to view.",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        moveTaskToBack(true)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!actionTaken.get() && !isFinishing && !isDestroyed && (isTestCall || SipEngine.hasActiveIncomingCall())) {
            SipVoipService.showSilentIncomingNotification(this, callerNumber, callerName, isTestCall)
        }
    }

    private var isHandingOffToCall = false

    private fun performAccept() {
        if (!isTestCall && !SipEngine.hasActiveIncomingCall()) {
            stopRingtoneAndVibrate()
            SipVoipService.dismissIncomingCall(this)
            android.widget.Toast.makeText(applicationContext, "Call ended by caller", android.widget.Toast.LENGTH_SHORT).show()
            if (!isFinishing && !isDestroyed) finish()
            return
        }
        if (!actionTaken.compareAndSet(false, true)) return
        binding.swipeCallAction.isInteractionEnabled = false
        binding.tvIncomingTitle.text = "CONNECTING"
        binding.tvSwipeHint.text = "Connecting..."
        binding.tvSwipeHint.setTextColor(ContextCompat.getColor(this, R.color.incoming_accept_text))
        stopRingtoneAndVibrate()
        stopAnswerButtonAnimation()
        callTimeoutHandler?.removeCallbacks(callTimeoutRunnable)
        callTimeoutHandler?.removeCallbacks(stateGuardRunnable)
        SipVoipService.dismissIncomingCall(this)
        val targetNumber = callerNumber
        val targetName = callerName
        val appCtx = applicationContext
        if (isTestCall) {
            android.widget.Toast.makeText(appCtx, "Test incoming call accepted", android.widget.Toast.LENGTH_SHORT).show()
            if (!isFinishing && !isDestroyed) finish()
            return
        }
        // Verify SIP 200 OK -> ACK confirmation BEFORE launching CallActivity so a call whose
        // caller already hung up NEVER opens a ghost in-call screen! Run the handoff on engineScope
        // (not lifecycleScope) so lockscreen/background transitions mid-ACK NEVER orphan a live call!
        val activityRef = java.lang.ref.WeakReference(this)
        SipEngine.engineScope.launch {
            val connected = try { SipEngine.answerIncomingCall() } catch (_: Exception) { false }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val act = activityRef.get()
                if (connected && SipEngine.currentCallState == com.opentalk.android.engine.CallState.CONNECTED) {
                    act?.isHandingOffToCall = true
                    val callIntent = Intent(appCtx, CallActivity::class.java).apply {
                        putExtra("number", targetNumber)
                        putExtra("name", targetName)
                        putExtra("is_incoming", true)
                        putExtra("auto_answer", false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    }
                    try {
                        if (act != null && !act.isFinishing && !act.isDestroyed) {
                            act.startActivity(callIntent)
                            @Suppress("DEPRECATION")
                            act.overridePendingTransition(0, 0)
                            // Keep IncomingCallActivity visible briefly until CallActivity renders its first frame (zero blank flash)
                            act.binding.root.postDelayed({
                                if (!act.isFinishing && !act.isDestroyed) {
                                    act.finish()
                                    @Suppress("DEPRECATION")
                                    act.overridePendingTransition(0, 0)
                                }
                            }, 220L)
                        } else {
                            appCtx.startActivity(callIntent)
                        }
                    } catch (_: Exception) {
                        try { appCtx.startActivity(callIntent) } catch (_: Exception) {}
                        if (act != null && !act.isFinishing && !act.isDestroyed) {
                            act.finish()
                            @Suppress("DEPRECATION")
                            act.overridePendingTransition(0, 0)
                        }
                    }
                } else {
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        android.widget.Toast.makeText(appCtx, "Call ended by caller", android.widget.Toast.LENGTH_SHORT).show()
                        act.finish()
                    }
                }
            }
        }
    }

    private fun performDecline() {
        if (!actionTaken.compareAndSet(false, true)) return
        binding.swipeCallAction.isInteractionEnabled = false
        binding.tvSwipeHint.text = "Declined"
        binding.tvSwipeHint.setTextColor(ContextCompat.getColor(this, R.color.incoming_decline_text))
        stopRingtoneAndVibrate()
        stopAnswerButtonAnimation()
        callTimeoutHandler?.removeCallbacks(callTimeoutRunnable)
        callTimeoutHandler?.removeCallbacks(stateGuardRunnable)
        SipVoipService.dismissIncomingCall(this)
        if (isTestCall) {
            android.widget.Toast.makeText(applicationContext, "Test incoming call declined", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            SipEngine.declineIncomingCall()
        }
        if (!isFinishing) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.opentalk.android.storage.SoundSettingsManager.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Fast guard: If caller already cancelled before this Activity finished loading, dismiss immediately!
        isTestCall = intent.getBooleanExtra("is_test", false)
        if (!isTestCall && !SipEngine.hasActiveIncomingCall()) {
            android.util.Log.w("IncomingCallActivity", "Call already cancelled or not ringing on entry. Finishing immediately.")
            SipVoipService.dismissIncomingCall(this)
            finish()
            return
        }
        activeInstance = java.lang.ref.WeakReference(this)

        // Show cleanly over secure lock screen WITHOUT forcing PIN/Biometric prompt via requestDismissKeyguard()!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val isDark = com.opentalk.android.storage.SoundSettingsManager.isDarkModeEnabled(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
        window.statusBarColor = ContextCompat.getColor(this, R.color.incall_bg_start)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.incall_bg_end)

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Register ALL cancellation hooks BEFORE binding caller / starting ringtone so CANCEL can never slip past!
        val filter = android.content.IntentFilter("com.opentalk.android.ACTION_CALL_CANCELLED")
        ContextCompat.registerReceiver(this, cancelReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        SipEngine.addIncomingCallCancelledListener(cancelledCallback)
        SipEngine.addCallStateListener(callStateCallback)

        bindCallerFromIntent(intent)

        if (!isTestCall && !SipEngine.hasActiveIncomingCall()) {
            android.util.Log.w("IncomingCallActivity", "Call cancelled during onCreate setup. Aborting ringtone.")
            SipVoipService.dismissIncomingCall(this)
            finish()
            return
        }

        // Hand foreground off NOTIFICATION_ID_INCOMING and cancel notification sound once full-screen UI is visible (zero double ringtone!)
        SipVoipService.silenceIncomingNotification(this)

        startRingtoneAndVibrate()
        startAnswerButtonAnimation()

        callTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        callTimeoutHandler?.postDelayed(callTimeoutRunnable, 30_000L)
        callTimeoutHandler?.postDelayed(stateGuardRunnable, 350L)

        binding.swipeCallAction.onAccept = {
            performAccept()
        }

        binding.swipeCallAction.onDecline = {
            performDecline()
        }

        val acceptColor = ContextCompat.getColor(this, R.color.incoming_accept_text)
        val declineColor = ContextCompat.getColor(this, R.color.incoming_decline_text)
        val secondaryColor = ContextCompat.getColor(this, R.color.incall_text_secondary)
        val primaryColor = ContextCompat.getColor(this, R.color.incall_text_primary)

        binding.swipeCallAction.onDragStateChanged = { progress ->
            if (!actionTaken.get()) {
                when {
                    progress >= 0.70f -> {
                        binding.tvSwipeHint.text = "Release to Accept"
                        binding.tvSwipeHint.setTextColor(acceptColor)
                        binding.tvAcceptLabel.alpha = 1.0f
                        binding.tvDeclineLabel.alpha = 0.25f
                    }
                    progress <= -0.70f -> {
                        binding.tvSwipeHint.text = "Release to Decline"
                        binding.tvSwipeHint.setTextColor(declineColor)
                        binding.tvDeclineLabel.alpha = 1.0f
                        binding.tvAcceptLabel.alpha = 0.25f
                    }
                    progress > 0.12f -> {
                        binding.tvSwipeHint.text = "Accept"
                        binding.tvSwipeHint.setTextColor(acceptColor)
                        binding.tvAcceptLabel.alpha = 1.0f
                        binding.tvDeclineLabel.alpha = (1f - progress * 0.9f).coerceIn(0.25f, 1f)
                    }
                    progress < -0.12f -> {
                        binding.tvSwipeHint.text = "Decline"
                        binding.tvSwipeHint.setTextColor(declineColor)
                        binding.tvDeclineLabel.alpha = 1.0f
                        binding.tvAcceptLabel.alpha = (1f + progress * 0.9f).coerceIn(0.25f, 1f)
                    }
                    else -> {
                        binding.tvSwipeHint.text = "Slide to answer"
                        binding.tvSwipeHint.setTextColor(secondaryColor)
                        binding.tvAcceptLabel.alpha = 1.0f
                        binding.tvDeclineLabel.alpha = 1.0f
                    }
                }
            }
        }

        binding.swipeCallAction.onTapHint = {
            if (!actionTaken.get()) {
                binding.tvSwipeHint.text = "Slide left or right"
                binding.tvSwipeHint.setTextColor(primaryColor)
            }
        }

        // Back button NEVER declines/cuts a ringing incoming call — minimizes safely to background instead!
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                minimizeIncomingCall()
            }
        })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
        }
        val returningToIncoming = intent?.getBooleanExtra("return_to_incoming", false) == true
        bindCallerFromIntent(intent)

        // If call was already answered while minimized, smoothly hand off to CallActivity instead of dropping!
        if (!isTestCall && SipEngine.currentCallState == com.opentalk.android.engine.CallState.CONNECTED) {
            stopRingtoneAndVibrate()
            SipVoipService.dismissIncomingCall(this)
            val callIntent = Intent(this, CallActivity::class.java).apply {
                putExtra("number", callerNumber)
                putExtra("name", callerName)
                putExtra("is_incoming", true)
                putExtra("return_to_call", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            try {
                startActivity(callIntent)
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            } catch (_: Exception) {}
            if (!isFinishing && !isDestroyed) {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            return
        }

        if (!isTestCall && !SipEngine.hasActiveIncomingCall()) {
            SipVoipService.dismissIncomingCall(this)
            finish()
            return
        }

        if (returningToIncoming && (actionTaken.get() || isHandingOffToCall)) {
            return
        }

        if (!returningToIncoming) {
            userSilencedRingtone = false
        }

        actionTaken.set(false)
        isHandingOffToCall = false
        binding.swipeCallAction.reset()
        binding.tvIncomingTitle.text = "INCOMING CALL"
        binding.tvSwipeHint.text = "Slide to answer"
        binding.tvSwipeHint.setTextColor(ContextCompat.getColor(this, R.color.incall_text_secondary))
        binding.tvAcceptLabel.alpha = 1.0f
        binding.tvDeclineLabel.alpha = 1.0f

        SipVoipService.silenceIncomingNotification(this)

        // Avoid restarting ringtone if user already silenced it or if it's already playing on return
        val alreadyRinging = try { ringtone?.isPlaying == true } catch (_: Exception) { false }
        if (!returningToIncoming || (!alreadyRinging && !userSilencedRingtone)) {
            if (!alreadyRinging) {
                startRingtoneAndVibrate()
            }
        }
        startAnswerButtonAnimation()

        if (!returningToIncoming) {
            callTimeoutHandler?.removeCallbacks(callTimeoutRunnable)
            callTimeoutHandler?.postDelayed(callTimeoutRunnable, 30_000L)
        }
        callTimeoutHandler?.removeCallbacks(stateGuardRunnable)
        callTimeoutHandler?.postDelayed(stateGuardRunnable, 350L)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            userSilencedRingtone = true
            stopRingtoneAndVibrate()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startRingtoneAndVibrate() {
        try {
            // Always kill any previous ringtone/vibrator instance first so 2 players NEVER overlap!
            stopGlobalRingtoneAndVibrate()
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                return
            }

            synchronized(ringtoneLock) {
                if (ringerMode == AudioManager.RINGER_MODE_VIBRATE || ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    if (com.opentalk.android.storage.SoundSettingsManager.isVibrateOnCallEnabled(this)) {
                        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                            vm.defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        }
                        vibrator = vib
                        globalVibrator = vib

                        val pattern = longArrayOf(0, 1000, 1000)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
                        } else {
                            @Suppress("DEPRECATION")
                            vib.vibrate(pattern, 0)
                        }
                    }
                }

                if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    val uri = com.opentalk.android.storage.SoundSettingsManager.getCallRingtoneUriOrNull(this)
                    if (uri != null) {
                        val rt = RingtoneManager.getRingtone(applicationContext, uri)?.apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                isLooping = true
                            }
                            audioAttributes = AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .build()
                        }
                        ringtone = rt
                        globalRingtone = rt
                        rt?.play()

                        // On Android 8.0-8.1 (< P), loop ringtone manually if it completes before call is answered
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                            legacyRingtoneHandler?.removeCallbacks(legacyRingtoneRunnable)
                            legacyRingtoneHandler = android.os.Handler(android.os.Looper.getMainLooper())
                            legacyRingtoneHandler?.postDelayed(legacyRingtoneRunnable, 2500L)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun startAnswerButtonAnimation() {
        try {
            binding.swipeCallAction.startIdleAnimations()
        } catch (_: Exception) {}
    }

    private fun stopAnswerButtonAnimation() {
        try {
            binding.swipeCallAction.stopIdleAnimations()
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun stopRingtoneAndVibrate() {
        stopGlobalRingtoneAndVibrate()
        try {
            legacyRingtoneHandler?.removeCallbacks(legacyRingtoneRunnable)
        } catch (_: Exception) {}
        legacyRingtoneHandler = null

        try {
            ringtone?.stop()
        } catch (_: Exception) {}
        ringtone = null

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
        vibrator = null
    }

    override fun onPause() {
        if (isFinishing || (!isTestCall && !SipEngine.hasActiveIncomingCall())) {
            stopRingtoneAndVibrate()
        }
        super.onPause()
    }

    override fun onStop() {
        if (isHandingOffToCall) {
            stopRingtoneAndVibrate()
            if (!isFinishing) {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        } else if (!isTestCall && !actionTaken.get() && !SipEngine.isAnsweringIncoming() && !SipEngine.hasActiveIncomingCall()) {
            stopRingtoneAndVibrate()
            if (!isFinishing) finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (activeInstance?.get() === this) {
            activeInstance = null
        }
        try {
            callTimeoutHandler?.removeCallbacks(callTimeoutRunnable)
            callTimeoutHandler?.removeCallbacks(stateGuardRunnable)
        } catch (_: Exception) {}
        SipEngine.removeIncomingCallCancelledListener(cancelledCallback)
        SipEngine.removeCallStateListener(callStateCallback)
        try {
            unregisterReceiver(cancelReceiver)
        } catch (_: Exception) {}
        stopRingtoneAndVibrate()
        stopAnswerButtonAnimation()
        super.onDestroy()
    }
}

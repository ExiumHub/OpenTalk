package com.opentalk.android

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Rect
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.text.Editable
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.media.RingtoneManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.opentalk.android.storage.SoundSettingsManager
import com.opentalk.android.utils.ContactResolver
import com.opentalk.android.utils.PhoneNumberHelper
import com.opentalk.android.databinding.ActivityMainBinding
import com.opentalk.android.databinding.DialogBlockListBinding
import com.opentalk.android.databinding.DialogUpdateAvailableBinding
import com.opentalk.android.updater.UpdateInfo
import com.opentalk.android.updater.UpdateManager
import com.opentalk.android.databinding.DialogCallDetailsBinding
import com.opentalk.android.databinding.DialogProfileSettingsBinding
import com.opentalk.android.databinding.DialogSipConfigurationBinding
import com.opentalk.android.databinding.DialogAmaripSyncSettingsBinding
import com.opentalk.android.databinding.ItemCallLogDetailBinding
import com.opentalk.android.databinding.ItemContactBinding
import com.opentalk.android.databinding.ItemDialerSuggestionBinding
import com.opentalk.android.databinding.ItemRecentCallBinding
import com.opentalk.android.databinding.ViewContactsBinding
import com.opentalk.android.databinding.ViewDialerBinding
import com.opentalk.android.databinding.ViewRecentsBinding
import com.opentalk.android.engine.CallState
import com.opentalk.android.engine.RegistrationStatus
import com.opentalk.android.engine.SipEngine
import com.opentalk.android.models.CallRecord
import com.opentalk.android.models.CallType
import com.opentalk.android.models.SipAccount
import com.opentalk.android.services.SipVoipService
import com.opentalk.android.storage.BlockListManager
import com.opentalk.android.storage.CallHistoryDbHelper
import com.opentalk.android.storage.SecureAccountManager
import com.opentalk.android.sync.CdrSyncManager

data class GroupedCall(
    val id: String,
    val remoteNumber: String,
    val displayName: String,
    val latestTimestamp: Long,
    val latestType: CallType,
    var count: Int,
    val calls: MutableList<CallRecord>,
    val dateKey: String = "",
    val dateHeader: String = "",
    val formattedNumber: String = "",
    val nationalNumber: String = "",
    val digitsOnly: String = "",
    val displayNameLower: String = displayName.lowercase(),
    val formattedTimeText: String = ""
)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var accountManager: SecureAccountManager
    private lateinit var dbHelper: CallHistoryDbHelper

    private var dialerBinding: ViewDialerBinding? = null
    private var recentsBinding: ViewRecentsBinding? = null
    private var contactsBinding: ViewContactsBinding? = null

    private var dialBuffer = StringBuilder()
    private var lastCallClickTime = 0L
    private var currentStatusMessage: String = "Not Registered"
    private var currentStatus: RegistrationStatus = SipEngine.currentStatus
    private var currentTab = 0 // 0: Recents, 1: Dialer, 2: Contacts

    private var onRingtoneSelectedCallback: ((Uri?, String) -> Unit)? = null
    private var pendingPickerType: String? = null

    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pickerType = pendingPickerType
        pendingPickerType = null
        if (result.resultCode == RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            val title = if (uri != null) {
                try {
                    val r = RingtoneManager.getRingtone(this, uri)
                    r?.getTitle(this) ?: "Selected Sound"
                } catch (_: Exception) {
                    "Selected Sound"
                }
            } else {
                "Silent"
            }
            val cb = onRingtoneSelectedCallback
            if (cb != null) {
                cb.invoke(uri, title)
            } else if (pickerType == "call_ringtone") {
                SoundSettingsManager.setCallRingtone(this, uri, title)
                Toast.makeText(this, "Call ringtone: ${SoundSettingsManager.getCallRingtoneName(this)}", Toast.LENGTH_SHORT).show()
            } else if (pickerType == "notif_sound") {
                SoundSettingsManager.setNotificationSound(this, uri, title)
                com.opentalk.android.notifications.MissedCallNotifier.refreshNotificationSoundChannel(this)
                Toast.makeText(this, "Notification tone: ${SoundSettingsManager.getNotificationSoundName(this)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var activeSettingsResumeRefresh: (() -> Unit)? = null

    private var onDefaultDialerResultCallback: (() -> Unit)? = null

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val isDefault = isDefaultDialer()
        android.util.Log.i("MainActivity", "Default dialer request returned: isDefault=$isDefault")
        onDefaultDialerResultCallback?.invoke()
    }

    fun isDefaultDialer(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecomManager?.defaultDialerPackage == packageName
        }
    }

    fun promptSetDefaultDialer(onResult: (() -> Unit)? = null) {
        onDefaultDialerResultCallback = onResult
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    defaultDialerLauncher.launch(intent)
                    return
                }
            }
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null) {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                }
                defaultDialerLauncher.launch(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error requesting default dialer: ${e.message}")
            try {
                val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun checkAndPromptDefaultDialer() {
        if (!isDefaultDialer()) {
            val prefs = getSharedPreferences("opentalk_default_prompt", Context.MODE_PRIVATE)
            val alreadyPrompted = prefs.getBoolean("prompted", false)
            if (!alreadyPrompted) {
                prefs.edit().putBoolean("prompted", true).apply()
                promptSetDefaultDialer()
            }
        }
    }

    private fun extractPhoneNumberFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        var raw: String? = null
        var isSipScheme = false
        val data = intent.data
        if (data != null) {
            val scheme = data.scheme?.lowercase()
            if (scheme == "tel" || scheme == "sip") {
                isSipScheme = (scheme == "sip")
                raw = data.schemeSpecificPart
            } else {
                val dataStr = data.toString()
                if (dataStr.startsWith("tel:", ignoreCase = true) || dataStr.startsWith("sip:", ignoreCase = true)) {
                    isSipScheme = dataStr.startsWith("sip:", ignoreCase = true)
                    raw = dataStr.substringAfter(":")
                }
            }
        }
        if (raw.isNullOrBlank()) {
            raw = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                ?: intent.getStringExtra("android.intent.extra.PHONE_NUMBER")
                ?: intent.getStringExtra("phoneNumber")
                ?: intent.getStringExtra("phone")
                ?: intent.getStringExtra("number")
        }
        if (raw.isNullOrBlank()) return null

        val decoded = try {
            Uri.decode(raw)
        } catch (_: Exception) {
            raw
        }

        val stripped = if (isSipScheme) {
            decoded.substringBefore(";").substringBefore("?").substringBefore("@")
        } else {
            decoded.substringBefore(";").substringBefore("?")
        }
        val clean = stripped.trim()
        return if (clean.isNotBlank()) clean else null
    }

    private fun handleIncomingDialIntent(incomingIntent: Intent?) {
        val number = extractPhoneNumberFromIntent(incomingIntent)
        if (incomingIntent?.action == Intent.ACTION_CALL) {
            if (!number.isNullOrBlank()) {
                placeOutgoingCall(number)
            }
            return
        }
        if (!number.isNullOrBlank()) {
            dialBuffer.clear()
            dialBuffer.append(number)
            selectTab(1)
            dialerBinding?.let { v ->
                v.tvPhoneNumber.setText(number)
                v.tvPhoneNumber.setSelection(number.length)
                v.btnBackspace.visibility = View.VISIBLE
            }
        } else if (incomingIntent?.action == Intent.ACTION_DIAL) {
            selectTab(1)
        }
    }

    private fun showDialerCopyPasteMenu(editText: EditText) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipData = clipboard?.primaryClip
        val clipText = if (clipData != null && clipData.itemCount > 0) {
            clipData.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        } else null
        val currentText = editText.text?.toString() ?: ""

        val popup = androidx.appcompat.widget.PopupMenu(this, editText)
        if (!clipText.isNullOrBlank()) {
            val preview = if (clipText.length > 18) clipText.take(16) + "…" else clipText
            popup.menu.add(0, 1, 0, "Paste ($preview)")
        }
        if (currentText.isNotEmpty()) {
            popup.menu.add(0, 2, 1, "Copy")
            popup.menu.add(0, 3, 2, "Cut")
            popup.menu.add(0, 4, 3, "Select All")
            popup.menu.add(0, 5, 4, "Clear")
        }

        if (popup.menu.size() == 0) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    pasteToDialer(editText)
                    true
                }
                2 -> {
                    copyDialerText(editText)
                    true
                }
                3 -> {
                    cutDialerText(editText)
                    true
                }
                4 -> {
                    editText.selectAll()
                    true
                }
                5 -> {
                    editText.setText("")
                    dialBuffer.clear()
                    performHapticFeedback()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun pasteToDialer(editText: EditText) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipData = clipboard?.primaryClip
        val clipText = if (clipData != null && clipData.itemCount > 0) {
            clipData.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        } else null

        if (clipText.isNullOrBlank()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val filtered = clipText.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val toPaste = if (filtered.isNotBlank()) filtered else clipText

        val current = editText.text ?: android.text.SpannableStringBuilder()
        val start = editText.selectionStart.coerceIn(0, current.length)
        val end = editText.selectionEnd.coerceIn(0, current.length)
        val min = minOf(start, end)
        val max = maxOf(start, end)
        val updated = if (min != max) {
            current.replace(min, max, toPaste).toString()
        } else {
            current.insert(min, toPaste).toString()
        }

        editText.setText(updated)
        val newPos = (min + toPaste.length).coerceAtMost(updated.length)
        editText.setSelection(newPos)
        performHapticFeedback()
        Toast.makeText(this, "Pasted: $toPaste", Toast.LENGTH_SHORT).show()
    }

    private fun copyDialerText(editText: EditText) {
        val currentText = editText.text?.toString() ?: ""
        if (currentText.isEmpty()) return
        val textToCopy = if (editText.hasSelection()) {
            val start = minOf(editText.selectionStart, editText.selectionEnd).coerceIn(0, currentText.length)
            val end = maxOf(editText.selectionStart, editText.selectionEnd).coerceIn(0, currentText.length)
            if (end > start) currentText.substring(start, end) else currentText
        } else {
            currentText
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Phone Number", textToCopy))
        performHapticFeedback()
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun cutDialerText(editText: EditText) {
        copyDialerText(editText)
        if (editText.hasSelection()) {
            val current = editText.text ?: android.text.SpannableStringBuilder()
            val start = minOf(editText.selectionStart, editText.selectionEnd).coerceIn(0, current.length)
            val end = maxOf(editText.selectionStart, editText.selectionEnd).coerceIn(0, current.length)
            val updated = current.delete(start, end).toString()
            editText.setText(updated)
            editText.setSelection(start.coerceAtMost(updated.length))
        } else {
            editText.setText("")
            dialBuffer.clear()
        }
        performHapticFeedback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SoundSettingsManager.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        pendingPickerType = savedInstanceState?.getString("pending_picker_type")
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val isDark = SoundSettingsManager.isDarkModeEnabled(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
        window.statusBarColor = ContextCompat.getColor(this, R.color.surface_canvas)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.surface_canvas)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SipEngine.initialize(this)
        accountManager = SecureAccountManager(this)
        dbHelper = CallHistoryDbHelper.getInstance(this)
        com.opentalk.android.notifications.MissedCallNotifier.init(this)

        requestRequiredPermissions()
        setupTopBar()
        setupBottomTabBar()
        setupStatusListener()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (onRecentsBackPressed?.invoke() == true) {
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        // In-Call return banner setup (never drops or redials an active incoming/outgoing call!)
        binding.layoutInCallBanner.setOnClickListener {
            if (!SipEngine.isInCall()) {
                updateInCallBanner()
                return@setOnClickListener
            }
            if (SipEngine.hasActiveIncomingCall()) {
                val incIntent = Intent(this, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("number", SipEngine.activeCallNumber ?: "")
                    putExtra("name", SipEngine.activeCallName ?: "")
                    putExtra("return_to_incoming", true)
                }
                startActivity(incIntent)
            } else {
                val callIntent = Intent(this, CallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("number", SipEngine.activeCallNumber ?: "")
                    putExtra("name", SipEngine.activeCallName ?: "")
                    putExtra("is_incoming", SipEngine.isCurrentCallIncoming)
                    putExtra("return_to_call", true)
                }
                startActivity(callIntent)
            }
        }

        callStateListener = { _, _ ->
            updateInCallBanner()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    updateRecentsTabBadge()
                }
            }
        }
        callStateListener?.let { SipEngine.addCallStateListener(it) }
        updateInCallBanner()

        // Automated AmarIP / SCL Offline Missed Call Sync listener (ONLY refreshes when Activity is RESUMED!)
        CdrSyncManager.setOnSyncCompleteListener { _ ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    updateRecentsTabBadge()
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                        currentTab == 0
                    ) {
                        refreshRecentsInPlace?.invoke() ?: showRecents()
                    }
                }
            }
        }

        // Auto-register configured account and start 24/7 background standby service BEFORE any cold-launch ACTION_CALL
        val saved = accountManager.getAccount()
        if (saved.username.isNotEmpty() && saved.server.isNotEmpty()) {
            SipVoipService.startStandby(this)
            if (!SipEngine.isRegistered() && SipEngine.currentStatus != RegistrationStatus.REGISTERING) {
                SipEngine.triggerRegister(saved)
            }
            CdrSyncManager.triggerSync(this)
        }

        // Initial tab & cold-launch ACTION_CALL / ACTION_DIAL / select_tab handling
        val initialDialNumber = extractPhoneNumberFromIntent(intent)
        val restoredTab = savedInstanceState?.getInt("current_tab", -1) ?: -1
        if (restoredTab in 0..2) {
            selectTab(restoredTab)
        } else if (intent.getStringExtra("select_tab") == "recents") {
            selectTab(0)
        } else if (intent.action == Intent.ACTION_CALL && !initialDialNumber.isNullOrBlank()) {
            selectTab(1)
            placeOutgoingCall(initialDialNumber)
        } else if (!initialDialNumber.isNullOrBlank() || intent.action == Intent.ACTION_DIAL) {
            if (!initialDialNumber.isNullOrBlank()) {
                dialBuffer.clear()
                dialBuffer.append(initialDialNumber)
            }
            selectTab(1)
        } else {
            selectTab(0)
        }

        // Asynchronously preload contacts and compute T9 search index for 0ms dialpad search, then refresh Recents names
        skipInitialOnResumeRefresh = true
        lifecycleScope.launch(Dispatchers.IO) {
            val wasAlreadyLoaded = ContactResolver.isCacheLoaded()
            ContactResolver.loadAllContacts(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed && !wasAlreadyLoaded) {
                    when (currentTab) {
                        0 -> refreshRecentsInPlace?.invoke()
                        1 -> refreshDialerSuggestionsInPlace?.invoke()
                        2 -> showContacts()
                    }
                }
            }
        }

        // Stagger secondary onboarding prompts safely so they never stack or collide with permission/settings dialogs
        fun schedulePrompt(delayMs: Long, action: () -> Unit) {
            val r = Runnable {
                if (!isFinishing && !isDestroyed &&
                    lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    trackedDialogs.none { it.isShowing }
                ) {
                    action()
                }
            }
            pendingPromptRunnables.add(r)
            binding.root.postDelayed(r, delayMs)
        }
        schedulePrompt(900L) { checkAndPromptDefaultDialer() }
        schedulePrompt(2200L) { checkAndPromptBatteryOptimization() }
        schedulePrompt(3600L) { checkAndPromptOverlayAndFullScreenPermission() }
    }

    private fun updateInCallBanner() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (SipEngine.isInCall()) {
                binding.layoutInCallBanner.visibility = View.VISIBLE
                val rawCaller = SipEngine.activeCallName?.ifBlank { null } ?: SipEngine.activeCallNumber ?: ""
                val caller = if (PhoneNumberHelper.looksLikePhoneNumber(rawCaller)) {
                    PhoneNumberHelper.formatForDisplay(rawCaller)
                } else {
                    rawCaller
                }
                val prefix = if (SipEngine.hasActiveIncomingCall()) "Incoming call" else "Call in progress"
                binding.tvInCallBannerText.text = if (caller.isNotBlank()) {
                    "$prefix ($caller) • Tap to return"
                } else {
                    "$prefix • Tap to return"
                }
            } else {
                binding.layoutInCallBanner.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateInCallBanner()
        val saved = accountManager.getAccount()
        if (saved.username.isNotEmpty() && saved.server.isNotEmpty()) {
            if (!SipEngine.isNetworkConnected(this)) {
                // NEVER kill an active call during mid-call Wi-Fi <-> Mobile Data handover grace period!
                if (!SipEngine.isInCall() && SipEngine.currentStatus != RegistrationStatus.FAILED) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (!SipEngine.isInCall()) {
                            SipEngine.onNetworkLost()
                        }
                    }
                }
            } else if (!SipEngine.isRegistered() && SipEngine.currentStatus != RegistrationStatus.REGISTERING) {
                SipEngine.triggerRegister(saved)
            } else if (SipEngine.isRegistered()) {
                SipEngine.onWatchdogPing(this)
            }
            CdrSyncManager.triggerSync(this)
        }
        val effectiveStatus = SipEngine.currentStatus
        currentStatus = effectiveStatus
        updateStatusUi(effectiveStatus)
        activeSettingsResumeRefresh?.invoke()

        val isFirstResumeAfterCreate = skipInitialOnResumeRefresh
        skipInitialOnResumeRefresh = false
        val forceContacts = pendingContactsRefreshOnResume
        pendingContactsRefreshOnResume = false

        if (!isFirstResumeAfterCreate) {
            lifecycleScope.launch(Dispatchers.IO) {
                val changed = forceContacts || ContactResolver.hasContactsChanged(this@MainActivity)
                if (changed) {
                    ContactResolver.loadAllContacts(this@MainActivity, forceReload = true)
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            when (currentTab) {
                                0 -> refreshRecentsInPlace?.invoke()
                                1 -> refreshDialerSuggestionsInPlace?.invoke()
                                2 -> showContacts()
                            }
                        }
                    }
                }
            }

            if (currentTab == 0) {
                com.opentalk.android.notifications.MissedCallNotifier.clearMissedCalls(this)
                if (refreshRecentsInPlace != null && recentsBinding != null) {
                    refreshRecentsInPlace?.invoke()
                } else {
                    showRecents()
                }
            }
        }
        updateRecentsTabBadge()
    }

    private fun updateRecentsTabBadge() {
        val missed = com.opentalk.android.notifications.MissedCallNotifier.getMissedCount(this)
        binding.tvTabRecents.text = if (missed > 0 && currentTab != 0) "Recents ($missed)" else "Recents"
    }

    private var pendingContactsRefreshOnResume = false
    private var skipInitialOnResumeRefresh = false

    private var sipStatusListener: ((RegistrationStatus, String) -> Unit)? = null
    private var callStateListener: ((CallState, String) -> Unit)? = null
    private var onRecentsBackPressed: (() -> Boolean)? = null
    private var refreshRecentsInPlace: (() -> Unit)? = null
    private var refreshDialerSuggestionsInPlace: (() -> Unit)? = null
    private var settingsDialog: BottomSheetDialog? = null
    private var activePopupWindow: PopupWindow? = null
    private val trackedDialogs = mutableListOf<android.app.Dialog>()
    private val pendingPromptRunnables = mutableListOf<Runnable>()

    private fun trackDialog(dialog: android.app.Dialog) {
        trackedDialogs.add(dialog)
        dialog.setOnDismissListener { trackedDialogs.remove(dialog) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("current_tab", currentTab)
        pendingPickerType?.let { outState.putString("pending_picker_type", it) }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("select_tab") == "recents") {
            selectTab(0)
        }
        handleIncomingDialIntent(intent)
    }

    override fun onDestroy() {
        pendingPromptRunnables.forEach { binding.root.removeCallbacks(it) }
        pendingPromptRunnables.clear()
        CdrSyncManager.setOnSyncCompleteListener(null)
        CdrSyncManager.setOnSyncResultListener(null)
        CdrSyncManager.setOnBalanceUpdatedListener(null)
        sipStatusListener?.let { SipEngine.removeStatusListener(it) }
        sipStatusListener = null
        callStateListener?.let { SipEngine.removeCallStateListener(it) }
        callStateListener = null
        try { activePopupWindow?.dismiss() } catch (_: Exception) {}
        activePopupWindow = null
        trackedDialogs.toList().forEach { d ->
            try { if (d.isShowing) d.dismiss() } catch (_: Exception) {}
        }
        trackedDialogs.clear()
        settingsDialog = null
        dialerBinding = null
        recentsBinding = null
        contactsBinding = null
        refreshRecentsInPlace = null
        refreshDialerSuggestionsInPlace = null
        onRecentsBackPressed = null
        onRingtoneSelectedCallback = null
        onDefaultDialerResultCallback = null
        activeSettingsResumeRefresh = null
        super.onDestroy()
    }

    // =========================================================================
    // TOP BAR SETUP (Hamburger Menu ☰ & Status Icon ONLY)
    // =========================================================================
    // TOP NAVIGATION BAR SETUP (100% Apple iOS Header)
    // =========================================================================

    private fun setupTopBar() {
        // Apple Settings Gear icon opens Profile & Settings Bottom Sheet
        binding.btnMainMenu.setOnClickListener {
            showProfileSettingsDialog()
        }

        // Status Capsule Pill opens Network Diagnostics Dialog
        binding.btnStatusBadge.setOnClickListener {
            showDiagnosticsDialog()
        }
    }

    private fun setupStatusListener() {
        val listener: (RegistrationStatus, String) -> Unit = { status, message ->
            currentStatus = status
            currentStatusMessage = message
            updateStatusUi(status)
        }
        sipStatusListener = listener
        SipEngine.addStatusListener(listener)
    }

    private fun updateStatusUi(status: RegistrationStatus) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            when (status) {
                RegistrationStatus.REGISTERED -> {
                    binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.registered_green)
                    binding.tvStatusText.text = "Ready"
                    binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.registered_green))
                }
                RegistrationStatus.REGISTERING -> {
                    binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.registering_amber)
                    binding.tvStatusText.text = "Connecting"
                    binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.registering_amber))
                }
                RegistrationStatus.FAILED -> {
                    binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.failed_red)
                    if (currentStatusMessage.contains("Internet", ignoreCase = true) || !SipEngine.isNetworkConnected(this)) {
                        binding.tvStatusText.text = "Offline"
                    } else {
                        binding.tvStatusText.text = "Failed"
                    }
                    binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.failed_red))
                }
                RegistrationStatus.UNREGISTERED -> {
                    binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.unregistered_grey)
                    binding.tvStatusText.text = if (accountManager.getAccount().username.isBlank()) "Not Configured" else "Offline"
                    binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            // Never clear focus on dialer's tvPhoneNumber so cursor and selection stay active during dialpad typing!
            if (v is EditText && v !== dialerBinding?.tvPhoneNumber) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // =========================================================================
    // BOTTOM TAB BAR SETUP (Apple iOS Style, 3 Tabs: Recents, Keypad, Contacts)
    // =========================================================================

    private fun setupBottomTabBar() {
        binding.tabRecents.setOnClickListener { selectTab(0) }
        binding.tabDialer.setOnClickListener { selectTab(1) }
        binding.tabContacts.setOnClickListener { selectTab(2) }
    }

    private fun selectTab(index: Int) {
        currentTab = index
        if (index != 0) {
            onRecentsBackPressed = null
        }

        // Modern Floating Pill Dock: Theme-aware active/inactive tab colors
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)
        val activeColor = ContextCompat.getColor(this, R.color.tab_active_fg)

        binding.tabRecents.background = null
        binding.ivTabRecents.setColorFilter(inactiveColor)
        binding.tvTabRecents.setTextColor(inactiveColor)
        binding.tvTabRecents.setTypeface(null, Typeface.NORMAL)

        binding.tabDialer.background = null
        binding.ivTabDialer.setColorFilter(inactiveColor)
        binding.tvTabDialer.setTextColor(inactiveColor)
        binding.tvTabDialer.setTypeface(null, Typeface.NORMAL)

        binding.tabContacts.background = null
        binding.ivTabContacts.setColorFilter(inactiveColor)
        binding.tvTabContacts.setTextColor(inactiveColor)
        binding.tvTabContacts.setTypeface(null, Typeface.NORMAL)

        fun animateTabSelect(tabView: View) {
            tabView.scaleX = 0.94f
            tabView.scaleY = 0.94f
            tabView.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
        }

        // Highlight selected tab in Brand Indigo (#4F46E5) with soft pill indicator
        when (index) {
            0 -> {
                binding.tabRecents.setBackgroundResource(R.drawable.bg_tab_pill_active)
                binding.ivTabRecents.setColorFilter(activeColor)
                binding.tvTabRecents.setTextColor(activeColor)
                binding.tvTabRecents.setTypeface(null, Typeface.BOLD)
                animateTabSelect(binding.tabRecents)

                // Recents Header: Show [ All | Missed ] and Clear button
                binding.tvAppTitle.visibility = View.GONE
                binding.btnStatusBadge.visibility = View.GONE
                binding.layoutRecentsFilter.visibility = View.VISIBLE
                binding.btnClearHistory.visibility = View.VISIBLE

                showRecents()
                CdrSyncManager.triggerSync(this)
            }
            1 -> {
                binding.tabDialer.setBackgroundResource(R.drawable.bg_tab_pill_active)
                binding.ivTabDialer.setColorFilter(activeColor)
                binding.tvTabDialer.setTextColor(activeColor)
                binding.tvTabDialer.setTypeface(null, Typeface.BOLD)
                animateTabSelect(binding.tabDialer)

                // Keypad Header: Clean header with Status Pill
                binding.tvAppTitle.visibility = View.GONE
                binding.tvAppTitle.text = ""
                binding.btnStatusBadge.visibility = View.VISIBLE
                binding.layoutRecentsFilter.visibility = View.GONE
                binding.btnClearHistory.visibility = View.GONE

                showDialer()
            }
            2 -> {
                binding.tabContacts.setBackgroundResource(R.drawable.bg_tab_pill_active)
                binding.ivTabContacts.setColorFilter(activeColor)
                binding.tvTabContacts.setTextColor(activeColor)
                binding.tvTabContacts.setTypeface(null, Typeface.BOLD)
                animateTabSelect(binding.tabContacts)

                // Contacts Header: Show Title and Status Pill
                binding.tvAppTitle.visibility = View.VISIBLE
                binding.tvAppTitle.text = "Contacts"
                binding.btnStatusBadge.visibility = View.VISIBLE
                binding.layoutRecentsFilter.visibility = View.GONE
                binding.btnClearHistory.visibility = View.GONE

                showContacts()
            }
        }
        updateRecentsTabBadge()
    }

    // =========================================================================
    // 1. APPLE STYLE DIALER TAB
    // =========================================================================

    private fun clearInactiveTabReferences() {
        dialerBinding = null
        recentsBinding = null
        contactsBinding = null
        refreshRecentsInPlace = null
        refreshDialerSuggestionsInPlace = null
        try { activePopupWindow?.dismiss() } catch (_: Exception) {}
        activePopupWindow = null
        binding.btnFilterAll.setOnClickListener(null)
        binding.btnFilterMissed.setOnClickListener(null)
        binding.btnClearHistory.setOnClickListener(null)
        binding.contentFrame.removeAllViews()
    }

    private fun showDialer() {
        clearInactiveTabReferences()
        val v = ViewDialerBinding.inflate(layoutInflater, binding.contentFrame, true)
        dialerBinding = v

        // Prevent system soft keyboard from popping up, keeping dialpad buttons visible while allowing cursor selection
        v.tvPhoneNumber.showSoftInputOnFocus = false
        v.tvPhoneNumber.isCursorVisible = true
        v.tvPhoneNumber.setTextIsSelectable(true)
        v.tvPhoneNumber.isLongClickable = true

        val dialerLongClick = View.OnLongClickListener {
            showDialerCopyPasteMenu(v.tvPhoneNumber)
            true
        }
        v.tvPhoneNumber.setOnLongClickListener(dialerLongClick)
        (v.tvPhoneNumber.parent as? View)?.setOnLongClickListener(dialerLongClick)

        v.tvPhoneNumber.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = true
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = true
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                when (item?.itemId) {
                    android.R.id.copy -> {
                        copyDialerText(v.tvPhoneNumber)
                        mode?.finish()
                        return true
                    }
                    android.R.id.paste -> {
                        pasteToDialer(v.tvPhoneNumber)
                        mode?.finish()
                        return true
                    }
                    android.R.id.cut -> {
                        cutDialerText(v.tvPhoneNumber)
                        mode?.finish()
                        return true
                    }
                }
                return false
            }
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }

        v.btnBackspace.isFocusable = false
        v.btnBackspace.isFocusableInTouchMode = false
        v.btnCallAction.isFocusable = false
        v.btnCallAction.isFocusableInTouchMode = false
        v.tvAddNumberHint.isFocusable = false
        v.tvAddNumberHint.isFocusableInTouchMode = false

        v.tvPhoneNumber.setOnClickListener {
            v.tvPhoneNumber.isCursorVisible = true
        }

        v.tvPhoneNumber.setOnTouchListener { _, _ ->
            v.tvPhoneNumber.isCursorVisible = true
            v.tvPhoneNumber.requestFocus()
            false
        }

        // Async preload deduplicated Recent calls snapshot for instant Dialer Suggestions (Contacts + Recents)
        data class DialerSuggestion(
            val name: String,
            val number: String,
            val normalizedNumber: String,
            val isFromRecent: Boolean,
            val lastCallType: CallType? = null,
            val nationalNumber: String = PhoneNumberHelper.toSipDialString(normalizedNumber),
            val formattedDisplay: String = PhoneNumberHelper.formatForDisplay(normalizedNumber.ifBlank { number }),
            val e164Digits: String = normalizedNumber.filter { it.isDigit() },
            val t9Name: String = ContactResolver.getT9KeyForString(name)
        )
        var recentSuggestionItems: List<DialerSuggestion> = emptyList()
        var recentCallTypeByNorm: Map<String, CallType> = emptyMap()
        // Pre-allocated pool of 2 suggestion rows (reused in place — zero XML re-inflation per keypad tap!)
        val suggestionRowPool = ArrayList<ItemDialerSuggestionBinding>(2)
        val primaryColor = ContextCompat.getColor(this@MainActivity, R.color.primary)
        val secondaryIconColor = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
        val density = resources.displayMetrics.density
        val maxWWithName = (145 * density).toInt()
        val maxWNumberOnly = (240 * density).toInt()

        fun buildHighlightedNumber(formattedNumber: String, queryDigits: String): CharSequence {
            if (queryDigits.isEmpty()) return formattedNumber
            val ssb = android.text.SpannableStringBuilder(formattedNumber)
            // Map each digit in formattedNumber back to its character index so highlighting works across spaces/hyphens
            val digitChars = StringBuilder()
            val indexMap = ArrayList<Int>(formattedNumber.length)
            for (i in formattedNumber.indices) {
                if (formattedNumber[i].isDigit()) {
                    digitChars.append(formattedNumber[i])
                    indexMap.add(i)
                }
            }
            val digitStr = digitChars.toString()
            val effectiveQuery = when {
                digitStr.contains(queryDigits) -> queryDigits
                queryDigits.startsWith("0") && queryDigits.length > 1 && digitStr.contains(queryDigits.drop(1)) -> queryDigits.drop(1)
                else -> ""
            }
            if (effectiveQuery.isEmpty()) return formattedNumber
            val matchDigitStart = digitStr.indexOf(effectiveQuery)
            if (matchDigitStart < 0) return formattedNumber
            val spanStart = indexMap[matchDigitStart]
            val spanEnd = indexMap[(matchDigitStart + effectiveQuery.length - 1).coerceAtMost(indexMap.size - 1)] + 1
            ssb.setSpan(
                android.text.style.ForegroundColorSpan(primaryColor),
                spanStart,
                spanEnd,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.setSpan(
                android.text.style.StyleSpan(Typeface.BOLD),
                spanStart,
                spanEnd,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            return ssb
        }

        fun renderDialerSuggestions(rawText: String) {
            val digitsOnly = rawText.filter { it.isDigit() }
            if (digitsOnly.isEmpty()) {
                v.scrollDialerSuggestions.visibility = View.GONE
                for (row in suggestionRowPool) row.root.visibility = View.GONE
                return
            }

            val normQuery = ContactResolver.normalizePhoneNumber(digitsOnly)
            val combined = ArrayList<DialerSuggestion>(2)
            val seenNorm = HashSet<String>(4)

            // 1. Ranked Contacts matches (number prefix, T9 prefix, number contains, T9 contains)
            val contactMatches = ContactResolver.findT9Matches(digitsOnly, limit = 3)
            for (c in contactMatches) {
                val key = c.normalizedNumber.ifBlank { c.number }
                if (seenNorm.add(key)) {
                    combined.add(
                        DialerSuggestion(
                            name = c.name,
                            number = key,
                            normalizedNumber = key,
                            isFromRecent = false,
                            lastCallType = recentCallTypeByNorm[key],
                            nationalNumber = c.nationalNumber,
                            formattedDisplay = c.displayNumber,
                            e164Digits = c.digitsOnly,
                            t9Name = c.t9Key
                        )
                    )
                    if (combined.size >= 2) break
                }
            }

            // 2. Supplement with Recent Call History matches (unsaved or recent callers)
            if (combined.size < 2 && recentSuggestionItems.isNotEmpty()) {
                for (r in recentSuggestionItems) {
                    if (seenNorm.contains(r.normalizedNumber)) continue
                    val matchesNum = r.number.contains(digitsOnly) ||
                        r.nationalNumber.contains(digitsOnly) ||
                        r.e164Digits.contains(digitsOnly) ||
                        (normQuery.isNotEmpty() && r.normalizedNumber.contains(normQuery))
                    val matchesT9 = digitsOnly.length >= 2 && r.t9Name.contains(digitsOnly)
                    if (matchesNum || matchesT9) {
                        seenNorm.add(r.normalizedNumber)
                        combined.add(r)
                        if (combined.size >= 2) break
                    }
                }
            }

            if (combined.isEmpty()) {
                v.scrollDialerSuggestions.visibility = View.GONE
                for (row in suggestionRowPool) row.root.visibility = View.GONE
                return
            }

            v.scrollDialerSuggestions.visibility = View.VISIBLE
            val inflater = LayoutInflater.from(this@MainActivity)
            while (suggestionRowPool.size < combined.size) {
                val rowBinding = ItemDialerSuggestionBinding.inflate(inflater, v.layoutDialerSuggestionsContainer, false)
                suggestionRowPool.add(rowBinding)
                v.layoutDialerSuggestionsContainer.addView(rowBinding.root)
            }
            for (idx in suggestionRowPool.indices) {
                val rowBinding = suggestionRowPool[idx]
                if (idx >= combined.size) {
                    rowBinding.root.visibility = View.GONE
                    continue
                }
                rowBinding.root.visibility = View.VISIBLE
                val item = combined[idx]
                val cleanName = item.name.trim()
                val formattedDisplay = item.formattedDisplay
                val hasDistinctName = cleanName.isNotBlank() &&
                    !PhoneNumberHelper.looksLikePhoneNumber(cleanName) &&
                    !PhoneNumberHelper.areSameNumber(cleanName, item.normalizedNumber) &&
                    cleanName.any { it.isLetter() }

                // Recents-style call history icon on the left (instead of profile avatar)
                val callType = item.lastCallType ?: recentCallTypeByNorm[item.normalizedNumber]
                if (callType != null) {
                    rowBinding.ivSuggestIcon.clearColorFilter()
                    when (callType) {
                        CallType.MISSED -> rowBinding.ivSuggestIcon.setImageResource(R.drawable.ic_recent_call_missed)
                        CallType.REJECTED -> rowBinding.ivSuggestIcon.setImageResource(R.drawable.ic_recent_call_declined)
                        CallType.OUTGOING -> rowBinding.ivSuggestIcon.setImageResource(R.drawable.ic_recent_call_outgoing)
                        CallType.INCOMING -> rowBinding.ivSuggestIcon.setImageResource(R.drawable.ic_recent_call_incoming)
                    }
                } else {
                    rowBinding.ivSuggestIcon.setImageResource(R.drawable.ic_tab_recents)
                    rowBinding.ivSuggestIcon.setColorFilter(secondaryIconColor)
                }

                if (hasDistinctName) {
                    rowBinding.tvSuggestPrimary.maxWidth = maxWWithName
                    rowBinding.tvSuggestPrimary.text = cleanName
                    rowBinding.tvSuggestSecondary.visibility = View.VISIBLE
                    rowBinding.tvSuggestSecondary.text = buildHighlightedNumber(formattedDisplay, digitsOnly)
                } else {
                    rowBinding.tvSuggestPrimary.maxWidth = maxWNumberOnly
                    rowBinding.tvSuggestPrimary.text = buildHighlightedNumber(formattedDisplay, digitsOnly)
                    rowBinding.tvSuggestSecondary.visibility = View.GONE
                }

                rowBinding.viewSuggestDivider.visibility = if (idx < combined.size - 1) View.VISIBLE else View.GONE

                // Tap row -> autofill canonical number into dialer input for easy review/edit or calling
                val canonicalNum = item.normalizedNumber.ifBlank { item.number }
                rowBinding.root.setOnClickListener {
                    performHapticFeedback()
                    v.tvPhoneNumber.setText(canonicalNum)
                    v.tvPhoneNumber.setSelection(canonicalNum.length)
                }
                // Tap quick-call green icon -> call immediately
                rowBinding.btnSuggestQuickCall.setOnClickListener {
                    placeOutgoingCall(canonicalNum, if (hasDistinctName) cleanName else null)
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val typeMap = HashMap<String, CallType>()
            val recentList = try {
                val seen = HashSet<String>()
                dbHelper.getCalls().mapNotNull { rec ->
                    val norm = ContactResolver.normalizePhoneNumber(rec.remoteNumber)
                    if (norm.isBlank() || !seen.add(norm)) null
                    else {
                        typeMap[norm] = rec.type
                        val name = ContactResolver.getCachedContactName(norm) ?: norm
                        DialerSuggestion(name, rec.remoteNumber.trim(), norm, isFromRecent = true, lastCallType = rec.type)
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed && dialerBinding === v) {
                    recentCallTypeByNorm = typeMap
                    recentSuggestionItems = recentList
                    val cur = v.tvPhoneNumber.text?.toString() ?: ""
                    if (cur.isNotEmpty()) {
                        renderDialerSuggestions(cur)
                    }
                }
            }
        }

        refreshDialerSuggestionsInPlace = {
            if (!isFinishing && !isDestroyed && dialerBinding === v) {
                val cur = v.tvPhoneNumber.text?.toString() ?: ""
                val alreadySaved = cur.isNotEmpty() && ContactResolver.getCachedContactName(cur) != null
                v.tvAddNumberHint.visibility = if (cur.isNotEmpty() && !alreadySaved) View.VISIBLE else View.GONE
                renderDialerSuggestions(cur)
            }
        }

        v.tvPhoneNumber.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString() ?: ""
                dialBuffer.clear()
                dialBuffer.append(text)
                val hasText = text.isNotEmpty()
                val alreadySaved = hasText && ContactResolver.getCachedContactName(text) != null
                v.tvAddNumberHint.visibility = if (hasText && !alreadySaved) View.VISIBLE else View.GONE
                v.btnBackspace.visibility = if (hasText) View.VISIBLE else View.INVISIBLE

                // Smart Multi-Match Dialer Suggestions (Contacts + Recents with highlighted digits)
                renderDialerSuggestions(text)
                val hasSuggestions = v.scrollDialerSuggestions.visibility == View.VISIBLE

                // Dynamic font size scaling only when crossing size boundary (prevents cursor recreation)
                val targetSizeSp = when {
                    text.length > 16 -> 22f
                    text.length > 12 -> 26f
                    hasSuggestions -> 29f
                    else -> 34f
                }
                val currentSizePx = v.tvPhoneNumber.textSize
                val targetSizePx = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    targetSizeSp,
                    resources.displayMetrics
                )
                if (kotlin.math.abs(currentSizePx - targetSizePx) > 1.0f) {
                    v.tvPhoneNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, targetSizeSp)
                }
            }
        })

        v.tvPhoneNumber.setText(dialBuffer.toString())
        v.tvPhoneNumber.setSelection(v.tvPhoneNumber.text.length)

        v.tvAddNumberHint.setOnClickListener {
            val num = v.tvPhoneNumber.text.toString().trim()
            if (num.isNotEmpty()) {
                val canonical = PhoneNumberHelper.toCanonicalE164(num).ifBlank { num }
                saveNumberToContacts(canonical)
            }
        }

        v.btnBackspace.setOnClickListener {
            deleteDialChar()
        }

        v.btnBackspace.setOnLongClickListener {
            v.tvPhoneNumber.setText("")
            performHapticFeedback(true)
            true
        }

        v.btnCallAction.setOnClickListener {
            val numberToCall = v.tvPhoneNumber.text.toString().trim()
            if (numberToCall.isEmpty()) {
                // Standard Phone behavior: redial last outgoing call number if buffer is empty (off UI thread)
                val cachedLastOut = recentSuggestionItems.firstOrNull { it.lastCallType == CallType.OUTGOING }?.normalizedNumber
                if (!cachedLastOut.isNullOrBlank()) {
                    v.tvPhoneNumber.setText(cachedLastOut)
                    v.tvPhoneNumber.setSelection(cachedLastOut.length)
                    performHapticFeedback()
                    return@setOnClickListener
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val lastOutNum = dbHelper.getCalls().firstOrNull { it.type == CallType.OUTGOING }?.remoteNumber.orEmpty()
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed && dialerBinding === v) {
                            if (lastOutNum.isNotBlank()) {
                                v.tvPhoneNumber.setText(lastOutNum)
                                v.tvPhoneNumber.setSelection(lastOutNum.length)
                                performHapticFeedback()
                            } else {
                                Toast.makeText(this@MainActivity, "Please enter a phone number", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                return@setOnClickListener
            }

            placeOutgoingCall(numberToCall)
        }

        setupDialpadButtons(v.dialGrid)
    }

    fun placeOutgoingCall(numberToCall: String, name: String? = null) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCallClickTime < 600L) return
        lastCallClickTime = now

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required to make calls", Toast.LENGTH_SHORT).show()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        val cleanNumber = ContactResolver.normalizePhoneNumber(numberToCall)
        if (cleanNumber.isEmpty()) return
        performHapticFeedback()
        // Instant 0ms cache lookup (zero Main-Thread ContentResolver IPC); CallActivity refreshes live phonebook on IO!
        val cachedContactName = ContactResolver.getCachedContactName(cleanNumber)
        val resolvedName = when {
            !cachedContactName.isNullOrBlank() -> cachedContactName
            !name.isNullOrBlank() && !PhoneNumberHelper.looksLikePhoneNumber(name) -> name
            cleanNumber == "121" && !name.isNullOrBlank() -> name
            else -> cleanNumber
        }

        // If already in a live call with this exact number, return to the live call instead of dropping/redialing!
        val activeNorm = ContactResolver.normalizePhoneNumber(SipEngine.activeCallNumber ?: "")
        if (SipEngine.isInCall() && activeNorm.isNotBlank() && activeNorm == cleanNumber) {
            val returnIntent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("number", SipEngine.activeCallNumber ?: cleanNumber)
                putExtra("name", SipEngine.activeCallName ?: resolvedName)
                putExtra("is_incoming", SipEngine.isCurrentCallIncoming)
                putExtra("return_to_call", true)
            }
            dialBuffer.clear()
            dialerBinding?.tvPhoneNumber?.setText("")
            startActivity(returnIntent)
            return
        }

        val callIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("number", cleanNumber)
            putExtra("name", resolvedName)
            putExtra("is_incoming", false)
        }
        dialBuffer.clear()
        dialerBinding?.tvPhoneNumber?.setText("")
        startActivity(callIntent)
    }

    private fun insertDialChar(charToInsert: String) {
        val editText = dialerBinding?.tvPhoneNumber ?: return
        val len = editText.text?.length ?: 0
        val start = editText.selectionStart.coerceIn(0, len)
        val end = editText.selectionEnd.coerceIn(0, len)
        val min = minOf(start, end)
        val max = maxOf(start, end)
        editText.text.replace(min, max, charToInsert)
        val newPos = (min + charToInsert.length).coerceAtMost(editText.text.length)
        editText.setSelection(newPos)
        editText.post {
            if (dialerBinding?.tvPhoneNumber === editText) {
                editText.requestFocus()
                editText.isCursorVisible = true
            }
        }
    }

    private fun deleteDialChar() {
        val editText = dialerBinding?.tvPhoneNumber ?: return
        val len = editText.text?.length ?: 0
        val start = editText.selectionStart.coerceIn(0, len)
        val end = editText.selectionEnd.coerceIn(0, len)
        val min = minOf(start, end)
        val max = maxOf(start, end)
        if (min != max) {
            editText.text.delete(min, max)
            editText.setSelection(min.coerceAtMost(editText.text.length))
        } else if (min > 0) {
            editText.text.delete(min - 1, min)
            editText.setSelection((min - 1).coerceAtMost(editText.text.length))
        }
        editText.post {
            if (dialerBinding?.tvPhoneNumber === editText) {
                editText.requestFocus()
                editText.isCursorVisible = true
            }
        }
    }

    private fun setupDialpadButtons(grid: GridLayout) {
        grid.removeAllViews()

        val buttonData = listOf(
            Pair("1", ""),
            Pair("2", "A B C"),
            Pair("3", "D E F"),
            Pair("4", "G H I"),
            Pair("5", "J K L"),
            Pair("6", "M N O"),
            Pair("7", "P Q R S"),
            Pair("8", "T U V"),
            Pair("9", "W X Y Z"),
            Pair("*", ""),
            Pair("0", "+"),
            Pair("#", "")
        )

        val dm = resources.displayMetrics
        val density = dm.density
        // Responsive key sizing across compact & tall Android displays (leaves ample top space for suggestions)
        val screenWidthDp = dm.widthPixels / density
        val screenHeightDp = dm.heightPixels / density
        val keyDp = minOf(66f, (screenWidthDp - 104f) / 3f, (screenHeightDp - 395f) / 4.8f).coerceIn(56f, 66f)
        val btnSize = (keyDp * density).toInt()
        val marginHorizontal = (minOf(14f, (screenWidthDp - keyDp * 3f) / 8f).coerceAtLeast(8f) * density).toInt()
        val marginVertical = (minOf(5.5f, keyDp * 0.085f).coerceAtLeast(4f) * density).toInt()

        for (item in buttonData) {
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_dial_button)
                elevation = 1.5f * density
                isClickable = true
                isFocusable = false
                isFocusableInTouchMode = false

                val params = GridLayout.LayoutParams().apply {
                    width = btnSize
                    height = btnSize
                    setMargins(marginHorizontal, marginVertical, marginHorizontal, marginVertical)
                }
                layoutParams = params

                val mainText = TextView(this@MainActivity).apply {
                    text = item.first
                    textSize = if (keyDp < 64f) 24f else 27f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.keypad_text))
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                addView(mainText)

                if (item.second.isNotEmpty()) {
                    val sub = TextView(this@MainActivity).apply {
                        text = item.second
                        textSize = 8.5f
                        letterSpacing = 0.08f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.keypad_subtext))
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                    }
                    addView(sub)
                }

                setOnClickListener { view ->
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60L).withEndAction {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                    }.start()
                    performHapticFeedback()
                    insertDialChar(item.first)
                }

                if (item.first == "0") {
                    setOnLongClickListener {
                        performHapticFeedback(true)
                        insertDialChar("+")
                        true
                    }
                } else if (item.first == "1") {
                    setOnLongClickListener {
                        performHapticFeedback(true)
                        val dlg = AlertDialog.Builder(this@MainActivity)
                            .setTitle("Speed Dial (121)")
                            .setMessage("Call Helpline / Customer Care (121)?")
                            .setPositiveButton("Call") { _, _ ->
                                placeOutgoingCall("121", "Customer Care")
                            }
                            .setNegativeButton("Cancel", null)
                            .create()
                        trackDialog(dlg)
                        dlg.show()
                        true
                    }
                }
            }
            grid.addView(btn)
        }
    }

    private fun performHapticFeedback(isLong: Boolean = false) {
        val view = window.decorView
        if (isLong) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    // =========================================================================
    // 2. APPLE STYLE RECENTS TAB (Consolidated Groups for Repeated Calls)
    // =========================================================================

    private fun saveNumberToContacts(number: String, displayName: String = "") {
        pendingContactsRefreshOnResume = true
        try {
            val cleanName = if (displayName.isNotBlank() && displayName != number && displayName.any { it.isLetter() }) {
                displayName
            } else {
                ""
            }
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
                if (cleanName.isNotBlank()) {
                    putExtra(ContactsContract.Intents.Insert.NAME, cleanName)
                }
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                    type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                    putExtra(ContactsContract.Intents.Insert.PHONE, number)
                }
                startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open Contacts application", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRecents() {
        clearInactiveTabReferences()
        val v = ViewRecentsBinding.inflate(layoutInflater, binding.contentFrame, true)
        recentsBinding = v

        // Clear missed call notifications and app icon badge when user views recents
        com.opentalk.android.notifications.MissedCallNotifier.clearMissedCalls(this)

        fun buildGroupedCalls(calls: List<CallRecord>): List<GroupedCall> {
            val groups = ArrayList<GroupedCall>(calls.size / 2 + 8)
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            val currentYearFmt = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.ENGLISH)
            val olderYearFmt = java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.ENGLISH)
            val timeFmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH)

            for (call in calls) {
                val cleanNum = PhoneNumberHelper.toCanonicalE164(call.remoteNumber).ifBlank { call.remoteNumber.trim() }
                val callDate = try {
                    java.time.Instant.ofEpochMilli(call.timestamp).atZone(zone).toLocalDate()
                } catch (_: Exception) {
                    today
                }
                val dateKey = callDate.toString()
                val lastGroup = groups.lastOrNull()
                // Group consecutive calls from the same canonical number with identical call type on the same calendar day
                if (lastGroup != null &&
                    lastGroup.remoteNumber == cleanNum &&
                    lastGroup.latestType == call.type &&
                    lastGroup.dateKey == dateKey
                ) {
                    lastGroup.calls.add(call)
                    lastGroup.count++
                } else {
                    val storedName = call.displayName.trim()
                    val formattedNum = PhoneNumberHelper.formatForDisplay(cleanNum)
                    val nationalNum = PhoneNumberHelper.toSipDialString(cleanNum)
                    val liveContactName = ContactResolver.getCachedContactName(cleanNum)
                    val resolvedName = when {
                        !liveContactName.isNullOrBlank() -> liveContactName
                        cleanNum == "121" && storedName.isNotBlank() -> storedName
                        else -> formattedNum
                    }
                    val diffDays = java.time.temporal.ChronoUnit.DAYS.between(callDate, today)
                    val dateHeader = when {
                        diffDays == 0L -> "Today"
                        diffDays == 1L -> "Yesterday"
                        today.year == callDate.year -> currentYearFmt.format(java.util.Date(call.timestamp))
                        else -> olderYearFmt.format(java.util.Date(call.timestamp))
                    }
                    val formattedTime = timeFmt.format(java.util.Date(call.timestamp)).lowercase(java.util.Locale.ENGLISH)
                    groups.add(
                        GroupedCall(
                            id = "grp_${call.id}",
                            remoteNumber = cleanNum,
                            displayName = resolvedName,
                            latestTimestamp = call.timestamp,
                            latestType = call.type,
                            count = 1,
                            calls = mutableListOf(call),
                            dateKey = dateKey,
                            dateHeader = dateHeader,
                            formattedNumber = formattedNum,
                            nationalNumber = nationalNum,
                            digitsOnly = cleanNum.filter { it.isDigit() },
                            displayNameLower = resolvedName.lowercase(),
                            formattedTimeText = formattedTime
                        )
                    )
                }
            }
            return groups
        }

        // Consume filter_missed once so tapping "All" is never reverted back to Missed on resume!
        var isMissedFilter = intent.getBooleanExtra("filter_missed", false)
        if (isMissedFilter) {
            intent.removeExtra("filter_missed")
        }
        var searchQuery = ""
        var isSelectionMode = false
        val selectedGroupIds = mutableSetOf<String>()

        var allCallsGroups: List<GroupedCall> = emptyList()
        var missedCallsGroups: List<GroupedCall> = emptyList()
        var allBaseGroups: List<GroupedCall> = emptyList()
        var displayList: List<GroupedCall> = emptyList()
        var blockedNormalizedSet: Set<String> = emptySet()
        var recentsLoadGen = 0L
        var hasLoadedInitialDb = false

        fun updateSelectionUi() {
            if (isSelectionMode) {
                v.layoutBatchActions.visibility = View.VISIBLE
                val allSelected = displayList.isNotEmpty() && displayList.all { selectedGroupIds.contains(it.id) }
                v.cbSelectAllRecents.isChecked = allSelected
                v.tvSelectedCount.text = "${selectedGroupIds.size} selected"
                val hasSelection = selectedGroupIds.isNotEmpty()
                v.btnDeleteSelectedRecents.isEnabled = hasSelection
                v.btnDeleteSelectedRecents.alpha = if (hasSelection) 1.0f else 0.45f
            } else {
                v.layoutBatchActions.visibility = View.GONE
                v.cbSelectAllRecents.isChecked = false
                selectedGroupIds.clear()
            }
        }

        val density = resources.displayMetrics.density
        val cardRadiusPx = 22f * density
        val normalCardBgColor = ContextCompat.getColor(this, R.color.surface_card)
        val selectedCardBgColor = ContextCompat.getColor(this, R.color.card_selected_bg)
        val callerNamePrimaryColor = ContextCompat.getColor(this, R.color.text_primary)
        val rippleColorList = android.content.res.ColorStateList.valueOf(0x144F46E5)
        val headerPadSide = (12 * density).toInt()
        val headerPadTopFirst = (4 * density).toInt()
        val headerPadTopNext = (14 * density).toInt()
        val headerPadBot = (7 * density).toInt()

        class RecentsRowHolder(
            val binding: ItemRecentCallBinding,
            var lastBgKey: Int = -1
        )

        fun buildDayCardRowDrawable(isFirst: Boolean, isLast: Boolean, isSelected: Boolean): android.graphics.drawable.Drawable {
            val topR = if (isFirst) cardRadiusPx else 0f
            val botR = if (isLast) cardRadiusPx else 0f
            val radii = floatArrayOf(topR, topR, topR, topR, botR, botR, botR, botR)
            val fillColor = if (isSelected) selectedCardBgColor else normalCardBgColor
            val content = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadii = radii
                setColor(fillColor)
            }
            val mask = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadii = radii
                setColor(0xFFFFFFFF.toInt())
            }
            return android.graphics.drawable.RippleDrawable(rippleColorList, content, mask)
        }

        val recentsAdapter = object : BaseAdapter() {
            override fun getCount(): Int = displayList.size
            override fun getItem(position: Int): Any = displayList[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val holder: RecentsRowHolder
                val rowView: View
                if (convertView == null) {
                    val itemBinding = ItemRecentCallBinding.inflate(LayoutInflater.from(this@MainActivity), parent, false)
                    itemBinding.tvCallerName.setTextColor(callerNamePrimaryColor)
                    holder = RecentsRowHolder(itemBinding)
                    rowView = itemBinding.root
                    rowView.tag = holder
                } else {
                    rowView = convertView
                    holder = rowView.tag as RecentsRowHolder
                }
                val itemBinding = holder.binding

                val group = displayList[position]
                val isFirstInDay = position == 0 || displayList[position - 1].dateKey != group.dateKey
                val isLastInDay = position == displayList.size - 1 || displayList[position + 1].dateKey != group.dateKey

                if (isFirstInDay) {
                    itemBinding.tvDateHeader.text = group.dateHeader
                    itemBinding.tvDateHeader.setPadding(
                        headerPadSide,
                        if (position == 0) headerPadTopFirst else headerPadTopNext,
                        headerPadSide,
                        headerPadBot
                    )
                    itemBinding.tvDateHeader.visibility = View.VISIBLE
                } else {
                    itemBinding.tvDateHeader.visibility = View.GONE
                }

                itemBinding.viewRowDivider.visibility = if (isLastInDay) View.GONE else View.VISIBLE

                // If multiple calls, display "+880 1404-004877 (2)" or "Dis master (3)"
                val titleText = if (group.count > 1) {
                    "${group.displayName} (${group.count})"
                } else {
                    group.displayName
                }
                itemBinding.tvCallerName.text = titleText

                val isCallerBlocked = blockedNormalizedSet.contains(group.remoteNumber)
                itemBinding.layoutBlockedBadge.visibility = if (isCallerBlocked) View.VISIBLE else View.GONE

                itemBinding.ivCallTypeIcon.clearColorFilter()
                when (group.latestType) {
                    CallType.MISSED -> itemBinding.ivCallTypeIcon.setImageResource(R.drawable.ic_recent_call_missed)
                    CallType.REJECTED -> itemBinding.ivCallTypeIcon.setImageResource(R.drawable.ic_recent_call_declined)
                    CallType.OUTGOING -> itemBinding.ivCallTypeIcon.setImageResource(R.drawable.ic_recent_call_outgoing)
                    CallType.INCOMING -> itemBinding.ivCallTypeIcon.setImageResource(R.drawable.ic_recent_call_incoming)
                }

                itemBinding.tvCallDetails.text = group.formattedTimeText

                // Only rebuild RippleDrawable when corner/selection bitmask actually changes for this recycled row!
                val isRowSelected = isSelectionMode && selectedGroupIds.contains(group.id)
                val bgKey = (if (isFirstInDay) 1 else 0) or (if (isLastInDay) 2 else 0) or (if (isRowSelected) 4 else 0)
                if (holder.lastBgKey != bgKey) {
                    holder.lastBgKey = bgKey
                    itemBinding.layoutCallContent.background = buildDayCardRowDrawable(
                        isFirst = isFirstInDay,
                        isLast = isLastInDay,
                        isSelected = isRowSelected
                    )
                }

                // Multi-Select UI state
                if (isSelectionMode) {
                    itemBinding.cbSelectCall.visibility = View.VISIBLE
                    itemBinding.cbSelectCall.isChecked = isRowSelected

                    itemBinding.layoutCallContent.setOnClickListener {
                        if (selectedGroupIds.contains(group.id)) {
                            selectedGroupIds.remove(group.id)
                            if (selectedGroupIds.isEmpty()) {
                                isSelectionMode = false
                            }
                        } else {
                            selectedGroupIds.add(group.id)
                        }
                        updateSelectionUi()
                        notifyDataSetChanged()
                    }
                    itemBinding.layoutCallContent.setOnLongClickListener(null)
                    itemBinding.layoutCallContent.isLongClickable = false
                } else {
                    itemBinding.cbSelectCall.visibility = View.GONE

                    itemBinding.layoutCallContent.setOnClickListener {
                        showGroupedCallInfoDialog(group)
                    }

                    itemBinding.layoutCallContent.setOnLongClickListener {
                        performHapticFeedback(true)
                        isSelectionMode = true
                        selectedGroupIds.clear()
                        selectedGroupIds.add(group.id)
                        updateSelectionUi()
                        notifyDataSetChanged()
                        true
                    }
                    itemBinding.layoutCallContent.isLongClickable = true
                }

                return rowView
            }
        }
        v.lvRecents.adapter = recentsAdapter

        onRecentsBackPressed = {
            if (isSelectionMode) {
                isSelectionMode = false
                selectedGroupIds.clear()
                updateSelectionUi()
                recentsAdapter.notifyDataSetChanged()
                true
            } else {
                false
            }
        }

        fun updateFilterUi() {
            val activeColor = ContextCompat.getColor(this, R.color.text_primary)
            val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)
            if (isMissedFilter) {
                binding.btnFilterMissed.setBackgroundResource(R.drawable.bg_ios_segment_active)
                binding.btnFilterMissed.setTextColor(activeColor)
                binding.btnFilterMissed.setTypeface(null, Typeface.BOLD)

                binding.btnFilterAll.background = null
                binding.btnFilterAll.setTextColor(inactiveColor)
                binding.btnFilterAll.setTypeface(null, Typeface.NORMAL)
            } else {
                binding.btnFilterAll.setBackgroundResource(R.drawable.bg_ios_segment_active)
                binding.btnFilterAll.setTextColor(activeColor)
                binding.btnFilterAll.setTypeface(null, Typeface.BOLD)

                binding.btnFilterMissed.background = null
                binding.btnFilterMissed.setTextColor(inactiveColor)
                binding.btnFilterMissed.setTypeface(null, Typeface.NORMAL)
            }
        }

        fun applyFilterAndRender() {
            val baseList = if (isMissedFilter) missedCallsGroups else allCallsGroups
            allBaseGroups = baseList
            if (selectedGroupIds.isNotEmpty()) {
                val validIds = baseList.map { it.id }.toSet()
                selectedGroupIds.retainAll(validIds)
                if (selectedGroupIds.isEmpty()) {
                    isSelectionMode = false
                }
            }

            displayList = if (searchQuery.isBlank()) {
                baseList
            } else {
                val queryLower = searchQuery.lowercase()
                val queryDigits = searchQuery.filter { it.isDigit() }
                baseList.filter { group ->
                    group.displayNameLower.contains(queryLower) ||
                        group.remoteNumber.contains(searchQuery, ignoreCase = true) ||
                        group.formattedNumber.contains(searchQuery, ignoreCase = true) ||
                        group.nationalNumber.contains(searchQuery, ignoreCase = true) ||
                        (queryDigits.isNotEmpty() && (group.digitsOnly.contains(queryDigits) || group.nationalNumber.contains(queryDigits))) ||
                        group.calls.any { it.formattedDateTime.contains(searchQuery, ignoreCase = true) }
                }
            }

            if (displayList.isEmpty()) {
                v.layoutEmptyRecents.visibility = View.VISIBLE
                v.tvEmptyRecentsMessage.text = if (searchQuery.isNotEmpty()) {
                    "No results for \"$searchQuery\""
                } else if (isMissedFilter) {
                    "No Missed Calls"
                } else {
                    "No Call History"
                }
                v.lvRecents.visibility = View.GONE
            } else {
                v.layoutEmptyRecents.visibility = View.GONE
                v.lvRecents.visibility = View.VISIBLE
            }

            updateSelectionUi()
            recentsAdapter.notifyDataSetChanged()
        }

        fun refreshList(reloadFromDb: Boolean = true) {
            updateFilterUi()
            if (!reloadFromDb) {
                if (!hasLoadedInitialDb) return
                blockedNormalizedSet = BlockListManager.getBlockedNormalizedSet(this)
                applyFilterAndRender()
                return
            }
            val myGen = ++recentsLoadGen
            lifecycleScope.launch(Dispatchers.IO) {
                val calls = dbHelper.getCalls()
                val freshAll = buildGroupedCalls(calls)
                val freshMissed = buildGroupedCalls(calls.filter { it.type == CallType.MISSED || it.type == CallType.REJECTED })
                val freshBlocked = BlockListManager.getBlockedNormalizedSet(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && recentsBinding === v && myGen == recentsLoadGen) {
                        hasLoadedInitialDb = true
                        allCallsGroups = freshAll
                        missedCallsGroups = freshMissed
                        blockedNormalizedSet = freshBlocked
                        applyFilterAndRender()
                    }
                }
            }
        }

        refreshList(reloadFromDb = true)

        // Real-time search watcher (filters in-memory without querying SQLite on every keystroke)
        v.etSearchRecents.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                v.btnClearSearchRecents.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                refreshList(reloadFromDb = false)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        v.btnClearSearchRecents.setOnClickListener {
            v.etSearchRecents.setText("")
        }

        // CheckBox (Select All / Deselect All)
        v.cbSelectAllRecents.setOnClickListener {
            if (v.cbSelectAllRecents.isChecked) {
                selectedGroupIds.clear()
                selectedGroupIds.addAll(displayList.map { it.id })
            } else {
                selectedGroupIds.clear()
                isSelectionMode = false
            }
            updateSelectionUi()
            recentsAdapter.notifyDataSetChanged()
        }

        // Delete Selected (resolves against unfiltered allBaseGroups so hidden selected items are properly deleted!)
        v.btnDeleteSelectedRecents.setOnClickListener {
            if (selectedGroupIds.isEmpty()) return@setOnClickListener
            val dlg = AlertDialog.Builder(this)
                .setTitle("Delete Selected Calls")
                .setMessage("Delete call records for ${selectedGroupIds.size} selected item(s)?")
                .setPositiveButton("Delete") { _, _ ->
                    val callIdsToDelete = allBaseGroups
                        .filter { selectedGroupIds.contains(it.id) }
                        .flatMap { it.calls.map { c -> c.id } }
                    selectedGroupIds.clear()
                    isSelectionMode = false
                    lifecycleScope.launch(Dispatchers.IO) {
                        val count = dbHelper.deleteCallsByIds(callIdsToDelete)
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed && recentsBinding === v) {
                                refreshList(reloadFromDb = true)
                                Toast.makeText(this@MainActivity, "Deleted $count call records", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .create()
            trackDialog(dlg)
            dlg.show()
        }

        refreshRecentsInPlace = {
            if (!isFinishing && !isDestroyed && recentsBinding === v) {
                refreshList(reloadFromDb = true)
            }
        }

        // iOS Segmented buttons in Top Navigation Bar: All / Missed
        binding.btnFilterAll.setOnClickListener {
            isMissedFilter = false
            refreshList(reloadFromDb = false)
        }

        binding.btnFilterMissed.setOnClickListener {
            isMissedFilter = true
            refreshList(reloadFromDb = false)
        }

        binding.btnClearHistory.setOnClickListener { anchor ->
            try { activePopupWindow?.dismiss() } catch (_: Exception) {}
            val popupView = layoutInflater.inflate(R.layout.popup_recents_menu, null)
            val popup = PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                isOutsideTouchable = true
                elevation = 10f * resources.displayMetrics.density
                setOnDismissListener {
                    if (activePopupWindow === this) activePopupWindow = null
                }
            }
            activePopupWindow = popup

            popupView.findViewById<View>(R.id.btnPopupClearHistory).setOnClickListener {
                popup.dismiss()
                val dlg = AlertDialog.Builder(this)
                    .setTitle("Clear Call History")
                    .setMessage("Are you sure you want to delete all call logs?")
                    .setPositiveButton("Clear All") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            dbHelper.clearAll()
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) {
                                    if (currentTab == 0 && recentsBinding === v) {
                                        refreshList(reloadFromDb = true)
                                    }
                                    Toast.makeText(this@MainActivity, "Call history cleared", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                trackDialog(dlg)
                dlg.show()
            }

            popupView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val xOff = anchor.width - popupView.measuredWidth
            val yOff = (6 * resources.displayMetrics.density).toInt()
            popup.showAsDropDown(anchor, xOff, yOff)
        }
    }

    private fun showGroupedCallInfoDialog(group: GroupedCall) {
        val sheetDialog = BottomSheetDialog(this)
        trackDialog(sheetDialog)
        val sheetBinding = DialogCallDetailsBinding.inflate(layoutInflater)
        sheetDialog.setContentView(sheetBinding.root)
        sheetDialog.setOnDismissListener {
            trackedDialogs.remove(sheetDialog)
        }

        // Make bottom sheet container transparent and configure non-collapsing smooth scroll
        sheetDialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            bottomSheet.setBackgroundResource(android.R.color.transparent)
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.isDraggable = false // Crucial: prevents sheet from closing when user scrolls call details!
            val displayMetrics = resources.displayMetrics
            behavior.maxHeight = (displayMetrics.heightPixels * 0.88).toInt()
        }

        // Close button action
        sheetBinding.btnDetailClose.setOnClickListener {
            sheetDialog.dismiss()
        }

        // 1. Caller Initials for Avatar (use letter initials for named contacts, '#' for raw phone numbers)
        val trimmedName = group.displayName.trim()
        val hasLetters = trimmedName.any { it.isLetter() }
        val initials = if (hasLetters) {
            val parts = trimmedName.split("\\s+".toRegex()).filter { it.isNotBlank() && it.any { c -> c.isLetterOrDigit() } }
            when {
                parts.size >= 2 -> "${parts[0].first { it.isLetterOrDigit() }}${parts[1].first { it.isLetterOrDigit() }}".uppercase()
                parts.isNotEmpty() -> parts[0].filter { it.isLetterOrDigit() }.take(2).uppercase()
                else -> "#"
            }
        } else {
            "#"
        }
        sheetBinding.tvAvatarInitials.text = initials.ifEmpty { "#" }

        // 2. Name & Number Subtitle (Consistent International Display Format)
        val formattedDetailNumber = PhoneNumberHelper.formatForDisplay(group.remoteNumber)
        sheetBinding.tvContactName.text = group.displayName.ifEmpty { formattedDetailNumber }
        sheetBinding.tvPhoneNumber.text = "$formattedDetailNumber • HD Voice"

        // 3. Quick Action Buttons (Call, Save, Copy, Block)
        // Call Action
        sheetBinding.btnDetailCall.setOnClickListener {
            sheetDialog.dismiss()
            placeOutgoingCall(group.remoteNumber, group.displayName)
        }

        // Save Contact Action (Top Icon)
        sheetBinding.btnDetailSaveContact.setOnClickListener {
            sheetDialog.dismiss()
            saveNumberToContacts(group.remoteNumber, group.displayName)
        }

        // Copy Number Action
        sheetBinding.btnDetailCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Phone Number", group.remoteNumber)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied $formattedDetailNumber to clipboard", Toast.LENGTH_SHORT).show()
            sheetDialog.dismiss()
        }

        // 4. Block / Unblock Caller Action (in 4th quick-action icon slot)
        fun refreshDetailBlockUi() {
            val isBlocked = BlockListManager.isBlocked(this, group.remoteNumber)
            if (isBlocked) {
                sheetBinding.tvPhoneNumber.text = "$formattedDetailNumber • Blocked"
                sheetBinding.frameDetailBlockCircle.setBackgroundResource(R.drawable.bg_action_btn_gray)
                sheetBinding.ivDetailBlockIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary))
            } else {
                sheetBinding.tvPhoneNumber.text = "$formattedDetailNumber • HD Voice"
                sheetBinding.frameDetailBlockCircle.setBackgroundResource(R.drawable.bg_action_btn_red)
                sheetBinding.ivDetailBlockIcon.setColorFilter(ContextCompat.getColor(this, R.color.call_red))
            }
        }
        refreshDetailBlockUi()

        sheetBinding.btnDetailBlock.setOnClickListener {
            val currentlyBlocked = BlockListManager.isBlocked(this, group.remoteNumber)
            sheetDialog.dismiss()
            if (currentlyBlocked) {
                BlockListManager.unblockNumber(this, group.remoteNumber)
                refreshRecentsInPlace?.invoke()
                Toast.makeText(this, "Unblocked $formattedDetailNumber", Toast.LENGTH_SHORT).show()
            } else {
                val hasDistinctName = group.displayName.isNotBlank() &&
                    !PhoneNumberHelper.looksLikePhoneNumber(group.displayName) &&
                    !PhoneNumberHelper.areSameNumber(group.displayName, group.remoteNumber)
                val blockTargetLabel = if (hasDistinctName) "${group.displayName} ($formattedDetailNumber)" else formattedDetailNumber
                val dlg = AlertDialog.Builder(this)
                    .setTitle("Block Number")
                    .setMessage("Block $blockTargetLabel?\n\nIncoming calls from this number will be silently rejected without ringing.")
                    .setPositiveButton("Block") { _, _ ->
                        BlockListManager.blockNumber(this, group.remoteNumber, group.displayName)
                        refreshRecentsInPlace?.invoke()
                        Toast.makeText(this, "Blocked $formattedDetailNumber", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                trackDialog(dlg)
                dlg.show()
            }
        }

        // 4. Call History Card List
        sheetBinding.layoutCallLogsContainer.removeAllViews()
        val totalCalls = group.calls.size
        for ((index, record) in group.calls.withIndex()) {
            val logBinding = ItemCallLogDetailBinding.inflate(layoutInflater, sheetBinding.layoutCallLogsContainer, false)

            when (record.type) {
                CallType.OUTGOING -> {
                    logBinding.ivLogTypeIcon.setImageResource(R.drawable.ic_call_made)
                    logBinding.ivLogTypeIcon.setColorFilter(0xFF007AFF.toInt())
                    logBinding.tvLogTypeTitle.text = "Outgoing Call"
                }
                CallType.INCOMING -> {
                    logBinding.ivLogTypeIcon.setImageResource(R.drawable.ic_call_received)
                    logBinding.ivLogTypeIcon.setColorFilter(0xFF34C759.toInt())
                    logBinding.tvLogTypeTitle.text = "Incoming Call"
                }
                CallType.MISSED -> {
                    logBinding.ivLogTypeIcon.setImageResource(R.drawable.ic_call_missed)
                    logBinding.ivLogTypeIcon.setColorFilter(0xFFFF3B30.toInt())
                    logBinding.tvLogTypeTitle.text = "Missed Call"
                }
                CallType.REJECTED -> {
                    logBinding.ivLogTypeIcon.setImageResource(R.drawable.ic_call_missed)
                    logBinding.ivLogTypeIcon.setColorFilter(0xFFFF3B30.toInt())
                    logBinding.tvLogTypeTitle.text = "Declined Call"
                }
            }

            logBinding.tvLogTimestamp.text = "${record.formattedDate} at ${record.formattedTime}"
            logBinding.tvLogDuration.text = if (record.type == CallType.MISSED || record.type == CallType.REJECTED) {
                "Cancelled"
            } else {
                record.formattedDuration
            }

            // Hide bottom divider for last item
            if (index == totalCalls - 1) {
                logBinding.viewLogDivider.visibility = View.GONE
            }

            sheetBinding.layoutCallLogsContainer.addView(logBinding.root)
        }

        sheetDialog.show()
    }

    // =========================================================================
    // 3. APPLE STYLE CONTACTS TAB
    // =========================================================================

    private fun showContacts() {
        clearInactiveTabReferences()
        val v = ViewContactsBinding.inflate(layoutInflater, binding.contentFrame, true)
        contactsBinding = v

        var allContacts: List<ContactResolver.ContactItem> = emptyList()
        var displayedContacts: List<ContactResolver.ContactItem> = emptyList()
        val nonDigitPlusRegex = Regex("[^0-9+]")

        val contactsAdapter = object : BaseAdapter() {
            override fun getCount(): Int = displayedContacts.size
            override fun getItem(position: Int): Any = displayedContacts[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val itemBinding: ItemContactBinding
                val rowView: View
                if (convertView == null) {
                    itemBinding = ItemContactBinding.inflate(LayoutInflater.from(this@MainActivity), parent, false)
                    rowView = itemBinding.root
                    rowView.tag = itemBinding
                } else {
                    rowView = convertView
                    itemBinding = rowView.tag as ItemContactBinding
                }

                val contact = displayedContacts[position]
                itemBinding.tvContactName.text = contact.name
                itemBinding.tvContactNumber.text = contact.displayNumber
                itemBinding.tvContactAvatar.text = contact.initials

                itemBinding.btnQuickCall.setOnClickListener {
                    placeOutgoingCall(contact.normalizedNumber, contact.name)
                }

                return rowView
            }
        }
        v.lvContacts.adapter = contactsAdapter
        v.lvContacts.setOnItemClickListener { _, _, position, _ ->
            if (position in displayedContacts.indices) {
                val contact = displayedContacts[position]
                placeOutgoingCall(contact.normalizedNumber, contact.name)
            }
        }

        fun applyContactFilter(rawQuery: String) {
            val query = rawQuery.trim().lowercase()
            v.btnClearSearchContact.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            val filtered = if (query.isEmpty()) {
                allContacts
            } else {
                val cleanQueryNum = query.replace(nonDigitPlusRegex, "")
                val queryDigits = query.filter { it.isDigit() }
                allContacts.filter { c ->
                    c.nameLower.contains(query) ||
                        (cleanQueryNum.isNotEmpty() && (c.normalizedNumber.contains(cleanQueryNum) || c.nationalNumber.contains(cleanQueryNum))) ||
                        (queryDigits.isNotEmpty() && (c.digitsOnly.contains(queryDigits) || c.nationalNumber.contains(queryDigits))) ||
                        c.displayLower.contains(query) ||
                        c.normalizedNumber.contains(query)
                }
            }
            displayedContacts = filtered
            if (filtered.isEmpty()) {
                v.layoutEmptyContacts.visibility = View.VISIBLE
                v.tvEmptyContactsMessage.text = if (query.isNotEmpty()) {
                    "No results for \"${rawQuery.trim()}\""
                } else {
                    "No Contacts Found"
                }
                v.lvContacts.visibility = View.GONE
            } else {
                v.layoutEmptyContacts.visibility = View.GONE
                v.lvContacts.visibility = View.VISIBLE
            }
            contactsAdapter.notifyDataSetChanged()
        }

        v.btnClearSearchContact.setOnClickListener {
            v.etSearchContact.setText("")
        }

        if (ContactResolver.isCacheLoaded()) {
            allContacts = ContactResolver.getCachedContacts()
            applyContactFilter(v.etSearchContact.text?.toString() ?: "")
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val loaded = ContactResolver.loadAllContacts(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && contactsBinding === v && currentTab == 2) {
                        allContacts = loaded
                        applyContactFilter(v.etSearchContact.text?.toString() ?: "")
                    }
                }
            }
        }

        v.etSearchContact.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                applyContactFilter(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })
    }

    // =========================================================================
    // PROFILE & SETTINGS BOTTOM SHEET DIALOG (Opened via ☰ Hamburger Menu)
    // =========================================================================

    private fun showProfileSettingsDialog() {
        if (settingsDialog?.isShowing == true) return
        val bottomSheet = BottomSheetDialog(this)
        settingsDialog = bottomSheet
        trackDialog(bottomSheet)
        val sheetBinding = DialogProfileSettingsBinding.inflate(layoutInflater)
        bottomSheet.setContentView(sheetBinding.root)

        // Make bottom sheet container transparent and configure non-collapsing smooth scroll
        bottomSheet.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheetView ->
            sheetView.setBackgroundResource(android.R.color.transparent)
            val behavior = BottomSheetBehavior.from(sheetView)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.isDraggable = false
            val displayMetrics = resources.displayMetrics
            behavior.maxHeight = (displayMetrics.heightPixels * 0.92).toInt()
        }

        fun maskPhoneNumber(number: String): String {
            val clean = number.trim()
            if (clean.isBlank() || clean.equals("Not Configured", ignoreCase = true) || !clean.any { it.isDigit() }) {
                return clean
            }
            return if (clean.length >= 4) {
                val prefix = clean.take(3)
                val maskLength = (clean.length - 3).coerceAtLeast(1)
                "$prefix${"*".repeat(maskLength)}"
            } else {
                clean
            }
        }

        // Populate Profile Card
        fun updateProfileCard() {
            val act = accountManager.getAccount()
            if (act.username.isNotBlank()) {
                val cleanName = if (act.displayName.equals("Sarker", ignoreCase = true) || act.displayName.equals("Sarker Communication", ignoreCase = true)) {
                    "BanglaCalls"
                } else {
                    act.displayName.ifEmpty { "SIP Account" }
                }
                sheetBinding.tvProfileAccountName.text = cleanName
                sheetBinding.tvProfileNumber.text = maskPhoneNumber(act.username)
            } else {
                sheetBinding.tvProfileAccountName.text = "Not Configured"
                sheetBinding.tvProfileNumber.text = ""
            }
        }
        updateProfileCard()

        val effectiveStatus = SipEngine.currentStatus
        currentStatus = effectiveStatus

        fun updateProfileStatusBadge(status: RegistrationStatus) {
            val greenColor = ContextCompat.getColor(this@MainActivity, R.color.registered_green)
            val amberColor = ContextCompat.getColor(this@MainActivity, R.color.registering_amber)
            val redColor = ContextCompat.getColor(this@MainActivity, R.color.failed_red)
            val neutralColor = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
            val accentColor = ContextCompat.getColor(this@MainActivity, R.color.accent)

            val act = accountManager.getAccount()
            if (act.username.isBlank()) {
                sheetBinding.tvProfileStatusBadge.text = "Not Configured"
                sheetBinding.tvProfileStatusBadge.setTextColor(neutralColor)
                sheetBinding.tvProfileBalance.text = "--"
                sheetBinding.tvProfileBalance.setTextColor(neutralColor)
                sheetBinding.tvBalanceRefreshLabel.visibility = View.GONE
                sheetBinding.layoutBalanceRefresh.isClickable = false
                sheetBinding.tvProfileBalance.isClickable = false
                sheetBinding.tvHealthOverallPill.text = "Standby"
                sheetBinding.tvHealthOverallPill.setTextColor(neutralColor)
                sheetBinding.tvHealthLatency.text = "-- ms"
                sheetBinding.tvHealthLatency.setTextColor(neutralColor)
                return
            }

            val isAmarIpProvider = CdrSyncManager.isAmarIpServer(act.server)
            sheetBinding.tvBalanceRefreshLabel.visibility = if (isAmarIpProvider) View.VISIBLE else View.GONE
            sheetBinding.layoutBalanceRefresh.isClickable = isAmarIpProvider
            sheetBinding.tvProfileBalance.isClickable = isAmarIpProvider

            val balanceStr = accountManager.getBalance().ifEmpty { "0.00" }
            val realLatency = SipEngine.lastMeasuredLatencyMs
            val latencyText = if (realLatency > 0L) "$realLatency ms" else "Online"
            when (status) {
                RegistrationStatus.REGISTERED -> {
                    sheetBinding.tvProfileStatusBadge.text = "Registered"
                    sheetBinding.tvProfileStatusBadge.setTextColor(greenColor)
                    sheetBinding.tvProfileBalance.text = if (isAmarIpProvider) {
                        "Balance: BDT $balanceStr"
                    } else {
                        "Standard SIP"
                    }
                    sheetBinding.tvProfileBalance.setTextColor(greenColor)

                    sheetBinding.tvHealthOverallPill.text = "Optimal"
                    sheetBinding.tvHealthOverallPill.setTextColor(greenColor)
                    sheetBinding.tvHealthLatency.text = latencyText
                    sheetBinding.tvHealthLatency.setTextColor(accentColor)
                }
                RegistrationStatus.REGISTERING -> {
                    sheetBinding.tvProfileStatusBadge.text = "Connecting..."
                    sheetBinding.tvProfileStatusBadge.setTextColor(amberColor)
                    sheetBinding.tvProfileBalance.text = if (isAmarIpProvider) {
                        "Balance: BDT $balanceStr"
                    } else {
                        "Standard SIP"
                    }
                    sheetBinding.tvProfileBalance.setTextColor(amberColor)

                    sheetBinding.tvHealthOverallPill.text = "Connecting"
                    sheetBinding.tvHealthOverallPill.setTextColor(amberColor)
                    sheetBinding.tvHealthLatency.text = "-- ms"
                    sheetBinding.tvHealthLatency.setTextColor(neutralColor)
                }
                RegistrationStatus.FAILED -> {
                    if (currentStatusMessage.contains("Internet", ignoreCase = true) || !SipEngine.isNetworkConnected(this@MainActivity)) {
                        sheetBinding.tvProfileStatusBadge.text = "Offline"
                    } else {
                        sheetBinding.tvProfileStatusBadge.text = "Failed"
                    }
                    sheetBinding.tvProfileStatusBadge.setTextColor(redColor)
                    sheetBinding.tvProfileBalance.text = if (isAmarIpProvider) {
                        "Balance: BDT $balanceStr"
                    } else {
                        "Standard SIP"
                    }
                    sheetBinding.tvProfileBalance.setTextColor(redColor)

                    sheetBinding.tvHealthOverallPill.text = "Offline"
                    sheetBinding.tvHealthOverallPill.setTextColor(redColor)
                    sheetBinding.tvHealthLatency.text = "-- ms"
                    sheetBinding.tvHealthLatency.setTextColor(redColor)
                }
                RegistrationStatus.UNREGISTERED -> {
                    sheetBinding.tvProfileStatusBadge.text = "Standby"
                    sheetBinding.tvProfileStatusBadge.setTextColor(neutralColor)
                    sheetBinding.tvProfileBalance.text = if (isAmarIpProvider) {
                        "Balance: BDT $balanceStr"
                    } else {
                        "Standard SIP"
                    }
                    sheetBinding.tvProfileBalance.setTextColor(neutralColor)

                    sheetBinding.tvHealthOverallPill.text = "Standby"
                    sheetBinding.tvHealthOverallPill.setTextColor(neutralColor)
                    sheetBinding.tvHealthLatency.text = "-- ms"
                    sheetBinding.tvHealthLatency.setTextColor(neutralColor)
                }
            }
        }

        updateProfileStatusBadge(effectiveStatus)

        // Trigger background balance sync + live SIP latency ping immediately when Settings opens
        CdrSyncManager.triggerSync(this, force = true)
        if (SipEngine.isRegistered()) {
            SipEngine.sendKeepAlivePing(forceOptions = true)
            lifecycleScope.launch {
                kotlinx.coroutines.delay(450L)
                if (bottomSheet.isShowing) {
                    updateProfileStatusBadge(SipEngine.currentStatus)
                }
            }
        }

        var manualBalanceRefreshPending = false
        var manualBalanceTimeoutJob: kotlinx.coroutines.Job? = null
        val dialogSyncErrorListener: (CdrSyncManager.SyncResult) -> Unit = { res ->
            runOnUiThread {
                if (isFinishing || isDestroyed || !bottomSheet.isShowing) return@runOnUiThread
                if (manualBalanceRefreshPending && res is CdrSyncManager.SyncResult.Error) {
                    manualBalanceRefreshPending = false
                    manualBalanceTimeoutJob?.cancel()
                    sheetBinding.tvBalanceRefreshLabel.text = "Refresh ↻"
                    Toast.makeText(this@MainActivity, "Balance refresh failed: ${res.message}", Toast.LENGTH_SHORT).show()
                } else if (manualBalanceRefreshPending && res is CdrSyncManager.SyncResult.Success) {
                    manualBalanceRefreshPending = false
                    manualBalanceTimeoutJob?.cancel()
                    sheetBinding.tvBalanceRefreshLabel.text = "Refresh ↻"
                }
            }
        }
        val refreshBalanceAction = View.OnClickListener {
            val act = accountManager.getAccount()
            if (act.username.isBlank() || !CdrSyncManager.isAmarIpServer(act.server)) return@OnClickListener
            if (!SipEngine.isNetworkConnected(this)) {
                Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
                return@OnClickListener
            }
            manualBalanceRefreshPending = true
            sheetBinding.tvBalanceRefreshLabel.text = "Updating..."
            Toast.makeText(this, "Updating balance...", Toast.LENGTH_SHORT).show()
            CdrSyncManager.setOnSyncResultListener(dialogSyncErrorListener)
            CdrSyncManager.triggerSync(this, force = true, isManual = true)
            manualBalanceTimeoutJob?.cancel()
            manualBalanceTimeoutJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(15_000L)
                if (manualBalanceRefreshPending && bottomSheet.isShowing) {
                    manualBalanceRefreshPending = false
                    sheetBinding.tvBalanceRefreshLabel.text = "Refresh ↻"
                }
            }
        }
        sheetBinding.layoutBalanceRefresh.setOnClickListener(refreshBalanceAction)
        sheetBinding.tvProfileBalance.setOnClickListener(refreshBalanceAction)

        val dialogStatusListener: (RegistrationStatus, String) -> Unit = { status, _ ->
            runOnUiThread {
                if (isFinishing || isDestroyed || !bottomSheet.isShowing) return@runOnUiThread
                updateProfileStatusBadge(status)
            }
        }
        val dialogBalanceListener: (String) -> Unit = { bal ->
            runOnUiThread {
                if (isFinishing || isDestroyed || !bottomSheet.isShowing) return@runOnUiThread
                updateProfileStatusBadge(SipEngine.currentStatus)
                if (manualBalanceRefreshPending) {
                    manualBalanceRefreshPending = false
                    manualBalanceTimeoutJob?.cancel()
                    sheetBinding.tvBalanceRefreshLabel.text = "Refresh ↻"
                    Toast.makeText(this@MainActivity, "Balance: BDT $bal", Toast.LENGTH_SHORT).show()
                }
            }
        }
        SipEngine.addStatusListener(dialogStatusListener)
        CdrSyncManager.setOnBalanceUpdatedListener(dialogBalanceListener)
        CdrSyncManager.setOnSyncResultListener(dialogSyncErrorListener)
        bottomSheet.setOnDismissListener {
            trackedDialogs.remove(bottomSheet)
            if (settingsDialog === bottomSheet) settingsDialog = null
            manualBalanceRefreshPending = false
            manualBalanceTimeoutJob?.cancel()
            manualBalanceTimeoutJob = null
            activeSettingsResumeRefresh = null
            onRingtoneSelectedCallback = null
            onDefaultDialerResultCallback = null
            SipEngine.removeStatusListener(dialogStatusListener)
            CdrSyncManager.setOnBalanceUpdatedListener(null)
            CdrSyncManager.setOnSyncResultListener(null)
        }

        // Offline Missed Call Sync badge updater (matches exact CdrSyncManager eligibility rules)
        fun updateSyncBadge() {
            val acc = accountManager.getAccount()
            val isEnabled = accountManager.isOfflineSyncEnabled()
            val hasAccount = acc.username.isNotBlank()
            val isAmarIp = CdrSyncManager.isAmarIpServer(acc.server)
            val hasExplicitPortalPair = accountManager.getCustomWebPortalUsername().isNotBlank() &&
                accountManager.getCustomWebPortalPassword().isNotBlank()
            val hasValidCreds = if (isAmarIp) {
                accountManager.getWebPortalPassword().isNotBlank()
            } else {
                hasExplicitPortalPair
            }
            sheetBinding.tvOfflineSyncStatusBadge.text = when {
                !hasAccount || !isEnabled -> "Disabled"
                hasValidCreds -> "Active"
                else -> "Configure"
            }
            sheetBinding.tvOfflineSyncStatusBadge.setTextColor(
                when {
                    !hasAccount || !isEnabled -> ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
                    hasValidCreds -> ContextCompat.getColor(this@MainActivity, R.color.registered_green)
                    else -> ContextCompat.getColor(this@MainActivity, R.color.primary)
                }
            )
        }

        // Account & SIP Configuration Card
        fun updateSipConfigSummary() {
            val acc = accountManager.getAccount()
            if (acc.username.isBlank()) {
                sheetBinding.tvSipConfigSummary.text = "Not Configured"
                return
            }
            val maskedUser = maskPhoneNumber(acc.username)
            val serverName = if (acc.displayName.isNotBlank() && !acc.displayName.equals("Sarker Communication", ignoreCase = true) && !acc.displayName.equals("Sarker", ignoreCase = true)) {
                acc.displayName
            } else if (acc.server.contains("sarker", ignoreCase = true) || acc.server.contains("bangla", ignoreCase = true) || acc.server.contains("103.170.231.10") || CdrSyncManager.isAmarIpServer(acc.server)) {
                "BanglaCalls"
            } else if (acc.server.isNotBlank()) {
                acc.server
            } else {
                "Standard SIP"
            }
            sheetBinding.tvSipConfigSummary.text = "$maskedUser • $serverName"
        }
        updateSipConfigSummary()

        sheetBinding.btnOpenSipConfig.setOnClickListener {
            showSipConfigurationDialog {
                updateProfileCard()
                updateSipConfigSummary()
                updateProfileStatusBadge(currentStatus)
                updateSyncBadge()
            }
        }

        // 24/7 Background Availability & Battery Optimization Card
        // Default Dialer Setting Item
        fun updateDefaultDialerUi() {
            val isDefault = isDefaultDialer()
            if (isDefault) {
                sheetBinding.tvDefaultDialerPill.text = "Default"
                sheetBinding.tvDefaultDialerPill.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.registered_green))
                sheetBinding.tvDefaultDialerSummary.text = "Active default phone app"
            } else {
                sheetBinding.tvDefaultDialerPill.text = "Set Default"
                sheetBinding.tvDefaultDialerPill.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                sheetBinding.tvDefaultDialerSummary.text = "Tap to make OpenTalk your default phone app"
            }
        }
        updateDefaultDialerUi()
        sheetBinding.btnDefaultDialer.setOnClickListener {
            promptSetDefaultDialer {
                updateDefaultDialerUi()
            }
        }

        fun updateDisplayOverlayUi() {
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this@MainActivity)
            } else true
            if (canOverlay) {
                val green = ContextCompat.getColor(this@MainActivity, R.color.registered_green)
                sheetBinding.tvDisplayOverlayPill.text = "Allowed"
                sheetBinding.tvDisplayOverlayPill.setTextColor(green)
                sheetBinding.tvDisplayOverlaySummary.text = "Pop-up call screen allowed over other apps"
            } else {
                val amber = ContextCompat.getColor(this@MainActivity, R.color.registering_amber)
                sheetBinding.tvDisplayOverlayPill.text = "Enable"
                sheetBinding.tvDisplayOverlayPill.setTextColor(amber)
                sheetBinding.tvDisplayOverlaySummary.text = "Tap to allow call screen over other apps"
            }
        }
        updateDisplayOverlayUi()
        sheetBinding.btnDisplayOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Unable to open overlay settings", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Overlay permission is always allowed on this Android version", Toast.LENGTH_SHORT).show()
            }
        }

        fun refreshSystemStatusUi() {
            updateDefaultDialerUi()
            updateDisplayOverlayUi()
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val batteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm?.isIgnoringBatteryOptimizations(packageName) == true
            } else true

            if (batteryIgnored) {
                val green = ContextCompat.getColor(this@MainActivity, R.color.registered_green)
                sheetBinding.tvBatteryPill.text = "Active"
                sheetBinding.tvBatteryPill.setTextColor(green)
                sheetBinding.tvBatteryStatusSummary.text = "24/7 Active • Always ready for calls"
                sheetBinding.tvHealthBattery.text = "Active"
                sheetBinding.tvHealthBattery.setTextColor(green)
            } else {
                val amber = ContextCompat.getColor(this@MainActivity, R.color.registering_amber)
                sheetBinding.tvBatteryPill.text = "Enable"
                sheetBinding.tvBatteryPill.setTextColor(amber)
                sheetBinding.tvBatteryStatusSummary.text = "Restricted • Tap to enable 24/7 calls"
                sheetBinding.tvHealthBattery.text = "Restricted"
                sheetBinding.tvHealthBattery.setTextColor(amber)
            }

            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isNetworkAvailable = SipEngine.isNetworkConnected(this)
            val netName: String
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(net)
                val vpnOn = SipEngine.isVpnActive(this)
                @Suppress("DEPRECATION")
                val physicalCaps = try {
                    cm?.allNetworks?.mapNotNull { cm.getNetworkCapabilities(it) }?.filter {
                        !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                val hasWifi = (caps != null && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) ||
                    physicalCaps.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) }
                val hasCell = (caps != null && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) ||
                    physicalCaps.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) }
                val hasEth = (caps != null && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) ||
                    physicalCaps.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) }
                val baseTransport = when {
                    !isNetworkAvailable -> "Offline"
                    hasWifi -> "Wi-Fi"
                    hasCell -> "4G / Data"
                    hasEth -> "Ethernet"
                    else -> "Online"
                }
                netName = if (isNetworkAvailable && vpnOn) {
                    if (baseTransport == "Online") "VPN Active" else "VPN • $baseTransport"
                } else {
                    baseTransport
                }
            } else {
                @Suppress("DEPRECATION")
                run {
                    val activeNet = cm?.activeNetworkInfo
                    netName = when {
                        !isNetworkAvailable || activeNet == null || !activeNet.isConnected -> "Offline"
                        activeNet.type == ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
                        activeNet.type == ConnectivityManager.TYPE_MOBILE -> "Mobile Data"
                        else -> "Online"
                    }
                }
            }
            sheetBinding.tvHealthNetwork.text = netName
            sheetBinding.tvHealthNetwork.setTextColor(
                ContextCompat.getColor(this@MainActivity, if (isNetworkAvailable) R.color.registered_green else R.color.failed_red)
            )
            updateProfileStatusBadge(SipEngine.currentStatus)
        }
        refreshSystemStatusUi()
        activeSettingsResumeRefresh = {
            if (bottomSheet.isShowing) {
                refreshSystemStatusUi()
            }
        }

        sheetBinding.btnBatteryOptimization.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // Sound & Notification Settings
        sheetBinding.tvCurrentRingtoneName.text = SoundSettingsManager.getCallRingtoneName(this)
        sheetBinding.btnSelectRingtone.setOnClickListener {
            onRingtoneSelectedCallback = { uri, title ->
                SoundSettingsManager.setCallRingtone(this, uri, title)
                sheetBinding.tvCurrentRingtoneName.text = SoundSettingsManager.getCallRingtoneName(this)
                Toast.makeText(this, "Call ringtone: ${SoundSettingsManager.getCallRingtoneName(this)}", Toast.LENGTH_SHORT).show()
            }
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Call Ringtone")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, SoundSettingsManager.getCallRingtoneUriOrNull(this@MainActivity))
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            }
            try {
                pendingPickerType = "call_ringtone"
                ringtonePickerLauncher.launch(intent)
            } catch (_: Exception) {
                pendingPickerType = null
                Toast.makeText(this, "Ringtone picker unavailable on this device", Toast.LENGTH_SHORT).show()
            }
        }

        sheetBinding.tvCurrentNotificationSoundName.text = SoundSettingsManager.getNotificationSoundName(this)
        sheetBinding.btnSelectNotificationSound.setOnClickListener {
            onRingtoneSelectedCallback = { uri, title ->
                SoundSettingsManager.setNotificationSound(this, uri, title)
                // Immediately recreate Android 8+ missed-call NotificationChannel with the chosen sound (or Silent)
                com.opentalk.android.notifications.MissedCallNotifier.refreshNotificationSoundChannel(this)
                sheetBinding.tvCurrentNotificationSoundName.text = SoundSettingsManager.getNotificationSoundName(this)
                Toast.makeText(this, "Notification tone: ${SoundSettingsManager.getNotificationSoundName(this)}", Toast.LENGTH_SHORT).show()
            }
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Notification Tone")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, SoundSettingsManager.getNotificationSoundUriOrNull(this@MainActivity))
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            }
            try {
                pendingPickerType = "notif_sound"
                ringtonePickerLauncher.launch(intent)
            } catch (_: Exception) {
                pendingPickerType = null
                Toast.makeText(this, "Notification tone picker unavailable on this device", Toast.LENGTH_SHORT).show()
            }
        }

        sheetBinding.switchVibrateOnCall.isChecked = SoundSettingsManager.isVibrateOnCallEnabled(this)
        sheetBinding.rowVibrateOnCall.setOnClickListener {
            sheetBinding.switchVibrateOnCall.toggle()
        }
        sheetBinding.switchVibrateOnCall.setOnCheckedChangeListener { _, isChecked ->
            SoundSettingsManager.setVibrateOnCall(this, isChecked)
            Toast.makeText(this, if (isChecked) "Vibrate on call enabled" else "Vibrate on call disabled", Toast.LENGTH_SHORT).show()
        }

        // Missed Call Notifications Toggle
        sheetBinding.switchMissedCallNotif.isChecked = SoundSettingsManager.isMissedCallNotificationEnabled(this)
        sheetBinding.rowMissedCallNotif.setOnClickListener {
            sheetBinding.switchMissedCallNotif.toggle()
        }
        sheetBinding.switchMissedCallNotif.setOnCheckedChangeListener { _, isChecked ->
            SoundSettingsManager.setMissedCallNotificationEnabled(this, isChecked)
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                }
                Toast.makeText(this, "Missed call notifications enabled", Toast.LENGTH_SHORT).show()
            } else {
                com.opentalk.android.notifications.MissedCallNotifier.clearMissedCalls(this)
                updateRecentsTabBadge()
                Toast.makeText(this, "Missed call notifications disabled", Toast.LENGTH_SHORT).show()
            }
        }

        // Dark Mode Toggle (Applies to entire app + Call & Incoming Call screens)
        sheetBinding.switchDarkMode.isChecked = SoundSettingsManager.isDarkModeEnabled(this)
        sheetBinding.rowDarkMode.setOnClickListener {
            sheetBinding.switchDarkMode.toggle()
        }
        sheetBinding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            bottomSheet.dismiss()
            SoundSettingsManager.setDarkModeEnabled(this, isChecked)
            Toast.makeText(this, if (isChecked) "Dark Mode enabled" else "Light Mode enabled", Toast.LENGTH_SHORT).show()
        }

        // Blocked Numbers (Block List) Entry Row
        fun updateBlockedCountUi() {
            val count = BlockListManager.getBlockedCount(this)
            sheetBinding.tvBlockedCountPill.text = if (count == 0) "0" else "$count Blocked"
            sheetBinding.tvBlockedCountPill.setTextColor(if (count > 0) 0xFFDC2626.toInt() else 0xFF64748B.toInt())
        }
        updateBlockedCountUi()
        sheetBinding.btnBlockList.setOnClickListener {
            showBlockListDialog {
                updateBlockedCountUi()
                if (currentTab == 0) refreshRecentsInPlace?.invoke()
            }
        }

        // Weak Network / Low-Data Mode
        sheetBinding.switchLowDataMode.isChecked = SoundSettingsManager.isLowDataModeEnabled(this)
        sheetBinding.rowLowDataMode.setOnClickListener {
            sheetBinding.switchLowDataMode.toggle()
        }
        sheetBinding.switchLowDataMode.setOnCheckedChangeListener { _, isChecked ->
            SoundSettingsManager.setLowDataMode(this, isChecked)
            SipEngine.setLowDataMode(isChecked)
            val msg = if (isChecked) "Low-Data Mode enabled" else "Low-Data Mode disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        sheetBinding.btnSystemNotificationSettings.setOnClickListener {
            val intent = Intent().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                } else {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.fromParts("package", packageName, null)
                }
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open notification settings", Toast.LENGTH_SHORT).show()
            }
        }

        // Offline Missed Call Sync settings & trigger
        updateSyncBadge()

        sheetBinding.btnOfflineSyncCard.setOnClickListener {
            showAmarIpSyncDialog {
                updateSyncBadge()
                updateProfileStatusBadge(currentStatus)
            }
        }

        // Network Diagnostics
        sheetBinding.btnDiagnostics.setOnClickListener {
            showDiagnosticsDialog()
        }

        // Update Specifications in About & Developer section (MacTunnel Style)
        sheetBinding.tvAboutAppVersion.text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        val isRegistered = SipEngine.isRegistered()
        sheetBinding.tvAboutSipStatus.text = if (isRegistered) "Active & Connected" else "Ready (Standby)"
        sheetBinding.tvAboutSipStatus.setTextColor(
            ContextCompat.getColor(this, if (isRegistered) R.color.registered_green else R.color.text_secondary)
        )

        // Check for Updates (GitHub Releases) - MacTunnel Style Outlined Button
        sheetBinding.btnAppUpdate.setOnClickListener {
            sheetBinding.layoutUpdateIdleState.visibility = View.GONE
            sheetBinding.layoutUpdateCheckingState.visibility = View.VISIBLE
            sheetBinding.btnAppUpdate.isClickable = false

            lifecycleScope.launch {
                val result = UpdateManager.checkForUpdate(this@MainActivity)
                sheetBinding.layoutUpdateCheckingState.visibility = View.GONE
                sheetBinding.layoutUpdateIdleState.visibility = View.VISIBLE
                sheetBinding.btnAppUpdate.isClickable = true

                result.onSuccess { info ->
                    if (info != null) {
                        showUpdateAvailableDialog(info)
                    } else {
                        Toast.makeText(this@MainActivity, "OpenTalk Pro is up to date (v${BuildConfig.VERSION_NAME}).", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    val msg = err.message ?: "Update check failed"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Source & Repository (GitHub) - Opens ExiumHub/OpenTalk
        sheetBinding.btnSourceRepo.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ExiumHub/OpenTalk")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Cannot open browser: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        sheetBinding.btnDoneSettings.setOnClickListener {
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    private fun showBlockListDialog(onUpdated: (() -> Unit)? = null) {
        val blockSheet = BottomSheetDialog(this)
        trackDialog(blockSheet)
        val bBinding = DialogBlockListBinding.inflate(layoutInflater)
        blockSheet.setContentView(bBinding.root)
        blockSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        blockSheet.behavior.skipCollapsed = true
        blockSheet.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheetView ->
            sheetView.setBackgroundResource(android.R.color.transparent)
            val behavior = BottomSheetBehavior.from(sheetView)
            behavior.maxHeight = (resources.displayMetrics.heightPixels * 0.85).toInt()
        }

        fun renderBlockedEntries() {
            val entries = BlockListManager.getBlockedNumbers(this)
            bBinding.layoutBlockedItemsContainer.removeAllViews()
            bBinding.btnClearAllBlocked.visibility = if (entries.isNotEmpty()) View.VISIBLE else View.GONE
            bBinding.tvBlockedListHeader.visibility = if (entries.isNotEmpty()) View.VISIBLE else View.GONE
            bBinding.layoutBlockedItemsContainer.visibility = if (entries.isNotEmpty()) View.VISIBLE else View.GONE
            bBinding.layoutEmptyBlockList.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            bBinding.tvBlockedListHeader.text = "BLOCKED CALLERS (${entries.size})"

            val density = resources.displayMetrics.density
            for ((index, entry) in entries.withIndex()) {
                val resolvedName = entry.label.ifBlank { ContactResolver.getCachedContactName(entry.normalizedNumber) ?: "" }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val hPad = (14 * density).toInt()
                    val vPad = (12 * density).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                }

                val iconWrap = FrameLayout(this).apply {
                    setBackgroundResource(R.drawable.bg_action_btn_red)
                    layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
                }
                val icon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_block)
                    setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.call_red))
                    layoutParams = FrameLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt(), Gravity.CENTER)
                }
                iconWrap.addView(icon)
                row.addView(iconWrap)

                val infoCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (12 * density).toInt()
                        marginEnd = (8 * density).toInt()
                    }
                }
                val formattedBlockedNum = PhoneNumberHelper.formatForDisplay(entry.normalizedNumber)
                val tvTitle = TextView(this).apply {
                    text = resolvedName.ifBlank { formattedBlockedNum }
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    textSize = 14.5f
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val subParts = mutableListOf<String>()
                if (resolvedName.isNotBlank()) subParts.add(formattedBlockedNum)
                if (entry.formattedDate.isNotBlank()) subParts.add("Blocked ${entry.formattedDate}")
                val tvSub = TextView(this).apply {
                    text = subParts.joinToString(" • ").ifBlank { "Blocked" }
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                infoCol.addView(tvTitle)
                infoCol.addView(tvSub)
                row.addView(infoCol)

                val btnUnblock = TextView(this).apply {
                    text = "Unblock"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                    setBackgroundResource(R.drawable.bg_settings_pill_action)
                    gravity = Gravity.CENTER
                    setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        BlockListManager.unblockNumber(this@MainActivity, entry.normalizedNumber)
                        Toast.makeText(this@MainActivity, "Unblocked ${entry.displayNumber}", Toast.LENGTH_SHORT).show()
                        renderBlockedEntries()
                        onUpdated?.invoke()
                    }
                }
                row.addView(btnUnblock)
                bBinding.layoutBlockedItemsContainer.addView(row)

                if (index < entries.size - 1) {
                    val div = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * density).toInt().coerceAtLeast(1)
                        ).apply {
                            marginStart = (62 * density).toInt()
                        }
                        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.border_subtle))
                    }
                    bBinding.layoutBlockedItemsContainer.addView(div)
                }
            }
        }

        renderBlockedEntries()

        bBinding.btnAddBlockNumber.setOnClickListener {
            val rawInput = bBinding.etBlockNumberInput.text?.toString()?.trim().orEmpty()
            val norm = ContactResolver.normalizePhoneNumber(rawInput)
            if (norm.isBlank()) {
                Toast.makeText(this, "Enter a valid phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val contactName = ContactResolver.getCachedContactName(norm) ?: ""
            BlockListManager.blockNumber(this, norm, contactName)
            bBinding.etBlockNumberInput.setText("")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(bBinding.etBlockNumberInput.windowToken, 0)
            renderBlockedEntries()
            onUpdated?.invoke()
            Toast.makeText(this, "Blocked ${PhoneNumberHelper.formatForDisplay(norm)}", Toast.LENGTH_SHORT).show()
        }

        bBinding.btnClearAllBlocked.setOnClickListener {
            val dlg = AlertDialog.Builder(this)
                .setTitle("Clear Block List")
                .setMessage("Unblock all blocked numbers?")
                .setPositiveButton("Clear All") { _, _ ->
                    BlockListManager.clearAll(this)
                    renderBlockedEntries()
                    onUpdated?.invoke()
                    Toast.makeText(this, "All numbers unblocked", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .create()
            trackDialog(dlg)
            dlg.show()
        }

        bBinding.btnCloseBlockList.setOnClickListener {
            blockSheet.dismiss()
        }

        blockSheet.setOnDismissListener {
            trackedDialogs.remove(blockSheet)
            onUpdated?.invoke()
        }
        blockSheet.show()
    }

    private fun showAmarIpSyncDialog(onDismiss: (() -> Unit)? = null) {
        val syncSheet = BottomSheetDialog(this)
        trackDialog(syncSheet)
        val syncBinding = DialogAmaripSyncSettingsBinding.inflate(layoutInflater)
        syncSheet.setContentView(syncBinding.root)

        syncSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        syncSheet.behavior.skipCollapsed = true

        val isOfflineSyncEnabled = accountManager.isOfflineSyncEnabled()
        syncBinding.switchOfflineSyncEnabled.isChecked = isOfflineSyncEnabled
        syncBinding.rowOfflineSyncToggle.setOnClickListener {
            syncBinding.switchOfflineSyncEnabled.toggle()
        }

        val customPortalUser = accountManager.getCustomWebPortalUsername()
        val customPortalPass = accountManager.getCustomWebPortalPassword()

        syncBinding.etAmaripUsername.setText(customPortalUser)
        syncBinding.etAmaripUsername.hint = "Optional"
        syncBinding.etAmaripUsername.setSelection(customPortalUser.length)

        syncBinding.etAmaripPassword.setText(customPortalPass)
        syncBinding.etAmaripPassword.hint = "Optional"
        applyNonCopyablePasswordProtection(syncBinding.etAmaripPassword)
        syncBinding.etAmaripPassword.setSelection(customPortalPass.length)

        fun setStatusPillBackground(colorInt: Int) {
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(colorInt)
            }
            syncBinding.tvSyncStatusMessage.background = bg
        }

        // Test connection & Immediate Sync button (does NOT permanently overwrite saved credentials until Save is tapped)
        syncBinding.btnTestSyncNow.setOnClickListener {
            val user = syncBinding.etAmaripUsername.text.toString().trim()
            val pass = syncBinding.etAmaripPassword.text.toString().trim()

            syncBinding.btnTestSyncNow.isEnabled = false
            syncBinding.pbSyncTesting.visibility = View.VISIBLE
            syncBinding.ivTestSyncIcon.visibility = View.GONE
            syncBinding.tvTestSyncBtnText.text = "Testing..."
            syncBinding.tvSyncStatusMessage.visibility = View.GONE

            CdrSyncManager.setOnSyncResultListener { result ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || !syncSheet.isShowing) return@runOnUiThread
                    when (result) {
                        is CdrSyncManager.SyncResult.InProgress -> {
                            syncBinding.btnTestSyncNow.isEnabled = false
                            syncBinding.pbSyncTesting.visibility = View.VISIBLE
                            syncBinding.ivTestSyncIcon.visibility = View.GONE
                            syncBinding.tvTestSyncBtnText.text = "Testing..."
                        }
                        is CdrSyncManager.SyncResult.Success -> {
                            syncBinding.btnTestSyncNow.isEnabled = true
                            syncBinding.pbSyncTesting.visibility = View.GONE
                            syncBinding.ivTestSyncIcon.visibility = View.VISIBLE
                            syncBinding.tvTestSyncBtnText.text = "Test & Sync Now"
                            syncBinding.tvSyncStatusMessage.visibility = View.VISIBLE
                            syncBinding.tvSyncStatusMessage.setTextColor(ContextCompat.getColor(this, R.color.incall_quality_text))
                            setStatusPillBackground(ContextCompat.getColor(this, R.color.incall_quality_bg))
                            val balText = if (!result.balance.isNullOrBlank()) " • Balance: BDT ${result.balance}" else ""
                            syncBinding.tvSyncStatusMessage.text = "✓ Connected. Synced ${result.newCallsCount} call(s)$balText"
                            Toast.makeText(this, "Sync successful", Toast.LENGTH_SHORT).show()
                            if (currentTab == 0) refreshRecentsInPlace?.invoke()
                        }
                        is CdrSyncManager.SyncResult.Error -> {
                            syncBinding.btnTestSyncNow.isEnabled = true
                            syncBinding.pbSyncTesting.visibility = View.GONE
                            syncBinding.ivTestSyncIcon.visibility = View.VISIBLE
                            syncBinding.tvTestSyncBtnText.text = "Test & Sync Now"
                            syncBinding.tvSyncStatusMessage.visibility = View.VISIBLE
                            syncBinding.tvSyncStatusMessage.setTextColor(ContextCompat.getColor(this, R.color.failed_red))
                            setStatusPillBackground(ContextCompat.getColor(this, R.color.danger_soft_bg))
                            syncBinding.tvSyncStatusMessage.text = "✗ Failed: ${result.message}"
                            Toast.makeText(this, "Failed: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            CdrSyncManager.testCredentialsAndSync(this, user, pass)
        }

        val saveAction = {
            val user = syncBinding.etAmaripUsername.text.toString().trim()
            val pass = syncBinding.etAmaripPassword.text.toString().trim()
            val enabled = syncBinding.switchOfflineSyncEnabled.isChecked

            if ((user.isNotEmpty() && pass.isEmpty()) || (user.isEmpty() && pass.isNotEmpty())) {
                Toast.makeText(this, "Enter both portal Username & Password, or leave both blank", Toast.LENGTH_LONG).show()
            } else {
                accountManager.setOfflineSyncEnabled(enabled)
                accountManager.setWebPortalCredentials(user, pass)

                if (enabled) {
                    CdrSyncManager.triggerSync(this, force = true)
                }
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                syncSheet.dismiss()
            }
        }

        syncBinding.btnCancelAmaripSync.setOnClickListener {
            syncSheet.dismiss()
        }
        syncBinding.btnSaveAmaripSync.setOnClickListener { saveAction() }
        syncBinding.btnDoneAmaripSync.setOnClickListener { saveAction() }

        syncSheet.setOnDismissListener {
            trackedDialogs.remove(syncSheet)
            CdrSyncManager.setOnSyncResultListener(null)
            onDismiss?.invoke()
        }

        syncSheet.show()
    }

    /**
     * Strictly prevents copying, cutting, sharing, or autofill extraction of a password field
     * while preserving normal typing, editing, and pasting of SIP/portal credentials.
     */
    private fun applyNonCopyablePasswordProtection(editText: android.widget.EditText) {
        val stripCopyCallback = object : ActionMode.Callback {
            private fun stripForbiddenItems(menu: Menu?) {
                menu?.removeItem(android.R.id.copy)
                menu?.removeItem(android.R.id.cut)
                menu?.removeItem(android.R.id.shareText)
                menu?.removeItem(android.R.id.copyUrl)
            }
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                stripForbiddenItems(menu)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                stripForbiddenItems(menu)
                return true
            }
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        editText.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        editText.customSelectionActionModeCallback = stripCopyCallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            editText.customInsertionActionModeCallback = stripCopyCallback
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            editText.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
    }

    private fun showSipConfigurationDialog(onAccountUpdated: (() -> Unit)? = null) {
        val configSheet = BottomSheetDialog(this)
        trackDialog(configSheet)
        val configBinding = DialogSipConfigurationBinding.inflate(layoutInflater)
        configSheet.setContentView(configBinding.root)
        configSheet.setOnDismissListener {
            trackedDialogs.remove(configSheet)
        }

        configSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        configSheet.behavior.skipCollapsed = true

        val current = accountManager.getAccount()
        configBinding.etSipUsername.setText(current.username)
        configBinding.etSipPassword.setText(current.password)
        applyNonCopyablePasswordProtection(configBinding.etSipPassword)
        configBinding.etSipPassword.setSelection(current.password.length)

        var selectedProtocol = "UDP"
        var selectedPort = 5060

        fun updateProtocolUi(proto: String) {
            val upper = proto.uppercase().trim()
            when {
                upper.startsWith("TLS") -> {
                    selectedProtocol = "TLS"
                    selectedPort = 5061
                    configBinding.tvSelectedProtocol.text = "TLS"
                }
                upper.startsWith("TCP") -> {
                    selectedProtocol = "TCP"
                    selectedPort = 5060
                    configBinding.tvSelectedProtocol.text = "TCP"
                }
                else -> {
                    selectedProtocol = "UDP"
                    selectedPort = 5060
                    configBinding.tvSelectedProtocol.text = "UDP"
                }
            }
        }

        val initialProto = when {
            current.transport.equals("TLS", ignoreCase = true) || current.port == 5061 -> "TLS"
            current.transport.equals("TCP", ignoreCase = true) -> "TCP"
            else -> "UDP"
        }
        updateProtocolUi(initialProto)

        val openProtocolMenu = {
            val popup = androidx.appcompat.widget.PopupMenu(this, configBinding.layoutProtocolDropdown)
            popup.menu.add(0, 1, 0, "UDP")
            popup.menu.add(0, 2, 1, "TCP")
            popup.menu.add(0, 3, 2, "TLS")
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> updateProtocolUi("UDP")
                    2 -> updateProtocolUi("TCP")
                    3 -> updateProtocolUi("TLS")
                }
                true
            }
            popup.show()
        }
        configBinding.layoutProtocolDropdown.setOnClickListener { openProtocolMenu() }
        configBinding.tvSelectedProtocol.setOnClickListener { openProtocolMenu() }

        fun updatePresetPills(selected: String) {
            val activeBg = R.drawable.bg_settings_pill_action
            val inactiveBg = R.drawable.bg_input_inset
            val activeText = ContextCompat.getColor(this, R.color.primary)
            val inactiveText = ContextCompat.getColor(this, R.color.text_primary)
            val isBanglaCalls = selected.equals("bangla", ignoreCase = true) || selected.equals("banglacalls", ignoreCase = true)
            val isOthers = selected.equals("others", ignoreCase = true)

            configBinding.btnPresetBanglaCalls.setBackgroundResource(if (isBanglaCalls) activeBg else inactiveBg)
            configBinding.btnPresetBanglaCalls.setTextColor(if (isBanglaCalls) activeText else inactiveText)
            configBinding.btnPresetOthers.setBackgroundResource(if (isOthers) activeBg else inactiveBg)
            configBinding.btnPresetOthers.setTextColor(if (isOthers) activeText else inactiveText)
        }
        val initialPreset = when {
            current.server.contains("bangla", ignoreCase = true) || current.server.contains("sarker", ignoreCase = true) || current.server == "103.170.231.10" || CdrSyncManager.isAmarIpServer(current.server) -> "bangla"
            current.username.isNotBlank() || current.server.isNotBlank() -> "others"
            else -> "bangla"
        }
        updatePresetPills(initialPreset)

        val initialServer = when {
            current.server.isNotBlank() -> current.server
            initialPreset == "bangla" -> "sip.sarkercommunication.com"
            else -> ""
        }
        configBinding.etSipServer.setText(initialServer)

        // Presets
        configBinding.btnPresetBanglaCalls.setOnClickListener {
            updatePresetPills("bangla")
            configBinding.etSipServer.setText("sip.sarkercommunication.com")
            updateProtocolUi("UDP")
            if (configBinding.etSipUsername.text.isNullOrBlank()) {
                configBinding.etSipUsername.requestFocus()
            }
            Toast.makeText(this, "Loaded BanglaCalls preset", Toast.LENGTH_SHORT).show()
        }

        configBinding.btnPresetOthers.setOnClickListener {
            updatePresetPills("others")
            configBinding.etSipServer.setText("")
            configBinding.etSipUsername.setText("")
            configBinding.etSipPassword.setText("")
            updateProtocolUi("UDP")
            configBinding.etSipUsername.requestFocus()
            Toast.makeText(this, "Cleared credentials for custom configuration", Toast.LENGTH_SHORT).show()
        }

        configBinding.btnCancelSipConfig.setOnClickListener {
            configSheet.dismiss()
        }

        val saveAction = {
            val username = configBinding.etSipUsername.text.toString().trim()
            val rawServer = configBinding.etSipServer.text.toString().trim()
            val cleanServer = rawServer
                .removePrefix("sip:")
                .removePrefix("sips:")
                .substringBefore("/")
                .trim()
            val server = if (cleanServer.count { it == ':' } == 1 && !cleanServer.startsWith("[")) {
                cleanServer.substringBefore(":")
            } else {
                cleanServer
            }
            if (username.isBlank() || server.isBlank()) {
                Toast.makeText(this, "Please enter SIP Username and Server", Toast.LENGTH_SHORT).show()
            } else {
                val finalDisplayName = when {
                    CdrSyncManager.isAmarIpServer(server) -> "BanglaCalls"
                    else -> username
                }
                val account = SipAccount(
                    displayName = finalDisplayName,
                    username = username,
                    authUsername = "",
                    password = configBinding.etSipPassword.text.toString(),
                    server = server,
                    port = selectedPort,
                    outboundProxy = "",
                    transport = selectedProtocol
                )
                val accountChanged = !current.username.equals(username, ignoreCase = true) ||
                    !current.server.equals(server, ignoreCase = true) ||
                    current.port != selectedPort ||
                    !current.transport.equals(selectedProtocol, ignoreCase = true)
                if (accountChanged) {
                    accountManager.setBalance("0.00")
                }
                accountManager.saveAccount(account)
                CdrSyncManager.triggerSync(this, force = true)

                SipVoipService.startStandby(this)
                SipEngine.triggerRegister(account, force = true)
                Toast.makeText(this, "Saved ($selectedProtocol). Connecting...", Toast.LENGTH_SHORT).show()
                onAccountUpdated?.invoke()
                configSheet.dismiss()
            }
        }

        configBinding.btnDoneSipConfig.setOnClickListener { saveAction() }
        configBinding.btnSaveAndRegister.setOnClickListener { saveAction() }

        configBinding.btnUnregister.visibility = if (current.username.isNotBlank()) View.VISIBLE else View.GONE
        configBinding.btnUnregister.setOnClickListener {
            val confirmDlg = AlertDialog.Builder(this)
                .setTitle("Remove SIP Account")
                .setMessage("Unregister and remove this SIP account from OpenTalk? Any active call will be ended.")
                .setPositiveButton("Remove") { _, _ ->
                    if (SipEngine.isInCall() || SipEngine.hasActiveIncomingCall()) {
                        SipEngine.hangUp(sendByeOrCancel = true)
                    }
                    SipEngine.unregister()
                    SipVoipService.stopService(this)
                    accountManager.clear()

                    configBinding.etSipUsername.setText("")
                    configBinding.etSipPassword.setText("")
                    configBinding.etSipServer.setText("")
                    updateProtocolUi("UDP")

                    currentStatus = RegistrationStatus.UNREGISTERED
                    updateStatusUi(RegistrationStatus.UNREGISTERED)

                    Toast.makeText(this, "Account removed", Toast.LENGTH_SHORT).show()
                    onAccountUpdated?.invoke()
                    configSheet.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .create()
            trackDialog(confirmDlg)
            confirmDlg.show()
        }

        configSheet.show()
    }

    private var hasPromptedOverlayThisSession = false

    private fun checkAndPromptOverlayAndFullScreenPermission() {
        if (hasPromptedOverlayThisSession) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
            hasPromptedOverlayThisSession = true
            val dialog = AlertDialog.Builder(this)
                .setTitle("Incoming Call Screen Permission")
                .setMessage("Allow 'Display over other apps' so incoming calls pop up full-screen on your display even when OpenTalk is closed or minimized.")
                .setPositiveButton("Allow") { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                        } catch (_: Exception) {}
                    }
                }
                .setNegativeButton("Later", null)
                .create()
            trackDialog(dialog)
            dialog.show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            if (nm != null && !nm.canUseFullScreenIntent()) {
                hasPromptedOverlayThisSession = true
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Full-Screen Call Alerts")
                    .setMessage("Allow Full-Screen Notifications so incoming calls wake your screen and show Accept/Decline buttons immediately.")
                    .setPositiveButton("Allow") { _, _ ->
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("Later", null)
                    .create()
                trackDialog(dialog)
                dialog.show()
            }
        }
    }

    private var hasPromptedBatteryThisSession = false

    private fun checkAndPromptBatteryOptimization() {
        if (hasPromptedBatteryThisSession) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                hasPromptedBatteryThisSession = true
                val dlg = AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("To receive calls 24/7 while your screen is off, please allow OpenTalk to run unrestricted in the background.")
                    .setPositiveButton("Allow") { _, _ ->
                        requestBatteryOptimizationExemption()
                    }
                    .setNegativeButton("Later", null)
                    .setCancelable(true)
                    .create()
                trackDialog(dlg)
                dlg.show()
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(fallback)
                    } catch (_: Exception) {
                        Toast.makeText(this, "Please allow unrestricted background in System Settings", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                val opened = openInfinixAutoStartIfAvailable()
                if (!opened) {
                    Toast.makeText(this, "24/7 Standby is active (Battery Unrestricted)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openInfinixAutoStartIfAvailable(): Boolean {
        val transsionIntents = listOf(
            android.content.Intent().setComponent(android.content.ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")),
            android.content.Intent().setComponent(android.content.ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.autostart.AutoStartActivity")),
            android.content.Intent().setComponent(android.content.ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.PermissionManagerActivity")),
            android.content.Intent().setComponent(android.content.ComponentName("com.transsion.phonemaster", "com.cyin.himgr.widget.activity.MainActivity")),
            android.content.Intent().setComponent(android.content.ComponentName("com.transsion.phonemaster", "com.transsion.powerchecker.PowerCheckActivity"))
        )
        for (intent in transsionIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    Toast.makeText(this, "Enable Auto-Start for OpenTalk in Phone Master", Toast.LENGTH_LONG).show()
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun showDiagnosticsDialog() {
        val account = accountManager.getAccount()
        val logs = synchronized(SipEngine.diagnosticLogs) {
            SipEngine.diagnosticLogs.take(35).joinToString("\n")
        }
        val statusText = when (currentStatus) {
            RegistrationStatus.REGISTERED -> "Status: REGISTERED (200 OK)"
            RegistrationStatus.REGISTERING -> "Status: REGISTERING..."
            RegistrationStatus.FAILED -> "Status: FAILED ($currentStatusMessage)"
            RegistrationStatus.UNREGISTERED -> "Status: NOT REGISTERED"
        }
        val latencyLine = if (SipEngine.lastMeasuredLatencyMs > 0L) "${SipEngine.lastMeasuredLatencyMs} ms" else "--"
        val vpnStateLine = if (SipEngine.isVpnActive(this)) "Active" else "Disconnected"
        val content = """
$statusText
Account: ${account.username.ifEmpty { "Not Configured" }}
Server: ${account.server.ifEmpty { "Not Configured" }}:${account.port}
Transport: UDP ${account.port} • Local IP: ${SipEngine.getLocalIpAddress(this)}
VPN Tunnel: $vpnStateLine
SIP Ping: $latencyLine

--- Real-Time SIP & Network Logs ---
${logs.ifEmpty { "[Ready] Engine initialized. No logs recorded yet." }}
        """.trimIndent()

        val diagDialog = AlertDialog.Builder(this)
            .setTitle("Network & SIP Diagnostics")
            .setMessage(content)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy Logs") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SIP Diagnostics", content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .create()
        trackDialog(diagDialog)
        diagDialog.show()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            lifecycleScope.launch(Dispatchers.IO) {
                ContactResolver.clearContactsCache()
                ContactResolver.loadAllContacts(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        when (currentTab) {
                            0 -> refreshRecentsInPlace?.invoke()
                            1 -> refreshDialerSuggestionsInPlace?.invoke()
                            2 -> showContacts()
                        }
                    }
                }
            }
        }
        updateRecentsTabBadge()
    }

    private fun showUpdateAvailableDialog(info: UpdateInfo) {
        val updateBinding = DialogUpdateAvailableBinding.inflate(layoutInflater)
        val updateDlg = AlertDialog.Builder(this)
            .setView(updateBinding.root)
            .create()
        trackDialog(updateDlg)
        updateDlg.window?.setBackgroundDrawableResource(android.R.color.transparent)

        updateBinding.tvUpdateVersionTag.text = "Version ${info.tagName}" + if (info.publishedAt.isNotBlank()) " • ${info.publishedAt}" else ""
        updateBinding.tvUpdateReleaseNotes.text = info.releaseNotes

        updateBinding.btnUpdateLater.setOnClickListener {
            updateDlg.dismiss()
        }

        updateBinding.btnUpdateNow.setOnClickListener {
            updateDlg.setCanceledOnTouchOutside(false)
            updateDlg.setCancelable(false)

            updateBinding.layoutUpdateButtons.visibility = View.GONE
            updateBinding.layoutUpdateProgress.visibility = View.VISIBLE
            updateBinding.progressUpdateBar.progress = 0
            updateBinding.tvUpdateProgressPercent.text = "Downloading update: 0%"

            lifecycleScope.launch {
                val dlResult = UpdateManager.downloadApk(
                    context = this@MainActivity,
                    downloadUrl = info.downloadUrl,
                    fileName = info.fileName
                ) { progressFloat ->
                    val percent = (progressFloat * 100).toInt().coerceIn(0, 100)
                    updateBinding.progressUpdateBar.progress = percent
                    updateBinding.tvUpdateProgressPercent.text = "Downloading update: $percent%"
                }

                dlResult.onSuccess { apkFile ->
                    updateBinding.layoutUpdateProgress.visibility = View.GONE
                    updateBinding.layoutUpdateInstalling.visibility = View.VISIBLE

                    val installResult = UpdateManager.installApk(this@MainActivity, apkFile)
                    installResult.onSuccess {
                        updateDlg.dismiss()
                    }.onFailure { err ->
                        updateBinding.layoutUpdateInstalling.visibility = View.GONE
                        updateBinding.layoutUpdateButtons.visibility = View.VISIBLE
                        updateDlg.setCanceledOnTouchOutside(true)
                        updateDlg.setCancelable(true)
                        Toast.makeText(this@MainActivity, "Install failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { err ->
                    updateBinding.layoutUpdateProgress.visibility = View.GONE
                    updateBinding.layoutUpdateButtons.visibility = View.VISIBLE
                    updateDlg.setCanceledOnTouchOutside(true)
                    updateDlg.setCancelable(true)
                    Toast.makeText(this@MainActivity, "Download failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        updateDlg.show()
    }
}
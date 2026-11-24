package com.opentalk.android

import android.app.Application
import android.util.Log
import com.opentalk.android.engine.SipEngine
import com.opentalk.android.storage.SoundSettingsManager

class OpenTalkApp : Application() {
    companion object {
        private const val TAG = "OpenTalkApp"
    }

    override fun onCreate() {
        super.onCreate()
        SoundSettingsManager.applySavedTheme(this)
        Log.i(TAG, "OpenTalk Application initializing...")
        // Initialize core singleton engines with persistent application context
        SipEngine.initialize(this)
        com.opentalk.android.notifications.MissedCallNotifier.init(this)

        // Pre-warm libphonenumber metadata & blocklist cache off the UI thread so first render is instant
        val appCtx = applicationContext
        Thread({
            com.opentalk.android.utils.PhoneNumberHelper.warmUp()
            try {
                com.opentalk.android.storage.BlockListManager.getBlockedNormalizedSet(appCtx)
            } catch (_: Exception) {}
        }, "OpenTalkWarmupThread").apply {
            isDaemon = true
            start()
        }
    }
}

package com.opentalk.android.services

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import com.opentalk.android.IncomingCallActivity
import com.opentalk.android.engine.CallState
import com.opentalk.android.engine.SipEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.O)
class TelecomConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val rawNumber = request?.address?.schemeSpecificPart?.trim() ?: ""
        val number = com.opentalk.android.utils.PhoneNumberHelper.toCanonicalE164(rawNumber).ifBlank { rawNumber }
        if (number.isBlank()) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }
        if (SipEngine.isInCall()) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.BUSY))
        }
        val conn = SipConnection(this, isIncoming = false)
        conn.setInitializing()
        conn.setDialing()
        conn.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        conn.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        conn.connectionCapabilities = Connection.CAPABILITY_SUPPORT_HOLD or
            Connection.CAPABILITY_HOLD or
            Connection.CAPABILITY_MUTE
        conn.setAudioModeIsVoip(true)
        conn.attachSipListener()
        SipEngine.startOutgoingCall(number)
        try {
            val callIntent = Intent(applicationContext, com.opentalk.android.CallActivity::class.java).apply {
                putExtra("number", number)
                putExtra("name", number)
                putExtra("is_incoming", false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            applicationContext.startActivity(callIntent)
        } catch (_: Exception) {}
        return conn
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        if (!SipEngine.hasActiveIncomingCall()) {
            return Connection.createCanceledConnection()
        }
        val conn = SipConnection(this, isIncoming = true)
        conn.setInitializing()
        conn.setRinging()
        conn.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        conn.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        conn.connectionCapabilities = Connection.CAPABILITY_SUPPORT_HOLD or
            Connection.CAPABILITY_HOLD or
            Connection.CAPABILITY_MUTE
        conn.setAudioModeIsVoip(true)
        conn.attachSipListener()
        if (!SipEngine.hasActiveIncomingCall() && SipEngine.currentCallState != CallState.CONNECTED) {
            // Do not invoke onAbort() (which could hang up an unrelated call) or return a destroyed Connection
            conn.detachSipListener()
            return Connection.createCanceledConnection()
        }
        return conn
    }

    class SipConnection(
        private val service: TelecomConnectionService,
        private val isIncoming: Boolean
    ) : Connection() {
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var isClosed = false

        private val sipStateListener: (CallState, String) -> Unit = { state, _ ->
            mainHandler.post {
                when (state) {
                    CallState.CONNECTED -> {
                        if (!isClosed && getState() != STATE_ACTIVE) {
                            setActive()
                        }
                    }
                    CallState.ENDED, CallState.FAILED, CallState.IDLE -> {
                        val cause = if (state == CallState.FAILED) DisconnectCause.ERROR else DisconnectCause.REMOTE
                        closeConnection(cause)
                    }
                    else -> {}
                }
            }
        }

        fun attachSipListener() {
            SipEngine.addCallStateListener(sipStateListener)
        }

        fun detachSipListener() {
            isClosed = true
            SipEngine.removeCallStateListener(sipStateListener)
        }

        private fun closeConnection(causeCode: Int) {
            if (isClosed) return
            isClosed = true
            SipEngine.removeCallStateListener(sipStateListener)
            val finishBlock = {
                try {
                    setDisconnected(DisconnectCause(causeCode))
                    destroy()
                } catch (_: Exception) {}
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                finishBlock()
            } else {
                mainHandler.post(finishBlock)
            }
        }

        override fun onShowIncomingCallUi() {
            val number = SipEngine.activeCallNumber ?: address?.schemeSpecificPart ?: "Unknown"
            val name = SipEngine.activeCallName ?: number
            try {
                val intent = Intent(service.applicationContext, IncomingCallActivity::class.java).apply {
                    putExtra("number", number)
                    putExtra("name", name)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
                service.applicationContext.startActivity(intent)
            } catch (_: Exception) {}
        }

        @Suppress("DEPRECATION")
        override fun onCallAudioStateChanged(state: CallAudioState?) {
            super.onCallAudioStateChanged(state)
            if (state != null && !isClosed) {
                SipEngine.setMute(state.isMuted)
            }
        }

        override fun onAnswer() {
            val number = SipEngine.activeCallNumber ?: address?.schemeSpecificPart ?: "Unknown"
            val name = SipEngine.activeCallName ?: number
            SipEngine.engineScope.launch(Dispatchers.Main) {
                val answered = SipEngine.answerIncomingCall()
                if (answered && !isClosed) {
                    setActive()
                    try {
                        val callIntent = Intent(service.applicationContext, com.opentalk.android.CallActivity::class.java).apply {
                            putExtra("number", number)
                            putExtra("name", name)
                            putExtra("is_incoming", true)
                            putExtra("auto_answer", false)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        service.applicationContext.startActivity(callIntent)
                    } catch (_: Exception) {}
                } else {
                    closeConnection(DisconnectCause.ERROR)
                }
            }
        }

        override fun onReject() {
            closeConnection(DisconnectCause.REJECTED)
            SipEngine.declineIncomingCall()
        }

        override fun onDisconnect() {
            closeConnection(DisconnectCause.LOCAL)
            SipEngine.hangUp()
        }

        override fun onAbort() {
            closeConnection(DisconnectCause.CANCELED)
            if (isIncoming && SipEngine.hasActiveIncomingCall()) {
                SipEngine.declineIncomingCall()
            } else {
                SipEngine.hangUp()
            }
        }

        override fun onHold() {
            if (!isClosed) {
                setOnHold()
                SipEngine.setHold(true)
            }
        }

        override fun onUnhold() {
            if (!isClosed) {
                setActive()
                SipEngine.setHold(false)
            }
        }
    }
}

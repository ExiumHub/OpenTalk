package com.opentalk.android.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.opentalk.android.models.CallRecord
import com.opentalk.android.models.CallType
import com.opentalk.android.models.SipAccount
import com.opentalk.android.services.SipVoipService
import com.opentalk.android.storage.CallHistoryDbHelper
import com.opentalk.android.sync.CdrSyncManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

enum class RegistrationStatus {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    FAILED
}

enum class CallState {
    IDLE,
    CONNECTING,
    RINGING,
    CONNECTED,
    ENDED,
    FAILED
}

object SipEngine {
    private const val TAG = "SipEngine"
    private const val SIP_USER_AGENT = "OpenTalk"
    private const val SDP_SESSION_NAME = "OpenTalk"

    private fun generateOpaqueCallId(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "")
    }

    @Volatile var currentStatus: RegistrationStatus = RegistrationStatus.UNREGISTERED
    @Volatile var currentCallState: CallState = CallState.IDLE
    @Volatile var activeAccount: SipAccount? = null
    val diagnosticLogs = mutableListOf<String>()

    private val callStateLock = Any()
    private var appContext: Context? = null

    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<(RegistrationStatus, String) -> Unit>()
    private val callStateListeners = java.util.concurrent.CopyOnWriteArrayList<(CallState, String) -> Unit>()
    private var onCallStateListener: ((CallState, String) -> Unit)? = null
    private var onIncomingCallListener: ((String, String) -> Unit)? = null
    private val incomingCallCancelledListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // Fallback carrier IP mapping for Bangladeshi IPTSPs if mobile DNS fails
    private val knownIpFallbacks = mapOf(
        "sip.sarkercommunication.com" to "103.170.231.10",
        "sarkercommunication.com" to "103.170.231.10",
        "103.170.231.10" to "103.170.231.10"
    )

    // Pre-compiled regular expressions & RFC 3261 compact header mappings for zero-allocation high throughput
    private val HEADER_UNFOLD_REGEX = Regex("\r?\n[ \t]+")
    private val TAG_REGEX = Regex("""tag=([^;\s]+)""", RegexOption.IGNORE_CASE)
    private val SDP_IP_REGEX = Regex("""c=IN\s+IP4\s+([0-9\.]+)""", RegexOption.IGNORE_CASE)
    private val SDP_IP6_REGEX = Regex("""c=IN\s+IP6\s+([0-9a-fA-F:\.]+)""", RegexOption.IGNORE_CASE)
    private val SDP_PORT_REGEX = Regex("""m=audio\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val SDP_CODEC_REGEX = Regex("""m=audio\s+\d+\s+RTP/AVP\s+([\d\s]+)""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("""\s+""")

    // RFC 3261 Section 7.3.3 Compact Header Form Mapping
    private val compactHeaderMap = mapOf(
        "call-id" to "i",
        "contact" to "m",
        "content-type" to "c",
        "content-length" to "l",
        "from" to "f",
        "to" to "t",
        "via" to "v",
        "subject" to "s"
    )

    fun generateBranch(): String = "z9hG4bK" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    // Persistent SIP Signaling Socket for Registration and Incoming Calls
    @Volatile private var persistentSipSocket: DatagramSocket? = null
    @Volatile private var inFlightRegisterSocket: DatagramSocket? = null
    private var sipListenerJob: Job? = null
    @Volatile private var lastRegisteredLocalIp: String? = null
    @Volatile private var lastRegisteredNetworkFingerprint: String? = null
    @Volatile private var regCallId: String? = null
    @Volatile private var regCSeq: Int = 0
    private val registerResponseChannel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    private fun abortInFlightRegistrationSocket() {
        val sock = inFlightRegisterSocket
        inFlightRegisterSocket = null
        if (sock != null && sock !== persistentSipSocket) {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    // Active Outgoing Call Dialog Tracking (RFC 3261 Section 9 & 15 Compliant)
    @Volatile private var activeCallSocket: DatagramSocket? = null
    @Volatile private var activeRtpSocket: DatagramSocket? = null
    @Volatile private var rtpStreamer: RtpAudioStreamer? = null
    @Volatile private var currentCallId: String? = null
    @Volatile private var currentFromTag: String? = null
    @Volatile private var currentToTag: String? = null
    @Volatile private var currentCallUri: String? = null
    @Volatile private var activeCallBranch: String = ""
    @Volatile private var activeCallCSeq: Int = 1
    @Volatile private var activeCallContactUri: String? = null
    @Volatile private var activeCallFromHeader: String? = null
    @Volatile private var activeCallRouteSet: List<String> = emptyList()
    @Volatile private var last200OkAckBytes: ByteArray? = null
    @Volatile private var last200OkAckAddress: InetAddress? = null
    @Volatile private var last200OkAckPort: Int = 5060
    @Volatile private var last200OkAckCSeq: Int = -1
    @Volatile private var activeTargetAddress: InetAddress? = null
    @Volatile private var activeTargetPort: Int = 5060
    private var callListeningJob: Job? = null
    @Volatile private var activeCallJob: Job? = null
    @Volatile private var isHangingUp = false
    private val outgoingCallResponseChannel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    // Incoming Call Context
    @Volatile private var incomingCallId: String? = null
    @Volatile private var incomingVia: String? = null
    @Volatile private var incomingViaHeaders: String = ""
    @Volatile private var incomingFrom: String? = null
    @Volatile private var incomingTo: String? = null
    @Volatile private var incomingCSeq: String? = null
    @Volatile private var incomingContactUri: String? = null
    @Volatile private var incomingRouteSet: List<String> = emptyList()
    @Volatile private var incomingRemoteAddress: InetAddress? = null
    @Volatile private var incomingRemotePort: Int = 5060
    @Volatile private var incomingSdp: String? = null
    @Volatile private var incomingLocalTag: String? = null
    @Volatile private var incomingLocalCSeq: Int = 10
    private var incomingRingingWatchdogJob: Job? = null
    private var incomingSetupJob: Job? = null

    // Mid-Call Seamless Network Handover (Wi-Fi <-> Mobile Data) State
    @Volatile private var pendingReInviteCSeq: Int? = null
    @Volatile private var pendingReInviteCallId: String? = null
    @Volatile private var reInviteSdpVersion: Int = 102
    @Volatile private var needsPostCallReRegister: Boolean = false
    @Volatile private var lastMigratedCallIp: String? = null
    @Volatile private var lastMigratedCallFingerprint: String? = null
    @Volatile private var lastMigratedCallGen: Long = -1L
    private var midCallHandoverJob: Job? = null
    private val reInviteResponseChannel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    // Tracks recently cancelled/declined/missed incoming Call-IDs for 60s so delayed UDP INVITE
    // retransmissions arriving after CANCEL never re-trigger ringing!
    private val recentlyCancelledIncomingCallIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun recordEndedIncomingCallId(callId: String?) {
        val clean = callId?.removePrefix("<")?.removeSuffix(">")?.trim()?.lowercase()
        if (clean.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        recentlyCancelledIncomingCallIds[clean] = now
        // Prune stale entries older than 60s
        recentlyCancelledIncomingCallIds.entries.removeIf { now - it.value > 60_000L }
    }

    private fun isRecentlyEndedIncomingCallId(callId: String?): Boolean {
        val clean = callId?.removePrefix("<")?.removeSuffix(">")?.trim()?.lowercase() ?: return false
        val ts = recentlyCancelledIncomingCallIds[clean] ?: return false
        if (System.currentTimeMillis() - ts > 60_000L) {
            recentlyCancelledIncomingCallIds.remove(clean)
            return false
        }
        return true
    }

    // Public NAT parameters learned from SIP server Via received/rport (RFC 3581) or STUN (RFC 5389) on IPv4 networks
    @Volatile var publicIp: String? = null
    @Volatile var publicPort: Int? = null
    @Volatile private var stunMappedSocketLocalPort: Int = -1
    @Volatile var serverMappedIp: String? = null
    @Volatile var serverMappedPort: Int? = null
    @Volatile private var activeRtpStunEndpoint: Pair<String, Int>? = null
    @Volatile private var activeRtpStunLocalPort: Int = -1

    private fun getActiveRtpStunForSocket(rtpSock: DatagramSocket? = activeRtpSocket): Pair<String, Int>? {
        val sock = rtpSock ?: return null
        if (sock.isClosed) return null
        val ep = activeRtpStunEndpoint ?: return null
        return if (sock.localPort == activeRtpStunLocalPort) ep else null
    }
    // Dual-stack Native IPv6 NAT64 / DNS64 transport state (RFC 6052 & RFC 7050)
    @Volatile private var activeNat64PrefixBytes: ByteArray? = null
    @Volatile private var preferIpv4FallbackForCurrentNetwork: Boolean = false
    @Volatile private var cachedResolvedHost: String? = null
    @Volatile private var cachedResolvedTimestampMs: Long = 0L
    @Volatile private var lastProvisionalResponse: ByteArray? = null

    // Active Call Metadata (Public for CallActivity & SipVoipService)
    @Volatile var activeCallNumber: String? = null
    @Volatile var activeCallName: String? = null
    @Volatile var callConnectedStartTime: Long = 0L
    // Real measured SIP round-trip latency (ms) & dead-NAT health tracking from REGISTER / OPTIONS responses
    @Volatile var lastMeasuredLatencyMs: Long = -1L
    @Volatile private var lastOptionsSentElapsedMs: Long = 0L
    @Volatile private var lastOptionsResponseElapsedMs: Long = 0L
    @Volatile private var consecutiveMissedOptions: Int = 0

    fun isInCall(): Boolean = currentCallState == CallState.CONNECTING ||
            currentCallState == CallState.RINGING ||
            currentCallState == CallState.CONNECTED

    /**
     * Atomically verifies the call is still active and has NOT been hung up before creating and
     * starting an RtpAudioStreamer. Eliminates the "zombie mic" race condition where a user hangs
     * up while 183/200 OK or answerIncomingCall() is mid-flight.
     */
    private fun startRtpStreamerSafe(
        expectedCallId: String?,
        isIncoming: Boolean,
        rtpSocket: DatagramSocket,
        remoteAddress: InetAddress,
        remotePort: Int,
        codec: Int,
        detectInBandRingback: Boolean = false,
        onRingbackToneDetected: (() -> Unit)? = null
    ): Boolean {
        val streamer: RtpAudioStreamer
        synchronized(callStateLock) {
            if (isHangingUp || rtpSocket.isClosed || activeRtpSocket !== rtpSocket) {
                log("Blocked RtpAudioStreamer start: call already hung up or RTP socket closed (Mic stays OFF).")
                return false
            }
            val activeId = if (isIncoming) incomingCallId else currentCallId
            if (expectedCallId == null || !expectedCallId.equals(activeId, ignoreCase = true)) {
                log("Blocked RtpAudioStreamer start: Call-ID mismatch ($expectedCallId != $activeId, Mic stays OFF).")
                return false
            }
            if (currentCallState == CallState.ENDED || currentCallState == CallState.IDLE || currentCallState == CallState.FAILED) {
                log("Blocked RtpAudioStreamer start: call state is $currentCallState (Mic stays OFF).")
                return false
            }
            if (rtpStreamer != null) {
                if (!detectInBandRingback) {
                    rtpStreamer?.enableInBandRingbackDetection = false
                }
                try {
                    rtpStreamer?.updateRemoteEndpoint(remoteAddress, remotePort, codec)
                } catch (_: Exception) {}
                return true
            }
            streamer = RtpAudioStreamer(
                socket = rtpSocket,
                initialRemoteAddress = remoteAddress,
                initialRemotePort = remotePort,
                sendPayloadType = codec
            )
            appContext?.let {
                streamer.isLowDataWeakNetworkMode = com.opentalk.android.storage.SoundSettingsManager.isLowDataModeEnabled(it)
            }
            streamer.onQualityUpdate = callQualityListener
            streamer.setMute(isCallMuted)
            streamer.setHold(isCallOnHold)
            streamer.enableMediaTimeout = isIncoming || (currentCallState == CallState.CONNECTED)
            streamer.initialMediaTimeoutMs = if (isIncoming) 5_000L else 7_000L
            streamer.onMediaTimeout = { reason ->
                val shouldHangup = synchronized(callStateLock) {
                    val curId = if (isIncoming) incomingCallId else currentCallId
                    currentCallState == CallState.CONNECTED &&
                        !isCallOnHold &&
                        midCallHandoverJob?.isActive != true &&
                        expectedCallId.equals(curId, ignoreCase = true)
                }
                if (shouldHangup) {
                    log("Auto-disconnecting dead call ($expectedCallId): $reason")
                    engineScope.launch {
                        hangUp(sendByeOrCancel = true)
                    }
                }
            }
            if (detectInBandRingback && onRingbackToneDetected != null) {
                streamer.onInBandRingbackDetected = onRingbackToneDetected
                streamer.enableInBandRingbackDetection = true
            }
            rtpStreamer = streamer
        }
        // Start outside callStateLock; if hangUp() races now, it already holds rtpStreamer reference
        // and sets isStoppedPermanently=true so start() aborts without opening AudioRecord!
        streamer.start()
        return true
    }

    // Background SIP Registration Keep-Alive & Renewal
    val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var lastSuccessfulRegistrationTime: Long = 0L
    @Volatile var registrationExpiresSeconds: Int = 120
    @Volatile var registrationAttemptStartTime: Long = 0L

    private var keepAliveJob: Job? = null
    private var reRegisterJob: Job? = null
    private var retryRegistrationJob: Job? = null
    private var incoming200OkRetransmitJob: Job? = null
    private var reInviteJob: Job? = null
    private val registrationMutex = kotlinx.coroutines.sync.Mutex()
    private val reInviteMutex = kotlinx.coroutines.sync.Mutex()
    private val registrationGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val callGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val dnsExecutor = java.util.concurrent.ThreadPoolExecutor(
        0,
        4,
        30L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue()
    ) { r ->
        Thread(r, "SipDnsLookup").apply { isDaemon = true }
    }

    @Volatile var isCurrentCallIncoming: Boolean = false
        private set
    @Volatile var isCallOnHold: Boolean = false
        private set
    @Volatile var isCallMuted: Boolean = false
        private set
    @Volatile var hasSessionProgress: Boolean = false
        private set
    @Volatile private var isDialogConfirmed: Boolean = false
    @Volatile private var sent200OkForIncoming: Boolean = false

    fun currentCallGeneration(): Long = callGeneration.get()
    @Volatile private var activeAnswerDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    @Volatile private var incomingAckDeferred: kotlinx.coroutines.CompletableDeferred<String?>? = null
    @Volatile private var incomingAckCallId: String? = null
    @Volatile private var incomingInviteReceivedElapsedMs: Long = 0L
    @Volatile private var lastIncomingInviteElapsedMs: Long = 0L
    @Volatile private var firstIncomingRetransmitElapsedMs: Long = 0L
    @Volatile private var incomingInviteRetransmitCount: Int = 0
    @Volatile private var incomingIsSemuxGw: Boolean = false

    private fun hasAuthQop(qop: String?): Boolean {
        if (qop.isNullOrBlank()) return false
        return qop.split(",").any { it.trim().equals("auth", ignoreCase = true) }
    }

    private val vpnStateListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun addVpnStateListener(listener: (Boolean) -> Unit) {
        if (!vpnStateListeners.contains(listener)) {
            vpnStateListeners.add(listener)
        }
        try {
            listener.invoke(isVpnActive(appContext))
        } catch (_: Exception) {}
    }

    fun removeVpnStateListener(listener: (Boolean) -> Unit) {
        vpnStateListeners.remove(listener)
    }

    fun notifyVpnStateChanged(isActive: Boolean) {
        // Invalidate cached DNS/NAT64 routes on any VPN transition so non-VPN IPv6/IPv4 routes are never reused
        cachedResolvedHost = null
        lastResolvedServerAddress = null
        activeNat64PrefixBytes = null
        for (l in vpnStateListeners) {
            try { l.invoke(isActive) } catch (_: Exception) {}
        }
    }

    private fun getActiveVpnLinkProperties(context: Context? = appContext): android.net.LinkProperties? {
        val ctx = context ?: appContext ?: return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            val activeNet = cm.activeNetwork
            if (activeNet != null && cm.getNetworkCapabilities(activeNet)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                return cm.getLinkProperties(activeNet)
            }
            @Suppress("DEPRECATION")
            val vpnNet = cm.allNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            vpnNet?.let { cm.getLinkProperties(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun isVpnTunnelInterfaceName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("tun") || lower.startsWith("wg") || lower.startsWith("ppp") || lower.startsWith("ipsec")
    }

    private fun isHotspotOrTetherApInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("ap") ||
            lower.startsWith("swlan") ||
            lower.startsWith("softap") ||
            lower == "wlan1" ||
            lower.startsWith("rndis") ||
            lower.startsWith("usb") ||
            lower.startsWith("bt-pan") ||
            lower.startsWith("p2p")
    }

    private fun getVpnInterfaceAddress(wantIpv6: Boolean): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp || !isVpnTunnelInterfaceName(iface.name)) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (wantIpv6) {
                        if (isGlobalUnicastIpv6(addr)) {
                            val host = cleanHostAddress(addr)
                            if (host.isNotBlank()) return host
                        }
                    } else {
                        if (!addr.isLoopbackAddress && !addr.isLinkLocalAddress && addr is java.net.Inet4Address) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank() && host != "0.0.0.0" && host != "127.0.0.1") {
                                return host
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun isValidNetworkFingerprint(sig: String?): Boolean {
        if (sig.isNullOrBlank()) return false
        return !sig.startsWith("127.0.0.1|none") && !sig.startsWith("vpn|127.0.0.1|none")
    }

    fun isVpnActive(context: Context? = appContext, excludeNetwork: android.net.Network? = null): Boolean {
        val ctx = context ?: appContext ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            val activeNet = cm.activeNetwork
            if (activeNet != null && activeNet != excludeNetwork) {
                val activeCaps = cm.getNetworkCapabilities(activeNet)
                if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return true
                // If this process has a live non-VPN default network (e.g. split-tunnel excluding OpenTalk),
                // do NOT treat another app's VPN as active for OpenTalk!
                return false
            }
            @Suppress("DEPRECATION")
            cm.allNetworks.any { net ->
                if (net == excludeNetwork) return@any false
                val caps = cm.getNetworkCapabilities(net)
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
                val lp = cm.getLinkProperties(net)
                lp != null && lp.linkAddresses.isNotEmpty()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun addStatusListener(listener: (RegistrationStatus, String) -> Unit) {
        if (!statusListeners.contains(listener)) {
            statusListeners.add(listener)
        }
        // Immediately sync caller with engine's live status
        try {
            listener.invoke(currentStatus, "Current Status")
        } catch (_: Exception) {}
    }

    fun removeStatusListener(listener: (RegistrationStatus, String) -> Unit) {
        statusListeners.remove(listener)
    }

    fun setStatusListener(listener: (RegistrationStatus, String) -> Unit) {
        addStatusListener(listener)
    }

    fun addCallStateListener(listener: (CallState, String) -> Unit) {
        if (!callStateListeners.contains(listener)) {
            callStateListeners.add(listener)
        }
        try {
            // Only notify initial state if there is an active ongoing call.
            // Never notify stale ENDED or FAILED states to newly created activities!
            if (currentCallState == CallState.CONNECTED || currentCallState == CallState.RINGING || currentCallState == CallState.CONNECTING) {
                listener.invoke(currentCallState, "")
            }
        } catch (_: Exception) {}
    }

    fun removeCallStateListener(listener: (CallState, String) -> Unit) {
        callStateListeners.remove(listener)
    }

    fun setCallStateListener(listener: ((CallState, String) -> Unit)?) {
        onCallStateListener = listener
    }

    fun hasActiveIncomingCall(): Boolean = incomingCallId != null && currentCallState == CallState.RINGING

    fun hasEarlyMedia(): Boolean = rtpStreamer != null

    fun getActiveCodecName(): String {
        return when (rtpStreamer?.sendPayloadType) {
            0 -> "PCMU / G.711u (HD 64 kbps)"
            else -> "PCMA / G.711a (HD 64 kbps)"
        }
    }

    fun getFriendlyFailureMessage(code: Int): String {
        return when (code) {
            404, 484 -> "Number Not Found"
            486, 600 -> "Busy"
            403 -> "Call Restricted"
            408 -> "Not Connected"
            480 -> "Number Unavailable"
            487 -> "Call Cancelled"
            603 -> "Call Declined"
            in 500..599 -> "Server Error"
            else -> "Call Failed"
        }
    }

    fun setIncomingCallListener(listener: (String, String) -> Unit) {
        onIncomingCallListener = listener
    }

    fun addIncomingCallCancelledListener(listener: () -> Unit) {
        if (!incomingCallCancelledListeners.contains(listener)) {
            incomingCallCancelledListeners.add(listener)
        }
    }

    fun removeIncomingCallCancelledListener(listener: () -> Unit) {
        incomingCallCancelledListeners.remove(listener)
    }

    fun setIncomingCallCancelledListener(listener: () -> Unit) {
        incomingCallCancelledListeners.clear()
        incomingCallCancelledListeners.add(listener)
    }

    private var callQualityListener: ((Long, Int) -> Unit)? = null

    fun setCallQualityListener(listener: ((Long, Int) -> Unit)?) {
        callQualityListener = listener
        rtpStreamer?.onQualityUpdate = listener
    }

    fun removeCallQualityListener(listener: (Long, Int) -> Unit) {
        if (callQualityListener === listener) {
            callQualityListener = null
            rtpStreamer?.onQualityUpdate = null
        }
    }

    fun isRegistered(): Boolean = currentStatus == RegistrationStatus.REGISTERED

    private var activeRegistrationJob: Job? = null

    fun isNetworkConnected(context: Context? = appContext): Boolean {
        val ctx = context ?: appContext ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNet = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(activeNet) ?: return false
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
                // When VPN is active, Android keeps NET_CAPABILITY_INTERNET=true on tun0 even if Wi-Fi AND Mobile Data
                // are BOTH off! Verify that at least one real underlying non-VPN physical transport has internet.
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    @Suppress("DEPRECATION")
                    val hasPhysicalInternet = cm.allNetworks.any { net ->
                        val c = cm.getNetworkCapabilities(net)
                        c != null &&
                            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
                    }
                    return hasPhysicalInternet
                }
                true
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun bindSocketToActiveNetwork(socket: DatagramSocket, context: Context? = appContext) {
        // Deliberate no-op: Do not hard-pin persistent sockets to a single Network instance with Network.bindSocket().
        // Pinned sockets fail to receive incoming packets when roaming between Wi-Fi and Hotspots.
        // Standard Android DatagramSockets automatically route across the active default network.
    }

    fun triggerRegister(account: SipAccount, delayMs: Long = 0L, isRenewal: Boolean = false, force: Boolean = false) {
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            val currentLocalIp = getCurrentTransportLocalIp()
            val currentFingerprint = getNetworkFingerprint(appContext)
            val ipChanged = (lastRegisteredLocalIp != null && currentLocalIp != "127.0.0.1" && currentLocalIp != lastRegisteredLocalIp)
            val fingerprintChanged = (lastRegisteredNetworkFingerprint != null &&
                isValidNetworkFingerprint(currentFingerprint) &&
                currentFingerprint != lastRegisteredNetworkFingerprint)
            if (!force && currentStatus == RegistrationStatus.REGISTERED && !isRenewal && !ipChanged && !fingerprintChanged) {
                if (now - lastSuccessfulRegistrationTime < 25_000L) {
                    log("Already registered (${(now - lastSuccessfulRegistrationTime) / 1000}s ago) and network unchanged. Skipping redundant register trigger.")
                    return
                }
            }
            if (!force && !fingerprintChanged && activeRegistrationJob?.isActive == true) {
                if (registrationAttemptStartTime > 0 && now - registrationAttemptStartTime < 12_000L) {
                    log("Registration already in progress (${now - registrationAttemptStartTime}ms). Not interrupting.")
                    return
                }
            }
            val prevJob = activeRegistrationJob
            prevJob?.cancel()
            if (force || fingerprintChanged) {
                // Immediately unblock any previous register() stuck inside blocking socket.receive()
                abortInFlightRegistrationSocket()
            }
            activeRegistrationJob = engineScope.launch {
                // Cleanly await prior cancelled registration so it unlocks registrationMutex itself
                try { withTimeoutOrNull(1_500L) { prevJob?.join() } } catch (_: Exception) {}
                if (delayMs > 0) delay(delayMs)
                register(account, isRenewal = isRenewal, force = force || fingerprintChanged)
            }
        }
    }

    /**
     * Called immediately when network connection is lost or device goes offline.
     */
    fun onNetworkLost() {
        log("Network connection lost (Device is OFFLINE)")

        // 1. If currently in an active call, tear down safely via callStateLock + cancel all call jobs
        if (currentCallState != CallState.IDLE) {
            log("Active call interrupted by network disconnection. Dropping call...")
            activeCallJob?.cancel()
            activeCallJob = null
            midCallHandoverJob?.cancel()
            midCallHandoverJob = null
            incomingRingingWatchdogJob?.cancel()
            incomingRingingWatchdogJob = null
            incoming200OkRetransmitJob?.cancel()
            incoming200OkRetransmitJob = null
            notifyCallState(CallState.FAILED, "No Internet")
            hangUp(sendByeOrCancel = false)
        }

        // 2. Stop running keepalive, renewal, and retries
        keepAliveJob?.cancel()
        keepAliveJob = null
        reRegisterJob?.cancel()
        reRegisterJob = null
        retryRegistrationJob?.cancel()
        retryRegistrationJob = null
        registrationGeneration.incrementAndGet()
        activeRegistrationJob?.cancel()
        activeRegistrationJob = null
        sipListenerJob?.cancel()
        sipListenerJob = null

        // 3. Close persistent socket and invalidate cached local IP & NAT state
        try { persistentSipSocket?.close() } catch (_: Exception) {}
        persistentSipSocket = null
        abortInFlightRegistrationSocket()
        lastRegisteredLocalIp = null
        lastRegisteredNetworkFingerprint = null
        regCallId = null
        regCSeq = 0
        publicIp = null
        publicPort = null
        stunMappedSocketLocalPort = -1
        serverMappedIp = null
        serverMappedPort = null
        activeNat64PrefixBytes = null
        preferIpv4FallbackForCurrentNetwork = false
        cachedResolvedHost = null
        cachedResolvedTimestampMs = 0L
        lastResolvedServerAddress = null

        // 4. Update status to FAILED ("No Internet") and notify all listeners immediately
        currentStatus = RegistrationStatus.FAILED
        notifyStatus(RegistrationStatus.FAILED, "No Internet")
    }

    /**
     * Called immediately when network connection is restored or default network switches.
     */
    fun onNetworkAvailable(context: Context? = null, forceRebind: Boolean = false) {
        val ctx = context ?: appContext
        log("Network connection available (forceRebind=$forceRebind). Checking connectivity...")

        if (isInCall()) {
            log("Network available event during active call: triggering seamless mid-call migration check...")
            migrateActiveCallToNewNetwork(ctx)
            return
        }

        if (forceRebind) {
            onNetworkHandover(ctx)
            return
        }

        val account = activeAccount ?: (ctx?.let { com.opentalk.android.storage.SecureAccountManager(it).getAccount() })
        if (account == null || account.username.isBlank() || account.server.isBlank()) {
            return
        }
        activeAccount = account

        // Stop any pending retry jobs
        retryRegistrationJob?.cancel()
        retryRegistrationJob = null

        // Immediately update status to REGISTERING so UI shows amber "Connecting"
        currentStatus = RegistrationStatus.REGISTERING
        notifyStatus(RegistrationStatus.REGISTERING, "Connecting...")

        // Launch registration with a brief 350ms delay to let IP routing and DHCP stabilize
        triggerRegister(account, delayMs = 350L)
    }

    /**
     * Softly informs UI that the active call's underlying network dropped (e.g. Wi-Fi lost)
     * while waiting up to 15s for Mobile Data / replacement network to take over seamlessly.
     * NEVER drops the call or stops AudioRecord/AudioTrack!
     */
    fun onCallNetworkInterrupted() {
        if (currentCallState == CallState.CONNECTED) {
            log("Active call network temporarily lost (e.g. Wi-Fi dropped). Holding call alive while waiting for Mobile Data handover...")
            notifyCallState(CallState.CONNECTED, "Reconnecting...")
        }
    }

    /**
     * Complete, seamless network handover (e.g. WiFi <-> Cellular Data, or Cellular IP change).
     * - When IDLE: destroys obsolete socket and re-registers cleanly on the new network.
     * - When IN CALL: keeps the call alive with ZERO hangup by hot-swapping the RTP media socket
     *   onto the new interface (instant Symmetric RTP recovery), migrating the SIP signaling socket,
     *   and sending an RFC 3261 Section 14 in-dialog re-INVITE with the new IP/RTP port!
     */
    fun onNetworkHandover(context: Context? = null) {
        val ctx = context ?: appContext
        log("Network handover event detected! (inCall=${isInCall()}, state=$currentCallState)")

        if (isInCall()) {
            migrateActiveCallToNewNetwork(ctx)
            return
        }

        log("Tearing down old idle interface session and re-registering on new network...")

        // Cancel previous registration or renewal jobs
        activeRegistrationJob?.cancel()
        activeRegistrationJob = null
        reRegisterJob?.cancel()
        reRegisterJob = null
        retryRegistrationJob?.cancel()
        retryRegistrationJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        sipListenerJob?.cancel()
        sipListenerJob = null

        // Clean up the obsolete socket & stale NAT mapping from the previous network interface
        try { persistentSipSocket?.close() } catch (_: Exception) {}
        persistentSipSocket = null
        abortInFlightRegistrationSocket()
        lastRegisteredLocalIp = null
        lastRegisteredNetworkFingerprint = null
        regCallId = null
        regCSeq = 0
        publicIp = null
        publicPort = null
        stunMappedSocketLocalPort = -1
        serverMappedIp = null
        serverMappedPort = null
        activeNat64PrefixBytes = null
        preferIpv4FallbackForCurrentNetwork = false
        cachedResolvedHost = null
        cachedResolvedTimestampMs = 0L
        lastResolvedServerAddress = null
        registrationGeneration.incrementAndGet()

        currentStatus = RegistrationStatus.REGISTERING
        notifyStatus(RegistrationStatus.REGISTERING, "Reconnecting...")

        val account = activeAccount ?: (ctx?.let { com.opentalk.android.storage.SecureAccountManager(it).getAccount() })
        if (account != null && account.username.isNotBlank() && account.server.isNotBlank()) {
            triggerRegister(account, delayMs = 350L, force = true)
        }
    }

    /**
     * Seamless Mid-Call Network Migration (Wi-Fi <-> Mobile Data):
     * 1. Hot-swaps the active RTP DatagramSocket onto the new network interface and fires 8x
     *    Symmetric RTP hole-punch packets so bidirectional voice recovers in ~100ms without dropping.
     * 2. Migrates persistentSipSocket onto the new interface so SIP signaling (re-INVITE / BYE) stays reachable.
     * 3. Sends an RFC 3261 Section 14 in-dialog re-INVITE with updated Contact & SDP (with full 401/407 auth support).
     * 4. Schedules clean background SIP re-registration automatically once the call finishes.
     */
    private fun migrateActiveCallToNewNetwork(ctx: Context?) {
        midCallHandoverJob?.cancel()
        midCallHandoverJob = engineScope.launch {
            try {
                if (currentCallState == CallState.CONNECTED) {
                    notifyCallState(CallState.CONNECTED, "Reconnecting...")
                }

                // Wait briefly for Android's new default network route & interface to stabilize
                val account = activeAccount ?: ctx?.let { com.opentalk.android.storage.SecureAccountManager(it).getAccount() }
                val targetHost = account?.server?.trim() ?: "sip.sarkercommunication.com"

                var newLocalIp = "127.0.0.1"
                for (attempt in 1..14) {
                    if (!isInCall() || isHangingUp) return@launch
                    if (isNetworkConnected(ctx)) {
                        val candidate = getCurrentTransportLocalIp(ctx)
                        if (candidate.isNotBlank() && candidate != "127.0.0.1" && candidate != "0.0.0.0") {
                            newLocalIp = candidate
                            break
                        }
                    }
                    delay(150L)
                }

                if (!isInCall() || isHangingUp) return@launch
                if (newLocalIp == "127.0.0.1") {
                    log("Mid-call handover: could not obtain valid local IP yet; preserving call and retrying when link updates.")
                    return@launch
                }

                // CRITICAL CALL-SETUP PROTECTION:
                // During CONNECTING or RINGING, if neither local IP nor underlying network fingerprint changed
                // and persistentSipSocket is still open, NEVER close/swap persistentSipSocket!
                val currentFingerprint = getNetworkFingerprint(ctx)
                val fingerprintUnchanged = lastRegisteredNetworkFingerprint != null &&
                    currentFingerprint == lastRegisteredNetworkFingerprint
                if ((currentCallState == CallState.CONNECTING || currentCallState == CallState.RINGING) &&
                    persistentSipSocket?.isClosed == false &&
                    (lastRegisteredLocalIp == null || newLocalIp == lastRegisteredLocalIp) &&
                    (lastRegisteredNetworkFingerprint == null || fingerprintUnchanged)) {
                    log("Suppressing redundant SIP socket swap during $currentCallState (network unchanged=$currentFingerprint). Sending NAT keepalive instead.")
                    try {
                        val sock = persistentSipSocket
                        val addr = activeTargetAddress ?: incomingRemoteAddress
                        if (sock != null && !sock.isClosed && addr != null) {
                            val crlf = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                            sock.send(DatagramPacket(crlf, crlf.size, addr, activeTargetPort))
                        }
                    } catch (_: Exception) {}
                    return@launch
                }

                // When VPN is active across an underlying Wi-Fi <-> Mobile Data switch, give the VPN tunnel
                // a brief stabilization window so its outer WireGuard/WARP handshake settles before STUN/re-INVITE.
                if (isVpnActive(ctx)) {
                    delay(450L)
                    if (!isInCall() || isHangingUp) return@launch
                }

                // Re-resolve server address cleanly for the NEW network (handles IPv4 Wi-Fi <-> IPv6 NAT64 4G seamlessly!)
                preferIpv4FallbackForCurrentNetwork = false
                cachedResolvedHost = null
                cachedResolvedTimestampMs = 0L
                val serverAddr = try {
                    resolveServerAddress(targetHost, forceRefresh = true)
                } catch (_: Exception) {
                    activeTargetAddress ?: incomingRemoteAddress ?: resolveServerAddress(targetHost)
                }
                newLocalIp = getLocalAddressForTarget(serverAddr, ctx)
                val postSwitchFingerprint = getNetworkFingerprint(ctx)

                val curCallGen = callGeneration.get()
                if (lastMigratedCallGen == curCallGen &&
                    newLocalIp == lastMigratedCallIp &&
                    postSwitchFingerprint == lastMigratedCallFingerprint &&
                    persistentSipSocket?.isClosed == false &&
                    activeRtpSocket?.isClosed == false) {
                    log("Mid-call handover already completed for $postSwitchFingerprint; restoring Connected UI.")
                    if (currentCallState == CallState.CONNECTED) {
                        notifyCallState(CallState.CONNECTED, "In Call")
                    }
                    return@launch
                }

                log("Mid-call seamless handover starting on new interface IP=$newLocalIp (sig=$postSwitchFingerprint) -> server=${cleanHostAddress(serverAddr)} (state=$currentCallState)...")
                lastMigratedCallGen = curCallGen
                lastMigratedCallIp = newLocalIp
                lastMigratedCallFingerprint = postSwitchFingerprint
                needsPostCallReRegister = true
                serverMappedIp = null
                serverMappedPort = null
                publicIp = null
                publicPort = null
                stunMappedSocketLocalPort = -1
                activeRtpStunEndpoint = null
                activeRtpStunLocalPort = -1
                if (currentCallId != null) {
                    activeTargetAddress = serverAddr
                } else if (incomingCallId != null) {
                    incomingRemoteAddress = serverAddr
                }
                val serverPort = when {
                    currentCallId != null -> activeTargetPort
                    incomingCallId != null && incomingRemotePort > 0 -> incomingRemotePort
                    else -> account?.port ?: 5060
                }

                // PHASE 1: Hot-swap RTP Media Socket immediately for instant Symmetric RTP audio continuity!
                var newRtpPort = activeRtpSocket?.localPort ?: 40000
                val currentStreamer = rtpStreamer
                if (currentStreamer != null) {
                    try {
                        val freshRtpSocket = DatagramSocket()
                        bindSocketToActiveNetwork(freshRtpSocket, ctx)
                        synchronized(callStateLock) {
                            if (isHangingUp || !isInCall()) {
                                try { freshRtpSocket.close() } catch (_: Exception) {}
                                return@launch
                            }
                            activeRtpSocket = freshRtpSocket
                        }
                        newRtpPort = freshRtpSocket.localPort
                        if (!isIpv6Target(serverAddr)) {
                            val rtpMapped = resolveMappedEndpointViaStun(freshRtpSocket, updateSipGlobals = false, maxServers = 1, timeoutMs = 350)
                            if (rtpMapped != null) {
                                activeRtpStunEndpoint = rtpMapped
                                activeRtpStunLocalPort = freshRtpSocket.localPort
                            }
                        }
                        // If address family changed (IPv4 Wi-Fi <-> IPv6 NAT64 4G), adapt RTP target address family too!
                        val (curRtpAddr, curRtpPort) = currentStreamer.getRemoteEndpoint()
                        val adaptedRtpAddr = adaptMediaAddressForPeer(curRtpAddr, serverAddr)
                        if (adaptedRtpAddr != curRtpAddr) {
                            currentStreamer.updateRemoteEndpoint(adaptedRtpAddr, curRtpPort)
                        }
                        currentStreamer.migrateSocket(freshRtpSocket)
                        log("Mid-call Phase 1 DONE: RTP media migrated to $newLocalIp:$newRtpPort -> ${cleanHostAddress(adaptedRtpAddr)}:$curRtpPort (Symmetric RTP hole-punch active).")
                    } catch (e: Exception) {
                        log("Mid-call RTP socket migration error: ${e.message}")
                    }
                }

                // PHASE 2: Migrate SIP Signaling Socket onto the new network interface
                val newSipSocket = try {
                    DatagramSocket().also {
                        bindSocketToActiveNetwork(it, ctx)
                        if (!isIpv6Target(serverAddr)) {
                            resolveMappedEndpointViaStun(it, updateSipGlobals = true, maxServers = 1, timeoutMs = 350)
                        }
                    }
                } catch (e: Exception) {
                    log("Mid-call SIP socket creation failed: ${e.message}")
                    null
                }

                if (newSipSocket != null) {
                    val oldSipSocket = persistentSipSocket
                    synchronized(callStateLock) {
                        if (isHangingUp || !isInCall()) {
                            try { newSipSocket.close() } catch (_: Exception) {}
                            return@launch
                        }
                        persistentSipSocket = newSipSocket
                        if (activeCallSocket === oldSipSocket) {
                            activeCallSocket = newSipSocket
                        }
                    }
                    // Start listener on newSipSocket FIRST, then close obsolete oldSipSocket cleanly
                    startPersistentSipListener(newSipSocket)
                    if (oldSipSocket != null && oldSipSocket !== newSipSocket) {
                        try { oldSipSocket.close() } catch (_: Exception) {}
                    }
                    // Open new Mobile Data SIP NAT pinhole immediately
                    try {
                        val crlf = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                        newSipSocket.send(DatagramPacket(crlf, crlf.size, serverAddr, serverPort))
                    } catch (_: Exception) {}
                    log("Mid-call Phase 2 DONE: SIP signaling socket migrated to $newLocalIp:${newSipSocket.localPort}.")
                }

                // PHASE 3: Send RFC 3261 In-Dialog re-INVITE for Connected Calls (preserves local Hold state!)
                val sipSock = persistentSipSocket
                if (currentCallState == CallState.CONNECTED && sipSock != null && !sipSock.isClosed && account != null) {
                    sendMidCallHandoverReInvite(
                        socket = sipSock,
                        account = account,
                        newLocalIp = newLocalIp,
                        newRtpPort = newRtpPort,
                        targetAddr = serverAddr,
                        targetPort = serverPort,
                        sdpDirection = if (isCallOnHold) "sendonly" else "sendrecv"
                    )
                }

                if (currentCallState == CallState.CONNECTED) {
                    notifyCallState(CallState.CONNECTED, "In Call")
                }
                log("Mid-call seamless network handover COMPLETE! Conversation continues uninterrupted on $newLocalIp.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Mid-call handover non-fatal error (call preserved): ${e.message}")
                if (currentCallState == CallState.CONNECTED) {
                    notifyCallState(CallState.CONNECTED, "In Call")
                }
            }
        }
    }

    /**
     * Sends an RFC 3261 Section 14 in-dialog re-INVITE to seamlessly move an active SIP dialog
     * and RTP media endpoint to the new network interface (Wi-Fi <-> Mobile Data).
     * Never drops the call if the remote server declines or ignores re-INVITE (Symmetric RTP still works).
     */
    private suspend fun sendMidCallHandoverReInvite(
        socket: DatagramSocket,
        account: SipAccount,
        newLocalIp: String,
        newRtpPort: Int,
        targetAddr: InetAddress,
        targetPort: Int,
        sdpDirection: String = "sendrecv"
    ) {
        // Serialize concurrent Hold/Unhold & Handover re-INVITEs BEFORE reading/incrementing CSeq & SDP o= version
        reInviteMutex.withLock {
        val activeSocket = persistentSipSocket?.takeIf { !it.isClosed } ?: socket
        if (activeSocket.isClosed || currentCallState != CallState.CONNECTED || isHangingUp) return

        val isOutgoing = currentCallId != null
        val dialogCallId = if (isOutgoing) currentCallId else incomingCallId
        if (dialogCallId.isNullOrBlank()) return

        val username = account.username.trim()
        val authUser = account.effectiveAuthUser
        val password = account.password
        val sipPort = activeSocket.localPort
        val liveRtpPort = activeRtpSocket?.takeIf { !it.isClosed }?.localPort ?: newRtpPort
        val effectiveLocalIp = if (newLocalIp.isNotBlank() && newLocalIp != "127.0.0.1" &&
            (isIpv6Target(targetAddr) == newLocalIp.contains(":"))) {
            newLocalIp
        } else {
            getLocalAddressForTarget(targetAddr)
        }
        val (viaHost, viaPort) = getViaEndpoint(effectiveLocalIp, sipPort, targetAddr)
        val (contactHost, contactPort) = getContactEndpoint(effectiveLocalIp, sipPort, targetAddr)
        val codec = rtpStreamer?.sendPayloadType ?: 8
        val sdpVer = ++reInviteSdpVersion
        val effectiveDirection = if (isCallOnHold) "sendonly" else sdpDirection

        val reInvCodecBlock = "$codec 101\r\n" +
                (if (codec == 8) "a=rtpmap:8 PCMA/8000\r\n" else "a=rtpmap:0 PCMU/8000\r\n") +
                "a=rtpmap:101 telephone-event/8000\r\n" +
                "a=fmtp:101 0-16\r\n" +
                "a=ptime:20\r\n"
        val sdp = buildAudioSdp(
            username = username,
            sessionVer = sdpVer,
            sessionName = SDP_SESSION_NAME,
            targetAddress = targetAddr,
            localIp = effectiveLocalIp,
            localRtpPort = liveRtpPort,
            rtpStun = getActiveRtpStunForSocket(activeRtpSocket),
            codecBlock = reInvCodecBlock,
            direction = effectiveDirection
        )
        val sdpBytesLen = sdp.toByteArray(Charsets.ISO_8859_1).size

        val reqUri: String
        val routeHeaders: String
        val toHeaderLine: String
        val fromHeaderLine: String
        var cseqNum: Int

        if (isOutgoing) {
            val baseUri = currentCallUri ?: return
            reqUri = activeCallContactUri ?: baseUri
            routeHeaders = formatRouteHeaders(activeCallRouteSet)
            toHeaderLine = if (currentToTag != null) "To: <$baseUri>;tag=$currentToTag\r\n" else "To: <$baseUri>\r\n"
            fromHeaderLine = activeCallFromHeader ?: "From: <sip:$username@${account.server}>;tag=$currentFromTag\r\n"
            cseqNum = synchronized(callStateLock) { ++activeCallCSeq }
        } else {
            val incFrom = incomingFrom ?: return
            val incTo = incomingTo ?: return
            val cleanIncTo = incTo.replace(Regex(";tag=[^;]+"), "")
            val toTagPart = if (incomingLocalTag != null) ";tag=$incomingLocalTag" else ""
            val fallbackHost = formatHostForUri(targetAddr)
            reqUri = (incomingContactUri ?: Regex("""<([^>]+)>""").find(incFrom)?.groupValues?.get(1)
                ?: "sip:$fallbackHost:$targetPort").replace("<", "").replace(">", "").trim()
            routeHeaders = formatRouteHeaders(incomingRouteSet)
            toHeaderLine = "To: $incFrom\r\n"
            fromHeaderLine = "From: $cleanIncTo$toTagPart\r\n"
            cseqNum = synchronized(callStateLock) { ++incomingLocalCSeq }
        }

        fun buildReInvite(cseq: Int, branch: String, authLine: String = ""): String {
            return "INVITE $reqUri SIP/2.0\r\n" +
                    "Via: SIP/2.0/UDP $viaHost:$viaPort;rport;branch=$branch\r\n" +
                    "Max-Forwards: 70\r\n" +
                    routeHeaders +
                    toHeaderLine +
                    fromHeaderLine +
                    "Call-ID: $dialogCallId\r\n" +
                    "CSeq: $cseq INVITE\r\n" +
                    "Contact: <sip:$username@$contactHost:$contactPort;transport=udp>\r\n" +
                    authLine +
                    "User-Agent: $SIP_USER_AGENT\r\n" +
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: $sdpBytesLen\r\n\r\n$sdp"
        }

        // Drain stale responses
        while (reInviteResponseChannel.tryReceive().isSuccess) {}

        var currentBranch = generateBranch()
        var currentInviteBytes = buildReInvite(cseqNum, currentBranch).toByteArray(Charsets.ISO_8859_1)
        var authAttempted = false

        pendingReInviteCallId = dialogCallId
        pendingReInviteCSeq = cseqNum

        try {
            activeSocket.send(DatagramPacket(currentInviteBytes, currentInviteBytes.size, targetAddr, targetPort))
            log("Mid-call handover re-INVITE sent (Call-ID=$dialogCallId, CSeq=$cseqNum, via=$viaHost:$viaPort, media=$effectiveLocalIp:$liveRtpPort, dir=$effectiveDirection)")

            val startMs = SystemClock.elapsedRealtime()
            var lastRetransmitMs = startMs
            var retransmitCount = 0

            while (SystemClock.elapsedRealtime() - startMs < 4_000L && currentCallState == CallState.CONNECTED && !isHangingUp) {
                val resp = withTimeoutOrNull(450L) { reInviteResponseChannel.receive() }
                if (resp == null) {
                    val now = SystemClock.elapsedRealtime()
                    if (retransmitCount < 3 && now - lastRetransmitMs >= 700L && !activeSocket.isClosed) {
                        retransmitCount++
                        lastRetransmitMs = now
                        try {
                            activeSocket.send(DatagramPacket(currentInviteBytes, currentInviteBytes.size, targetAddr, targetPort))
                            log("Retransmitting mid-call handover re-INVITE ($retransmitCount/3, CSeq=$cseqNum)...")
                        } catch (_: Exception) {}
                    }
                    continue
                }

                val code = extractStatusCode(resp)
                val (respCSeqNum, _) = parseCSeq(extractHeader(resp, "CSeq"))
                if (respCSeqNum != null && respCSeqNum != cseqNum) continue
                if (code in 100..199) continue

                if ((code == 401 || code == 407) && !authAttempted) {
                    authAttempted = true
                    // ACK the 401/407 challenge
                    val ack401 = "ACK $reqUri SIP/2.0\r\n" +
                            "Via: SIP/2.0/UDP $viaHost:$viaPort;rport;branch=$currentBranch\r\n" +
                            "Max-Forwards: 70\r\n" +
                            routeHeaders +
                            (extractHeader(resp, "To")?.let { "To: $it\r\n" } ?: toHeaderLine) +
                            fromHeaderLine +
                            "Call-ID: $dialogCallId\r\n" +
                            "CSeq: $cseqNum ACK\r\n" +
                            "Content-Length: 0\r\n\r\n"
                    try {
                        val b = ack401.toByteArray(Charsets.ISO_8859_1)
                        activeSocket.send(DatagramPacket(b, b.size, targetAddr, targetPort))
                    } catch (_: Exception) {}

                    val authHeader = if (code == 407) {
                        extractHeader(resp, "Proxy-Authenticate") ?: extractHeader(resp, "WWW-Authenticate")
                    } else {
                        extractHeader(resp, "WWW-Authenticate") ?: extractHeader(resp, "Proxy-Authenticate")
                    }
                    if (authHeader != null) {
                        val realm = extractAuthParam(authHeader, "realm") ?: "semux.io"
                        val nonce = extractAuthParam(authHeader, "nonce") ?: ""
                        val qop = extractAuthParam(authHeader, "qop")
                        val opaque = extractAuthParam(authHeader, "opaque")
                        val ha1 = md5("$authUser:$realm:$password")
                        val ha2 = md5("INVITE:$reqUri")
                        val cnonce = "%08x".format(Random().nextInt(0x7fffffff))
                        val nc = "00000001"
                        val useQopAuth = hasAuthQop(qop)
                        val digestResp = if (useQopAuth) {
                            md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
                        } else {
                            md5("$ha1:$nonce:$ha2")
                        }
                        val headerName = if (code == 407) "Proxy-Authorization" else "Authorization"
                        val sb = StringBuilder("$headerName: Digest username=\"$authUser\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$reqUri\", response=\"$digestResp\", algorithm=MD5")
                        if (useQopAuth) sb.append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
                        if (opaque != null) sb.append(", opaque=\"$opaque\"")
                        sb.append("\r\n")

                        cseqNum = synchronized(callStateLock) {
                            if (isOutgoing) ++activeCallCSeq else ++incomingLocalCSeq
                        }
                        pendingReInviteCSeq = cseqNum
                        currentBranch = generateBranch()
                        currentInviteBytes = buildReInvite(cseqNum, currentBranch, sb.toString()).toByteArray(Charsets.ISO_8859_1)
                        retransmitCount = 0
                        lastRetransmitMs = SystemClock.elapsedRealtime()
                        try {
                            activeSocket.send(DatagramPacket(currentInviteBytes, currentInviteBytes.size, targetAddr, targetPort))
                            log("Sent authenticated mid-call handover re-INVITE (CSeq=$cseqNum)")
                        } catch (_: Exception) {}
                    }
                    continue
                }

                if (code == 200) {
                    val respTo = extractHeader(resp, "To")?.let { "To: $it\r\n" } ?: toHeaderLine
                    val ackBranch = generateBranch()
                    val ackMsg = "ACK $reqUri SIP/2.0\r\n" +
                            "Via: SIP/2.0/UDP $viaHost:$viaPort;rport;branch=$ackBranch\r\n" +
                            "Max-Forwards: 70\r\n" +
                            routeHeaders +
                            respTo +
                            fromHeaderLine +
                            "Call-ID: $dialogCallId\r\n" +
                            "CSeq: $cseqNum ACK\r\n" +
                            "Contact: <sip:$username@$contactHost:$contactPort;transport=udp>\r\n" +
                            "Content-Length: 0\r\n\r\n"
                    val ackBytes = ackMsg.toByteArray(Charsets.ISO_8859_1)
                    last200OkAckBytes = ackBytes
                    last200OkAckAddress = targetAddr
                    last200OkAckPort = targetPort
                    last200OkAckCSeq = cseqNum
                    for (k in 0..1) {
                        try { activeSocket.send(DatagramPacket(ackBytes, ackBytes.size, targetAddr, targetPort)) } catch (_: Exception) {}
                        if (k < 1) delay(15)
                    }

                    // Update Contact URI if remote updated it
                    extractContactUri(extractHeader(resp, "Contact"))?.let { updatedContact ->
                        if (isOutgoing) activeCallContactUri = updatedContact else incomingContactUri = updatedContact
                    }

                    // Update remote RTP endpoint if server returned new SDP in 200 OK
                    val newRemoteMediaAddr = resolveRemoteMediaAddress(resp, targetAddr)
                    val newRemoteSdpPort = extractSdpAudioPortNullable(resp)
                    if (newRemoteMediaAddr != null && newRemoteSdpPort != null && newRemoteSdpPort > 0) {
                        val negotiatedCodec = extractSdpCodec(resp)
                        try {
                            rtpStreamer?.updateRemoteEndpoint(newRemoteMediaAddr, newRemoteSdpPort, negotiatedCodec)
                        } catch (_: Exception) {}
                    }
                    log("Mid-call handover re-INVITE 200 OK confirmed & ACKed (CSeq=$cseqNum)!")
                    break
                } else {
                    // Non-2xx final response (e.g. 405/488/501): ACK it cleanly and KEEP CALL ALIVE via Symmetric RTP!
                    val ackNon2xx = "ACK $reqUri SIP/2.0\r\n" +
                            "Via: SIP/2.0/UDP $viaHost:$viaPort;rport;branch=$currentBranch\r\n" +
                            "Max-Forwards: 70\r\n" +
                            routeHeaders +
                            (extractHeader(resp, "To")?.let { "To: $it\r\n" } ?: toHeaderLine) +
                            fromHeaderLine +
                            "Call-ID: $dialogCallId\r\n" +
                            "CSeq: $cseqNum ACK\r\n" +
                            "Content-Length: 0\r\n\r\n"
                    try {
                        val b = ackNon2xx.toByteArray(Charsets.ISO_8859_1)
                        activeSocket.send(DatagramPacket(b, b.size, targetAddr, targetPort))
                    } catch (_: Exception) {}
                    log("Mid-call handover re-INVITE returned $code (ACKed); continuing call via Symmetric RTP on new network.")
                    break
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Mid-call handover re-INVITE exception (continuing via Symmetric RTP): ${e.message}")
        } finally {
            if (pendingReInviteCSeq == cseqNum) {
                pendingReInviteCallId = null
                pendingReInviteCSeq = null
            }
        }
        } // end reInviteMutex.withLock
    }

    suspend fun reRegister(): Boolean = withContext(Dispatchers.IO) {
        val account = activeAccount ?: return@withContext false
        log("Re-registering SIP account ${account.username}@${account.server}...")
        register(account, isRenewal = true)
    }

    /**
     * Called by SipWatchdogReceiver every 25-30 seconds to ensure 24/7 background availability.
     * Prevents Android 11 / Infinix XOS Doze freezing and cellular NAT timeout.
     */
    fun onWatchdogPing(context: Context) {
        engineScope.launch {
            onWatchdogPingSuspend(context.applicationContext)
        }
    }

    /**
     * Suspend watchdog entry point awaited by SipWatchdogReceiver BEFORE releasing WakeLock & goAsync()!
     */
    suspend fun onWatchdogPingSuspend(context: Context) = withContext(Dispatchers.IO) {
        val account = activeAccount ?: com.opentalk.android.storage.SecureAccountManager(context).getAccount()
        if (account.username.isBlank() || account.server.isBlank()) return@withContext
        activeAccount = account

        val now = SystemClock.elapsedRealtime()

        // Never disrupt or tear down sockets while a call is active or ringing
        if (isInCall()) {
            val socket = persistentSipSocket
            if (socket != null && !socket.isClosed) {
                try {
                    val targetAddress = resolveServerAddress(account.server)
                    val crlfBytes = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                    socket.send(DatagramPacket(crlfBytes, crlfBytes.size, targetAddress, account.port))
                } catch (_: Exception) {}
            }
            return@withContext
        }

        // 1. Check if registration has been stuck in REGISTERING state for > 16s
        if (currentStatus == RegistrationStatus.REGISTERING) {
            if (registrationAttemptStartTime > 0 && now - registrationAttemptStartTime > 16_000L) {
                log("Watchdog: Registration was stuck in REGISTERING for >16s. Force resetting...")
                registrationGeneration.incrementAndGet()
                activeRegistrationJob?.cancel()
                abortInFlightRegistrationSocket()
                try { persistentSipSocket?.close() } catch (_: Exception) {}
                persistentSipSocket = null
                lastRegisteredLocalIp = null
                lastRegisteredNetworkFingerprint = null
                serverMappedIp = null
                serverMappedPort = null
                currentStatus = RegistrationStatus.FAILED
                notifyStatus(RegistrationStatus.FAILED, "Reconnecting...")
                if (isNetworkConnected(context)) {
                    triggerRegister(account, force = true)
                }
            }
            return@withContext
        }

        // 2. If status is FAILED or UNREGISTERED, trigger auto-reconnection if network is available
        if (currentStatus != RegistrationStatus.REGISTERED) {
            if (isNetworkConnected(context)) {
                log("Watchdog: SIP status is $currentStatus with internet available. Auto-reconnecting...")
                triggerRegister(account)
            }
            return@withContext
        }

        // 3. Status is REGISTERED: verify socket is valid and open
        val socket = persistentSipSocket
        if (socket == null || socket.isClosed) {
            log("Watchdog: Socket is closed or null while REGISTERED. Re-establishing connection...")
            triggerRegister(account)
            return@withContext
        }

        // 3b. Verify local network interface IP or underlying physical transport has not shifted
        // (catches WiFi <-> LTE/Hotspot roaming even when VPN keeps the same tun0 IP!)
        val currentLocalIp = getCurrentTransportLocalIp(context)
        val currentFingerprint = getNetworkFingerprint(context)
        val localIpChanged = lastRegisteredLocalIp != null && currentLocalIp != "127.0.0.1" && currentLocalIp != lastRegisteredLocalIp
        val fingerprintChanged = lastRegisteredNetworkFingerprint != null &&
            isValidNetworkFingerprint(currentFingerprint) &&
            currentFingerprint != lastRegisteredNetworkFingerprint
        if (localIpChanged || fingerprintChanged) {
            log("Watchdog: Network changed (ip=$lastRegisteredLocalIp->$currentLocalIp, sig=$lastRegisteredNetworkFingerprint->$currentFingerprint). Re-registering immediately...")
            onNetworkHandover(context)
            return@withContext
        }

        // 4. Check if registration renewal is due
        val renewalDueMs = (registrationExpiresSeconds * 500L).coerceIn(60_000L, 180_000L)
        if (lastSuccessfulRegistrationTime > 0 && now - lastSuccessfulRegistrationTime >= renewalDueMs) {
            log("Watchdog: Registration renewal due (${(now - lastSuccessfulRegistrationTime) / 1000}s elapsed). Renewing...")
            triggerRegister(account, isRenewal = true)
            return@withContext
        }

        // 5. Send RFC 5626 double CRLF keepalive synchronously inside this suspend call so WakeLock stays held until sent
        try {
            val targetAddress = resolveServerAddress(account.server)
            val crlfBytes = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            socket.send(DatagramPacket(crlfBytes, crlfBytes.size, targetAddress, account.port))
        } catch (e: Exception) {
            log("Watchdog keepalive warning: ${e.message}")
        }
    }

    fun log(msg: String) {
        val cleanMsg = msg
            .replace(Regex("password[:=]\\s*\\S+", RegexOption.IGNORE_CASE), "password=***")
            .replace(Regex("response=\"[^\"]+\"", RegexOption.IGNORE_CASE), "response=\"***\"")
            .replace(Regex("nonce=\"[^\"]+\"", RegexOption.IGNORE_CASE), "nonce=\"***\"")
        if (com.opentalk.android.BuildConfig.DEBUG) {
            Log.i(TAG, cleanMsg)
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $cleanMsg"
        synchronized(diagnosticLogs) {
            diagnosticLogs.add(0, entry)
            if (diagnosticLogs.size > 60) {
                diagnosticLogs.removeAt(diagnosticLogs.size - 1)
            }
        }
    }

    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.ISO_8859_1))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun cleanHostAddress(addr: InetAddress?): String {
        return addr?.hostAddress?.substringBefore('%')?.trim() ?: ""
    }

    private fun isIpv6Target(addr: InetAddress?): Boolean {
        return addr is java.net.Inet6Address
    }

    private fun formatHostForUri(addr: InetAddress): String {
        val h = cleanHostAddress(addr)
        return if (addr is java.net.Inet6Address && h.contains(":")) "[$h]" else h
    }

    private fun isGlobalUnicastIpv6(addr: InetAddress?): Boolean {
        if (addr !is java.net.Inet6Address) return false
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isMulticastAddress || addr.isAnyLocalAddress) return false
        val bytes = addr.address
        if (bytes.size != 16) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        // Reject ULA (fc00::/7)
        if ((b0 and 0xFE) == 0xFC) return false
        // Reject RFC 6052 well-known NAT64 prefix (0064:ff9b::/96)
        if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B) return false
        // Accept Global Unicast IPv6 (2000::/3, e.g. 2400::, 2600::, 2a00::, 2001::)
        return (b0 and 0xE0) == 0x20
    }

    fun getLocalIpv6Address(targetAddress: InetAddress? = null, context: Context? = appContext): String? {
        val ctx = context ?: appContext

        // SECURITY: When a VPN is active, NEVER inspect or leak the underlying SIM/Wi-Fi Global IPv6!
        // Only return a Global Unicast IPv6 if the VPN tunnel itself provides one; otherwise return null
        // so IPv4-only VPNs never bypass the VPN over carrier IPv6.
        if (isVpnActive(ctx)) {
            val vpnLp = getActiveVpnLinkProperties(ctx)
            val vpnV6 = vpnLp?.linkAddresses?.firstOrNull { isGlobalUnicastIpv6(it.address) }?.address
            val vpnHost = cleanHostAddress(vpnV6)
            if (vpnHost.isNotBlank()) return vpnHost
            return getVpnInterfaceAddress(wantIpv6 = true)
        }

        // 1. Query OS kernel IPv6 routing table (returns EXACT RFC 6724 source IPv6 placed in IPv6 headers!)
        try {
            val probeTarget = if (targetAddress is java.net.Inet6Address) {
                targetAddress
            } else {
                InetAddress.getByName("2001:4860:4860::8888")
            }
            DatagramSocket().use { dummy ->
                bindSocketToActiveNetwork(dummy, ctx)
                dummy.connect(probeTarget, 5060)
                val local = dummy.localAddress
                if (isGlobalUnicastIpv6(local)) {
                    val host = cleanHostAddress(local)
                    if (host.isNotBlank()) return host
                }
            }
        } catch (_: Exception) {}

        // 2. Active network LinkProperties global unicast IPv6
        if (ctx != null) {
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNet = cm?.activeNetwork
                if (activeNet != null) {
                    val lp = cm.getLinkProperties(activeNet)
                    val v6 = lp?.linkAddresses?.firstOrNull { isGlobalUnicastIpv6(it.address) }?.address
                    val host = cleanHostAddress(v6)
                    if (host.isNotBlank()) return host
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback: inspect NetworkInterfaces preferring active transport (wifi vs cellular)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isWifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val sorted = interfaces.sortedByDescending { iface ->
                val name = iface.name.lowercase()
                when {
                    isWifi && name.startsWith("wlan") -> 5
                    !isWifi && (name.startsWith("rmnet") || name.startsWith("ccmni")) -> 5
                    name.startsWith("wlan") -> 3
                    name.startsWith("rmnet") || name.startsWith("ccmni") -> 3
                    else -> 1
                }
            }
            for (iface in sorted) {
                if (iface.isLoopback || !iface.isUp || isHotspotOrTetherApInterface(iface.name) || isVpnTunnelInterfaceName(iface.name)) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (isGlobalUnicastIpv6(addr)) {
                        val host = cleanHostAddress(addr)
                        if (host.isNotBlank()) return host
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun getLocalIpv4Address(context: Context? = appContext): String {
        val ctx = context ?: appContext

        // SECURITY: When a VPN is active, strictly use the VPN tunnel IPv4 (tun0/wg0) so the phone's
        // real Wi-Fi LAN IP or carrier CGNAT IP is NEVER exposed inside SIP Via/Contact/SDP headers!
        if (isVpnActive(ctx)) {
            val vpnLp = getActiveVpnLinkProperties(ctx)
            val vpnV4 = vpnLp?.linkAddresses?.firstOrNull {
                it.address is java.net.Inet4Address && !it.address.isLoopbackAddress
            }?.address?.hostAddress
            if (!vpnV4.isNullOrBlank() && vpnV4 != "0.0.0.0" && vpnV4 != "127.0.0.1") {
                return vpnV4
            }
            val tunV4 = getVpnInterfaceAddress(wantIpv6 = false)
            if (!tunV4.isNullOrBlank()) return tunV4
            return "127.0.0.1"
        }

        // 1. Try ConnectivityManager active network LinkProperties
        if (ctx != null) {
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNet = cm?.activeNetwork
                if (activeNet != null) {
                    val lp = cm.getLinkProperties(activeNet)
                    val directIp = lp?.linkAddresses?.firstOrNull {
                        it.address is java.net.Inet4Address && !it.address.isLoopbackAddress
                    }?.address?.hostAddress
                    if (!directIp.isNullOrBlank() && directIp != "0.0.0.0" && directIp != "127.0.0.1") {
                        return directIp
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Query OS kernel IPv4 routing table using .use{} so socket NEVER leaks on exception
        try {
            DatagramSocket().use { dummy ->
                bindSocketToActiveNetwork(dummy, ctx)
                val target = (activeTargetAddress as? java.net.Inet4Address) ?: InetAddress.getByName("103.170.231.10")
                dummy.connect(target, 5060)
                val ip = dummy.localAddress?.hostAddress
                if (!ip.isNullOrBlank() && ip != "0.0.0.0" && ip != "127.0.0.1" && !ip.contains(":")) {
                    return ip
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback: inspect network interfaces preferring active transport (cellular vs wifi)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "127.0.0.1"
            val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isWifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            val sorted = interfaces.sortedByDescending { iface ->
                val name = iface.name.lowercase()
                when {
                    isWifi && name.startsWith("wlan") -> 5
                    !isWifi && (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("clat") || name.startsWith("v4-")) -> 5
                    name.startsWith("wlan") -> 3
                    name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("clat") || name.startsWith("v4-") -> 3
                    else -> 1
                }
            }
            for (iface in sorted) {
                if (iface.isLoopback || !iface.isUp || isHotspotOrTetherApInterface(iface.name) || isVpnTunnelInterfaceName(iface.name)) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && !addr.isLinkLocalAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun getLocalIpAddress(context: Context? = appContext): String {
        val v4 = getLocalIpv4Address(context)
        if (v4 != "127.0.0.1") return v4
        return getLocalIpv6Address(null, context) ?: "127.0.0.1"
    }

    private fun getLocalAddressForTarget(targetAddress: InetAddress?, context: Context? = appContext): String {
        return if (isIpv6Target(targetAddress)) {
            getLocalIpv6Address(targetAddress, context) ?: getLocalIpAddress(context)
        } else {
            getLocalIpv4Address(context)
        }
    }

    private fun getCurrentTransportLocalIp(context: Context? = appContext): String {
        return getLocalAddressForTarget(lastResolvedServerAddress, context)
    }

    /**
     * Internal-only signature of the active non-VPN physical transport (Wi-Fi / Cellular / Hotspot / Ethernet),
     * including Android's unique NetId so switching Wi-Fi routers/Hotspots with identical LAN IPs (192.168.1.x)
     * or switching Wi-Fi <-> Mobile Data while a VPN keeps the same tun0 IP (172.16.0.2) is ALWAYS detected.
     * SECURITY: Strictly internal change-detection key — NEVER placed in SIP Via/Contact/SDP headers!
     */
    fun getActivePhysicalNetworkSignature(context: Context? = appContext): String {
        val ctx = context ?: appContext ?: return "none"
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "none"
        return try {
            val activeNet = cm.activeNetwork
            val activeCaps = activeNet?.let { cm.getNetworkCapabilities(it) }
            val chosenNet: android.net.Network? = if (
                activeNet != null &&
                activeCaps != null &&
                !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                activeNet
            } else {
                @Suppress("DEPRECATION")
                val candidates = cm.allNetworks.mapNotNull { net ->
                    val c = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                    if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                    ) return@mapNotNull null
                    val validatedScore = if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0
                    val transportScore = when {
                        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 30
                        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 20
                        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 10
                        else -> 5
                    }
                    Pair(validatedScore + transportScore, net)
                }
                candidates.maxByOrNull { it.first }?.second
            }

            if (chosenNet == null) return "none"
            val caps = cm.getNetworkCapabilities(chosenNet)
            val lp = cm.getLinkProperties(chosenNet)
            val transport = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cell"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "eth"
                else -> "other"
            }
            val ifName = lp?.interfaceName?.takeIf { it.isNotBlank() } ?: "if"
            val v4 = lp?.linkAddresses?.firstOrNull {
                it.address is java.net.Inet4Address && !it.address.isLoopbackAddress
            }?.address?.hostAddress ?: "nov4"
            // When VPN is active, traffic routes through the VPN tunnel (tun0), so routine Wi-Fi IPv6 RA
            // fluctuations on the same physical NetId/IPv4 must NOT trigger spurious socket teardowns.
            if (isVpnActive(ctx)) {
                return "phy:${chosenNet}:$transport:$ifName:$v4"
            }
            val v6Addr = lp?.linkAddresses?.firstOrNull { isGlobalUnicastIpv6(it.address) }?.address
            val v6 = if (v6Addr is java.net.Inet6Address && v6Addr.address.size == 16) {
                val b = v6Addr.address
                "%02x%02x:%02x%02x:%02x%02x:%02x%02x".format(
                    b[0].toInt() and 0xFF, b[1].toInt() and 0xFF,
                    b[2].toInt() and 0xFF, b[3].toInt() and 0xFF,
                    b[4].toInt() and 0xFF, b[5].toInt() and 0xFF,
                    b[6].toInt() and 0xFF, b[7].toInt() and 0xFF
                )
            } else "nov6"
            "phy:${chosenNet}:$transport:$ifName:$v4:$v6"
        } catch (_: Exception) {
            "none"
        }
    }

    /**
     * Stable network interface signature combining VPN state, IPv4/IPv6 /64 prefix, and underlying
     * physical network NetId/transport so SipVoipService & SipEngine detect:
     * - Wi-Fi <-> Mobile Data <-> Hotspot handovers (even when two routers share 192.168.1.x!)
     * - Underlying Wi-Fi <-> Mobile Data <-> Hotspot switches WHILE VPN stays connected with the same tun0 IP!
     * - IPv6 Router Advertisement upgrades without oscillating on temporary privacy IPv6 suffixes.
     */
    fun getNetworkFingerprint(
        context: Context? = appContext,
        linkProperties: android.net.LinkProperties? = null
    ): String {
        val vpnActive = isVpnActive(context)
        val effectiveLp = if (vpnActive) getActiveVpnLinkProperties(context) else linkProperties
        val phySig = getActivePhysicalNetworkSignature(context)

        val v4 = effectiveLp?.linkAddresses?.firstOrNull {
            it.address is java.net.Inet4Address && !it.address.isLoopbackAddress
        }?.address?.hostAddress?.takeIf { it.isNotBlank() && it != "0.0.0.0" && it != "127.0.0.1" }
            ?: getLocalIpv4Address(context)

        val v6Addr = effectiveLp?.linkAddresses?.firstOrNull {
            isGlobalUnicastIpv6(it.address)
        }?.address ?: try {
            getLocalIpv6Address(null, context)?.let { InetAddress.getByName(it) }
        } catch (_: Exception) { null }

        val v6Prefix = if (v6Addr is java.net.Inet6Address) {
            val b = v6Addr.address
            if (b.size == 16) {
                "%02x%02x:%02x%02x:%02x%02x:%02x%02x".format(
                    b[0].toInt() and 0xFF, b[1].toInt() and 0xFF,
                    b[2].toInt() and 0xFF, b[3].toInt() and 0xFF,
                    b[4].toInt() and 0xFF, b[5].toInt() and 0xFF,
                    b[6].toInt() and 0xFF, b[7].toInt() and 0xFF
                )
            } else "none"
        } else {
            "none"
        }
        return if (vpnActive) "vpn|$v4|$v6Prefix|$phySig" else "$v4|$v6Prefix|$phySig"
    }

    fun isPrivateIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return true
        val clean = ip.substringBefore('%').trim().lowercase()
        if (clean.contains(":")) {
            return clean == "::" || clean == "::1" || clean.startsWith("fe80:") ||
                clean.startsWith("fc") || clean.startsWith("fd")
        }
        if (clean == "0.0.0.0" || clean == "127.0.0.1") return true
        if (clean.startsWith("10.") || clean.startsWith("192.168.") || clean.startsWith("169.254.")) return true
        val parts = clean.split(".")
        if (parts.size == 4) {
            val first = parts[0].toIntOrNull() ?: 0
            val second = parts[1].toIntOrNull() ?: 0
            if (first == 172 && second in 16..31) return true
        }
        return false
    }

    private fun getMediaIpv4Address(fallbackLocalIp: String? = null): String {
        val srvIp = serverMappedIp
        if (!srvIp.isNullOrBlank() && !srvIp.contains(":") && !isPrivateIp(srvIp)) return srvIp
        val stunIp = publicIp
        if (!stunIp.isNullOrBlank() && !stunIp.contains(":") && !isPrivateIp(stunIp)) return stunIp
        if (!fallbackLocalIp.isNullOrBlank() && !fallbackLocalIp.contains(":") && fallbackLocalIp != "127.0.0.1") {
            return fallbackLocalIp
        }
        val cachedReg = lastRegisteredLocalIp
        if (!cachedReg.isNullOrBlank() && !cachedReg.contains(":") && cachedReg != "127.0.0.1") {
            return cachedReg
        }
        return getLocalIpv4Address()
    }

    fun getMediaIpAddress(): String {
        val target = activeTargetAddress ?: incomingRemoteAddress ?: lastResolvedServerAddress
        return if (isIpv6Target(target)) {
            getLocalIpv6Address(target) ?: getMediaIpv4Address()
        } else {
            getMediaIpv4Address()
        }
    }

    /**
     * Returns the host:port to advertise in SIP Contact & Via headers.
     * STRICT L3 FAMILY INVARIANT (prevents Linux nf_conntrack_sip NF_DROP / EPERM on all Android kernels!):
     * - When signaling over Native IPv6 (AF_INET6 / NAT64): ALWAYS uses [<globalIpv6>]:<localPort> (RFC 5118).
     * - When signaling over IPv4 (AF_INET): ALWAYS uses IPv4 (preferring RFC 3581 serverMappedIp or STUN publicIp).
     */
    private fun getContactEndpoint(
        localIp: String,
        localPort: Int,
        targetAddress: InetAddress? = lastResolvedServerAddress
    ): Pair<String, Int> {
        if (isIpv6Target(targetAddress)) {
            val v6 = if (localIp.contains(":")) {
                localIp.substringBefore('%').trim()
            } else {
                getLocalIpv6Address(targetAddress) ?: localIp
            }
            return Pair("[$v6]", localPort)
        }
        val mappedIp = serverMappedIp
        val mappedPort = serverMappedPort
        if (!mappedIp.isNullOrBlank() && !mappedIp.contains(":") && !isPrivateIp(mappedIp) && mappedPort != null && mappedPort > 0) {
            return Pair(mappedIp, mappedPort)
        }
        val stunIp = publicIp
        val stunPort = publicPort
        if (!stunIp.isNullOrBlank() && !stunIp.contains(":") && !isPrivateIp(stunIp)) {
            val portToUse = if (stunPort != null && stunPort > 0 && stunMappedSocketLocalPort == localPort) {
                stunPort
            } else {
                localPort
            }
            return Pair(stunIp, portToUse)
        }
        val safeV4 = if (localIp.contains(":")) getLocalIpv4Address() else localIp
        return Pair(safeV4, localPort)
    }

    private fun getViaEndpoint(
        localIp: String,
        localPort: Int,
        targetAddress: InetAddress? = lastResolvedServerAddress
    ): Pair<String, Int> {
        return getContactEndpoint(localIp, localPort, targetAddress)
    }

    private fun buildAudioSdp(
        username: String,
        sessionVer: Int,
        sessionName: String,
        targetAddress: InetAddress,
        localIp: String,
        localRtpPort: Int,
        rtpStun: Pair<String, Int>?,
        codecBlock: String,
        direction: String = "sendrecv"
    ): String {
        return if (isIpv6Target(targetAddress)) {
            // Native IPv6 / NAT64 transport (AF_INET6):
            // SDP strictly uses IN IP6 <globalIpv6> matching our AF_INET6 UDP socket!
            // Both Android kernel nf_conntrack_sip and carrier NAT64 SIP-ALG accept IN IP6 cleanly in both directions.
            val v6 = if (localIp.contains(":")) {
                localIp.substringBefore('%').trim()
            } else {
                getLocalIpv6Address(targetAddress) ?: localIp
            }
            "v=0\r\n" +
                    "o=$username 100 $sessionVer IN IP6 $v6\r\n" +
                    "s=$sessionName\r\n" +
                    "c=IN IP6 $v6\r\n" +
                    "t=0 0\r\n" +
                    "m=audio $localRtpPort RTP/AVP $codecBlock" +
                    "a=$direction\r\n"
        } else {
            // Standard IPv4 transport (AF_INET):
            // SDP strictly uses IN IP4 <publicOrLocalIpv4> matching our AF_INET UDP socket!
            val mediaIp = rtpStun?.first?.takeIf { !it.contains(":") } ?: getMediaIpv4Address(localIp)
            val mediaPort = rtpStun?.second ?: localRtpPort
            "v=0\r\n" +
                    "o=$username 100 $sessionVer IN IP4 $mediaIp\r\n" +
                    "s=$sessionName\r\n" +
                    "c=IN IP4 $mediaIp\r\n" +
                    "t=0 0\r\n" +
                    "m=audio $mediaPort RTP/AVP $codecBlock" +
                    "a=$direction\r\n"
        }
    }

    private fun parseViaReceivedAndRport(viaHeader: String, targetAddress: InetAddress? = lastResolvedServerAddress) {
        if (viaHeader.isBlank()) return
        // On Native IPv6 / NAT64, our global IPv6 address + localPort is already end-to-end routable;
        // never pollute IPv4 serverMappedIp/publicIp state from IPv6 Via headers.
        if (isIpv6Target(targetAddress)) return
        val received = Regex("""received=([0-9\.]+)""", RegexOption.IGNORE_CASE).find(viaHeader)?.groupValues?.get(1)
        val rport = Regex("""rport=(\d+)""", RegexOption.IGNORE_CASE).find(viaHeader)?.groupValues?.get(1)?.toIntOrNull()
        if (!received.isNullOrBlank() && !isPrivateIp(received)) {
            publicIp = received
            serverMappedIp = received
        }
        if (rport != null && rport > 0) {
            publicPort = rport
            if (!received.isNullOrBlank() && !isPrivateIp(received)) {
                serverMappedPort = rport
            }
        }
    }

    private fun extractContactUri(contactHeader: String?): String? {
        if (contactHeader.isNullOrBlank()) return null
        val bracketed = Regex("""<([^>]+)>""").find(contactHeader)?.groupValues?.get(1)
        if (!bracketed.isNullOrBlank()) {
            return bracketed.trim()
        }
        val bare = contactHeader.trim().substringBefore(";").trim().removePrefix("<").removeSuffix(">")
        return if (bare.startsWith("sip:", ignoreCase = true) || bare.startsWith("sips:", ignoreCase = true)) bare else null
    }

    private fun extractRouteSetFromResponse(sipMsg: String): List<String> {
        val unfolded = sipMsg.replace(HEADER_UNFOLD_REGEX, " ")
        val routes = mutableListOf<String>()
        for (line in unfolded.lines()) {
            if (line.startsWith("Record-Route:", ignoreCase = true)) {
                val value = line.substring("Record-Route:".length).trim()
                if (value.isNotEmpty()) {
                    // Split comma-separated Record-Route entries (each <sip:...>;params)
                    val entries = Regex("""<[^>]+>(?:[^,]*)""").findAll(value).map { it.value.trim() }.toList()
                    if (entries.isNotEmpty()) {
                        routes.addAll(entries)
                    } else {
                        routes.add(value)
                    }
                }
            }
        }
        // RFC 3261 Section 12.1.2: UAC reverses Record-Route list for its dialog route set
        return routes.reversed()
    }

    private fun formatRouteHeaders(routeSet: List<String>): String {
        if (routeSet.isEmpty()) return ""
        return routeSet.joinToString("") { "Route: $it\r\n" }
    }

    private fun parseCSeq(cseqHeader: String?): Pair<Int?, String> {
        if (cseqHeader.isNullOrBlank()) return Pair(null, "")
        val parts = cseqHeader.trim().split(WHITESPACE_REGEX)
        val num = parts.getOrNull(0)?.toIntOrNull()
        val method = parts.getOrNull(1)?.uppercase() ?: ""
        return Pair(num, method)
    }

    fun parseStunResponse(data: ByteArray, length: Int): Pair<String, Int>? {
        if (length < 20) return null
        val msgType = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (msgType != 0x0101) return null // 0x0101 = Binding Success Response

        var pos = 20
        while (pos + 4 <= length) {
            val attrType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val attrLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (attrType == 0x0020 && attrLen >= 8 && pos + 8 <= length) { // XOR-MAPPED-ADDRESS
                val family = data[pos + 1].toInt() and 0xFF
                val xport = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                val port = xport xor 0x2112
                if (family == 0x01) { // IPv4
                    val b1 = (data[pos + 4].toInt() and 0xFF) xor 0x21
                    val b2 = (data[pos + 5].toInt() and 0xFF) xor 0x12
                    val b3 = (data[pos + 6].toInt() and 0xFF) xor 0xA4
                    val b4 = (data[pos + 7].toInt() and 0xFF) xor 0x42
                    return Pair("$b1.$b2.$b3.$b4", port)
                } else if (family == 0x02 && attrLen >= 20 && pos + 20 <= length) { // IPv6 / NAT64
                    val rawIp = ByteArray(16)
                    for (i in 0 until 4) {
                        rawIp[i] = (data[pos + 4 + i].toInt() xor data[4 + i].toInt()).toByte()
                    }
                    for (i in 4 until 16) {
                        rawIp[i] = (data[pos + 4 + i].toInt() xor data[4 + i].toInt()).toByte()
                    }
                    val isNat64 = (rawIp[0].toInt() and 0xFF == 0x00) && (rawIp[1].toInt() and 0xFF == 0x64) &&
                                  (rawIp[2].toInt() and 0xFF == 0xFF) && (rawIp[3].toInt() and 0xFF == 0x9B) &&
                                  rawIp.sliceArray(4 until 12).all { it == 0.toByte() }
                    val isIpv4Mapped = rawIp.sliceArray(0 until 10).all { it == 0.toByte() } &&
                                      (rawIp[10].toInt() and 0xFF == 0xFF) && (rawIp[11].toInt() and 0xFF == 0xFF)

                    if (isNat64 || isIpv4Mapped) {
                        val b1 = rawIp[12].toInt() and 0xFF
                        val b2 = rawIp[13].toInt() and 0xFF
                        val b3 = rawIp[14].toInt() and 0xFF
                        val b4 = rawIp[15].toInt() and 0xFF
                        val mappedIpv4 = "$b1.$b2.$b3.$b4"
                        log("Extracted IPv4 from STUN NAT64/IPv6 mapped address: $mappedIpv4:$port")
                        return Pair(mappedIpv4, port)
                    }
                }
            } else if (attrType == 0x0001 && attrLen >= 8 && pos + 8 <= length) { // MAPPED-ADDRESS (RFC 3489)
                val family = data[pos + 1].toInt() and 0xFF
                if (family == 0x01) {
                    val port = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                    val b1 = data[pos + 4].toInt() and 0xFF
                    val b2 = data[pos + 5].toInt() and 0xFF
                    val b3 = data[pos + 6].toInt() and 0xFF
                    val b4 = data[pos + 7].toInt() and 0xFF
                    return Pair("$b1.$b2.$b3.$b4", port)
                }
            }
            pos += (attrLen + 3) and 3.inv()
        }
        return null
    }

    fun resolveMappedEndpointViaStun(
        socket: DatagramSocket,
        updateSipGlobals: Boolean = false,
        maxServers: Int = 3,
        timeoutMs: Int = 650
    ): Pair<String, Int>? {
        val req = ByteArray(20)
        req[0] = 0x00; req[1] = 0x01 // STUN Binding Request
        req[2] = 0x00; req[3] = 0x00 // Length: 0
        req[4] = 0x21; req[5] = 0x12; req[6] = 0xA4.toByte(); req[7] = 0x42 // Magic Cookie (0x2112A442)
        val rng = Random()
        for (i in 8 until 20) req[i] = rng.nextInt(256).toByte()

        val stunServers = listOf(
            "74.125.250.129" to 19302,  // stun.l.google.com IPv4 literal
            "162.159.192.1" to 3478,    // stun.cloudflare.com IPv4 literal
            "142.250.150.127" to 19302, // stun1.l.google.com IPv4 literal
            "172.253.115.127" to 19302, // stun2.l.google.com IPv4 literal
            "217.10.68.152" to 3478,    // stun.sipgate.net IPv4 literal
            "stun.l.google.com" to 19302,
            "stun.cloudflare.com" to 3478
        ).take(maxServers.coerceIn(1, 7))

        val savedTimeout = try { socket.soTimeout } catch (_: Exception) { 1000 }
        val buf = ByteArray(512)

        for ((host, port) in stunServers) {
            try {
                val server = try {
                    val all = InetAddress.getAllByName(host)
                    all.firstOrNull { it is java.net.Inet4Address } ?: all.first()
                } catch (_: Exception) {
                    InetAddress.getByName(host)
                }
                socket.send(DatagramPacket(req, req.size, server, port))
                socket.soTimeout = timeoutMs

                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)

                val parsed = parseStunResponse(buf, resp.length)
                if (parsed != null && !isPrivateIp(parsed.first)) {
                    log("STUN mapped endpoint discovered (localPort=${socket.localPort}): ${parsed.first}:${parsed.second} (via $host:$port)")
                    if (updateSipGlobals) {
                        publicIp = parsed.first
                        publicPort = parsed.second
                        stunMappedSocketLocalPort = socket.localPort
                    } else if (publicIp.isNullOrBlank()) {
                        publicIp = parsed.first
                    }
                    try { socket.soTimeout = savedTimeout } catch (_: Exception) {}
                    return parsed
                }
            } catch (e: Exception) {
                log("STUN probe to $host:$port skipped: ${e.message}")
            }
        }
        try { socket.soTimeout = savedTimeout } catch (_: Exception) {}
        return null
    }

    fun resolvePublicIpViaStun(): String? {
        try {
            val sock = DatagramSocket()
            try {
                val ep = resolveMappedEndpointViaStun(sock)
                return ep?.first
            } finally {
                try { sock.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            return null
        }
    }

    // =========================================================================
    // SIP REGISTRATION & PERSISTENT LISTENER (RFC 3261 & RFC 3581 Compliant)
    // =========================================================================

    private suspend fun sendWithRetransmit(
        socket: DatagramSocket,
        data: ByteArray,
        targetAddress: InetAddress,
        targetPort: Int,
        totalTimeoutMs: Long = 10000L,
        retransmitDelays: LongArray = longArrayOf(1200L, 2200L, 3500L),
        expectedMethod: String? = null,
        expectedCSeqNumber: Int? = null
    ): String {
        val sendPacket = DatagramPacket(data, data.size, targetAddress, targetPort)
        socket.send(sendPacket)

        val startTime = SystemClock.elapsedRealtime()
        var nextRetransmitIdx = 0
        var nextRetransmitTime = startTime + retransmitDelays[0]

        val recvBuf = ByteArray(8192)
        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
        socket.soTimeout = 350

        while (SystemClock.elapsedRealtime() - startTime < totalTimeoutMs) {
            currentCoroutineContext().ensureActive()
            try {
                recvPacket.length = recvBuf.size
                socket.receive(recvPacket)
                val resp = String(recvPacket.data, 0, recvPacket.length, Charsets.ISO_8859_1)
                val trimmed = resp.trim()
                if (trimmed.isNotEmpty()) {
                    val respCode = extractStatusCode(resp)
                    if (respCode in 100..199) {
                        log("Received provisional $respCode for ${expectedMethod ?: "SIP"}; awaiting final response...")
                        continue
                    }
                    if (expectedMethod != null) {
                        val cseq = extractHeader(resp, "CSeq")
                        if (expectedCSeqNumber != null && cseq != null) {
                            val seqNum = cseq.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                            if (seqNum != null && seqNum != expectedCSeqNumber) {
                                log("Discarding delayed/stale UDP packet for CSeq $seqNum (waiting for $expectedCSeqNumber)...")
                                continue
                            }
                        }
                        if (cseq != null && cseq.contains(expectedMethod, ignoreCase = true)) {
                            return resp
                        }
                        if (resp.startsWith("SIP/2.0") && (cseq == null || cseq.contains(expectedMethod, ignoreCase = true))) {
                            return resp
                        }
                    } else {
                        return resp
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Short read timeout (350ms) to allow checking cancellation & retransmission timer
            }

            currentCoroutineContext().ensureActive()
            val now = SystemClock.elapsedRealtime()
            if (nextRetransmitIdx < retransmitDelays.size && now >= nextRetransmitTime) {
                try {
                    socket.send(sendPacket)
                    log("UDP retransmission sent (attempt ${nextRetransmitIdx + 2})...")
                } catch (_: Exception) {}
                nextRetransmitIdx++
                if (nextRetransmitIdx < retransmitDelays.size) {
                    nextRetransmitTime = now + retransmitDelays[nextRetransmitIdx]
                }
            }
        }
        throw SocketTimeoutException("Registration timed out after ${totalTimeoutMs}ms with ${nextRetransmitIdx + 1} UDP attempts")
    }

    private suspend fun sendRegisterViaChannel(
        socket: DatagramSocket,
        data: ByteArray,
        targetAddress: InetAddress,
        targetPort: Int,
        expectedCallId: String,
        expectedCSeqNumber: Int,
        totalTimeoutMs: Long = 10000L,
        retransmitDelays: LongArray = longArrayOf(1200L, 2200L, 3500L)
    ): String {
        while (registerResponseChannel.tryReceive().isSuccess) {}

        val sendPacket = DatagramPacket(data, data.size, targetAddress, targetPort)
        socket.send(sendPacket)

        val startTime = SystemClock.elapsedRealtime()
        var nextRetransmitIdx = 0
        var nextRetransmitTime = startTime + retransmitDelays[0]

        while (SystemClock.elapsedRealtime() - startTime < totalTimeoutMs) {
            currentCoroutineContext().ensureActive()
            val resp = withTimeoutOrNull(350L) {
                registerResponseChannel.receive()
            }
            if (resp != null) {
                val respCode = extractStatusCode(resp)
                if (respCode in 100..199) {
                    continue
                }
                val respCallId = extractHeader(resp, "Call-ID")?.trim()
                val (seqNum, method) = parseCSeq(extractHeader(resp, "CSeq"))
                if (method == "REGISTER" && seqNum == expectedCSeqNumber && (respCallId == null || respCallId == expectedCallId)) {
                    return resp
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (nextRetransmitIdx < retransmitDelays.size && now >= nextRetransmitTime) {
                try {
                    socket.send(sendPacket)
                    log("UDP REGISTER renewal retransmission sent (attempt ${nextRetransmitIdx + 2})...")
                } catch (_: Exception) {}
                nextRetransmitIdx++
                if (nextRetransmitIdx < retransmitDelays.size) {
                    nextRetransmitTime = now + retransmitDelays[nextRetransmitIdx]
                }
            }
        }
        throw SocketTimeoutException("Registration renewal timed out after ${totalTimeoutMs}ms with ${nextRetransmitIdx + 1} UDP attempts")
    }

    suspend fun register(account: SipAccount, isRenewal: Boolean = false, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val pm = appContext?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val regWakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "OpenTalk:RegistrationWakeLock")?.apply {
            setReferenceCounted(false)
            acquire(15_000)
        }

        if (!isNetworkConnected(appContext)) {
            log("Cannot register: Device is OFFLINE.")
            if (!isRenewal || currentStatus != RegistrationStatus.REGISTERED) {
                currentStatus = RegistrationStatus.FAILED
                notifyStatus(RegistrationStatus.FAILED, "No Internet")
            }
            try { if (regWakeLock?.isHeld == true) regWakeLock.release() } catch (_: Exception) {}
            return@withContext false
        }

        if (!force && (isInCall() || currentCallState == CallState.RINGING)) {
            if (persistentSipSocket != null && persistentSipSocket?.isClosed == false) {
                log("Cannot register: Call is active or ringing. Preserving live SIP socket.")
                try { if (regWakeLock?.isHeld == true) regWakeLock.release() } catch (_: Exception) {}
                return@withContext true
            }
        }

        val lockOwner = Any()
        val acquired = if (force) {
            // Serialize cleanly with any in-flight registration instead of cross-coroutine unlocking!
            withTimeoutOrNull(2_000L) {
                registrationMutex.lock(lockOwner)
                true
            } ?: false
        } else {
            registrationMutex.tryLock(lockOwner)
        }
        if (!acquired) {
            log("Registration already in progress. Ignoring duplicate trigger.")
            try { if (regWakeLock?.isHeld == true) regWakeLock.release() } catch (_: Exception) {}
            return@withContext false
        }

        val myRegGen = registrationGeneration.incrementAndGet()
        registrationAttemptStartTime = SystemClock.elapsedRealtime()
        activeAccount = account

        val isBackgroundRenewal = (isRenewal && currentStatus == RegistrationStatus.REGISTERED)
        if (!isBackgroundRenewal) {
            currentStatus = RegistrationStatus.REGISTERING
            log("Initiating registration for ${account.username}@${account.server}...")
            notifyStatus(RegistrationStatus.REGISTERING, "Connecting...")
        } else {
            log("Initiating zero-downtime background renewal for ${account.username}@${account.server} (status remains REGISTERED)...")
        }

        val oldPersistentSocket = persistentSipSocket
        var newSocket: DatagramSocket? = null

        try {
            val serverHost = account.server.trim()
            var targetAddress = resolveServerAddress(serverHost, forceRefresh = (!isBackgroundRenewal || force))
            log("Resolved $serverHost -> ${cleanHostAddress(targetAddress)} (ipv6=${isIpv6Target(targetAddress)})")

            // Dual-stack registration with automatic IPv6 -> IPv4 failover if a Wi-Fi router advertises
            // broken IPv6/DNS64 that drops UDP 5060.
            var useForceIpv4 = false
            for (transportAttempt in 0..1) {
                if (useForceIpv4) {
                    targetAddress = resolveServerAddress(serverHost, forceRefresh = true, forceIpv4 = true)
                    log("IPv4 failover resolved $serverHost -> ${cleanHostAddress(targetAddress)}")
                }
                val isV6 = isIpv6Target(targetAddress)
                val localIp = getLocalAddressForTarget(targetAddress)
                val currentFingerprint = getNetworkFingerprint(appContext)
                val fingerprintUnchanged = lastRegisteredNetworkFingerprint != null &&
                        lastRegisteredNetworkFingerprint == currentFingerprint

                val reuseExistingSocket = !force && !useForceIpv4 &&
                        oldPersistentSocket != null &&
                        !oldPersistentSocket.isClosed &&
                        sipListenerJob?.isActive == true &&
                        lastRegisteredLocalIp != null &&
                        lastRegisteredLocalIp == localIp &&
                        fingerprintUnchanged

                val socket: DatagramSocket = if (reuseExistingSocket) {
                    oldPersistentSocket!!
                } else {
                    serverMappedIp = null
                    serverMappedPort = null
                    publicIp = null
                    publicPort = null
                    stunMappedSocketLocalPort = -1
                    regCallId = null
                    regCSeq = 0
                    DatagramSocket().also {
                        bindSocketToActiveNetwork(it)
                        newSocket = it
                        inFlightRegisterSocket = it
                        // On IPv4 networks, discover public IPv4 & NAT port via STUN before sending REGISTER.
                        // Capped to 2 servers x 450ms (<=900ms total) so slow VPN/network wakeups never stall 2.55s;
                        // if STUN times out before the 1st REGISTER, we re-probe immediately after 401 arrives!
                        if (!isV6) {
                            resolveMappedEndpointViaStun(it, updateSipGlobals = true, maxServers = 2, timeoutMs = 450)
                        }
                    }
                }

                val localPort = socket.localPort
                val (viaHost1, viaPort1) = getViaEndpoint(localIp, localPort, targetAddress)
                val (contactHost1, contactPort1) = getContactEndpoint(localIp, localPort, targetAddress)

                val username = account.username.trim()
                val authUser = account.effectiveAuthUser
                val password = account.password
                val callId = regCallId ?: generateOpaqueCallId().also { regCallId = it }
                val fromTag = "tag${Random().nextInt(999999)}"
                val requestUri = "sip:$serverHost"

                val cseq1 = ++regCSeq
                val branch1 = generateBranch()
                val registerMsg1 = "REGISTER $requestUri SIP/2.0\r\n" +
                        "Via: SIP/2.0/UDP $viaHost1:$viaPort1;rport;branch=$branch1\r\n" +
                        "Max-Forwards: 70\r\n" +
                        "To: <sip:$username@$serverHost>\r\n" +
                        "From: <sip:$username@$serverHost>;tag=$fromTag\r\n" +
                        "Call-ID: $callId\r\n" +
                        "CSeq: $cseq1 REGISTER\r\n" +
                        "Contact: <sip:$username@$contactHost1:$contactPort1;transport=udp>\r\n" +
                        "Expires: 600\r\n" +
                        "User-Agent: $SIP_USER_AGENT\r\n" +
                        "Content-Length: 0\r\n\r\n"

                val data1 = registerMsg1.toByteArray(Charsets.ISO_8859_1)
                log("Probe REGISTER sent to ${formatHostForUri(targetAddress)}:${account.port} (ipv6=$isV6, reuseSocket=$reuseExistingSocket, via=$viaHost1:$viaPort1, local=$localIp:$localPort, CSeq=$cseq1)")
                val probeStartMs = SystemClock.elapsedRealtime()
                val canFallbackToIpv4 = isV6 && !useForceIpv4 && !preferIpv4FallbackForCurrentNetwork
                val response1 = try {
                    if (reuseExistingSocket) {
                        sendRegisterViaChannel(
                            socket, data1, targetAddress, account.port,
                            expectedCallId = callId, expectedCSeqNumber = cseq1,
                            totalTimeoutMs = if (canFallbackToIpv4) 5500L else 10000L
                        )
                    } else {
                        sendWithRetransmit(
                            socket, data1, targetAddress, account.port,
                            totalTimeoutMs = if (canFallbackToIpv4) 5500L else 10000L,
                            expectedMethod = "REGISTER", expectedCSeqNumber = cseq1
                        )
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (canFallbackToIpv4 && isNetworkConnected(appContext)) {
                        log("Native IPv6 probe REGISTER failed (${e.message}); falling back to IPv4 for this network...")
                        preferIpv4FallbackForCurrentNetwork = true
                        cachedResolvedHost = null
                        cachedResolvedTimestampMs = 0L
                        try { newSocket?.close() } catch (_: Exception) {}
                        newSocket = null
                        useForceIpv4 = true
                        continue
                    }
                    throw e
                }
                val measuredRtt = (SystemClock.elapsedRealtime() - probeStartMs).coerceIn(1L, 9999L)
                lastMeasuredLatencyMs = measuredRtt

                val via1 = extractHeader(response1, "Via") ?: ""
                log("Server probe response Via: $via1")
                parseViaReceivedAndRport(via1, targetAddress)

                val statusCode1 = extractStatusCode(response1)
                log("Server response code: $statusCode1")

                if (statusCode1 == 200) {
                    onRegistrationSuccess(account, response1, socket, oldPersistentSocket, localIp, reusedSocket = reuseExistingSocket)
                    return@withContext true
                }

                if (statusCode1 == 401 || statusCode1 == 407) {
                    val authHeader = if (statusCode1 == 407) {
                        extractHeader(response1, "Proxy-Authenticate") ?: extractHeader(response1, "WWW-Authenticate")
                    } else {
                        extractHeader(response1, "WWW-Authenticate") ?: extractHeader(response1, "Proxy-Authenticate")
                    } ?: throw Exception("Server sent $statusCode1 without challenge header")

                    val realm = extractAuthParam(authHeader, "realm") ?: "semux.io"
                    val nonce = extractAuthParam(authHeader, "nonce") ?: ""
                    val qop = extractAuthParam(authHeader, "qop")
                    val opaque = extractAuthParam(authHeader, "opaque")

                    log("Digest challenge received: realm=\"$realm\", qop=\"$qop\"")

                    val ha1 = md5("$authUser:$realm:$password")
                    val ha2 = md5("REGISTER:$requestUri")
                    val cnonce = "%08x".format(Random().nextInt(0x7fffffff))
                    val nc = "00000001"
                    val useQopAuth = hasAuthQop(qop)

                    val digestResponse = if (useQopAuth) {
                        md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
                    } else {
                        md5("$ha1:$nonce:$ha2")
                    }

                    val authHeaderName = if (statusCode1 == 407) "Proxy-Authorization" else "Authorization"
                    val authHeaderVal = StringBuilder(
                        "Digest username=\"$authUser\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$requestUri\", response=\"$digestResponse\", algorithm=MD5"
                    )
                    if (useQopAuth) {
                        authHeaderVal.append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
                    }
                    if (opaque != null) {
                        authHeaderVal.append(", opaque=\"$opaque\"")
                    }

                    val cseq2 = ++regCSeq
                    val branch2 = generateBranch()
                    // CRITICAL POST-401 STUN RECOVERY:
                    // If initial STUN timed out while the VPN/network was still waking up, receiving 401 proves UDP
                    // internet is NOW live, and SemuxGW/2.0 does not populate Via ;received/;rport!
                    // Re-probe STUN on this exact socket right now so Contact: gets our real public IP:port (never 172.16.0.2 / 192.168.x.x)!
                    if (!reuseExistingSocket && !isV6 && serverMappedIp == null && (publicIp == null || isPrivateIp(publicIp) || stunMappedSocketLocalPort != localPort)) {
                        log("Post-401 STUN recovery: initial STUN missed while tunnel was waking up; probing public IP:port on live socket...")
                        resolveMappedEndpointViaStun(socket, updateSipGlobals = true, maxServers = 2, timeoutMs = 450)
                    }
                    val (viaHost2, viaPort2) = getViaEndpoint(localIp, localPort, targetAddress)
                    val (contactHost2, contactPort2) = getContactEndpoint(localIp, localPort, targetAddress)
                    log("Sending authenticated REGISTER with Via: $viaHost2:$viaPort2, Contact: $contactHost2:$contactPort2 (CSeq=$cseq2)")
                    val registerMsg2 = "REGISTER $requestUri SIP/2.0\r\n" +
                            "Via: SIP/2.0/UDP $viaHost2:$viaPort2;rport;branch=$branch2\r\n" +
                            "Max-Forwards: 70\r\n" +
                            "To: <sip:$username@$serverHost>\r\n" +
                            "From: <sip:$username@$serverHost>;tag=$fromTag\r\n" +
                            "Call-ID: $callId\r\n" +
                            "CSeq: $cseq2 REGISTER\r\n" +
                            "Contact: <sip:$username@$contactHost2:$contactPort2;transport=udp>\r\n" +
                            "$authHeaderName: $authHeaderVal\r\n" +
                            "Expires: 600\r\n" +
                            "User-Agent: $SIP_USER_AGENT\r\n" +
                            "Content-Length: 0\r\n\r\n"

                    val data2 = registerMsg2.toByteArray(Charsets.ISO_8859_1)
                    log("Authenticated REGISTER sent. Waiting for 200 OK...")
                    val response2 = if (reuseExistingSocket) {
                        sendRegisterViaChannel(socket, data2, targetAddress, account.port, expectedCallId = callId, expectedCSeqNumber = cseq2)
                    } else {
                        try {
                            socket.soTimeout = 1
                            val drainPacket = DatagramPacket(ByteArray(2048), 2048)
                            while (true) {
                                socket.receive(drainPacket)
                            }
                        } catch (_: Exception) {}
                        sendWithRetransmit(socket, data2, targetAddress, account.port, expectedMethod = "REGISTER", expectedCSeqNumber = cseq2)
                    }

                    val via2 = extractHeader(response2, "Via") ?: ""
                    log("Server auth response Via: $via2")
                    parseViaReceivedAndRport(via2, targetAddress)

                    val statusCode2 = extractStatusCode(response2)
                    log("Authentication verification result: $statusCode2")

                    if (statusCode2 == 200) {
                        onRegistrationSuccess(account, response2, socket, oldPersistentSocket, localIp, reusedSocket = reuseExistingSocket)
                        return@withContext true
                    } else if (statusCode2 == 401 || statusCode2 == 403 || statusCode2 == 407) {
                        if (!isInCall()) {
                            try { socket.close() } catch (_: Exception) {}
                            if (oldPersistentSocket != null && oldPersistentSocket !== socket) {
                                try { oldPersistentSocket.close() } catch (_: Exception) {}
                            }
                            persistentSipSocket = null
                        } else if (newSocket != null && newSocket !== oldPersistentSocket) {
                            try { newSocket?.close() } catch (_: Exception) {}
                        }
                        regCallId = null
                        regCSeq = 0
                        currentStatus = RegistrationStatus.FAILED
                        log("Registration Rejected ($statusCode2): Bad SIP password or unapproved IP")
                        notifyStatus(RegistrationStatus.FAILED, "Invalid credentials ($statusCode2)")
                        return@withContext false
                    } else {
                        handleRegistrationFailure(account, isBackgroundRenewal, "Error $statusCode2", oldPersistentSocket, newSocket, myRegGen)
                        return@withContext false
                    }
                } else {
                    handleRegistrationFailure(account, isBackgroundRenewal, "Error $statusCode1", oldPersistentSocket, newSocket, myRegGen)
                    return@withContext false
                }
            }
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            log("Registration coroutine cleanly superseded/cancelled.")
            try { newSocket?.close() } catch (_: Exception) {}
            throw e
        } catch (e: SocketTimeoutException) {
            handleRegistrationFailure(account, isBackgroundRenewal, "Connection Timeout", oldPersistentSocket, newSocket, myRegGen)
            return@withContext false
        } catch (e: Exception) {
            handleRegistrationFailure(account, isBackgroundRenewal, e.message ?: "Connection Error", oldPersistentSocket, newSocket, myRegGen)
            return@withContext false
        } finally {
            if (newSocket != null && inFlightRegisterSocket === newSocket) {
                inFlightRegisterSocket = null
            }
            try {
                if (regWakeLock?.isHeld == true) regWakeLock.release()
            } catch (_: Exception) {}
            if (registrationGeneration.get() == myRegGen && !isBackgroundRenewal && currentStatus == RegistrationStatus.REGISTERING) {
                log("Registration ended while still in REGISTERING status. Resetting to FAILED.")
                currentStatus = RegistrationStatus.FAILED
                notifyStatus(RegistrationStatus.FAILED, "Reconnecting...")
            }
            try { registrationMutex.unlock(lockOwner) } catch (_: Exception) {}
        }
    }

    private fun onRegistrationSuccess(
        account: SipAccount,
        response: String,
        newSocket: DatagramSocket,
        oldSocket: DatagramSocket?,
        localIp: String,
        reusedSocket: Boolean = false
    ) {
        // Critical Call Safety Guard: If ANY outgoing/incoming call is CONNECTING, RINGING, or CONNECTED
        // on oldSocket while a fresh newSocket was registering, NEVER tear down oldSocket or swap listeners!
        if (!reusedSocket && isInCall() && oldSocket != null && oldSocket !== newSocket && !oldSocket.isClosed) {
            log("Active call in progress ($currentCallState) on previous socket! Deferring socket swap & flagging post-call re-register.")
            try { newSocket.close() } catch (_: Exception) {}
            needsPostCallReRegister = true
            currentStatus = RegistrationStatus.REGISTERED
            lastSuccessfulRegistrationTime = SystemClock.elapsedRealtime()
            consecutiveMissedOptions = 0
            scheduleNextRegistration(account, 60)
            return
        }

        currentStatus = RegistrationStatus.REGISTERED
        lastSuccessfulRegistrationTime = SystemClock.elapsedRealtime()
        lastRegisteredLocalIp = localIp
        lastRegisteredNetworkFingerprint = getNetworkFingerprint(appContext)
        consecutiveMissedOptions = 0
        if (inFlightRegisterSocket === newSocket) {
            inFlightRegisterSocket = null
        }
        persistentSipSocket = newSocket

        log("SUCCESS! Registered (200 OK) with ${account.server} (reusedSocket=$reusedSocket, localPort=${newSocket.localPort})")
        notifyStatus(RegistrationStatus.REGISTERED, "Registered (200 OK)")

        val grantedExpires = extractGrantedExpires(response)
        registrationExpiresSeconds = grantedExpires

        if (!reusedSocket || sipListenerJob?.isActive != true) {
            startPersistentSipListener(newSocket)
        }
        if (keepAliveJob?.isActive != true) {
            startKeepAlive(account)
        }

        // Close old socket cleanly only if we created a brand new socket and it is not in use by a call
        if (!reusedSocket && oldSocket != null && oldSocket !== newSocket && !oldSocket.isClosed) {
            try { oldSocket.close() } catch (_: Exception) {}
        }

        scheduleNextRegistration(account, grantedExpires)
        appContext?.let { CdrSyncManager.triggerSync(it) }
    }

    private fun handleRegistrationFailure(
        account: SipAccount,
        isBackgroundRenewal: Boolean,
        reason: String,
        oldSocket: DatagramSocket?,
        newSocket: DatagramSocket?,
        expectedGen: Long = registrationGeneration.get()
    ) {
        try {
            if (newSocket != null && newSocket !== oldSocket) newSocket.close()
        } catch (_: Exception) {}

        // If a newer registration already superseded this one, NEVER clobber shared state!
        if (registrationGeneration.get() != expectedGen) {
            log("Ignoring stale registration failure ($reason) superseded by newer registration.")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val isOnline = isNetworkConnected(appContext)

        if (!isOnline) {
            if (!isInCall() && oldSocket != null && persistentSipSocket === oldSocket) {
                try { oldSocket.close() } catch (_: Exception) {}
                persistentSipSocket = null
            }
            lastRegisteredLocalIp = null
            lastRegisteredNetworkFingerprint = null
            currentStatus = RegistrationStatus.FAILED
            log("Registration offline ($reason). Switching to No Internet...")
            notifyStatus(RegistrationStatus.FAILED, "No Internet")
            return
        }

        val leaseValidDurationMs = (registrationExpiresSeconds * 1000L).coerceAtLeast(120_000L)
        val timeSinceSuccess = if (lastSuccessfulRegistrationTime > 0) now - lastSuccessfulRegistrationTime else Long.MAX_VALUE

        if (isBackgroundRenewal && currentStatus == RegistrationStatus.REGISTERED && timeSinceSuccess < (leaseValidDurationMs * 0.85).toLong()) {
            val remainingSec = ((leaseValidDurationMs - timeSinceSuccess) / 1000).coerceAtLeast(0)
            log("Background renewal warning: $reason. Server lease remains valid for ${remainingSec}s. Keeping connection active & retrying in 15s...")
            scheduleRetry(account, 15_000L, isRenewal = true)
            return
        }

        if (!isInCall()) {
            if (oldSocket != null && persistentSipSocket === oldSocket) {
                try { oldSocket.close() } catch (_: Exception) {}
                persistentSipSocket = null
            } else if (persistentSipSocket == null) {
                try { oldSocket?.close() } catch (_: Exception) {}
            }
        }
        lastRegisteredLocalIp = null
        lastRegisteredNetworkFingerprint = null
        currentStatus = RegistrationStatus.FAILED
        val msg = if (isOnline) "Reconnecting..." else "No Internet"
        log("Registration failed: $reason (isOnline=$isOnline). Transitioning to FAILED...")
        notifyStatus(RegistrationStatus.FAILED, msg)
        if (isOnline) {
            scheduleRetry(account, 3_500L, isRenewal = false)
        }
    }

    private fun extractGrantedExpires(response: String): Int {
        val expiresHeader = extractHeader(response, "Expires")?.trim()?.toIntOrNull()
        if (expiresHeader != null && expiresHeader > 0) return expiresHeader

        val contactHeader = extractHeader(response, "Contact")
        if (contactHeader != null) {
            val match = Regex("""expires=(\d+)""").find(contactHeader)
            if (match != null) {
                val v = match.groupValues[1].toIntOrNull()
                if (v != null && v > 0) return v
            }
        }
        return 120
    }

    private fun scheduleNextRegistration(account: SipAccount, expiresSec: Int) {
        reRegisterJob?.cancel()
        val refreshSeconds = (expiresSec * 0.45).toLong().coerceIn(45L, 180L)
        log("Registration valid for ${expiresSec}s. Auto-renewal scheduled in ${refreshSeconds}s.")

        reRegisterJob = engineScope.launch {
            delay(refreshSeconds * 1000L)
            if (isActive && currentStatus == RegistrationStatus.REGISTERED) {
                log("Executing scheduled SIP registration renewal (zero downtime)...")
                triggerRegister(account, isRenewal = true)
            }
        }
    }

    private fun scheduleRetry(account: SipAccount, delayMs: Long, isRenewal: Boolean = false) {
        retryRegistrationJob?.cancel()
        retryRegistrationJob = engineScope.launch {
            delay(delayMs)
            if (!isActive) return@launch
            if (isRenewal || currentStatus != RegistrationStatus.REGISTERED) {
                if (isNetworkConnected(appContext)) {
                    log("Executing registration retry (isRenewal=$isRenewal)...")
                    triggerRegister(account, isRenewal = isRenewal)
                } else {
                    log("Skipping retry: Device is offline. Rescheduling check in 8s...")
                    scheduleRetry(account, 8_000L, isRenewal = isRenewal)
                }
            }
        }
    }

    private fun startPersistentSipListener(socket: DatagramSocket) {
        sipListenerJob?.cancel()
        sipListenerJob = engineScope.launch {
            val buf = ByteArray(8192)
            try {
                // Short 2.5s read timeout ensures cancelled listeners exit rapidly without competing for UDP packets
                socket.soTimeout = 2_500
            } catch (_: Exception) { return@launch }

            var consecutiveErrors = 0
            while (isActive && !socket.isClosed && socket === persistentSipSocket) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    consecutiveErrors = 0
                    // First check if this is a binary STUN Binding Success Response (0x0101)
                    if (packet.length >= 20 && buf[0] == 0x01.toByte() && buf[1] == 0x01.toByte()) {
                        val mapped = parseStunResponse(buf, packet.length)
                        if (mapped != null && !isPrivateIp(mapped.first)) {
                            val wasUnmapped = (publicIp == null || isPrivateIp(publicIp) || stunMappedSocketLocalPort != socket.localPort)
                            publicIp = mapped.first
                            publicPort = mapped.second
                            stunMappedSocketLocalPort = socket.localPort
                            log("STUN mapped endpoint refreshed on persistent socket: $publicIp:$publicPort")
                            if (wasUnmapped && !isInCall() && currentStatus == RegistrationStatus.REGISTERED) {
                                activeAccount?.let { acc ->
                                    log("STUN public IP recovered; renewing Contact binding immediately...")
                                    triggerRegister(acc, isRenewal = true)
                                }
                            }
                        }
                        continue
                    }

                    val rawMsg = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    val msg = rawMsg.trimStart()
                    if (msg.isBlank()) continue

                    val method = msg.substringBefore(" ").trim().uppercase()
                    log("SIP packet received on persistent socket: method=$method (${packet.length} bytes from ${packet.address.hostAddress}:${packet.port})")

                    if (method == "INVITE" || msg.startsWith("INVITE", ignoreCase = true)) {
                        handleIncomingInvite(msg, packet.address, packet.port)
                    } else if (method == "CANCEL" || msg.startsWith("CANCEL", ignoreCase = true)) {
                        handleIncomingCancel(msg, packet.address, packet.port)
                    } else if (method == "BYE" || msg.startsWith("BYE", ignoreCase = true)) {
                        handleIncomingBye(msg, packet.address, packet.port)
                    } else if (method == "ACK" || msg.startsWith("ACK", ignoreCase = true)) {
                        val ackCallId = extractHeader(msg, "Call-ID")?.removePrefix("<")?.removeSuffix(">")?.trim()
                        log("SIP ACK confirmed on persistent socket (Call-ID=$ackCallId).")
                        val pendingAckId = incomingAckCallId?.removePrefix("<")?.removeSuffix(">")?.trim()
                        val activeIncId = incomingCallId?.removePrefix("<")?.removeSuffix(">")?.trim()
                        if (ackCallId == null ||
                            (pendingAckId != null && ackCallId.equals(pendingAckId, ignoreCase = true)) ||
                            (activeIncId != null && ackCallId.equals(activeIncId, ignoreCase = true))) {
                            incoming200OkRetransmitJob?.cancel()
                            incoming200OkRetransmitJob = null
                            incomingAckDeferred?.complete(msg)
                        }
                        // Support delayed-offer incoming calls (SDP answer carried inside ACK) — validate Call-ID!
                        val activeOutId = currentCallId?.removePrefix("<")?.removeSuffix(">")?.trim()
                        val matchesActiveDialog = ackCallId != null && (
                            (activeIncId != null && ackCallId.equals(activeIncId, ignoreCase = true)) ||
                            (activeOutId != null && ackCallId.equals(activeOutId, ignoreCase = true))
                        )
                        if (matchesActiveDialog && msg.contains("m=audio", ignoreCase = true) && currentCallState == CallState.CONNECTED) {
                            val ackMediaAddr = resolveRemoteMediaAddress(msg, packet.address)
                            val ackPort = extractSdpAudioPortNullable(msg)
                            val ackCodec = extractSdpCodec(msg)
                            if (ackMediaAddr != null && ackPort != null && ackPort > 0) {
                                try {
                                    rtpStreamer?.updateRemoteEndpoint(ackMediaAddr, ackPort, ackCodec)
                                    log("Applied delayed-offer SDP from ACK -> ${cleanHostAddress(ackMediaAddr)}:$ackPort (codec=$ackCodec)")
                                } catch (_: Exception) {}
                            }
                        }
                    } else if (method == "OPTIONS" || method == "UPDATE" || method == "INFO" || method == "NOTIFY" ||
                        msg.startsWith("OPTIONS", ignoreCase = true) ||
                        msg.startsWith("UPDATE", ignoreCase = true) ||
                        msg.startsWith("INFO", ignoreCase = true) ||
                        msg.startsWith("NOTIFY", ignoreCase = true)) {
                        val rawViaBlock = extractAllViaHeaders(msg).ifBlank {
                            extractHeader(msg, "Via")?.let { "Via: $it\r\n" } ?: ""
                        }
                        val viaBlock = formatResponseViaHeaders(rawViaBlock, packet.address, packet.port)
                        val ok = "SIP/2.0 200 OK\r\n" +
                                viaBlock +
                                (extractHeader(msg, "From")?.let { "From: $it\r\n" } ?: "") +
                                (extractHeader(msg, "To")?.let { "To: $it\r\n" } ?: "") +
                                (extractHeader(msg, "Call-ID")?.let { "Call-ID: $it\r\n" } ?: "") +
                                (extractHeader(msg, "CSeq")?.let { "CSeq: $it\r\n" } ?: "") +
                                "Content-Length: 0\r\n\r\n"
                        val okData = ok.toByteArray(Charsets.ISO_8859_1)
                        socket.send(DatagramPacket(okData, okData.size, packet.address, packet.port))
                    } else if (msg.startsWith("SIP/2.0", ignoreCase = true)) {
                        val respCallId = extractHeader(msg, "Call-ID")?.removePrefix("<")?.removeSuffix(">")?.trim()
                        val activeId = currentCallId?.removePrefix("<")?.removeSuffix(">")?.trim()
                        val respCSeq = extractHeader(msg, "CSeq") ?: ""
                        val (_, respCSeqMethod) = parseCSeq(respCSeq)
                        val code = extractStatusCode(msg)

                        // 1. Route REGISTER responses to registerResponseChannel (for zero-churn socket reuse renewals)
                        if (respCSeqMethod == "REGISTER") {
                            registerResponseChannel.trySend(msg)
                            continue
                        }

                        // 2. Track OPTIONS keepalive responses (proves NAT pinhole is alive + measures live SIP RTT)
                        if (respCSeqMethod == "OPTIONS") {
                            extractHeader(msg, "Via")?.let { parseViaReceivedAndRport(it, packet.address) }
                            val nowResp = SystemClock.elapsedRealtime()
                            lastOptionsResponseElapsedMs = nowResp
                            consecutiveMissedOptions = 0
                            val sentAt = lastOptionsSentElapsedMs
                            if (sentAt > 0L) {
                                val rtt = nowResp - sentAt
                                if (rtt in 1L..5000L) {
                                    lastMeasuredLatencyMs = rtt
                                }
                                lastOptionsSentElapsedMs = 0L
                            }
                            continue
                        }

                        log("Incoming SIP response on persistent socket: code=$code ($respCSeq, Call-ID=$respCallId, activeCallId=$activeId, callState=$currentCallState)")

                        // 2b. Route responses for an in-flight mid-call network handover re-INVITE!
                        val reInvCallId = pendingReInviteCallId
                        val reInvCSeq = pendingReInviteCSeq
                        val (respCSeqNum, _) = parseCSeq(respCSeq)
                        if (respCSeqMethod == "INVITE" &&
                            reInvCallId != null &&
                            respCallId != null &&
                            respCallId.equals(reInvCallId, ignoreCase = true) &&
                            (reInvCSeq == null || respCSeqNum == reInvCSeq) &&
                            currentCallState == CallState.CONNECTED) {
                            log("Dispatching mid-call handover re-INVITE response: code=$code ($respCSeq)")
                            reInviteResponseChannel.trySend(msg)
                            continue
                        }

                        // 3. Handle retransmitted 200 OK for an already-CONNECTED outgoing/incoming INVITE
                        val activeIncId = incomingCallId?.removePrefix("<")?.removeSuffix(">")?.trim()
                        val matchesConnectedDialog = respCallId != null &&
                                (respCallId.equals(activeId, ignoreCase = true) || respCallId.equals(activeIncId, ignoreCase = true))
                        if (code == 200 && respCSeqMethod == "INVITE" && matchesConnectedDialog && currentCallState == CallState.CONNECTED) {
                            val cachedAck = last200OkAckBytes
                            val cachedAckCSeq = last200OkAckCSeq
                            val ackAddr = last200OkAckAddress ?: packet.address
                            val ackPort = if (last200OkAckAddress != null) last200OkAckPort else packet.port
                            if (cachedAck != null && (respCSeqNum == null || cachedAckCSeq <= 0 || respCSeqNum == cachedAckCSeq)) {
                                try {
                                    socket.send(DatagramPacket(cachedAck, cachedAck.size, ackAddr, ackPort))
                                    log("Re-sent cached SIP ACK for retransmitted 200 OK (Call-ID=$respCallId, CSeq=$respCSeqNum)")
                                } catch (_: Exception) {}
                            }
                            continue
                        }

                        // 4. Acknowledge late 487 Request Terminated after local CANCEL (RFC 3261 §17.1.1.3 exact branch + Request-URI)
                        if (code == 487 && respCSeqMethod == "INVITE" && currentCallState != CallState.CONNECTING && currentCallState != CallState.RINGING) {
                            val toHeader = extractHeader(msg, "To") ?: ""
                            val fromHeader = extractHeader(msg, "From") ?: ""
                            val viaHeader = extractHeader(msg, "Via") ?: ""
                            val branch = Regex("""branch=([^;\s]+)""", RegexOption.IGNORE_CASE).find(viaHeader)?.groupValues?.get(1)
                                ?: activeCallBranch.ifBlank { generateBranch() }
                            val cseqAck = respCSeq.replace("INVITE", "ACK", ignoreCase = true).ifBlank { "1 ACK" }
                            val localAckIp = getLocalAddressForTarget(packet.address)
                            val (ackHost, ackPort) = getViaEndpoint(localAckIp, socket.localPort, packet.address)
                            val reqUri = if (respCallId != null && respCallId.equals(activeId, ignoreCase = true) && currentCallUri != null) {
                                currentCallUri!!
                            } else {
                                Regex("""<([^>]+)>""").find(toHeader)?.groupValues?.get(1)
                                    ?: "sip:${formatHostForUri(packet.address)}:${packet.port}"
                            }
                            val ackMsg = "ACK $reqUri SIP/2.0\r\n" +
                                    "Via: SIP/2.0/UDP $ackHost:$ackPort;rport;branch=$branch\r\n" +
                                    "Max-Forwards: 70\r\n" +
                                    "To: $toHeader\r\n" +
                                    "From: $fromHeader\r\n" +
                                    "Call-ID: ${respCallId ?: ""}\r\n" +
                                    "CSeq: $cseqAck\r\n" +
                                    "Content-Length: 0\r\n\r\n"
                            val ackBytes = ackMsg.toByteArray(Charsets.ISO_8859_1)
                            try { socket.send(DatagramPacket(ackBytes, ackBytes.size, packet.address, packet.port)) } catch (_: Exception) {}
                            log("SIP ACK automatically sent on persistent socket for late 487 (branch=$branch)")
                            continue
                        }

                        // 5. Strictly dispatch INVITE responses matching the active Call-ID to initiateCall() (case-insensitive per RFC 3261)
                        val matchesActiveCall = (respCallId != null && activeId != null && respCallId.equals(activeId, ignoreCase = true))
                        if (matchesActiveCall && (currentCallState == CallState.CONNECTING || currentCallState == CallState.RINGING)) {
                            log("SIP response on persistent socket ($respCallId, $respCSeq, code=$code): dispatching to active call!")
                            outgoingCallResponseChannel.trySend(msg)
                        } else if (code == 200 && respCSeqMethod == "INVITE" && !matchesActiveCall && !matchesConnectedDialog) {
                            // Glare 200 OK for an already-cancelled/abandoned outgoing INVITE:
                            // Send immediate RFC 3261 ACK + BYE so the carrier PSTN leg never stays open & billing!
                            val toHeader = extractHeader(msg, "To") ?: ""
                            val fromHeader = extractHeader(msg, "From") ?: ""
                            val routeHeaders = formatRouteHeaders(extractRouteSetFromResponse(msg))
                            val reqUri = extractContactUri(extractHeader(msg, "Contact"))
                                ?: Regex("""<([^>]+)>""").find(toHeader)?.groupValues?.get(1)
                                ?: "sip:${formatHostForUri(packet.address)}:${packet.port}"
                            val localAckIp = getLocalAddressForTarget(packet.address)
                            val (ackHost, ackPort) = getViaEndpoint(localAckIp, socket.localPort, packet.address)
                            val cseqNumVal = respCSeqNum ?: 1
                            val ackMsg = "ACK $reqUri SIP/2.0\r\n" +
                                    "Via: SIP/2.0/UDP $ackHost:$ackPort;rport;branch=${generateBranch()}\r\n" +
                                    "Max-Forwards: 70\r\n" +
                                    routeHeaders +
                                    "To: $toHeader\r\n" +
                                    "From: $fromHeader\r\n" +
                                    "Call-ID: ${respCallId ?: ""}\r\n" +
                                    "CSeq: $cseqNumVal ACK\r\n" +
                                    "Content-Length: 0\r\n\r\n"
                            val byeMsg = "BYE $reqUri SIP/2.0\r\n" +
                                    "Via: SIP/2.0/UDP $ackHost:$ackPort;rport;branch=${generateBranch()}\r\n" +
                                    "Max-Forwards: 70\r\n" +
                                    routeHeaders +
                                    "To: $toHeader\r\n" +
                                    "From: $fromHeader\r\n" +
                                    "Call-ID: ${respCallId ?: ""}\r\n" +
                                    "CSeq: ${cseqNumVal + 1} BYE\r\n" +
                                    "Content-Length: 0\r\n\r\n"
                            try {
                                val ackBytes = ackMsg.toByteArray(Charsets.ISO_8859_1)
                                socket.send(DatagramPacket(ackBytes, ackBytes.size, packet.address, packet.port))
                                val byeBytes = byeMsg.toByteArray(Charsets.ISO_8859_1)
                                socket.send(DatagramPacket(byeBytes, byeBytes.size, packet.address, packet.port))
                                log("Terminated glare 200 OK for cancelled outgoing call (Call-ID=$respCallId) with ACK + BYE.")
                            } catch (_: Exception) {}
                        } else if (code in 300..699 && respCSeqMethod == "INVITE" && !matchesActiveCall) {
                            // Stray final error response from a previous/abandoned INVITE: ACK it so server stops retransmitting
                            val toHeader = extractHeader(msg, "To") ?: ""
                            val fromHeader = extractHeader(msg, "From") ?: ""
                            val viaHeader = extractHeader(msg, "Via") ?: ""
                            val viaBranch = Regex("""branch=([^;\s]+)""", RegexOption.IGNORE_CASE).find(viaHeader)?.groupValues?.get(1) ?: generateBranch()
                            val cseqAck = respCSeq.replace("INVITE", "ACK", ignoreCase = true).ifBlank { "1 ACK" }
                            val localAckIp = getLocalAddressForTarget(packet.address)
                            val (ackHost, ackPort) = getViaEndpoint(localAckIp, socket.localPort, packet.address)
                            val reqUri = Regex("""<([^>]+)>""").find(toHeader)?.groupValues?.get(1)
                                ?: "sip:${formatHostForUri(packet.address)}:${packet.port}"
                            val ackMsg = "ACK $reqUri SIP/2.0\r\n" +
                                    "Via: SIP/2.0/UDP $ackHost:$ackPort;rport;branch=$viaBranch\r\n" +
                                    "Max-Forwards: 70\r\n" +
                                    "To: $toHeader\r\n" +
                                    "From: $fromHeader\r\n" +
                                    "Call-ID: ${respCallId ?: ""}\r\n" +
                                    "CSeq: $cseqAck\r\n" +
                                    "Content-Length: 0\r\n\r\n"
                            val ackBytes = ackMsg.toByteArray(Charsets.ISO_8859_1)
                            try { socket.send(DatagramPacket(ackBytes, ackBytes.size, packet.address, packet.port)) } catch (_: Exception) {}
                            log("ACKed stray $code response for non-active Call-ID=$respCallId")
                        }
                    } else {
                        log("Unhandled SIP message on persistent socket: $method")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    log("Persistent SIP listener cleanly cancelled.")
                    throw e
                } catch (e: SocketTimeoutException) {
                    // Periodic timeout allows loop to stay active without blocking forever.
                    // This is expected and normal during idle standby. NEVER break or mark FAILED!
                    continue
                } catch (e: Exception) {
                    if (!isActive) break
                    if (socket.isClosed || socket !== persistentSipSocket) {
                        break
                    }
                    consecutiveErrors++
                    // When phone screen locks or CPU throttles, transient socket interrupts can occur.
                    // Do NOT kill the socket or mark FAILED unless we see repeated fatal errors (> 5 consecutive)
                    if (consecutiveErrors < 5) {
                        log("Persistent SIP listener transient tick ($consecutiveErrors/5): ${e.message}. Resuming listener...")
                        delay(600)
                        continue
                    }
                    // Socket encountered repeated unrecoverable errors while registered
                    log("Persistent SIP listener read error: ${e.message}. Recovering connection...")
                    val acc = activeAccount
                    if (acc != null) {
                        try { socket.close() } catch (_: Exception) {}
                        persistentSipSocket = null
                        currentStatus = RegistrationStatus.FAILED
                        notifyStatus(RegistrationStatus.FAILED, "Reconnecting...")
                        scheduleRetry(acc, 2_000)
                    }
                    break
                }
            }
        }
    }

    private fun handleIncomingCancel(cancelMsg: String, remoteAddr: InetAddress, remotePort: Int) {
        log("RAW INCOMING CANCEL:\n$cancelMsg")
        val cancelCallId = extractHeader(cancelMsg, "Call-ID")?.removePrefix("<")?.removeSuffix(">")?.trim()
        val activeIncId = incomingCallId?.removePrefix("<")?.removeSuffix(">")?.trim()
        val socket = persistentSipSocket

        // RFC 3261 Section 9.2: If CANCEL does not match an active ringing incoming INVITE,
        // reply 481 Call/Transaction Does Not Exist; if call was already answered (glare CANCEL),
        // reply 200 OK to CANCEL and KEEP the live connected call alive!
        val isMatchingCancel: Boolean
        val alreadyConfirmedGlare: Boolean
        synchronized(callStateLock) {
            val idMatch = activeIncId != null &&
                (cancelCallId.isNullOrBlank() || cancelCallId.equals(activeIncId, ignoreCase = true))
            alreadyConfirmedGlare = idMatch && (isDialogConfirmed || currentCallState == CallState.CONNECTED || sent200OkForIncoming || isAnswering)
            isMatchingCancel = idMatch && !alreadyConfirmedGlare && currentCallState == CallState.RINGING
        }
        if (alreadyConfirmedGlare) {
            log("Glare CANCEL received for already-CONNECTED incoming call ($activeIncId). Replying 200 OK to CANCEL and keeping call alive.")
            if (socket != null && !socket.isClosed) {
                try {
                    val rawVia = extractAllViaHeaders(cancelMsg).ifBlank {
                        extractHeader(cancelMsg, "Via")?.let { "Via: $it\r\n" } ?: ""
                    }
                    val viaBlock = formatResponseViaHeaders(rawVia, remoteAddr, remotePort)
                    val ok200 = "SIP/2.0 200 OK\r\n" +
                            viaBlock +
                            (extractHeader(cancelMsg, "From")?.let { "From: $it\r\n" } ?: "") +
                            (extractHeader(cancelMsg, "To")?.let { "To: $it\r\n" } ?: "") +
                            (extractHeader(cancelMsg, "Call-ID")?.let { "Call-ID: $it\r\n" } ?: "") +
                            (extractHeader(cancelMsg, "CSeq")?.let { "CSeq: $it\r\n" } ?: "") +
                            "Content-Length: 0\r\n\r\n"
                    val bytes = ok200.toByteArray(Charsets.ISO_8859_1)
                    socket.send(DatagramPacket(bytes, bytes.size, remoteAddr, remotePort))
                } catch (_: Exception) {}
            }
            return
        }
        if (!isMatchingCancel) {
            log("Ignoring unmatched/stray CANCEL (Call-ID=$cancelCallId, activeIncomingCallId=$activeIncId). Sending 481.")
            if (socket != null && !socket.isClosed) {
                try {
                    val viaBlock = extractAllViaHeaders(cancelMsg).ifBlank {
                        extractHeader(cancelMsg, "Via")?.let { "Via: $it\r\n" } ?: ""
                    }
                    val resp481 = "SIP/2.0 481 Call/Transaction Does Not Exist\r\n" +
                            viaBlock +
                            (extractHeader(cancelMsg, "From")?.let { "From: $it\r\n" } ?: "") +
                            (extractHeader(cancelMsg, "To")?.let { "To: $it\r\n" } ?: "") +
                            (extractHeader(cancelMsg, "Call-ID")?.let { "Call-ID: $it\r\n" } ?: "") +
                            (extractHeader(cancelMsg, "CSeq")?.let { "CSeq: $it\r\n" } ?: "") +
                            "Content-Length: 0\r\n\r\n"
                    val bytes = resp481.toByteArray(Charsets.ISO_8859_1)
                    socket.send(DatagramPacket(bytes, bytes.size, remoteAddr, remotePort))
                } catch (_: Exception) {}
            }
            return
        }

        log("Incoming CANCEL received for active ringing call ($activeIncId): caller hung up before answer.")
        incomingAckDeferred?.complete(null)
        incomingSetupJob?.cancel()
        incomingSetupJob = null
        incomingRingingWatchdogJob?.cancel()
        incomingRingingWatchdogJob = null

        // Capture dialog headers and atomically clear incoming state FIRST so any racing
        // IncomingCallActivity.onCreate() or postIncomingCallNotification() immediately sees
        // hasActiveIncomingCall() == false and aborts before starting ringtone!
        val savedIncomingVia: String?
        val savedIncomingViaHeaders: String
        val savedIncomingFrom: String?
        val savedIncomingTo: String?
        val savedIncomingCallId: String?
        val savedIncomingCSeq: String?
        val savedIncomingLocalTag: String?
        val socketToClose: DatagramSocket?

        recordEndedIncomingCallId(activeIncId)
        recordEndedIncomingCallId(cancelCallId)

        incomingAckDeferred?.complete(null)
        synchronized(callStateLock) {
            savedIncomingVia = incomingVia
            savedIncomingViaHeaders = incomingViaHeaders
            savedIncomingFrom = incomingFrom
            savedIncomingTo = incomingTo
            savedIncomingCallId = incomingCallId
            savedIncomingCSeq = incomingCSeq
            savedIncomingLocalTag = incomingLocalTag
            socketToClose = activeRtpSocket

            incomingCallId = null
            incomingLocalTag = null
            incomingContactUri = null
            incomingRouteSet = emptyList()
            incomingFrom = null
            incomingTo = null
            incomingRemoteAddress = null
            incomingRemotePort = 0
            incomingCSeq = null
            incomingSdp = null
            incomingViaHeaders = ""
            incomingVia = ""
            lastProvisionalResponse = null
            activeRtpSocket = null
            activeCallNumber = null
            activeCallName = null
            isCurrentCallIncoming = false
            isDialogConfirmed = false
            currentCallState = CallState.IDLE
        }

        // IMMEDIATELY stop ringing & vibration synchronously before doing any network/DB I/O!
        try { socketToClose?.close() } catch (_: Exception) {}
        com.opentalk.android.IncomingCallActivity.stopRingingAndDismissImmediately()
        appContext?.let { SipVoipService.dismissIncomingCall(it) }
        try {
            val via = extractHeader(cancelMsg, "Via") ?: savedIncomingVia
            val from = extractHeader(cancelMsg, "From") ?: savedIncomingFrom
            val to = extractHeader(cancelMsg, "To") ?: savedIncomingTo
            val callId = extractHeader(cancelMsg, "Call-ID") ?: savedIncomingCallId
            val cseq = extractHeader(cancelMsg, "CSeq") ?: savedIncomingCSeq

            val rawCancelViaBlock = extractAllViaHeaders(cancelMsg).ifBlank {
                if (savedIncomingViaHeaders.isNotBlank()) savedIncomingViaHeaders else "Via: $via\r\n"
            }
            val cancelViaBlock = formatResponseViaHeaders(rawCancelViaBlock, remoteAddr, remotePort)

            if (socket != null && !socket.isClosed) {
                // 1. Send 200 OK for CANCEL
                val okCancel = "SIP/2.0 200 OK\r\n" +
                        cancelViaBlock +
                        "From: $from\r\n" +
                        "To: $to\r\n" +
                        "Call-ID: $callId\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Content-Length: 0\r\n\r\n"
                val okBytes = okCancel.toByteArray(Charsets.ISO_8859_1)
                val okPacket = DatagramPacket(okBytes, okBytes.size, remoteAddr, remotePort)
                for (k in 0..1) {
                    try { socket.send(okPacket) } catch (_: Exception) {}
                    if (k < 1) Thread.sleep(10)
                }

                // 2. Send 487 Request Terminated for the INVITE
                val cleanTo = (savedIncomingTo ?: to ?: "").replace(Regex(";tag=[^;]+"), "")
                val toTagPart = if (savedIncomingLocalTag != null) ";tag=$savedIncomingLocalTag" else ""
                val rawInviteViaBlock = if (savedIncomingViaHeaders.isNotBlank()) savedIncomingViaHeaders else "Via: $savedIncomingVia\r\n"
                val inviteViaBlock = formatResponseViaHeaders(rawInviteViaBlock, remoteAddr, remotePort)
                val termInvite = "SIP/2.0 487 Request Terminated\r\n" +
                        inviteViaBlock +
                        "From: $savedIncomingFrom\r\n" +
                        "To: $cleanTo$toTagPart\r\n" +
                        "Call-ID: $savedIncomingCallId\r\n" +
                        "CSeq: $savedIncomingCSeq\r\n" +
                        "Content-Length: 0\r\n\r\n"
                val termBytes = termInvite.toByteArray(Charsets.ISO_8859_1)
                val termPacket = DatagramPacket(termBytes, termBytes.size, remoteAddr, remotePort)
                for (k in 0..1) {
                    try { socket.send(termPacket) } catch (_: Exception) {}
                    if (k < 1) Thread.sleep(10)
                }
                log("SIP 200 OK (CANCEL) and 487 Request Terminated (INVITE) sent to $remoteAddr:$remotePort")
            }
        } catch (e: Exception) {
            log("Error sending CANCEL responses: ${e.message}")
        }

        val ctx = appContext
        if (ctx != null) {
            SipVoipService.dismissIncomingCall(ctx)
            AudioRouteManager.resetToNormal(ctx)
            try {
                val cancelBroadcast = android.content.Intent("com.opentalk.android.ACTION_CALL_CANCELLED").apply {
                    setPackage(ctx.packageName)
                }
                ctx.sendBroadcast(cancelBroadcast)
            } catch (_: Exception) {}
            // Offload Contacts Provider IPC & SQLite write so persistent SIP listener stays 0ms non-blocking
            engineScope.launch {
                try {
                    val fromHeader = savedIncomingFrom ?: ""
                    val rawNumber = Regex("""sip:([^@>]+)""").find(fromHeader)?.groupValues?.get(1) ?: "Unknown"
                    val callerNumber = com.opentalk.android.utils.PhoneNumberHelper.toCanonicalE164(rawNumber)
                        .ifBlank { com.opentalk.android.utils.ContactResolver.cleanPhoneNumber(rawNumber) }
                    val resolvedName = com.opentalk.android.utils.ContactResolver.resolveContactName(ctx, callerNumber)
                    val callerName = resolvedName ?: callerNumber

                    CallHistoryDbHelper.getInstance(ctx).insertCall(
                        CallRecord(
                            remoteNumber = callerNumber,
                            displayName = callerName,
                            type = CallType.MISSED,
                            timestamp = System.currentTimeMillis(),
                            durationSeconds = 0
                        )
                    )
                    // Post Missed Call Notification in notification drawer & update app icon badge
                    com.opentalk.android.notifications.MissedCallNotifier.postMissedCall(ctx, callerNumber, callerName)
                } catch (_: Exception) {}
            }
        }

        val cancelGen = callGeneration.get()
        notifyCallState(CallState.ENDED, "Call Cancelled", expectedGen = cancelGen)
        notifyCallState(CallState.IDLE, "Idle", expectedGen = cancelGen)
        engineScope.launch(Dispatchers.Main) {
            incomingCallCancelledListeners.forEach {
                try { it.invoke() } catch (_: Exception) {}
            }
        }
    }

    private fun handleIncomingBye(byeMsg: String, remoteAddr: InetAddress, remotePort: Int) {
        log("RAW INCOMING BYE:\n$byeMsg")
        val byeCallId = extractHeader(byeMsg, "Call-ID")?.removePrefix("<")?.removeSuffix(">")?.trim()
        val activeOutId = currentCallId?.removePrefix("<")?.removeSuffix(">")?.trim()
        val activeIncId = incomingCallId?.removePrefix("<")?.removeSuffix(">")?.trim()

        log("Incoming BYE received (Call-ID=$byeCallId, activeOut=$activeOutId, activeInc=$activeIncId).")
        val matchesOut = byeCallId != null && activeOutId != null && byeCallId.equals(activeOutId, ignoreCase = true)
        val matchesInc = byeCallId != null && activeIncId != null && byeCallId.equals(activeIncId, ignoreCase = true)

        val socket = persistentSipSocket
        if (socket != null && !socket.isClosed) {
            try {
                val rawViaBlock = extractAllViaHeaders(byeMsg).ifBlank {
                    extractHeader(byeMsg, "Via")?.let { "Via: $it\r\n" } ?: ""
                }
                val viaBlock = formatResponseViaHeaders(rawViaBlock, remoteAddr, remotePort)
                // RFC 3261 §15.1.2: Reply 481 Call/Transaction Does Not Exist for unmatched BYE
                val statusLine = if (matchesOut || matchesInc) "SIP/2.0 200 OK" else "SIP/2.0 481 Call/Transaction Does Not Exist"
                val respMsg = "$statusLine\r\n" +
                        viaBlock +
                        (extractHeader(byeMsg, "From")?.let { "From: $it\r\n" } ?: "") +
                        (extractHeader(byeMsg, "To")?.let { "To: $it\r\n" } ?: "") +
                        (extractHeader(byeMsg, "Call-ID")?.let { "Call-ID: $it\r\n" } ?: "") +
                        (extractHeader(byeMsg, "CSeq")?.let { "CSeq: $it\r\n" } ?: "") +
                        "Content-Length: 0\r\n\r\n"
                val respBytes = respMsg.toByteArray(Charsets.ISO_8859_1)
                socket.send(DatagramPacket(respBytes, respBytes.size, remoteAddr, remotePort))
            } catch (_: Exception) {}
        }

        if (!matchesOut && !matchesInc) {
            log("Replied 481 to stray BYE for non-active dialog ($byeCallId); active call preserved.")
            return
        }

        incomingAckDeferred?.complete(null)
        incomingSetupJob?.cancel()
        incomingSetupJob = null
        incomingRingingWatchdogJob?.cancel()
        incomingRingingWatchdogJob = null
        incoming200OkRetransmitJob?.cancel()
        incoming200OkRetransmitJob = null
        lastProvisionalResponse = null

        val wasRinging = currentCallState == CallState.RINGING && matchesInc
        if (wasRinging) {
            log("Incoming BYE received while RINGING (early media cancelled by caller).")
            val savedIncomingFrom = incomingFrom
            recordEndedIncomingCallId(activeIncId)
            recordEndedIncomingCallId(byeCallId)
            synchronized(callStateLock) {
                incomingCallId = null
                currentCallState = CallState.IDLE
                isCurrentCallIncoming = false
                isDialogConfirmed = false
                lastProvisionalResponse = null
            }
            com.opentalk.android.IncomingCallActivity.stopRingingAndDismissImmediately()
            val ctx = appContext
            if (ctx != null) {
                SipVoipService.dismissIncomingCall(ctx)
                AudioRouteManager.resetToNormal(ctx)
                try {
                    val cancelBroadcast = android.content.Intent("com.opentalk.android.ACTION_CALL_CANCELLED").apply {
                        setPackage(ctx.packageName)
                    }
                    ctx.sendBroadcast(cancelBroadcast)
                } catch (_: Exception) {}
                engineScope.launch {
                    try {
                        val fromHeader = savedIncomingFrom ?: ""
                        val rawNumber = Regex("""sip:([^@>]+)""").find(fromHeader)?.groupValues?.get(1) ?: "Unknown"
                        val callerNumber = com.opentalk.android.utils.PhoneNumberHelper.toCanonicalE164(rawNumber)
                            .ifBlank { com.opentalk.android.utils.ContactResolver.cleanPhoneNumber(rawNumber) }
                        val resolvedName = com.opentalk.android.utils.ContactResolver.resolveContactName(ctx, callerNumber)
                        val callerName = resolvedName ?: callerNumber

                        CallHistoryDbHelper.getInstance(ctx).insertCall(
                            CallRecord(
                                remoteNumber = callerNumber,
                                displayName = callerName,
                                type = CallType.MISSED,
                                timestamp = System.currentTimeMillis(),
                                durationSeconds = 0
                            )
                        )
                        com.opentalk.android.notifications.MissedCallNotifier.postMissedCall(ctx, callerNumber, callerName)
                    } catch (_: Exception) {}
                }
            }
            engineScope.launch(Dispatchers.Main) {
                incomingCallCancelledListeners.forEach {
                    try { it.invoke() } catch (_: Exception) {}
                }
            }
        }
        hangUp(sendByeOrCancel = false)
    }

    private fun handleInDialogReInvite(inviteMsg: String, remoteAddr: InetAddress, remotePort: Int) {
        val socket = persistentSipSocket ?: return
        try {
            val localIp = getLocalAddressForTarget(remoteAddr)
            val (contactHost, contactPort) = getContactEndpoint(localIp, socket.localPort, remoteAddr)
            val rtpPort = activeRtpSocket?.localPort ?: 40000
            val offeredCodec = if (inviteMsg.contains("m=audio", ignoreCase = true)) {
                extractSdpCodec(inviteMsg)
            } else {
                rtpStreamer?.sendPayloadType ?: 8
            }
            val rawSdpIp = extractRawSdpIp(inviteMsg)
            val isRemoteHold = rawSdpIp == "0.0.0.0" || rawSdpIp == "::" ||
                inviteMsg.contains("a=sendonly", ignoreCase = true) ||
                inviteMsg.contains("a=inactive", ignoreCase = true)
            val answerDir = when {
                inviteMsg.contains("a=inactive", ignoreCase = true) -> "inactive"
                isRemoteHold && isCallOnHold -> "inactive"
                inviteMsg.contains("a=sendonly", ignoreCase = true) || rawSdpIp == "0.0.0.0" || rawSdpIp == "::" -> "recvonly"
                isCallOnHold -> "sendonly"
                else -> "sendrecv"
            }
            val cseq = extractHeader(inviteMsg, "CSeq") ?: ""
            val sdpVer = if (cseq.isNotBlank() && cseq.equals(incomingCSeq, ignoreCase = true)) {
                reInviteSdpVersion
            } else {
                ++reInviteSdpVersion
            }
            val codecBlock = "$offeredCodec 101\r\n" +
                    (if (offeredCodec == 8) "a=rtpmap:8 PCMA/8000\r\n" else "a=rtpmap:0 PCMU/8000\r\n") +
                    "a=rtpmap:101 telephone-event/8000\r\n" +
                    "a=ptime:20\r\n"
            val sdp = buildAudioSdp(
                username = activeAccount?.username ?: "Z",
                sessionVer = sdpVer,
                sessionName = SDP_SESSION_NAME,
                targetAddress = remoteAddr,
                localIp = localIp,
                localRtpPort = rtpPort,
                rtpStun = getActiveRtpStunForSocket(activeRtpSocket),
                codecBlock = codecBlock,
                direction = answerDir
            )

            val rawViaBlock = extractAllViaHeaders(inviteMsg).ifBlank {
                extractHeader(inviteMsg, "Via")?.let { "Via: $it\r\n" } ?: ""
            }
            val viaBlock = formatResponseViaHeaders(rawViaBlock, remoteAddr, remotePort)
            val from = extractHeader(inviteMsg, "From") ?: ""
            val to = extractHeader(inviteMsg, "To") ?: ""
            val callId = extractHeader(inviteMsg, "Call-ID") ?: ""
            val localTag = incomingLocalTag ?: currentFromTag
            val toTagPart = if (localTag != null && !to.contains("tag=", ignoreCase = true)) ";tag=$localTag" else ""

            extractContactUri(extractHeader(inviteMsg, "Contact"))?.let { updatedContact ->
                if (currentCallId != null) {
                    activeCallContactUri = updatedContact
                } else {
                    incomingContactUri = updatedContact
                }
            }

            val okMsg = "SIP/2.0 200 OK\r\n" +
                    viaBlock +
                    "From: $from\r\n" +
                    "To: $to$toTagPart\r\n" +
                    "Call-ID: $callId\r\n" +
                    "CSeq: $cseq\r\n" +
                    "Contact: <sip:${activeAccount?.username}@$contactHost:$contactPort;transport=udp>\r\n" +
                    "User-Agent: $SIP_USER_AGENT\r\n" +
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: ${sdp.toByteArray(Charsets.ISO_8859_1).size}\r\n\r\n$sdp"

            val okBytes = okMsg.toByteArray(Charsets.ISO_8859_1)
            socket.send(DatagramPacket(okBytes, okBytes.size, remoteAddr, remotePort))
            log("SIP 200 OK sent for in-dialog re-INVITE ($callId, dir=$answerDir)")

            if (isRemoteHold) {
                rtpStreamer?.setHold(true)
            } else {
                // Preserve local Hold state when remote sends a session-timer refresh re-INVITE!
                if (!isCallOnHold) {
                    rtpStreamer?.setHold(false)
                }
                val sdpPort = extractSdpAudioPortNullable(inviteMsg)
                val newAddr = resolveRemoteMediaAddress(inviteMsg, remoteAddr)
                if (newAddr != null && sdpPort != null && sdpPort > 0) {
                    try {
                        rtpStreamer?.updateRemoteEndpoint(newAddr, sdpPort, offeredCodec)
                        log("Updated remote RTP endpoint from re-INVITE: ${cleanHostAddress(newAddr)}:$sdpPort (codec=$offeredCodec)")
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            log("Error handling in-dialog re-INVITE: ${e.message}")
        }
    }

    private fun handleIncomingInvite(inviteMsg: String, remoteAddr: InetAddress, remotePort: Int) {
        val newCallId = extractHeader(inviteMsg, "Call-ID")
        val isRetransmission = newCallId != null && newCallId.equals(incomingCallId, ignoreCase = true) && currentCallState == CallState.RINGING

        log("INCOMING CALL RECEIVED! (isRetransmission=$isRetransmission, Call-ID=$newCallId)")

        // Guard against delayed UDP INVITE retransmissions arriving AFTER CANCEL/BYE/Decline:
        // reply 487 Request Terminated so server stops retrying and NEVER start ringing again!
        if (!isRetransmission && isRecentlyEndedIncomingCallId(newCallId)) {
            log("Ignoring late/retransmitted INVITE for recently ended Call-ID=$newCallId. Sending 487.")
            val socket = persistentSipSocket
            if (socket != null && !socket.isClosed) {
                try {
                    val rawViaBlock = extractAllViaHeaders(inviteMsg).ifBlank {
                        extractHeader(inviteMsg, "Via")?.let { "Via: $it\r\n" } ?: ""
                    }
                    val viaBlock = formatResponseViaHeaders(rawViaBlock, remoteAddr, remotePort)
                    val resp487 = "SIP/2.0 487 Request Terminated\r\n" +
                            viaBlock +
                            (extractHeader(inviteMsg, "From")?.let { "From: $it\r\n" } ?: "") +
                            (extractHeader(inviteMsg, "To")?.let { "To: $it;tag=term${Random().nextInt(99999)}\r\n" } ?: "") +
                            (extractHeader(inviteMsg, "Call-ID")?.let { "Call-ID: $it\r\n" } ?: "") +
                            (extractHeader(inviteMsg, "CSeq")?.let { "CSeq: $it\r\n" } ?: "") +
                            "Content-Length: 0\r\n\r\n"
                    val bytes = resp487.toByteArray(Charsets.ISO_8859_1)
                    socket.send(DatagramPacket(bytes, bytes.size, remoteAddr, remotePort))
                } catch (_: Exception) {}
            }
            return
        }

        if (isRetransmission) {
            val nowRetx = SystemClock.elapsedRealtime()
            if (incomingInviteRetransmitCount == 0) {
                firstIncomingRetransmitElapsedMs = nowRetx
            }
            lastIncomingInviteElapsedMs = nowRetx
            incomingInviteRetransmitCount++
            val cached = lastProvisionalResponse
            if (cached != null) {
                log("Resending cached response for retransmitted INVITE (Call-ID=$newCallId, retransmit=#$incomingInviteRetransmitCount)")
                try {
                    persistentSipSocket?.send(DatagramPacket(cached, cached.size, remoteAddr, remotePort))
                } catch (e: Exception) {
                    log("Failed to resend provisional response: ${e.message}")
                }
            } else if (!isAnswering && incomingSetupJob?.isActive != true) {
                sendStandard180Ringing(remoteAddr, remotePort)
            }
            return
        }

        // In-Dialog re-INVITE for ongoing call (Session timer / hold / codec update)
        val isReInvite = isInCall() && newCallId != null &&
                (newCallId.equals(incomingCallId, ignoreCase = true) || newCallId.equals(currentCallId, ignoreCase = true))
        if (isReInvite) {
            log("Received in-dialog re-INVITE for active call ($newCallId). Sending 200 OK...")
            handleInDialogReInvite(inviteMsg, remoteAddr, remotePort)
            return
        }

        // Block List Gate: silently reject blocked callers with SIP 603 Decline BEFORE ringing or notifying!
        val preFromHeader = extractHeader(inviteMsg, "From") ?: ""
        val preRawNumber = Regex("""sip:([^@>]+)""").find(preFromHeader)?.groupValues?.get(1) ?: ""
        val preCallerNumber = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(preRawNumber)
        val preCtx = appContext
        if (preCtx != null && preCallerNumber.isNotBlank() &&
            com.opentalk.android.storage.BlockListManager.isBlocked(preCtx, preCallerNumber)) {
            log("Blocked incoming call from $preCallerNumber (in Block List). Sending SIP 603 Decline silently (Call-ID=$newCallId).")
            recordEndedIncomingCallId(newCallId)
            val socket = persistentSipSocket
            if (socket != null && !socket.isClosed) {
                try {
                    val rawVia = extractAllViaHeaders(inviteMsg).ifBlank {
                        extractHeader(inviteMsg, "Via")?.let { "Via: $it\r\n" } ?: ""
                    }
                    val viaBlock = formatResponseViaHeaders(rawVia, remoteAddr, remotePort)
                    val resp603 = "SIP/2.0 603 Decline\r\n" +
                            viaBlock +
                            (extractHeader(inviteMsg, "From")?.let { "From: $it\r\n" } ?: "") +
                            (extractHeader(inviteMsg, "To")?.let { "To: $it;tag=blk${Random().nextInt(99999)}\r\n" } ?: "") +
                            (extractHeader(inviteMsg, "Call-ID")?.let { "Call-ID: $it\r\n" } ?: "") +
                            (extractHeader(inviteMsg, "CSeq")?.let { "CSeq: $it\r\n" } ?: "") +
                            "Reason: Q.850;cause=21;text=\"Call Rejected\"\r\n" +
                            "Content-Length: 0\r\n\r\n"
                    val bytes = resp603.toByteArray(Charsets.ISO_8859_1)
                    socket.send(DatagramPacket(bytes, bytes.size, remoteAddr, remotePort))
                } catch (_: Exception) {}
            }
            try {
                val rawNm = Regex(""""([^"]+)"""").find(preFromHeader)?.groupValues?.get(1) ?: preCallerNumber
                val cleanNm = com.opentalk.android.utils.ContactResolver.stripOperatorName(rawNm)
                val resolved = com.opentalk.android.utils.ContactResolver.resolveContactName(preCtx, preCallerNumber)
                val finalName = resolved ?: cleanNm.ifBlank { preCallerNumber }
                CallHistoryDbHelper.getInstance(preCtx).insertCall(
                    com.opentalk.android.models.CallRecord(
                        remoteNumber = preCallerNumber,
                        displayName = finalName,
                        type = com.opentalk.android.models.CallType.REJECTED,
                        timestamp = System.currentTimeMillis(),
                        durationSeconds = 0
                    )
                )
            } catch (_: Exception) {}
            return
        }

        // Pre-parse SDP, Contact, and Record-Route BEFORE claiming callStateLock so no field is ever half-initialized!
        val sdpBody = when {
            inviteMsg.contains("\r\n\r\n") -> inviteMsg.substringAfter("\r\n\r\n")
            inviteMsg.contains("\n\n") -> inviteMsg.substringAfter("\n\n")
            else -> ""
        }
        val parsedLocalTag = "tag${Random().nextInt(999999)}"
        val parsedContactUri = extractHeader(inviteMsg, "Contact")?.let {
            val bracketed = Regex("""<([^>]+)>""").find(it)?.groupValues?.get(1)
            if (!bracketed.isNullOrBlank()) {
                bracketed.trim()
            } else {
                it.trim().substringBefore(";").trim().removePrefix("<").removeSuffix(">")
            }
        }
        val parsedRouteSet = extractRouteSetFromResponse(inviteMsg).reversed()

        // Atomic busy/dialog claim under callStateLock (prevents simultaneous incoming + outgoing clobbering!)
        var isBusy = false
        var myCallGen = 0L
        synchronized(callStateLock) {
            if (isInCall() || (currentCallState == CallState.RINGING && newCallId != incomingCallId)) {
                isBusy = true
            } else {
                myCallGen = callGeneration.incrementAndGet()
                currentCallState = CallState.RINGING
                isCurrentCallIncoming = true
                isCallOnHold = false
                isDialogConfirmed = false
                incomingCallId = newCallId
                incomingViaHeaders = extractAllViaHeaders(inviteMsg)
                incomingVia = extractHeader(inviteMsg, "Via")
                incomingFrom = extractHeader(inviteMsg, "From")
                incomingTo = extractHeader(inviteMsg, "To")
                incomingCSeq = extractHeader(inviteMsg, "CSeq")
                incomingSdp = sdpBody
                incomingLocalTag = parsedLocalTag
                incomingContactUri = parsedContactUri
                incomingRouteSet = parsedRouteSet
                incomingRemoteAddress = remoteAddr
                incomingRemotePort = remotePort
                reInviteSdpVersion = 100
                val nowInvite = SystemClock.elapsedRealtime()
                incomingInviteReceivedElapsedMs = nowInvite
                lastIncomingInviteElapsedMs = nowInvite
                firstIncomingRetransmitElapsedMs = 0L
                incomingInviteRetransmitCount = 0
                val ua = extractHeader(inviteMsg, "User-Agent") ?: ""
                incomingIsSemuxGw = ua.contains("SemuxGW", ignoreCase = true) || inviteMsg.contains("s=SemuxGW", ignoreCase = true)
            }
        }

        if (isBusy) {
            log("Line is BUSY (currentCallState=$currentCallState). Sending SIP 486 Busy Here to $newCallId")
            sendBusyHereForInvite(inviteMsg, remoteAddr, remotePort)

            val fromHdr = extractHeader(inviteMsg, "From") ?: ""
            val rawNum = Regex("""sip:([^@>]+)""").find(fromHdr)?.groupValues?.get(1) ?: "Unknown"
            val rawNm = Regex(""""([^"]+)"""").find(fromHdr)?.groupValues?.get(1) ?: rawNum
            val busyCallerNum = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(rawNum)
            val isSameRingingCaller = (currentCallState == CallState.RINGING &&
                busyCallerNum == com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(activeCallNumber ?: ""))
            val busyCallerNm = com.opentalk.android.utils.ContactResolver.stripOperatorName(rawNm)
            val ctx = appContext
            if (ctx != null && !isSameRingingCaller) {
                try {
                    val resolvedName = com.opentalk.android.utils.ContactResolver.resolveContactName(ctx, busyCallerNum)
                    val finalName = resolvedName ?: busyCallerNm.ifBlank { busyCallerNum }
                    CallHistoryDbHelper.getInstance(ctx).insertCall(
                        com.opentalk.android.models.CallRecord(
                            remoteNumber = busyCallerNum,
                            displayName = finalName,
                            type = com.opentalk.android.models.CallType.MISSED,
                            timestamp = System.currentTimeMillis(),
                            durationSeconds = 0
                        )
                    )
                    com.opentalk.android.notifications.MissedCallNotifier.postMissedCall(ctx, busyCallerNum, finalName)
                } catch (_: Exception) {}
            }
            return
        }

        log("RAW INCOMING INVITE:\n$inviteMsg")

        val fromHeader = incomingFrom ?: ""
        val rawNumber = Regex("""sip:([^@>]+)""").find(fromHeader)?.groupValues?.get(1) ?: "Unknown"
        val callerNumber = com.opentalk.android.utils.PhoneNumberHelper.toCanonicalE164(rawNumber)
            .ifBlank { com.opentalk.android.utils.ContactResolver.cleanPhoneNumber(rawNumber) }

        val ctx = appContext
        activeCallNumber = callerNumber
        activeCallName = callerNumber

        log("Incoming Call from: $callerNumber")
        if (sdpBody.isNotBlank()) {
            log("Incoming Offer SDP:\n$sdpBody")
        }

        // RFC 3261 + RFC 3581: Send 100 Trying IMMEDIATELY (<1ms) BEFORE Contacts IPC or STUN
        // so the persistent SIP listener returns to socket.receive() instantly and
        // NEVER delays reading an incoming CANCEL or BYE!
        val snapIncomingViaHeaders = incomingViaHeaders
        val snapIncomingVia = incomingVia
        val snapIncomingFrom = incomingFrom
        val snapIncomingTo = incomingTo
        val snapIncomingCallId = incomingCallId
        val snapIncomingCSeq = incomingCSeq

        val rawViaBlock = if (snapIncomingViaHeaders.isNotBlank()) snapIncomingViaHeaders else "Via: $snapIncomingVia\r\n"
        val rfcViaBlock = formatResponseViaHeaders(rawViaBlock, remoteAddr, remotePort)
        try {
            val tryingMsg = "SIP/2.0 100 Trying\r\n" +
                    rfcViaBlock +
                    "From: $snapIncomingFrom\r\n" +
                    "To: $snapIncomingTo\r\n" +
                    "Call-ID: $snapIncomingCallId\r\n" +
                    "CSeq: $snapIncomingCSeq\r\n" +
                    "Content-Length: 0\r\n\r\n"
            val tryingBytes = tryingMsg.toByteArray(Charsets.ISO_8859_1)
            persistentSipSocket?.send(DatagramPacket(tryingBytes, tryingBytes.size, remoteAddr, remotePort))
            log("Sent 100 Trying (${rfcViaBlock.trim().replace("\r\n", " | ")})")
        } catch (_: Exception) {}

        fun isStillRingingSameCall(): Boolean = synchronized(callStateLock) {
            callGeneration.get() == myCallGen &&
                currentCallState == CallState.RINGING &&
                !isAnswering &&
                !sent200OkForIncoming &&
                newCallId != null &&
                newCallId.equals(incomingCallId, ignoreCase = true)
        }

        // Autonomous 30-second Ringing Watchdog + 2-second SIP NAT pinhole keepalive + Fast Dropped-CANCEL Detector:
        // Start immediately so even if setup is slow, NAT pinhole & dropped-CANCEL tracking are active from t=0!
        incomingRingingWatchdogJob?.cancel()
        incomingRingingWatchdogJob = engineScope.launch {
            val keepAliveBytes = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            for (tick in 1..100) { // 100 x 300ms = 30.0s (300ms detection granularity instead of 1000ms)
                delay(300L)
                if (!isStillRingingSameCall()) {
                    return@launch
                }
                val elapsedSinceInvite = SystemClock.elapsedRealtime() - lastIncomingInviteElapsedMs
                val retxCount = incomingInviteRetransmitCount
                val firstRetxAt = firstIncomingRetransmitElapsedMs
                val receivedAt = incomingInviteReceivedElapsedMs
                val firstPollGap = if (firstRetxAt > receivedAt) firstRetxAt - receivedAt else 0L
                val sawMultiPoll = retxCount >= 2 &&
                    firstRetxAt > 0L &&
                    firstPollGap >= 1_800L &&
                    (lastIncomingInviteElapsedMs - firstRetxAt) >= 2_000L
                // SemuxGW periodic polling (~3.1s) vs standard RFC 3261 Timer-A (500ms):
                // If even 1 poll arrived at 2.2s..4.5s on SemuxGW, we know the gateway polls periodically!
                val sawSingleSemuxPoll = incomingIsSemuxGw &&
                    retxCount == 1 &&
                    firstPollGap in 2_200L..4_500L

                val dropThresholdMs = when {
                    sawMultiPoll -> {
                        val avgCadence = ((lastIncomingInviteElapsedMs - firstRetxAt) / (retxCount - 1)).coerceIn(2_000L, 4_000L)
                        (avgCadence + 1_400L).coerceIn(3_800L, 5_000L)
                    }
                    sawSingleSemuxPoll -> (firstPollGap + 1_500L).coerceIn(4_000L, 5_500L)
                    else -> Long.MAX_VALUE
                }
                if (elapsedSinceInvite >= dropThresholdMs) {
                    log("Incoming INVITE polling cadence stopped (${elapsedSinceInvite}ms >= ${dropThresholdMs}ms, retransmits=$retxCount) — caller hung up & UDP CANCEL was dropped! Dismissing ringing immediately.")
                    handleIncomingCallMissed()
                    return@launch
                }
                if (tick < 100 && tick % 7 == 0) { // Every ~2.1s keep UDP NAT pinhole open
                    try {
                        val sock = persistentSipSocket
                        if (sock != null && !sock.isClosed) {
                            sock.send(DatagramPacket(keepAliveBytes, keepAliveBytes.size, remoteAddr, remotePort))
                        }
                    } catch (_: Exception) {}
                }
            }
            if (isStillRingingSameCall()) {
                log("Autonomous 30s incoming call ringing watchdog expired. Auto-dismissing call.")
                handleIncomingCallMissed()
            }
        }

        // Offload Contact lookup, STUN, 180 Ringing, and UI notification so
        // startPersistentSipListener immediately resumes socket.receive() for zero-latency CANCEL handling!
        incomingSetupJob?.cancel()
        incomingSetupJob = engineScope.launch {
            if (!isStillRingingSameCall()) return@launch

            val resolvedName = if (ctx != null) com.opentalk.android.utils.ContactResolver.resolveContactName(ctx, callerNumber) else null
            val callerName = resolvedName ?: callerNumber
            synchronized(callStateLock) {
                if (callGeneration.get() == myCallGen && currentCallState == CallState.RINGING) {
                    activeCallName = callerName
                }
            }
            if (!isStillRingingSameCall()) return@launch
            log("Incoming Call resolved: $callerName ($callerNumber)")

            if (!isStillRingingSameCall()) return@launch
            sendStandard180Ringing(remoteAddr, remotePort)

            if (!isStillRingingSameCall()) return@launch

            // Trigger Incoming Call via Foreground Service (Heads-Up & FullScreenIntent) & In-App Listener
            if (ctx != null) {
                SipVoipService.showIncomingCall(ctx, callerNumber, callerName)
            }

            // Notify call state synchronously with generation guard so a fast CANCEL/Decline can NEVER be overwritten!
            notifyCallState(CallState.RINGING, "Incoming Call...", expectedGen = myCallGen)
            engineScope.launch(Dispatchers.Main) {
                if (callGeneration.get() == myCallGen && currentCallState == CallState.RINGING) {
                    onIncomingCallListener?.invoke(callerNumber, callerName)
                }
            }
        }
    }

    private fun sendBusyHereForInvite(inviteMsg: String, remoteAddr: InetAddress, remotePort: Int) {
        val socket = persistentSipSocket ?: return
        try {
            val rawViaBlock = extractAllViaHeaders(inviteMsg).ifBlank {
                extractHeader(inviteMsg, "Via")?.let { "Via: $it\r\n" } ?: ""
            }
            val viaBlock = formatResponseViaHeaders(rawViaBlock, remoteAddr, remotePort)
            val from = extractHeader(inviteMsg, "From") ?: ""
            val to = extractHeader(inviteMsg, "To") ?: ""
            val toTagPart = ";tag=busy${Random().nextInt(999999)}"
            val callId = extractHeader(inviteMsg, "Call-ID") ?: ""
            val cseq = extractHeader(inviteMsg, "CSeq") ?: ""
            val busyMsg = "SIP/2.0 486 Busy Here\r\n" +
                    viaBlock +
                    "From: $from\r\n" +
                    "To: $to$toTagPart\r\n" +
                    "Call-ID: $callId\r\n" +
                    "CSeq: $cseq\r\n" +
                    "Content-Length: 0\r\n\r\n"
            val bytes = busyMsg.toByteArray(Charsets.ISO_8859_1)
            val packet = DatagramPacket(bytes, bytes.size, remoteAddr, remotePort)
            socket.send(packet)
            log("SIP 486 Busy Here sent to $remoteAddr:$remotePort for Call-ID: $callId")
        } catch (e: Exception) {
            log("Error sending 486 Busy Here: ${e.message}")
        }
    }

    private fun sendStandard180Ringing(remoteAddr: InetAddress, remotePort: Int) {
        val snapTo: String
        val snapFrom: String
        val snapLocalTag: String
        val snapCallId: String
        val snapCSeq: String
        val snapViaHeaders: String
        val snapVia: String
        synchronized(callStateLock) {
            if (incomingCallId == null || isAnswering || sent200OkForIncoming || currentCallState != CallState.RINGING) {
                return
            }
            snapTo = incomingTo ?: ""
            snapFrom = incomingFrom ?: ""
            snapLocalTag = incomingLocalTag ?: ""
            snapCallId = incomingCallId ?: return
            snapCSeq = incomingCSeq ?: ""
            snapViaHeaders = incomingViaHeaders
            snapVia = incomingVia ?: ""
        }
        val myUser = activeAccount?.username ?: "Z"
        val baseTo = snapTo.replace(Regex(";tag=[^;]+"), "")
        val localIp = getLocalAddressForTarget(remoteAddr)
        val (contactHost, contactPort) = getContactEndpoint(localIp, persistentSipSocket?.localPort ?: 5060, remoteAddr)
        val rawViaBlock = if (snapViaHeaders.isNotBlank()) snapViaHeaders else "Via: $snapVia\r\n"
        val viaBlock = formatResponseViaHeaders(rawViaBlock, remoteAddr, remotePort)
        val ringingMsg = "SIP/2.0 180 Ringing\r\n" +
                viaBlock +
                "From: $snapFrom\r\n" +
                "To: $baseTo;tag=$snapLocalTag\r\n" +
                "Call-ID: $snapCallId\r\n" +
                "CSeq: $snapCSeq\r\n" +
                "Contact: <sip:$myUser@$contactHost:$contactPort;transport=udp>\r\n" +
                "Content-Length: 0\r\n\r\n"

        try {
            val bytes = ringingMsg.toByteArray(Charsets.ISO_8859_1)
            synchronized(callStateLock) {
                if (incomingCallId != snapCallId || isAnswering || sent200OkForIncoming || currentCallState != CallState.RINGING) {
                    return
                }
                lastProvisionalResponse = bytes
            }
            persistentSipSocket?.send(DatagramPacket(bytes, bytes.size, remoteAddr, remotePort))
            log("Standard SIP 180 Ringing sent to $remoteAddr:$remotePort")
        } catch (_: Exception) {}
    }

    @Volatile private var isAnswering = false

    fun isAnsweringIncoming(): Boolean = isAnswering

    suspend fun answerIncomingCall(): Boolean = withContext(Dispatchers.IO) {
        // Atomically check state, deduplicate concurrent answer attempts via CompletableDeferred,
        // and snapshot ALL incoming dialog fields under callStateLock (eliminates null From/Call-ID races!).
        val inFlight: kotlinx.coroutines.CompletableDeferred<Boolean>?
        val myDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>?
        val snapCallId: String?
        val snapFrom: String?
        val snapTo: String?
        val snapCSeq: String?
        val snapLocalTag: String?
        val snapViaHeaders: String
        val snapVia: String?
        val snapSdp: String?
        val snapAddr: InetAddress?
        val snapPort: Int
        val snapGen: Long
        val snapContactUri: String?
        val snapRouteSet: List<String>

        synchronized(callStateLock) {
            if (currentCallState == CallState.CONNECTED) {
                log("Incoming call already active, skipping duplicate answer.")
                return@withContext true
            }
            val existing = activeAnswerDeferred
            if (isAnswering && existing != null) {
                inFlight = existing
                myDeferred = null
                snapCallId = null; snapFrom = null; snapTo = null; snapCSeq = null
                snapLocalTag = null; snapViaHeaders = ""; snapVia = null; snapSdp = null
                snapAddr = null; snapPort = 0; snapGen = 0L
                snapContactUri = null; snapRouteSet = emptyList()
            } else if (incomingCallId == null) {
                log("No active incoming call dialog to answer.")
                return@withContext false
            } else {
                isAnswering = true
                val created = kotlinx.coroutines.CompletableDeferred<Boolean>()
                activeAnswerDeferred = created
                inFlight = null
                myDeferred = created
                snapCallId = incomingCallId
                snapFrom = incomingFrom
                snapTo = incomingTo
                snapCSeq = incomingCSeq
                snapLocalTag = incomingLocalTag
                snapViaHeaders = incomingViaHeaders
                snapVia = incomingVia
                snapSdp = incomingSdp
                snapAddr = incomingRemoteAddress
                snapPort = incomingRemotePort
                snapGen = callGeneration.get()
                snapContactUri = incomingContactUri
                snapRouteSet = incomingRouteSet
            }
        }

        // If another caller (e.g. notification Accept + IncomingCallActivity) is already answering, AWAIT its real result!
        if (inFlight != null) {
            return@withContext try { inFlight.await() } catch (_: Exception) { false }
        }

        var result = false
        var ownedRtpSocket: DatagramSocket? = null
        var createdNewRtpSocket = false
        try {
            incomingSetupJob?.cancel()
            incomingSetupJob = null
            incomingRingingWatchdogJob?.cancel()
            incomingRingingWatchdogJob = null
            com.opentalk.android.IncomingCallActivity.stopRingtoneOnly()
            appContext?.let { SipVoipService.dismissIncomingCall(it) }

            lastProvisionalResponse = null
            val socket = persistentSipSocket ?: return@withContext false
            val targetAddr = snapAddr ?: return@withContext false
            val targetPort = snapPort
            if (snapCallId == null) return@withContext false

            val rtpSocket: DatagramSocket = synchronized(callStateLock) {
                if (incomingCallId != snapCallId || callGeneration.get() != snapGen) {
                    return@withContext false
                }
                val existingSock = activeRtpSocket?.takeIf { !it.isClosed }
                if (existingSock != null) {
                    existingSock
                } else {
                    createdNewRtpSocket = true
                    DatagramSocket().also { activeRtpSocket = it }
                }
            }
            ownedRtpSocket = rtpSocket
            val localIp = getLocalAddressForTarget(targetAddr)
            // Reuse cached STUN if already resolved on earlySocket (0ms); NEVER block answer 200 OK on 800ms STUN
            // because carrier/SemuxGW uses Symmetric RTP and punches NAT on our first 20ms RTP frame!
            val ansStun = if (!isIpv6Target(targetAddr)) {
                getActiveRtpStunForSocket(rtpSocket)
            } else null
            val remoteRtpAddr = snapSdp?.let { resolveRemoteMediaAddress(it, targetAddr) } ?: targetAddr
            val remoteRtpPort = snapSdp?.let { extractSdpAudioPort(it) } ?: 40000
            val codec = snapSdp?.let { extractSdpCodec(it) } ?: 8

            // Increment SDP o= version (100 in 183 -> 101 in 200 OK) per RFC 3264 so gateways switch sendonly -> sendrecv!
            val sdpVer = ++reInviteSdpVersion
            val ansCodecBlock = "$codec 101\r\n" +
                    (if (codec == 8) "a=rtpmap:8 PCMA/8000\r\n" else "a=rtpmap:0 PCMU/8000\r\n") +
                    "a=rtpmap:101 telephone-event/8000\r\n" +
                    "a=fmtp:101 0-16\r\n" +
                    "a=ptime:20\r\n"
            val sdp = buildAudioSdp(
                username = activeAccount?.username ?: "Z",
                sessionVer = sdpVer,
                sessionName = SDP_SESSION_NAME,
                targetAddress = targetAddr,
                localIp = localIp,
                localRtpPort = rtpSocket.localPort,
                rtpStun = ansStun,
                codecBlock = ansCodecBlock,
                direction = "sendrecv"
            )

            val baseTo = snapTo?.replace(Regex(";tag=[^;]+"), "") ?: ""
            val rawViaBlock = if (snapViaHeaders.isNotBlank()) snapViaHeaders else "Via: $snapVia\r\n"
            val viaBlock = formatResponseViaHeaders(rawViaBlock, targetAddr, targetPort)
            val (contactHost, contactPort) = getContactEndpoint(localIp, socket.localPort, targetAddr)
            val okMsg = "SIP/2.0 200 OK\r\n" +
                    viaBlock +
                    "From: $snapFrom\r\n" +
                    "To: $baseTo;tag=$snapLocalTag\r\n" +
                    "Call-ID: $snapCallId\r\n" +
                    "CSeq: $snapCSeq\r\n" +
                    "Contact: <sip:${activeAccount?.username}@$contactHost:$contactPort;transport=udp>\r\n" +
                    "User-Agent: $SIP_USER_AGENT\r\n" +
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: ${sdp.toByteArray(Charsets.ISO_8859_1).size}\r\n\r\n$sdp"

            val okBytes = okMsg.toByteArray(Charsets.ISO_8859_1)
            lastProvisionalResponse = okBytes
            val okPacket = DatagramPacket(okBytes, okBytes.size, targetAddr, targetPort)

            // RFC 3261 §13.3.1.4: Verify SIP ACK from server before opening CONNECTED call UI!
            // On a live call, SemuxGW / carrier sends ACK in ~30-150ms. If the caller already hung up
            // (e.g. during ringing and CANCEL was dropped), no ACK ever arrives -> abort cleanly
            // instead of answering a dead/ghost call with a fake ticking timer!
            val ackWait = kotlinx.coroutines.CompletableDeferred<String?>()
            incomingAckCallId = snapCallId
            incomingAckDeferred = ackWait

            synchronized(callStateLock) {
                if (incomingCallId != snapCallId || callGeneration.get() != snapGen) {
                    return@withContext false
                }
                sent200OkForIncoming = true
            }
            try { socket.send(okPacket) } catch (_: Exception) {}
            log("Sent 200 OK for Incoming Call (Codec: $codec, Target RTP: ${cleanHostAddress(remoteRtpAddr)}:$remoteRtpPort, sdpVer=$sdpVer). Awaiting SIP ACK...")

            // Up to 2.4s resilient mobile/VPN ACK window (live ACK resolves immediately in ~60-180ms);
            // only retransmit 200 OK BEFORE the final wait interval so every send is awaited.
            val retryDelays = longArrayOf(250L, 450L, 700L, 1000L)
            var confirmedAckMsg: String? = null
            for (idx in retryDelays.indices) {
                val res = withTimeoutOrNull(retryDelays[idx]) { ackWait.await() }
                if (res != null) {
                    confirmedAckMsg = res
                    break
                }
                if (ackWait.isCompleted || incomingCallId != snapCallId || callGeneration.get() != snapGen || socket.isClosed) {
                    break
                }
                if (idx < retryDelays.lastIndex) {
                    try { socket.send(okPacket) } catch (_: Exception) { break }
                }
            }

            if (confirmedAckMsg == null) {
                log("No SIP ACK received for 200 OK (Call-ID=$snapCallId) — caller already hung up or dialog expired! Aborting ghost answer.")
                recordEndedIncomingCallId(snapCallId)
                try { rtpSocket.close() } catch (_: Exception) {}
                var clearedUnloggedDeadDialog = false
                synchronized(callStateLock) {
                    if (incomingCallId == snapCallId) {
                        incomingCallId = null
                        incomingLocalTag = null
                        incomingContactUri = null
                        incomingRouteSet = emptyList()
                        incomingFrom = null
                        incomingTo = null
                        incomingRemoteAddress = null
                        incomingRemotePort = 0
                        incomingCSeq = null
                        incomingSdp = null
                        incomingViaHeaders = ""
                        incomingVia = ""
                        lastProvisionalResponse = null
                        activeRtpSocket = null
                        activeCallNumber = null
                        activeCallName = null
                        isCurrentCallIncoming = false
                        isDialogConfirmed = false
                        currentCallState = CallState.IDLE
                        clearedUnloggedDeadDialog = true
                    }
                }
                appContext?.let { ctx ->
                    SipVoipService.dismissIncomingCall(ctx)
                    AudioRouteManager.resetToNormal(ctx)
                    // If CANCEL was dropped (not already logged by handleIncomingCancel), record the missed call cleanly!
                    if (clearedUnloggedDeadDialog) {
                        engineScope.launch {
                            try {
                                val fromHeader = snapFrom ?: ""
                                val rawNumber = Regex("""sip:([^@>]+)""").find(fromHeader)?.groupValues?.get(1) ?: "Unknown"
                                val callerNumber = com.opentalk.android.utils.PhoneNumberHelper.toCanonicalE164(rawNumber)
                                    .ifBlank { com.opentalk.android.utils.ContactResolver.cleanPhoneNumber(rawNumber) }
                                val resolvedName = com.opentalk.android.utils.ContactResolver.resolveContactName(ctx, callerNumber)
                                val callerName = resolvedName ?: callerNumber
                                CallHistoryDbHelper.getInstance(ctx).insertCall(
                                    CallRecord(
                                        remoteNumber = callerNumber,
                                        displayName = callerName,
                                        type = CallType.MISSED,
                                        timestamp = System.currentTimeMillis(),
                                        durationSeconds = 0
                                    )
                                )
                                com.opentalk.android.notifications.MissedCallNotifier.postMissedCall(ctx, callerNumber, callerName)
                            } catch (_: Exception) {}
                        }
                    }
                }
                notifyCallState(CallState.ENDED, "Call Ended", expectedGen = snapGen)
                notifyCallState(CallState.IDLE, "Idle", expectedGen = snapGen)
                return@withContext false
            }

            // Support delayed-offer incoming calls (SDP answer carried inside ACK!)
            var effectiveRemoteRtpAddr = remoteRtpAddr
            var effectiveRemoteRtpPort = remoteRtpPort
            var effectiveCodec = codec
            if (confirmedAckMsg.contains("m=audio", ignoreCase = true)) {
                val ackMediaAddr = resolveRemoteMediaAddress(confirmedAckMsg, targetAddr)
                val ackPort = extractSdpAudioPortNullable(confirmedAckMsg)
                if (ackMediaAddr != null && ackPort != null && ackPort > 0) {
                    effectiveRemoteRtpAddr = ackMediaAddr
                    effectiveRemoteRtpPort = ackPort
                    effectiveCodec = extractSdpCodec(confirmedAckMsg)
                    log("Applied delayed-offer SDP from ACK -> ${cleanHostAddress(effectiveRemoteRtpAddr)}:$effectiveRemoteRtpPort (codec=$effectiveCodec)")
                }
            }

            log("Incoming call ACK confirmed! Transitioning UI to CONNECTED immediately and starting 2-way RTP audio...")
            lastProvisionalResponse = null

            val confirmed = synchronized(callStateLock) {
                if (incomingCallId != snapCallId || callGeneration.get() != snapGen) {
                    false
                } else {
                    isDialogConfirmed = true
                    currentCallState = CallState.CONNECTED
                    callConnectedStartTime = SystemClock.elapsedRealtime()
                    true
                }
            }
            if (!confirmed) {
                log("Aborted answerIncomingCall because call ended concurrently after ACK; sending cleanup BYE.")
                incoming200OkRetransmitJob?.cancel()
                if (!socket.isClosed) {
                    try {
                        val byeReqUri = (snapContactUri ?: Regex("""<([^>]+)>""").find(snapFrom ?: "")?.groupValues?.get(1)
                            ?: "sip:${formatHostForUri(targetAddr)}:$targetPort").replace("<", "").replace(">", "").trim()
                        val byeRouteHeaders = formatRouteHeaders(snapRouteSet)
                        val byeCSeq = synchronized(callStateLock) { ++incomingLocalCSeq }
                        val (byeViaHost, byeViaPort) = getViaEndpoint(localIp, socket.localPort, targetAddr)
                        val byeMsg = "BYE $byeReqUri SIP/2.0\r\n" +
                                "Via: SIP/2.0/UDP $byeViaHost:$byeViaPort;rport;branch=${generateBranch()}\r\n" +
                                "Max-Forwards: 70\r\n" +
                                byeRouteHeaders +
                                "To: $snapFrom\r\n" +
                                "From: $baseTo;tag=$snapLocalTag\r\n" +
                                "Call-ID: $snapCallId\r\n" +
                                "CSeq: $byeCSeq BYE\r\n" +
                                "Content-Length: 0\r\n\r\n"
                        val byeBytes = byeMsg.toByteArray(Charsets.ISO_8859_1)
                        socket.send(DatagramPacket(byeBytes, byeBytes.size, targetAddr, targetPort))
                    } catch (_: Exception) {}
                }
                return@withContext false
            }

            // Notify CONNECTED immediately (0ms UI wait) while AudioRecord/AudioTrack spin up asynchronously on IO!
            notifyCallState(CallState.CONNECTED, "In Call", expectedGen = snapGen)
            appContext?.let { SipVoipService.dismissIncomingCall(it) }

            engineScope.launch {
                appContext?.let { ctx ->
                    try {
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        am?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                    } catch (_: Exception) {}
                }
                val started = startRtpStreamerSafe(
                    expectedCallId = snapCallId,
                    isIncoming = true,
                    rtpSocket = rtpSocket,
                    remoteAddress = effectiveRemoteRtpAddr,
                    remotePort = effectiveRemoteRtpPort,
                    codec = effectiveCodec
                )
                if (!started) {
                    log("Async RTP streamer start aborted because call ended concurrently.")
                }
            }

            result = true
            true
        } catch (e: Exception) {
            log("Failed to answer incoming call: ${e.message}")
            false
        } finally {
            incomingAckDeferred = null
            incomingAckCallId = null
            var cleanedUpWedged = false
            synchronized(callStateLock) {
                isAnswering = false
                if (!result) {
                    sent200OkForIncoming = false
                }
                if (activeAnswerDeferred === myDeferred) {
                    activeAnswerDeferred = null
                }
                // If answerIncomingCall() failed/returned early before confirming, clear wedged RINGING state!
                if (!result && snapCallId != null && incomingCallId == snapCallId && currentCallState != CallState.CONNECTED) {
                    incomingCallId = null
                    incomingLocalTag = null
                    incomingContactUri = null
                    incomingRouteSet = emptyList()
                    incomingFrom = null
                    incomingTo = null
                    incomingRemoteAddress = null
                    incomingRemotePort = 0
                    incomingCSeq = null
                    incomingSdp = null
                    incomingViaHeaders = ""
                    incomingVia = ""
                    lastProvisionalResponse = null
                    activeRtpSocket = null
                    activeCallNumber = null
                    activeCallName = null
                    isCurrentCallIncoming = false
                    isDialogConfirmed = false
                    currentCallState = CallState.IDLE
                    cleanedUpWedged = true
                }
            }
            if (cleanedUpWedged) {
                try { ownedRtpSocket?.close() } catch (_: Exception) {}
                appContext?.let {
                    SipVoipService.dismissIncomingCall(it)
                    AudioRouteManager.resetToNormal(it)
                }
                notifyCallState(CallState.ENDED, "Call Ended", expectedGen = snapGen)
                notifyCallState(CallState.IDLE, "Idle", expectedGen = snapGen)
            } else if (!result && createdNewRtpSocket && ownedRtpSocket != null) {
                try { ownedRtpSocket.close() } catch (_: Exception) {}
                synchronized(callStateLock) {
                    if (activeRtpSocket === ownedRtpSocket) activeRtpSocket = null
                }
            }
            myDeferred?.complete(result)
        }
    }

    fun declineIncomingCall() {
        val savedCallId: String?
        val savedLocalTag: String?
        val savedViaHeaders: String
        val savedVia: String?
        val savedFrom: String?
        val savedTo: String?
        val savedCSeq: String?
        val targetAddr: InetAddress?
        val targetPort: Int
        val socketToClose: DatagramSocket?

        val declineGen: Long
        synchronized(callStateLock) {
            savedCallId = incomingCallId
            if (savedCallId == null || isDialogConfirmed || isAnswering || sent200OkForIncoming || currentCallState != CallState.RINGING) {
                // Idempotent & glare guard: never clobber an already-answered/answering call or insert duplicate REJECTED!
                return
            }
            savedLocalTag = incomingLocalTag
            savedViaHeaders = incomingViaHeaders
            savedVia = incomingVia
            savedFrom = incomingFrom
            savedTo = incomingTo
            savedCSeq = incomingCSeq
            targetAddr = incomingRemoteAddress
            targetPort = incomingRemotePort
            declineGen = callGeneration.get()
            socketToClose = activeRtpSocket

            incomingCallId = null
            incomingLocalTag = null
            incomingContactUri = null
            incomingRouteSet = emptyList()
            incomingFrom = null
            incomingTo = null
            incomingRemoteAddress = null
            incomingRemotePort = 0
            incomingCSeq = null
            incomingSdp = null
            incomingViaHeaders = ""
            incomingVia = ""
            activeRtpSocket = null
            activeCallNumber = null
            activeCallName = null
            currentCallState = CallState.IDLE
            isCurrentCallIncoming = false
            isDialogConfirmed = false
            lastProvisionalResponse = null
        }

        incomingAckDeferred?.complete(null)
        incomingSetupJob?.cancel()
        incomingSetupJob = null
        incomingRingingWatchdogJob?.cancel()
        incomingRingingWatchdogJob = null
        incoming200OkRetransmitJob?.cancel()
        incoming200OkRetransmitJob = null
        try { socketToClose?.close() } catch (_: Exception) {}

        CallProximityManager.forceRelease("SipEngine declineIncomingCall")
        recordEndedIncomingCallId(savedCallId)
        com.opentalk.android.IncomingCallActivity.stopRingingAndDismissImmediately()
        val ctx = appContext
        if (ctx != null) {
            SipVoipService.dismissIncomingCall(ctx)
            AudioRouteManager.resetToNormal(ctx)
            try {
                val fromHeader = savedFrom ?: ""
                val rawNumber = Regex("""sip:([^@>]+)""").find(fromHeader)?.groupValues?.get(1) ?: "Unknown"
                val callerNumber = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(rawNumber)
                val resolvedName = com.opentalk.android.utils.ContactResolver.resolveContactName(ctx, callerNumber)
                val callerName = resolvedName ?: callerNumber

                CallHistoryDbHelper.getInstance(ctx).insertCall(
                    CallRecord(
                        remoteNumber = callerNumber,
                        displayName = callerName,
                        type = CallType.REJECTED,
                        timestamp = System.currentTimeMillis(),
                        durationSeconds = 0
                    )
                )
            } catch (_: Exception) {}
        }

        notifyCallState(CallState.ENDED, "Call Declined", expectedGen = declineGen)
        notifyCallState(CallState.IDLE, "Idle", expectedGen = declineGen)

        val socket = persistentSipSocket ?: return
        if (targetAddr == null) return

        engineScope.launch {
            try {
                val cleanTo = (savedTo ?: "").replace(Regex(";tag=[^;]+"), "")
                val toTagPart = if (savedLocalTag != null) ";tag=$savedLocalTag" else ""
                val rawViaBlock = if (savedViaHeaders.isNotBlank()) savedViaHeaders else "Via: $savedVia\r\n"
                val viaBlock = formatResponseViaHeaders(rawViaBlock, targetAddr, targetPort)
                // Send 487 Request Terminated + Q.850 Cause 16 (Normal Call Clearing) instead of 486 Busy Here
                // so the telecom gateway/mobile operator disconnects cleanly without playing "Sorry / Busy" IVR!
                val termMsg = "SIP/2.0 487 Request Terminated\r\n" +
                        viaBlock +
                        "From: $savedFrom\r\n" +
                        "To: $cleanTo$toTagPart\r\n" +
                        "Call-ID: $savedCallId\r\n" +
                        "CSeq: $savedCSeq\r\n" +
                        "Reason: Q.850;cause=16;text=\"Normal Call Clearing\"\r\n" +
                        "Content-Length: 0\r\n\r\n"

                val bytes = termMsg.toByteArray(Charsets.ISO_8859_1)
                val pkt = DatagramPacket(bytes, bytes.size, targetAddr, targetPort)
                for (k in 0..1) {
                    try { socket.send(pkt) } catch (_: Exception) {}
                    if (k < 1) delay(15)
                }
                log("Sent 487 Request Terminated (Q.850 cause=16 Normal Call Clearing) for Declined Incoming Call")
            } catch (_: Exception) {}
        }
    }

    fun handleIncomingCallMissed() {
        val savedCallId: String?
        val savedLocalTag: String?
        val savedViaHeaders: String
        val savedVia: String?
        val savedFrom: String?
        val savedTo: String?
        val savedCSeq: String?
        val targetAddr: InetAddress?
        val targetPort: Int
        val missedGen: Long
        val socketToClose: DatagramSocket?

        synchronized(callStateLock) {
            savedCallId = incomingCallId
            if (savedCallId == null || isDialogConfirmed || isAnswering || sent200OkForIncoming || currentCallState != CallState.RINGING) {
                // Idempotent & glare guard: never clobber an answered/answering call or insert 2 missed rows!
                return
            }
            savedLocalTag = incomingLocalTag
            savedViaHeaders = incomingViaHeaders
            savedVia = incomingVia
            savedFrom = incomingFrom
            savedTo = incomingTo
            savedCSeq = incomingCSeq
            targetAddr = incomingRemoteAddress
            targetPort = incomingRemotePort
            missedGen = callGeneration.get()
            socketToClose = activeRtpSocket

            incomingCallId = null
            incomingLocalTag = null
            incomingContactUri = null
            incomingRouteSet = emptyList()
            incomingFrom = null
            incomingTo = null
            incomingRemoteAddress = null
            incomingRemotePort = 0
            incomingCSeq = null
            incomingSdp = null
            incomingViaHeaders = ""
            incomingVia = ""
            activeRtpSocket = null
            activeCallNumber = null
            activeCallName = null
            currentCallState = CallState.IDLE
            isCurrentCallIncoming = false
            isDialogConfirmed = false
            lastProvisionalResponse = null
        }

        incomingAckDeferred?.complete(null)
        incomingSetupJob?.cancel()
        incomingSetupJob = null
        incomingRingingWatchdogJob?.cancel()
        incomingRingingWatchdogJob = null
        incoming200OkRetransmitJob?.cancel()
        incoming200OkRetransmitJob = null
        try { socketToClose?.close() } catch (_: Exception) {}
        CallProximityManager.forceRelease("SipEngine handleIncomingCallMissed")

        recordEndedIncomingCallId(savedCallId)
        com.opentalk.android.IncomingCallActivity.stopRingingAndDismissImmediately()
        val ctx = appContext
        if (ctx != null) {
            SipVoipService.dismissIncomingCall(ctx)
            AudioRouteManager.resetToNormal(ctx)
            try {
                val fromHeader = savedFrom ?: ""
                val rawNumber = Regex("""sip:([^@>]+)""").find(fromHeader)?.groupValues?.get(1) ?: "Unknown"
                val callerNumber = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(rawNumber)
                val resolvedName = com.opentalk.android.utils.ContactResolver.resolveContactName(ctx, callerNumber)
                val callerName = resolvedName ?: callerNumber

                CallHistoryDbHelper.getInstance(ctx).insertCall(
                    CallRecord(
                        remoteNumber = callerNumber,
                        displayName = callerName,
                        type = CallType.MISSED,
                        timestamp = System.currentTimeMillis(),
                        durationSeconds = 0
                    )
                )
                com.opentalk.android.notifications.MissedCallNotifier.postMissedCall(ctx, callerNumber, callerName)
                log("Recorded missed incoming call from $callerName ($callerNumber)")
            } catch (_: Exception) {}

            try {
                val cancelBroadcast = android.content.Intent("com.opentalk.android.ACTION_CALL_CANCELLED").apply {
                    setPackage(ctx.packageName)
                }
                ctx.sendBroadcast(cancelBroadcast)
            } catch (_: Exception) {}
        }

        notifyCallState(CallState.ENDED, "Call Missed", expectedGen = missedGen)
        notifyCallState(CallState.IDLE, "Idle", expectedGen = missedGen)
        engineScope.launch(Dispatchers.Main) {
            incomingCallCancelledListeners.forEach {
                try { it.invoke() } catch (_: Exception) {}
            }
        }

        val socket = persistentSipSocket
        if (socket == null || targetAddr == null) return

        engineScope.launch {
            try {
                val cleanTo = (savedTo ?: "").replace(Regex(";tag=[^;]+"), "")
                val toTagPart = if (savedLocalTag != null) ";tag=$savedLocalTag" else ""
                val rawViaBlock = if (savedViaHeaders.isNotBlank()) savedViaHeaders else "Via: $savedVia\r\n"
                val viaBlock = formatResponseViaHeaders(rawViaBlock, targetAddr, targetPort)
                // Send 487 Request Terminated + Q.850 Cause 16 (Normal Call Clearing) instead of 486 Busy Here
                // so the telecom gateway/mobile operator disconnects cleanly without playing "Sorry / Busy" IVR!
                val termMsg = "SIP/2.0 487 Request Terminated\r\n" +
                        viaBlock +
                        "From: $savedFrom\r\n" +
                        "To: $cleanTo$toTagPart\r\n" +
                        "Call-ID: $savedCallId\r\n" +
                        "CSeq: $savedCSeq\r\n" +
                        "Reason: Q.850;cause=16;text=\"Normal Call Clearing\"\r\n" +
                        "Content-Length: 0\r\n\r\n"

                val bytes = termMsg.toByteArray(Charsets.ISO_8859_1)
                val pkt = DatagramPacket(bytes, bytes.size, targetAddr, targetPort)
                for (k in 0..1) {
                    try { socket.send(pkt) } catch (_: Exception) {}
                    if (k < 1) delay(15)
                }
                log("Sent 487 Request Terminated (Q.850 cause=16 Normal Call Clearing) for Timed Out Missed Call")
            } catch (_: Exception) {}
        }
    }

    fun unregister() {
        val hadActiveCall = isInCall() || hasActiveIncomingCall()
        if (hadActiveCall) {
            hangUp(sendByeOrCancel = true)
        }
        registrationGeneration.incrementAndGet()
        activeRegistrationJob?.cancel()
        activeRegistrationJob = null
        activeCallJob?.cancel()
        activeCallJob = null
        midCallHandoverJob?.cancel()
        midCallHandoverJob = null
        incomingRingingWatchdogJob?.cancel()
        incomingRingingWatchdogJob = null
        incoming200OkRetransmitJob?.cancel()
        incoming200OkRetransmitJob = null
        retryRegistrationJob?.cancel()
        retryRegistrationJob = null
        reRegisterJob?.cancel()
        reRegisterJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        sipListenerJob?.cancel()
        sipListenerJob = null

        // Snapshot NAT endpoints & resolved server address BEFORE clearing globals so Expires: 0 matches our registered Contact!
        val sockToClose = persistentSipSocket
        val acc = activeAccount
        val savedCallId = regCallId
        val nextCSeq = ++regCSeq
        val snapTargetAddr = lastResolvedServerAddress
        val snapViaAndContact = if (sockToClose != null && !sockToClose.isClosed && snapTargetAddr != null) {
            val localIp = getLocalAddressForTarget(snapTargetAddr)
            val localPort = sockToClose.localPort
            Pair(
                getViaEndpoint(localIp, localPort, snapTargetAddr),
                getContactEndpoint(localIp, localPort, snapTargetAddr)
            )
        } else null

        persistentSipSocket = null
        abortInFlightRegistrationSocket()
        lastRegisteredLocalIp = null
        lastRegisteredNetworkFingerprint = null
        regCallId = null
        regCSeq = 0
        publicIp = null
        publicPort = null
        stunMappedSocketLocalPort = -1
        serverMappedIp = null
        serverMappedPort = null
        activeNat64PrefixBytes = null
        preferIpv4FallbackForCurrentNetwork = false
        cachedResolvedHost = null
        cachedResolvedTimestampMs = 0L
        lastResolvedServerAddress = null
        activeAccount = null
        currentStatus = RegistrationStatus.UNREGISTERED

        if (sockToClose != null && !sockToClose.isClosed && acc != null && acc.username.isNotBlank() && acc.server.isNotBlank()) {
            engineScope.launch {
                try {
                    if (hadActiveCall) {
                        // Allow async BYE/CANCEL packet from hangUp() to leave sockToClose before de-registering & closing
                        delay(150L)
                    }
                    val serverHost = acc.server.trim()
                    val targetAddr = snapTargetAddr ?: try { InetAddress.getByName(knownIpFallbacks[serverHost.lowercase()] ?: serverHost) } catch (_: Exception) { null }
                    if (targetAddr != null) {
                        val (vHost, vPort) = snapViaAndContact?.first ?: Pair(getLocalAddressForTarget(targetAddr), sockToClose.localPort)
                        val (cHost, cPort) = snapViaAndContact?.second ?: Pair(vHost, vPort)
                        val unregMsg = "REGISTER sip:$serverHost SIP/2.0\r\n" +
                                "Via: SIP/2.0/UDP $vHost:$vPort;rport;branch=${generateBranch()}\r\n" +
                                "Max-Forwards: 70\r\n" +
                                "To: <sip:${acc.username.trim()}@$serverHost>\r\n" +
                                "From: <sip:${acc.username.trim()}@$serverHost>;tag=unreg${Random().nextInt(999999)}\r\n" +
                                "Call-ID: ${savedCallId ?: generateOpaqueCallId()}\r\n" +
                                "CSeq: $nextCSeq REGISTER\r\n" +
                                "Contact: <sip:${acc.username.trim()}@$cHost:$cPort;transport=udp>\r\n" +
                                "User-Agent: $SIP_USER_AGENT\r\n" +
                                "Expires: 0\r\n" +
                                "Content-Length: 0\r\n\r\n"
                        val bytes = unregMsg.toByteArray(Charsets.ISO_8859_1)
                        sockToClose.send(DatagramPacket(bytes, bytes.size, targetAddr, acc.port))
                    }
                } catch (_: Exception) {
                } finally {
                    try { sockToClose.close() } catch (_: Exception) {}
                }
            }
        } else if (sockToClose != null && !sockToClose.isClosed && hadActiveCall) {
            engineScope.launch {
                delay(150L)
                try { sockToClose.close() } catch (_: Exception) {}
            }
        } else {
            try { sockToClose?.close() } catch (_: Exception) {}
        }

        val ctx = appContext
        if (ctx != null) {
            SipVoipService.stopService(ctx)
        }
        log("Cleanly unregistered from SIP server")
        notifyStatus(RegistrationStatus.UNREGISTERED, "Not Registered")
    }

    // =========================================================================
    // SIP OUTGOING CALL & AUDIO
    // =========================================================================

    fun startOutgoingCall(destinationNumber: String): Job {
        val hadActiveCall = synchronized(callStateLock) {
            (currentCallState == CallState.CONNECTING ||
                currentCallState == CallState.RINGING ||
                currentCallState == CallState.CONNECTED) &&
                (currentCallId != null || incomingCallId != null)
        }
        // CRITICAL: Terminate any prior active call BEFORE clearing isDialogConfirmed or assigning activeCallJob,
        // so hangUp() sends BYE (for confirmed calls) / CANCEL (for ringing) instead of cancelling the NEW call!
        if (hadActiveCall) {
            log("Previous call still active. Terminating previous call before placing new call to $destinationNumber...")
            hangUp(sendByeOrCancel = true)
        } else {
            activeCallJob?.cancel()
            activeCallJob = null
        }
        synchronized(callStateLock) {
            val cleanDest = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(destinationNumber)
            if (cleanDest.isNotBlank()) {
                activeCallNumber = cleanDest
            }
            // Synchronously transition to CONNECTING on the calling thread BEFORE CallActivity.onResume() runs!
            isHangingUp = false
            isCurrentCallIncoming = false
            isCallOnHold = false
            isCallMuted = false
            sent200OkForIncoming = false
            hasSessionProgress = false
            isDialogConfirmed = false
            currentCallState = CallState.CONNECTING
        }
        val job = engineScope.launch {
            initiateCall(destinationNumber, hadPriorCallToTerminate = hadActiveCall)
        }
        activeCallJob = job
        return job
    }

    suspend fun initiateCall(
        destinationNumber: String,
        hadPriorCallToTerminate: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val account = activeAccount ?: (appContext?.let { com.opentalk.android.storage.SecureAccountManager(it).getAccount() })
        if (account == null || account.username.isBlank() || account.server.isBlank()) {
            log("Cannot place call: No active SIP account configured")
            notifyCallState(CallState.FAILED, "No Account")
            synchronized(callStateLock) { currentCallState = CallState.IDLE }
            return@withContext false
        }
        activeAccount = account

        if (!isNetworkConnected(appContext)) {
            log("Cannot place call: Device is OFFLINE")
            notifyCallState(CallState.FAILED, "No Internet")
            synchronized(callStateLock) { currentCallState = CallState.IDLE }
            return@withContext false
        }

        // Clean up any lingering prior call (when initiateCall was invoked directly) without self-cancelling activeCallJob!
        if (currentCallId != null || incomingCallId != null) {
            log("Previous call still active (state=$currentCallState, callId=$currentCallId). Terminating previous call before placing new call...")
            hangUp(sendByeOrCancel = true, cancelActiveCallJob = false)
            delay(150)
        } else if (hadPriorCallToTerminate) {
            // Brief settle pause after startOutgoingCall() sent BYE/CANCEL for the prior call
            delay(120)
        }

        val cleanDest = com.opentalk.android.utils.ContactResolver.normalizePhoneNumber(destinationNumber)
        if (cleanDest.isBlank()) {
            log("Cannot place call: Destination number is empty after normalization")
            notifyCallState(CallState.FAILED, "Invalid Number")
            synchronized(callStateLock) { currentCallState = CallState.IDLE }
            return@withContext false
        }

        val myCallGen: Long
        synchronized(callStateLock) {
            myCallGen = callGeneration.incrementAndGet()
            isHangingUp = false
            isCurrentCallIncoming = false
            hasSessionProgress = false
            isDialogConfirmed = false
            currentCallState = CallState.CONNECTING
            reInviteSdpVersion = 100
        }
        var myCallId: String? = null
        var createdRtpSocket: DatagramSocket? = null

        try {
            val serverHost = account.server.trim()
            var targetAddress = resolveServerAddress(serverHost)
            activeTargetAddress = targetAddress
            activeTargetPort = account.port

            val currentLocalIp = getLocalAddressForTarget(targetAddress)
            val currentFingerprint = getNetworkFingerprint(appContext)
            val ipMismatch = (lastRegisteredLocalIp != null && currentLocalIp != "127.0.0.1" && lastRegisteredLocalIp != currentLocalIp)
            val fingerprintMismatch = (lastRegisteredNetworkFingerprint != null &&
                isValidNetworkFingerprint(currentFingerprint) &&
                lastRegisteredNetworkFingerprint != currentFingerprint)
            val networkMismatch = ipMismatch || fingerprintMismatch

            var initialSocket = persistentSipSocket
            val listenerDead = (sipListenerJob?.isActive != true)
            if (initialSocket == null || initialSocket.isClosed || networkMismatch || currentStatus != RegistrationStatus.REGISTERED || listenerDead) {
                log("SIP socket unready, listener inactive, or network changed (cachedIp=$lastRegisteredLocalIp, currentIp=$currentLocalIp, sigMismatch=$fingerprintMismatch, status=$currentStatus, listenerActive=${sipListenerJob?.isActive}). Re-establishing on active network before call...")
                val prevRegJob = activeRegistrationJob
                prevRegJob?.cancel()
                activeRegistrationJob = null
                abortInFlightRegistrationSocket()
                try { withTimeoutOrNull(800L) { prevRegJob?.join() } } catch (_: Exception) {}
                try { initialSocket?.close() } catch (_: Exception) {}
                persistentSipSocket = null
                lastRegisteredLocalIp = null
                lastRegisteredNetworkFingerprint = null
                regCallId = null
                regCSeq = 0
                publicIp = null
                publicPort = null
                stunMappedSocketLocalPort = -1
                serverMappedIp = null
                serverMappedPort = null
                cachedResolvedHost = null
                cachedResolvedTimestampMs = 0L
                if (networkMismatch) {
                    preferIpv4FallbackForCurrentNetwork = false
                }
                registrationGeneration.incrementAndGet()
                register(account, force = true)
                initialSocket = persistentSipSocket
                // Refresh targetAddress in case register() triggered IPv6 <-> IPv4 failover!
                targetAddress = lastResolvedServerAddress ?: resolveServerAddress(serverHost, forceRefresh = true)
                activeTargetAddress = targetAddress
            }
            if (initialSocket == null || initialSocket.isClosed) {
                log("Cannot place call: SIP connection to server could not be established")
                notifyCallState(CallState.FAILED, "No Connection", expectedGen = myCallGen)
                synchronized(callStateLock) {
                    if (callGeneration.get() == myCallGen) currentCallState = CallState.IDLE
                }
                return@withContext false
            }
            var socket: DatagramSocket = initialSocket
            if (sipListenerJob?.isActive != true) {
                log("Persistent SIP listener was not active. Starting listener on socket...")
                startPersistentSipListener(socket)
            }
            synchronized(callStateLock) {
                activeCallSocket = socket
            }

            val rtpSocket = DatagramSocket()
            createdRtpSocket = rtpSocket
            activeRtpSocket = rtpSocket

            var isV6 = isIpv6Target(targetAddress)
            var localIp = getLocalAddressForTarget(targetAddress)
            var localPort = socket.localPort
            val localRtpPort = rtpSocket.localPort

            // On IPv4 networks, discover RTP socket's public IPv4 & NAT port via fast STUN (~40ms).
            // Close rtpSocket cleanly if cancelled mid-STUN so no file descriptor leaks!
            val rtpStun = try {
                if (!isV6) {
                    resolveMappedEndpointViaStun(rtpSocket, updateSipGlobals = false, maxServers = 2, timeoutMs = 450)?.also {
                        activeRtpStunEndpoint = it
                        activeRtpStunLocalPort = rtpSocket.localPort
                    }
                } else null
            } catch (ce: CancellationException) {
                try { rtpSocket.close() } catch (_: Exception) {}
                synchronized(callStateLock) {
                    if (activeRtpSocket === rtpSocket) activeRtpSocket = null
                }
                throw ce
            }

            try {
                currentCoroutineContext().ensureActive()
            } catch (ce: CancellationException) {
                try { rtpSocket.close() } catch (_: Exception) {}
                synchronized(callStateLock) {
                    if (activeRtpSocket === rtpSocket) activeRtpSocket = null
                }
                throw ce
            }

            val (viaHost, viaPort) = getViaEndpoint(localIp, localPort, targetAddress)
            val (contactHost, contactPort) = getContactEndpoint(localIp, localPort, targetAddress)
            var activeViaHost = viaHost
            var activeViaPort = viaPort
            var activeContactHost = contactHost
            var activeContactPort = contactPort

            val username = account.username.trim()
            val authUser = account.effectiveAuthUser
            val password = account.password

            val resolvedDestName = appContext?.let { com.opentalk.android.utils.ContactResolver.resolveContactName(it, cleanDest) } ?: cleanDest

            val callId = generateOpaqueCallId()
            val fromTag = "tag${Random().nextInt(999999)}"
            val sipDialDest = com.opentalk.android.utils.PhoneNumberHelper.toSipDialString(cleanDest).ifBlank { cleanDest }
            val requestUri = "sip:$sipDialDest@$serverHost"

            val branch1 = generateBranch()
            val fromHeader = if (account.displayName.isNotBlank() && account.displayName != "OpenTalk" && account.displayName != "BanglaCalls" && account.displayName != "Sarker Communication" && account.displayName != "Sarker" && account.displayName != username) {
                "From: \"${account.displayName.trim()}\" <sip:$username@$serverHost>;tag=$fromTag\r\n"
            } else {
                "From: <sip:$username@$serverHost>;tag=$fromTag\r\n"
            }

            // Atomic post-STUN / post-IPC check right before publishing currentCallId & sending INVITE!
            var abortedBeforeInvite = false
            synchronized(callStateLock) {
                if (isHangingUp || callGeneration.get() != myCallGen || currentCallState != CallState.CONNECTING) {
                    abortedBeforeInvite = true
                } else {
                    myCallId = callId
                    activeCallNumber = cleanDest
                    activeCallName = resolvedDestName
                    currentCallId = callId
                    currentFromTag = fromTag
                    currentToTag = null
                    currentCallUri = requestUri
                    activeCallBranch = branch1
                    activeCallCSeq = 1
                    activeCallContactUri = null
                    activeCallFromHeader = fromHeader
                    activeCallRouteSet = emptyList()
                    last200OkAckBytes = null
                    last200OkAckAddress = null
                }
            }
            if (abortedBeforeInvite) {
                log("Outgoing call aborted before INVITE send (hung up or superseded during setup).")
                try { rtpSocket.close() } catch (_: Exception) {}
                synchronized(callStateLock) {
                    if (activeRtpSocket === rtpSocket) activeRtpSocket = null
                }
                return@withContext false
            }

            // 1. Construct SDP offering G.711a (PCMA) first (telecom standard in Bangladesh) and G.711u (PCMU) second
            val outCodecBlock = "8 0 101\r\n" +
                    "a=rtpmap:8 PCMA/8000\r\n" +
                    "a=rtpmap:0 PCMU/8000\r\n" +
                    "a=rtpmap:101 telephone-event/8000\r\n" +
                    "a=fmtp:101 0-16\r\n"
            val sdp = buildAudioSdp(
                username = username,
                sessionVer = 100,
                sessionName = SDP_SESSION_NAME,
                targetAddress = targetAddress,
                localIp = localIp,
                localRtpPort = localRtpPort,
                rtpStun = rtpStun,
                codecBlock = outCodecBlock,
                direction = "sendrecv"
            )
            val sdpByteLen = sdp.toByteArray(Charsets.ISO_8859_1).size

            // 2. Send Initial INVITE (CSeq: 1 INVITE) over persistent registered SIP socket
            val invite1 = "INVITE $requestUri SIP/2.0\r\n" +
                    "Via: SIP/2.0/UDP $viaHost:$viaPort;rport;branch=$branch1\r\n" +
                    "Max-Forwards: 70\r\n" +
                    "To: <$requestUri>\r\n" +
                    fromHeader +
                    "Call-ID: $callId\r\n" +
                    "CSeq: 1 INVITE\r\n" +
                    "Contact: <sip:$username@$contactHost:$contactPort;transport=udp>\r\n" +
                    "Allow: INVITE, ACK, CANCEL, BYE, NOTIFY, REFER, OPTIONS, INFO\r\n" +
                    "User-Agent: $SIP_USER_AGENT\r\n" +
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: $sdpByteLen\r\n\r\n" + sdp

            // Drain any lingering messages from prior calls
            while (outgoingCallResponseChannel.tryReceive().isSuccess) {}

            val data1 = invite1.toByteArray(Charsets.ISO_8859_1)
            socket.send(DatagramPacket(data1, data1.size, targetAddress, account.port))
            log("INVITE sent for $cleanDest to ${formatHostForUri(targetAddress)}:${account.port} (ipv6=$isV6, Call-ID=$callId, CSeq=1, via=$viaHost:$viaPort, localRtpPort=$localRtpPort, branch=$branch1)...")
            notifyCallState(CallState.CONNECTING, "Calling...", expectedGen = myCallGen)

            var currentInviteCSeq = 1
            var currentInviteBranch = branch1
            var currentInviteData = data1
            var authChallengeCount = 0
            val sentInviteBranches = mutableMapOf(1 to branch1)

            val callStartTime = SystemClock.elapsedRealtime()
            var lastInviteSentTime = callStartTime
            var lastKeepaliveSentTime = callStartTime
            var inviteRetransmitCount = 0
            val maxInviteRetransmits = 5
            val inviteRetransmitIntervals = longArrayOf(1000L, 2000L, 4000L, 4000L, 4000L)
            var hasReceivedProvisional = false

            fun refreshSwappedSocketIfAvailable(): Boolean {
                val replacement = persistentSipSocket
                if (replacement != null && !replacement.isClosed && callGeneration.get() == myCallGen && !isHangingUp) {
                    socket = replacement
                    activeTargetAddress?.let { targetAddress = it }
                    isV6 = isIpv6Target(targetAddress)
                    localIp = getLocalAddressForTarget(targetAddress)
                    localPort = socket.localPort
                    val (vh, vp) = getViaEndpoint(localIp, localPort, targetAddress)
                    val (ch, cp) = getContactEndpoint(localIp, localPort, targetAddress)
                    activeViaHost = vh
                    activeViaPort = vp
                    activeContactHost = ch
                    activeContactPort = cp
                    synchronized(callStateLock) {
                        if (callGeneration.get() == myCallGen) activeCallSocket = socket
                    }
                    log("Outgoing call SIP socket swapped during handover; refreshed socket & endpoints (via=$activeViaHost:$activeViaPort).")
                    return true
                }
                return false
            }

            fun sendOnCallSocket(bytes: ByteArray) {
                try {
                    socket.send(DatagramPacket(bytes, bytes.size, targetAddress, account.port))
                } catch (se: java.net.SocketException) {
                    if (!isHangingUp && refreshSwappedSocketIfAvailable()) {
                        socket.send(DatagramPacket(bytes, bytes.size, targetAddress, account.port))
                    } else {
                        throw se
                    }
                }
            }

            // 3. Receive Loop: RFC 3261 strict CSeq & branch transaction state machine
            while (!isHangingUp && callGeneration.get() == myCallGen && currentCallId == callId) {
                currentCoroutineContext().ensureActive()
                if (socket.isClosed) {
                    if (!refreshSwappedSocketIfAvailable()) {
                        log("Outgoing call SIP socket closed unexpectedly during setup. Failing cleanly instead of hanging on Calling...")
                        notifyCallState(CallState.FAILED, "Network Interrupted", expectedGen = myCallGen)
                        hangUp(sendByeOrCancel = false)
                        return@withContext false
                    }
                }
                val now = SystemClock.elapsedRealtime()

                // Overall call setup timeout (60s)
                if (now - callStartTime > 60_000L) {
                    log("Call setup timed out after 60 seconds")
                    notifyCallState(CallState.FAILED, if (hasReceivedProvisional) "No Answer" else "Not Connected", expectedGen = myCallGen)
                    hangUp()
                    return@withContext false
                }

                // If no provisional or final response was ever received for current INVITE after exhausting retransmissions:
                if (!hasReceivedProvisional && inviteRetransmitCount >= maxInviteRetransmits && (now - lastInviteSentTime > 4000L)) {
                    log("No response received from SIP server for CSeq $currentInviteCSeq after $maxInviteRetransmits retransmissions. Server unreachable.")
                    notifyCallState(CallState.FAILED, "Not Connected", expectedGen = myCallGen)
                    hangUp()
                    return@withContext false
                }

                val response: String? = try {
                    withTimeoutOrNull(400L) {
                        outgoingCallResponseChannel.receive()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }

                val loopNow = SystemClock.elapsedRealtime()

                // Outgoing call NAT pinhole keepalive every 1.2s while waiting for remote answer
                if (loopNow - lastKeepaliveSentTime >= 1200L) {
                    lastKeepaliveSentTime = loopNow
                    try {
                        val ping = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                        sendOnCallSocket(ping)
                    } catch (_: Exception) {}
                }

                if (response == null) {
                    if (!hasReceivedProvisional && inviteRetransmitCount < maxInviteRetransmits) {
                        if (loopNow - lastInviteSentTime >= inviteRetransmitIntervals[inviteRetransmitCount]) {
                            inviteRetransmitCount++
                            lastInviteSentTime = loopNow
                            sendOnCallSocket(currentInviteData)
                            log("Retransmitting INVITE (CSeq=$currentInviteCSeq, branch=$currentInviteBranch, attempt $inviteRetransmitCount)...")
                        }
                    }
                    continue
                }

                val cseqHeader = extractHeader(response, "CSeq") ?: ""
                val (respCSeqNum, respCSeqMethod) = parseCSeq(cseqHeader)
                val code = extractStatusCode(response)
                val reasonHeader = extractHeader(response, "Reason") ?: ""
                val warningHeader = extractHeader(response, "Warning") ?: ""
                log("Call signaling response: $code ($cseqHeader) [activeCSeq=$currentInviteCSeq] reason=[$reasonHeader] warning=[$warningHeader]")
                log("RAW SIP RESPONSE:\n$response")

                // Learn server-confirmed Via received/rport if returned (IPv4 only)
                extractHeader(response, "Via")?.let { parseViaReceivedAndRport(it, targetAddress) }

                // Discard responses for non-INVITE methods (CANCEL, BYE, OPTIONS, etc.)
                if (respCSeqMethod.isNotEmpty() && respCSeqMethod != "INVITE") {
                    log("Ignoring response $code for non-INVITE transaction ($cseqHeader)")
                    continue
                }

                // STRICT TRANSACTION ISOLATION (Prevents duplicate 401 -> double CSeq 2 INVITE & stale 100 Trying suppression!)
                if (respCSeqNum != null && respCSeqNum != currentInviteCSeq) {
                    if (code in 300..699) {
                        // RFC 3261 §17.1.1.3: ACK retransmitted/stale final response for superseded CSeq so server stops retransmitting,
                        // but NEVER send another INVITE and NEVER abort our current transaction!
                        val oldBranch = sentInviteBranches[respCSeqNum] ?: branch1
                        sendNon2xxAck(
                            socket = socket,
                            requestUri = requestUri,
                            response = response,
                            viaHost = activeViaHost,
                            viaPort = activeViaPort,
                            contactHost = activeContactHost,
                            contactPort = activeContactPort,
                            ackBranch = oldBranch,
                            ackCSeq = respCSeqNum,
                            fromHeader = fromHeader,
                            callId = callId,
                            targetAddress = targetAddress,
                            targetPort = account.port
                        )
                        log("ACKed duplicate/stale $code response for superseded CSeq $respCSeqNum (active CSeq is $currentInviteCSeq). Ignoring.")
                    } else {
                        log("Ignoring stale $code response for superseded CSeq $respCSeqNum (active CSeq is $currentInviteCSeq).")
                    }
                    continue
                }

                when (code) {
                    100 -> {
                        hasReceivedProvisional = true
                        log("Server trying call (100 Trying for CSeq $currentInviteCSeq)...")
                        // 100 Trying keeps UI on "Calling..." without downgrading later 183/180 states
                        if (!hasSessionProgress && currentCallState != CallState.RINGING && currentCallState != CallState.CONNECTED) {
                            notifyCallState(CallState.CONNECTING, "Calling...", expectedGen = myCallGen)
                        }
                    }
                    401, 407 -> {
                        // Send RFC 3261 ACK for the 401/407 challenge matching exact branch & CSeq
                        val toHeader = extractHeader(response, "To") ?: "<$requestUri>"
                        val ack = "ACK $requestUri SIP/2.0\r\n" +
                                "Via: SIP/2.0/UDP $activeViaHost:$activeViaPort;rport;branch=$currentInviteBranch\r\n" +
                                "Max-Forwards: 70\r\n" +
                                "To: $toHeader\r\n" +
                                fromHeader +
                                "Call-ID: $callId\r\n" +
                                "CSeq: $currentInviteCSeq ACK\r\n" +
                                "Contact: <sip:$username@$activeContactHost:$activeContactPort;transport=udp>\r\n" +
                                "Content-Length: 0\r\n\r\n"
                        val ackData = ack.toByteArray(Charsets.ISO_8859_1)
                        sendOnCallSocket(ackData)

                        if (authChallengeCount >= 2) {
                            log("Call authentication failed after $authChallengeCount challenge retries ($code).")
                            notifyCallState(CallState.FAILED, "Authentication Failed", expectedGen = myCallGen)
                            hangUp(sendByeOrCancel = false)
                            return@withContext false
                        }
                        authChallengeCount++

                        val authHeader = if (code == 407) {
                            extractHeader(response, "Proxy-Authenticate") ?: extractHeader(response, "WWW-Authenticate")
                        } else {
                            extractHeader(response, "WWW-Authenticate") ?: extractHeader(response, "Proxy-Authenticate")
                        } ?: throw Exception("Challenge code $code without auth header")

                        val realm = extractAuthParam(authHeader, "realm") ?: "semux.io"
                        val nonce = extractAuthParam(authHeader, "nonce") ?: ""
                        val qop = extractAuthParam(authHeader, "qop")
                        val opaque = extractAuthParam(authHeader, "opaque")

                        val ha1 = md5("$authUser:$realm:$password")
                        val ha2 = md5("INVITE:$requestUri")
                        val cnonce = "%08x".format(Random().nextInt(0x7fffffff))
                        val nc = "00000001"
                        val useQopAuth = hasAuthQop(qop)

                        val digestResponse = if (useQopAuth) {
                            md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
                        } else {
                            md5("$ha1:$nonce:$ha2")
                        }

                        val authHeaderName = if (code == 407) "Proxy-Authorization" else "Authorization"
                        val authHeaderVal = StringBuilder(
                            "Digest username=\"$authUser\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$requestUri\", response=\"$digestResponse\", algorithm=MD5"
                        )
                        if (useQopAuth) {
                            authHeaderVal.append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
                        }
                        if (opaque != null) {
                            authHeaderVal.append(", opaque=\"$opaque\"")
                        }

                        // Increment CSeq strictly (1 -> 2, or 2 -> 3 if secondary challenge) with fresh branch
                        val nextCSeq = currentInviteCSeq + 1
                        val nextBranch = generateBranch()
                        val (authViaHost, authViaPort) = getViaEndpoint(localIp, localPort, targetAddress)
                        val (authContactHost, authContactPort) = getContactEndpoint(localIp, localPort, targetAddress)
                        activeViaHost = authViaHost
                        activeViaPort = authViaPort
                        activeContactHost = authContactHost
                        activeContactPort = authContactPort
                        val authSdp = buildAudioSdp(
                            username = username,
                            sessionVer = 100,
                            sessionName = SDP_SESSION_NAME,
                            targetAddress = targetAddress,
                            localIp = localIp,
                            localRtpPort = localRtpPort,
                            rtpStun = rtpStun,
                            codecBlock = outCodecBlock,
                            direction = "sendrecv"
                        )
                        val authSdpByteLen = authSdp.toByteArray(Charsets.ISO_8859_1).size

                        currentInviteCSeq = nextCSeq
                        currentInviteBranch = nextBranch
                        activeCallCSeq = nextCSeq
                        activeCallBranch = nextBranch
                        sentInviteBranches[nextCSeq] = nextBranch

                        val nextInvite = "INVITE $requestUri SIP/2.0\r\n" +
                                "Via: SIP/2.0/UDP $authViaHost:$authViaPort;rport;branch=$nextBranch\r\n" +
                                "Max-Forwards: 70\r\n" +
                                "To: <$requestUri>\r\n" +
                                fromHeader +
                                "Call-ID: $callId\r\n" +
                                "CSeq: $nextCSeq INVITE\r\n" +
                                "Contact: <sip:$username@$authContactHost:$authContactPort;transport=udp>\r\n" +
                                "Allow: INVITE, ACK, CANCEL, BYE, NOTIFY, REFER, OPTIONS, INFO\r\n" +
                                "$authHeaderName: $authHeaderVal\r\n" +
                                "User-Agent: $SIP_USER_AGENT\r\n" +
                                "Content-Type: application/sdp\r\n" +
                                "Content-Length: $authSdpByteLen\r\n\r\n" + authSdp

                        currentInviteData = nextInvite.toByteArray(Charsets.ISO_8859_1)
                        sendOnCallSocket(currentInviteData)
                        log("Authenticated INVITE sent with credentials (CSeq: $nextCSeq INVITE, via=$authViaHost:$authViaPort, ipv6=$isV6, branch=$nextBranch)...")
                        hasReceivedProvisional = false
                        inviteRetransmitCount = 0
                        lastInviteSentTime = SystemClock.elapsedRealtime()
                    }
                    180 -> {
                        hasReceivedProvisional = true
                        log("Remote phone is alerting / ringing (180 Ringing)...")
                        val toHeader = extractHeader(response, "To") ?: ""
                        val toTag = extractTag(toHeader)
                        val contactUri180 = extractContactUri(extractHeader(response, "Contact"))
                        val rr180 = extractRouteSetFromResponse(response)
                        val stillActive180 = synchronized(callStateLock) {
                            if (!isHangingUp && callGeneration.get() == myCallGen && currentCallId == callId) {
                                rtpStreamer?.enableInBandRingbackDetection = false
                                if (toTag != null) currentToTag = toTag
                                if (contactUri180 != null) activeCallContactUri = contactUri180
                                if (rr180.isNotEmpty()) activeCallRouteSet = rr180
                                currentCallState = CallState.RINGING
                                true
                            } else false
                        }
                        if (!stillActive180) return@withContext false

                        // If early media SDP is provided in 180, start RTP stream to hear remote ringback tone
                        val mediaAddr = resolveRemoteMediaAddress(response, targetAddress)
                        val rawSdpPort = extractSdpAudioPortNullable(response)
                        if (mediaAddr != null && rawSdpPort != null && rawSdpPort > 0) {
                            val codec = extractSdpCodec(response)
                            if (rtpStreamer == null) {
                                log("180 Early media SDP detected: ${cleanHostAddress(mediaAddr)}:$rawSdpPort (Codec: $codec). Starting ringback audio stream...")
                                try {
                                    startRtpStreamerSafe(
                                        expectedCallId = callId,
                                        isIncoming = false,
                                        rtpSocket = rtpSocket,
                                        remoteAddress = mediaAddr,
                                        remotePort = rawSdpPort,
                                        codec = codec
                                    )
                                    notifyCallState(CallState.RINGING, "Ringing (Early Media)...", expectedGen = myCallGen)
                                } catch (e: Exception) {
                                    log("Early media start error: ${e.message}")
                                    notifyCallState(CallState.RINGING, "Ringing...", expectedGen = myCallGen)
                                }
                            } else {
                                try {
                                    rtpStreamer?.updateRemoteEndpoint(mediaAddr, rawSdpPort, codec)
                                } catch (_: Exception) {}
                                notifyCallState(CallState.RINGING, "Ringing (Early Media)...", expectedGen = myCallGen)
                            }
                        } else {
                            notifyCallState(CallState.RINGING, if (hasEarlyMedia()) "Ringing (Early Media)..." else "Ringing...", expectedGen = myCallGen)
                        }
                    }
                    183 -> {
                        hasReceivedProvisional = true
                        log("Session Progress received (183)...")
                        val toHeader = extractHeader(response, "To") ?: ""
                        val toTag = extractTag(toHeader)
                        val contactUri183 = extractContactUri(extractHeader(response, "Contact"))
                        val rr183 = extractRouteSetFromResponse(response)
                        val stillActive183Pre = synchronized(callStateLock) {
                            if (!isHangingUp && callGeneration.get() == myCallGen && currentCallId == callId) {
                                if (toTag != null) currentToTag = toTag
                                if (contactUri183 != null) activeCallContactUri = contactUri183
                                if (rr183.isNotEmpty()) activeCallRouteSet = rr183
                                true
                            } else false
                        }
                        if (!stillActive183Pre) return@withContext false

                        val mediaAddr = resolveRemoteMediaAddress(response, targetAddress)
                        val rawSdpPort = extractSdpAudioPortNullable(response)

                        // RFC 3261 / RFC 3960: 183 Session Progress opens Early Media while keeping UI on "Calling..."
                        // (so switched-off voice announcements play under "Calling..." and NEVER falsely show "Ringing").
                        // On 183-only PSTN gateways that skip SIP 180, our Goertzel ITU-T ringback tone detector promotes
                        // to RINGING ONLY when genuine 400/425/440+480 Hz ringback tone is verified in the RTP stream.
                        if (mediaAddr != null && rawSdpPort != null && rawSdpPort > 0) {
                            val codec = extractSdpCodec(response)
                            if (rtpStreamer == null) {
                                log("183 Early media SDP detected: ${cleanHostAddress(mediaAddr)}:$rawSdpPort (Codec: $codec). Starting in-band media stream...")
                                try {
                                    val shouldDetectRbt = (currentCallState != CallState.RINGING && currentCallState != CallState.CONNECTED)
                                    startRtpStreamerSafe(
                                        expectedCallId = callId,
                                        isIncoming = false,
                                        rtpSocket = rtpSocket,
                                        remoteAddress = mediaAddr,
                                        remotePort = rawSdpPort,
                                        codec = codec,
                                        detectInBandRingback = shouldDetectRbt,
                                        onRingbackToneDetected = {
                                            var shouldPromote = false
                                            synchronized(callStateLock) {
                                                if (callGeneration.get() == myCallGen &&
                                                    currentCallId == callId &&
                                                    currentCallState == CallState.CONNECTING &&
                                                    !isHangingUp
                                                ) {
                                                    currentCallState = CallState.RINGING
                                                    shouldPromote = true
                                                }
                                            }
                                            if (shouldPromote) {
                                                log("Promoted outgoing call from Calling -> Ringing via verified in-band ringback tone.")
                                                notifyCallState(CallState.RINGING, "Ringing (Early Media)...", expectedGen = myCallGen)
                                            }
                                        }
                                    )
                                } catch (e: Exception) {
                                    log("183 Early media start error: ${e.message}")
                                }
                            } else {
                                try {
                                    rtpStreamer?.updateRemoteEndpoint(mediaAddr, rawSdpPort, codec)
                                } catch (_: Exception) {}
                            }
                        }

                        if (currentCallState == CallState.RINGING) {
                            notifyCallState(CallState.RINGING, if (hasEarlyMedia()) "Ringing (Early Media)..." else "Ringing...", expectedGen = myCallGen)
                        } else if (currentCallState != CallState.CONNECTED) {
                            val stillActive183 = synchronized(callStateLock) {
                                if (!isHangingUp && callGeneration.get() == myCallGen && currentCallId == callId) {
                                    hasSessionProgress = true
                                    currentCallState = CallState.CONNECTING
                                    true
                                } else false
                            }
                            if (!stillActive183) return@withContext false
                            notifyCallState(CallState.CONNECTING, "Calling...", expectedGen = myCallGen)
                        }
                    }
                    181, 182 -> {
                        hasReceivedProvisional = true
                        log("Call progress received ($code)...")
                        if (currentCallState != CallState.RINGING && currentCallState != CallState.CONNECTED) {
                            hasSessionProgress = true
                            notifyCallState(CallState.CONNECTING, "Calling...", expectedGen = myCallGen)
                        }
                    }
                    200 -> {
                        log("Call ANSWERED! (200 OK for CSeq $currentInviteCSeq)")
                        val toHeader = extractHeader(response, "To") ?: "<$requestUri>"
                        val toTag = extractTag(toHeader)
                        val contactUri200 = extractContactUri(extractHeader(response, "Contact"))
                        val rr200 = extractRouteSetFromResponse(response)
                        val preActive200: Boolean
                        val ackUri: String
                        val routeHeaders: String
                        synchronized(callStateLock) {
                            preActive200 = !isHangingUp && callGeneration.get() == myCallGen && currentCallId == callId
                            if (preActive200) {
                                rtpStreamer?.enableInBandRingbackDetection = false
                                rtpStreamer?.enableMediaTimeout = true
                                if (toTag != null) currentToTag = toTag
                                if (contactUri200 != null) activeCallContactUri = contactUri200
                                if (rr200.isNotEmpty()) activeCallRouteSet = rr200
                            }
                            ackUri = contactUri200 ?: activeCallContactUri ?: requestUri
                            routeHeaders = formatRouteHeaders(if (rr200.isNotEmpty()) rr200 else activeCallRouteSet)
                        }

                        // Send ACK for 200 OK (RFC 3261 Section 13.2.2.4: Request-URI = remote Contact, Route = reversed Record-Route)
                        val ackCSeq = currentInviteCSeq
                        val ackBranch = generateBranch()
                        val (ackViaHost, ackViaPort) = getViaEndpoint(localIp, localPort, targetAddress)
                        val (ackContactHost, ackContactPort) = getContactEndpoint(localIp, localPort, targetAddress)
                        val ack = "ACK $ackUri SIP/2.0\r\n" +
                                "Via: SIP/2.0/UDP $ackViaHost:$ackViaPort;rport;branch=$ackBranch\r\n" +
                                "Max-Forwards: 70\r\n" +
                                routeHeaders +
                                "To: $toHeader\r\n" +
                                fromHeader +
                                "Call-ID: $callId\r\n" +
                                "CSeq: $ackCSeq ACK\r\n" +
                                "Contact: <sip:$username@$ackContactHost:$ackContactPort;transport=udp>\r\n" +
                                "User-Agent: $SIP_USER_AGENT\r\n" +
                                "Content-Length: 0\r\n\r\n"

                        val ackBytes = ack.toByteArray(Charsets.ISO_8859_1)
                        if (preActive200) {
                            synchronized(callStateLock) {
                                if (!isHangingUp && callGeneration.get() == myCallGen && currentCallId == callId) {
                                    // Cache ACK so persistentSipListener immediately re-ACKs any retransmitted 200 OK
                                    last200OkAckBytes = ackBytes
                                    last200OkAckAddress = targetAddress
                                    last200OkAckPort = account.port
                                    last200OkAckCSeq = ackCSeq
                                }
                            }
                        }

                        for (k in 0..1) {
                            sendOnCallSocket(ackBytes)
                            if (k < 1) delay(15)
                        }
                        log("ACK sent for 200 OK on persistent socket (Request-URI: $ackUri, CSeq: $ackCSeq ACK)")

                        if (!preActive200) {
                            // Call was already hung up / superseded before 200 OK arrived: send immediate BYE and abort without touching audio mode or newer call!
                            log("200 OK arrived after hangUp/superseded (Call-ID=$callId); sending immediate BYE and aborting.")
                            try {
                                val byeBranch = generateBranch()
                                val byeMsg = "BYE $ackUri SIP/2.0\r\n" +
                                        "Via: SIP/2.0/UDP $ackViaHost:$ackViaPort;rport;branch=$byeBranch\r\n" +
                                        "Max-Forwards: 70\r\n" +
                                        routeHeaders +
                                        "To: $toHeader\r\n" +
                                        fromHeader +
                                        "Call-ID: $callId\r\n" +
                                        "CSeq: ${ackCSeq + 1} BYE\r\n" +
                                        "Content-Length: 0\r\n\r\n"
                                val byeBytes = byeMsg.toByteArray(Charsets.ISO_8859_1)
                                if (!socket.isClosed) {
                                    socket.send(DatagramPacket(byeBytes, byeBytes.size, targetAddress, account.port))
                                }
                            } catch (_: Exception) {}
                            return@withContext false
                        }

                        // Ensure audio mode is switched to MODE_IN_COMMUNICATION before starting hardware audio streams
                        appContext?.let { ctx ->
                            try {
                                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                                am?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                            } catch (_: Exception) {}
                        }

                        // Parse Remote RTP Audio Address, Port & Codec from 200 OK SDP (ONLY update if SDP is actually present!)
                        val remoteMediaAddr = resolveRemoteMediaAddress(response, targetAddress)
                        val rawRtpPort = extractSdpAudioPortNullable(response)
                        val has200Sdp = (remoteMediaAddr != null && rawRtpPort != null && rawRtpPort > 0)

                        if (has200Sdp) {
                            val remoteRtpPort = rawRtpPort!!
                            val codec = extractSdpCodec(response)
                            log("Remote RTP Audio Target from 200 OK SDP: ${cleanHostAddress(remoteMediaAddr)}:$remoteRtpPort (Negotiated Codec: $codec, Local RTP Port: $localRtpPort)")

                            if (rtpStreamer == null) {
                                startRtpStreamerSafe(
                                    expectedCallId = callId,
                                    isIncoming = false,
                                    rtpSocket = rtpSocket,
                                    remoteAddress = remoteMediaAddr!!,
                                    remotePort = remoteRtpPort,
                                    codec = codec
                                )
                            } else {
                                try {
                                    rtpStreamer?.updateRemoteEndpoint(remoteMediaAddr!!, remoteRtpPort, codec)
                                } catch (e: Exception) {
                                    log("Error updating RTP remote endpoint: ${e.message}")
                                }
                            }
                        } else if (rtpStreamer != null) {
                            // 200 OK had no SDP body -> keep the valid Early Media RTP stream negotiated in 183/180!
                            log("200 OK received without SDP body; preserving existing Early Media RTP stream.")
                        } else {
                            log("WARNING: 200 OK received without SDP and no Early Media stream existed.")
                        }

                        var myStreamerToStop: RtpAudioStreamer? = null
                        val confirmedActive = synchronized(callStateLock) {
                            if (!isHangingUp && callGeneration.get() == myCallGen && currentCallId == callId) {
                                isDialogConfirmed = true
                                currentCallState = CallState.CONNECTED
                                true
                            } else {
                                // Only stop rtpStreamer if it belongs to THIS call's rtpSocket (never kill a newer call's streamer!)
                                if (activeRtpSocket === rtpSocket && rtpStreamer != null) {
                                    myStreamerToStop = rtpStreamer
                                    rtpStreamer = null
                                }
                                false
                            }
                        }
                        if (!confirmedActive) {
                            // 200 OK raced with local hangUp(): send immediate BYE for the already-ACKed dialog so remote doesn't stay open!
                            log("200 OK raced with hangUp/superseded (Call-ID=$callId); sending immediate BYE and aborting.")
                            try {
                                myStreamerToStop?.stop()
                                val byeBranch = generateBranch()
                                val byeMsg = "BYE $ackUri SIP/2.0\r\n" +
                                        "Via: SIP/2.0/UDP $ackViaHost:$ackViaPort;rport;branch=$byeBranch\r\n" +
                                        "Max-Forwards: 70\r\n" +
                                        routeHeaders +
                                        "To: $toHeader\r\n" +
                                        fromHeader +
                                        "Call-ID: $callId\r\n" +
                                        "CSeq: ${ackCSeq + 1} BYE\r\n" +
                                        "Content-Length: 0\r\n\r\n"
                                val byeBytes = byeMsg.toByteArray(Charsets.ISO_8859_1)
                                if (!socket.isClosed) {
                                    socket.send(DatagramPacket(byeBytes, byeBytes.size, targetAddress, account.port))
                                }
                            } catch (_: Exception) {}
                            return@withContext false
                        }
                        notifyCallState(CallState.CONNECTED, "In Call", expectedGen = myCallGen)
                        return@withContext true
                    }
                    in 300..399 -> {
                        log("Outgoing call redirected ($code); ACKing and failing cleanly.")
                        sendNon2xxAck(socket, requestUri, response, activeViaHost, activeViaPort, activeContactHost, activeContactPort, currentInviteBranch, currentInviteCSeq, fromHeader, callId, targetAddress, account.port)
                        notifyCallState(CallState.FAILED, "Call Redirected ($code)", expectedGen = myCallGen)
                        hangUp(sendByeOrCancel = false)
                        return@withContext false
                    }
                    487 -> {
                        log("Call CANCEL confirmed by server (487 Request Terminated)")
                        sendNon2xxAck(socket, requestUri, response, activeViaHost, activeViaPort, activeContactHost, activeContactPort, currentInviteBranch, currentInviteCSeq, fromHeader, callId, targetAddress, account.port)
                        hangUp(sendByeOrCancel = false)
                        return@withContext true
                    }
                    486, 600 -> {
                        log("Remote party is busy ($code)")
                        sendNon2xxAck(socket, requestUri, response, activeViaHost, activeViaPort, activeContactHost, activeContactPort, currentInviteBranch, currentInviteCSeq, fromHeader, callId, targetAddress, account.port)
                        notifyCallState(CallState.FAILED, getFriendlyFailureMessage(code), expectedGen = myCallGen)
                        hangUp(sendByeOrCancel = false)
                        return@withContext false
                    }
                    403 -> {
                        log("Call rejected: 403 Forbidden (Insufficient balance or restricted destination)")
                        sendNon2xxAck(socket, requestUri, response, activeViaHost, activeViaPort, activeContactHost, activeContactPort, currentInviteBranch, currentInviteCSeq, fromHeader, callId, targetAddress, account.port)
                        notifyCallState(CallState.FAILED, getFriendlyFailureMessage(code), expectedGen = myCallGen)
                        hangUp(sendByeOrCancel = false)
                        return@withContext false
                    }
                    404, 484 -> {
                        log("Number not found ($code)")
                        sendNon2xxAck(socket, requestUri, response, activeViaHost, activeViaPort, activeContactHost, activeContactPort, currentInviteBranch, currentInviteCSeq, fromHeader, callId, targetAddress, account.port)
                        notifyCallState(CallState.FAILED, getFriendlyFailureMessage(code), expectedGen = myCallGen)
                        hangUp(sendByeOrCancel = false)
                        return@withContext false
                    }
                    408, 480 -> {
                        val reason = extractHeader(response, "Reason") ?: ""
                        val warning = extractHeader(response, "Warning") ?: ""
                        log("Temporary failure or timeout ($code), reason: $reason, warning: $warning")
                        sendNon2xxAck(socket, requestUri, response, activeViaHost, activeViaPort, activeContactHost, activeContactPort, currentInviteBranch, currentInviteCSeq, fromHeader, callId, targetAddress, account.port)
                        val failMsg = when {
                            reason.contains("cause=1", ignoreCase = true) && !reason.contains("cause=17") && !reason.contains("cause=18") && !reason.contains("cause=19") || reason.contains("unallocated", ignoreCase = true) -> "Number Not Found"
                            reason.contains("cause=17", ignoreCase = true) || reason.contains("busy", ignoreCase = true) -> "Busy"
                            reason.contains("cause=18", ignoreCase = true) || reason.contains("cause=20", ignoreCase = true) || reason.contains("cause=27", ignoreCase = true) || reason.contains("absent", ignoreCase = true) -> "Number Unavailable"
                            reason.contains("cause=19", ignoreCase = true) || reason.contains("no answer", ignoreCase = true) -> "No Answer"
                            reason.contains("cause=21", ignoreCase = true) || reason.contains("rejected", ignoreCase = true) -> "Call Rejected"
                            reason.contains("cause=34", ignoreCase = true) || reason.contains("congestion", ignoreCase = true) -> "Network Busy"
                            reason.contains("cause=38", ignoreCase = true) -> "Network Out of Order"
                            warning.isNotBlank() -> warning.take(30)
                            else -> getFriendlyFailureMessage(code)
                        }
                        notifyCallState(CallState.FAILED, failMsg, expectedGen = myCallGen)
                        hangUp(sendByeOrCancel = false)
                        return@withContext false
                    }
                    603 -> {
                        log("Call declined by user ($code)")
                        sendNon2xxAck(socket, requestUri, response, activeViaHost, activeViaPort, activeContactHost, activeContactPort, currentInviteBranch, currentInviteCSeq, fromHeader, callId, targetAddress, account.port)
                        notifyCallState(CallState.FAILED, getFriendlyFailureMessage(code), expectedGen = myCallGen)
                        hangUp(sendByeOrCancel = false)
                        return@withContext false
                    }
                    in 400..699 -> {
                        log("Call failed with server error $code")
                        sendNon2xxAck(socket, requestUri, response, activeViaHost, activeViaPort, activeContactHost, activeContactPort, currentInviteBranch, currentInviteCSeq, fromHeader, callId, targetAddress, account.port)
                        notifyCallState(CallState.FAILED, getFriendlyFailureMessage(code), expectedGen = myCallGen)
                        hangUp(sendByeOrCancel = false)
                        return@withContext false
                    }
                }
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            log("Call coroutine cleanly superseded/cancelled (callId=$myCallId).")
            throw e
        } catch (e: SocketTimeoutException) {
            if (!isHangingUp) {
                log("Call ringing timed out (no answer)")
                notifyCallState(CallState.FAILED, "No Answer")
                hangUp()
            }
            false
        } catch (e: Exception) {
            if (isHangingUp) {
                log("Call socket closed cleanly on hangup")
                synchronized(callStateLock) {
                    if (myCallId != null && currentCallId == myCallId) {
                        currentCallState = CallState.ENDED
                    }
                }
            } else {
                log("Call initiation failed: ${e.message}")
                notifyCallState(CallState.FAILED, "Call Failed", expectedGen = myCallGen)
                hangUp(sendByeOrCancel = false)
            }
            false
        } finally {
            // If coroutine exited/cancelled before publishing myCallId, close any allocated RTP socket to prevent FD leak!
            if (myCallId == null && createdRtpSocket != null) {
                try { createdRtpSocket.close() } catch (_: Exception) {}
                synchronized(callStateLock) {
                    if (activeRtpSocket === createdRtpSocket) activeRtpSocket = null
                }
            }
            // Compute cleanup decision inside lock, then run hangUp() OUTSIDE callStateLock to prevent lock-order deadlock with playThread!
            val needsCleanup = synchronized(callStateLock) {
                myCallId != null && currentCallId == myCallId &&
                    (currentCallState == CallState.CONNECTING || currentCallState == CallState.RINGING)
            }
            if (needsCleanup) {
                log("Call setup ($myCallId) exited without CONNECTED state. Ensuring state reset to ENDED.")
                hangUp(sendByeOrCancel = false, cancelActiveCallJob = false)
            }
        }
    }

    private fun sendNon2xxAck(
        socket: DatagramSocket,
        requestUri: String,
        response: String,
        viaHost: String,
        viaPort: Int,
        contactHost: String,
        contactPort: Int,
        ackBranch: String,
        ackCSeq: Int,
        fromHeader: String,
        callId: String,
        targetAddress: InetAddress,
        targetPort: Int
    ) {
        try {
            val account = activeAccount ?: return
            val toHeader = extractHeader(response, "To") ?: "<$requestUri>"
            val ack = "ACK $requestUri SIP/2.0\r\n" +
                    "Via: SIP/2.0/UDP $viaHost:$viaPort;rport;branch=$ackBranch\r\n" +
                    "Max-Forwards: 70\r\n" +
                    "To: $toHeader\r\n" +
                    fromHeader +
                    "Call-ID: $callId\r\n" +
                    "CSeq: $ackCSeq ACK\r\n" +
                    "Contact: <sip:${account.username}@$contactHost:$contactPort;transport=udp>\r\n" +
                    "Content-Length: 0\r\n\r\n"
            val ackBytes = ack.toByteArray(Charsets.ISO_8859_1)
            socket.send(DatagramPacket(ackBytes, ackBytes.size, targetAddress, targetPort))
            log("RFC 3261 ACK sent for non-2xx final response (CSeq=$ackCSeq, branch=$ackBranch).")
        } catch (_: Exception) {}
    }

    fun hangUp(sendByeOrCancel: Boolean = true, cancelActiveCallJob: Boolean = true) {
        if (cancelActiveCallJob) {
            activeCallJob?.cancel()
            activeCallJob = null
        }
        midCallHandoverJob?.cancel()
        midCallHandoverJob = null
        reInviteJob?.cancel()
        reInviteJob = null
        pendingReInviteCallId = null
        pendingReInviteCSeq = null
        lastMigratedCallIp = null
        lastMigratedCallFingerprint = null
        lastMigratedCallGen = -1L
        incomingSetupJob?.cancel()
        incomingSetupJob = null
        incomingRingingWatchdogJob?.cancel()
        incomingRingingWatchdogJob = null
        incomingAckDeferred?.complete(null)
        val priorState: CallState
        val socketToClose: DatagramSocket?
        val rtpSocketToClose: DatagramSocket?
        val targetAddr: InetAddress?
        val callId: String?
        val uri: String?
        val fromTag: String?
        val toTag: String?
        val account: SipAccount?
        val branch: String
        val cseq: Int
        val contactUri: String?
        val savedFromHeader: String?
        val savedRouteSet: List<String>

        val incCallId: String?
        val incRemoteAddr: InetAddress?
        val incViaHeadersSnap: String
        val incViaSnap: String?
        val incCSeqSnap: String?
        val incSocket: DatagramSocket?
        val incFrom: String
        val incTo: String
        val incContact: String
        val incPort: Int
        val incTag: String?
        val incRouteSet: List<String>
        val incByeCSeq: Int
        val wasConfirmed: Boolean

        synchronized(callStateLock) {
            if (isHangingUp) {
                RtpAudioStreamer.stopAllActiveStreams()
                return
            }
            isHangingUp = true
            callGeneration.incrementAndGet()
            priorState = currentCallState
            wasConfirmed = isDialogConfirmed
            isDialogConfirmed = false
            sent200OkForIncoming = false
            isCurrentCallIncoming = false
            isCallOnHold = false
            isCallMuted = false
            hasSessionProgress = false
            currentCallState = CallState.ENDED
            CallProximityManager.forceRelease("SipEngine hangUp")

            socketToClose = if (activeCallSocket != null && activeCallSocket !== persistentSipSocket) activeCallSocket else null
            rtpSocketToClose = activeRtpSocket
            activeCallSocket = null
            activeRtpSocket = null
            activeRtpStunEndpoint = null
            activeRtpStunLocalPort = -1

            targetAddr = activeTargetAddress
            activeTargetAddress = null
            callId = currentCallId
            uri = currentCallUri
            fromTag = currentFromTag
            toTag = currentToTag
            account = activeAccount
            branch = activeCallBranch
            cseq = activeCallCSeq
            contactUri = activeCallContactUri
            savedFromHeader = activeCallFromHeader
            savedRouteSet = activeCallRouteSet

            currentCallId = null
            currentFromTag = null
            currentToTag = null
            currentCallUri = null
            activeCallBranch = ""
            activeCallCSeq = 1
            activeCallContactUri = null
            activeCallFromHeader = null
            activeCallRouteSet = emptyList()
            last200OkAckBytes = null
            last200OkAckAddress = null
            last200OkAckCSeq = -1

            incCallId = incomingCallId
            incRemoteAddr = incomingRemoteAddress
            incSocket = persistentSipSocket
            incFrom = incomingFrom ?: ""
            incTo = incomingTo ?: ""
            incTag = incomingLocalTag
            incPort = incomingRemotePort
            incRouteSet = incomingRouteSet
            incViaHeadersSnap = incomingViaHeaders
            incViaSnap = incomingVia
            incCSeqSnap = incomingCSeq
            // Atomically snapshot CSeq BEFORE resetting incomingLocalCSeq for next call!
            incByeCSeq = ++incomingLocalCSeq
            incomingLocalCSeq = 10
            incContact = (incomingContactUri ?: incFrom.let {
                Regex("""<([^>]+)>""").find(it)?.groupValues?.get(1) ?: "sip:${incRemoteAddr?.hostAddress ?: ""}"
            }).replace("<", "").replace(">", "").trim()

            incomingCallId = null
            incomingLocalTag = null
            incomingContactUri = null
            incomingRouteSet = emptyList()
            incomingFrom = null
            incomingTo = null
            incomingRemoteAddress = null
            incomingCSeq = null
            incomingSdp = null
            incomingViaHeaders = ""
            incomingVia = ""
            lastProvisionalResponse = null
        }

        callListeningJob?.cancel()
        callListeningJob = null
        incoming200OkRetransmitJob?.cancel()
        incoming200OkRetransmitJob = null

        if (CallRecorder.isRecording) {
            CallRecorder.stopRecording()
        }
        rtpStreamer?.stop()
        rtpStreamer = null
        RtpAudioStreamer.stopAllActiveStreams()
        try { rtpSocketToClose?.close() } catch (_: Exception) {}

        // 1. Handle Outgoing Call Termination (ALWAYS CANCEL if !wasConfirmed, BYE only if confirmed!)
        val outSocket = persistentSipSocket ?: socketToClose
        if (sendByeOrCancel && outSocket != null && !outSocket.isClosed && targetAddr != null && callId != null && uri != null && account != null) {
            engineScope.launch {
                try {
                    val rawCancelHost = getLocalAddressForTarget(targetAddr)
                    val (cancelHost, cancelPort) = getViaEndpoint(rawCancelHost, outSocket.localPort, targetAddr)
                    val effectiveFrom = savedFromHeader ?: "From: <sip:${account.username}@${account.server}>;tag=$fromTag\r\n"
                    if (!wasConfirmed) {
                        val cancelMsg = "CANCEL $uri SIP/2.0\r\n" +
                                "Via: SIP/2.0/UDP $cancelHost:$cancelPort;rport;branch=$branch\r\n" +
                                "Max-Forwards: 70\r\n" +
                                "To: <$uri>\r\n" +
                                effectiveFrom +
                                "Call-ID: $callId\r\n" +
                                "CSeq: $cseq CANCEL\r\n" +
                                "Content-Length: 0\r\n\r\n"

                        val bytes = cancelMsg.toByteArray(Charsets.ISO_8859_1)
                        val packet = DatagramPacket(bytes, bytes.size, targetAddr, activeTargetPort)
                        for (i in 0..2) {
                            try { outSocket.send(packet) } catch (_: Exception) {}
                            if (i < 2) delay(15)
                        }
                        log("SIP CANCEL sent (branch=$branch, CSeq=$cseq CANCEL).")
                    } else {
                        val reqUri = contactUri ?: uri
                        val toHeader = if (toTag != null) "<$uri>;tag=$toTag" else "<$uri>"
                        val byeBranch = generateBranch()
                        val routeHeaders = formatRouteHeaders(savedRouteSet)
                        val byeMsg = "BYE $reqUri SIP/2.0\r\n" +
                                "Via: SIP/2.0/UDP $cancelHost:$cancelPort;rport;branch=$byeBranch\r\n" +
                                "Max-Forwards: 70\r\n" +
                                routeHeaders +
                                "To: $toHeader\r\n" +
                                effectiveFrom +
                                "Call-ID: $callId\r\n" +
                                "CSeq: ${cseq + 1} BYE\r\n" +
                                "Content-Length: 0\r\n\r\n"
                        val bytes = byeMsg.toByteArray(Charsets.ISO_8859_1)
                        val packet = DatagramPacket(bytes, bytes.size, targetAddr, activeTargetPort)
                        for (i in 0..2) {
                            try { outSocket.send(packet) } catch (_: Exception) {}
                            if (i < 2) delay(15)
                        }
                        log("SIP BYE sent (CSeq=${cseq + 1} BYE).")
                        delay(250)
                    }
                } catch (e: Exception) {
                    log("Hangup error: ${e.message}")
                } finally {
                    try { socketToClose?.close() } catch (_: Exception) {}
                }
            }
        } else {
            try { socketToClose?.close() } catch (_: Exception) {}
        }

        // 2. Handle Incoming Call Termination (487 if unconfirmed ringing per RFC 3261 §15, BYE if confirmed!)
        if (sendByeOrCancel && incCallId != null && incRemoteAddr != null && incSocket != null && !incSocket.isClosed) {
            engineScope.launch {
                try {
                    val cleanIncTo = incTo.replace(Regex(";tag=[^;]+"), "")
                    val toTagPart = if (incTag != null) ";tag=$incTag" else ""
                    val rawMsg = if (!wasConfirmed) {
                        val rawViaBlock = if (incViaHeadersSnap.isNotBlank()) incViaHeadersSnap else "Via: $incViaSnap\r\n"
                        val viaBlock = formatResponseViaHeaders(rawViaBlock, incRemoteAddr, incPort)
                        "SIP/2.0 487 Request Terminated\r\n" +
                                viaBlock +
                                "From: $incFrom\r\n" +
                                "To: $cleanIncTo$toTagPart\r\n" +
                                "Call-ID: $incCallId\r\n" +
                                "CSeq: $incCSeqSnap\r\n" +
                                "Reason: Q.850;cause=16;text=\"Normal Call Clearing\"\r\n" +
                                "Content-Length: 0\r\n\r\n"
                    } else {
                        val rawIncByeHost = getLocalAddressForTarget(incRemoteAddr)
                        val (incByeHost, incByePort) = getViaEndpoint(rawIncByeHost, incSocket.localPort, incRemoteAddr)
                        val byeBranch = generateBranch()
                        val routeHeaders = formatRouteHeaders(incRouteSet)
                        "BYE $incContact SIP/2.0\r\n" +
                                "Via: SIP/2.0/UDP $incByeHost:$incByePort;rport;branch=$byeBranch\r\n" +
                                "Max-Forwards: 70\r\n" +
                                routeHeaders +
                                "To: $incFrom\r\n" +
                                "From: $cleanIncTo$toTagPart\r\n" +
                                "Call-ID: $incCallId\r\n" +
                                "CSeq: $incByeCSeq BYE\r\n" +
                                "Content-Length: 0\r\n\r\n"
                    }
                    val bytes = rawMsg.toByteArray(Charsets.ISO_8859_1)
                    val packet = DatagramPacket(bytes, bytes.size, incRemoteAddr, incPort)
                    for (i in 0..2) {
                        try { incSocket.send(packet) } catch (_: Exception) {}
                        if (i < 2) delay(15)
                    }
                    log(if (!wasConfirmed) "SIP 487 sent for unconfirmed incoming call." else "SIP BYE sent for answered incoming call (CSeq=$incByeCSeq).")
                } catch (_: Exception) {}
            }
        }

        val ctx = appContext
        if (ctx != null) {
            AudioRouteManager.resetToNormal(ctx)
            SipVoipService.dismissIncomingCall(ctx)
            SipVoipService.callEnded(ctx)
        }

        if (priorState != CallState.FAILED) {
            notifyCallState(CallState.ENDED, "Call Ended")
        }
        synchronized(callStateLock) {
            if (currentCallState == CallState.ENDED || currentCallState == CallState.FAILED) {
                currentCallState = CallState.IDLE
            }
            isHangingUp = false
        }

        // If a mid-call Wi-Fi <-> Mobile Data handover occurred during this call, immediately
        // re-register SIP on the new network interface now that the call has ended cleanly!
        if (needsPostCallReRegister) {
            needsPostCallReRegister = false
            val acc = activeAccount ?: ctx?.let { com.opentalk.android.storage.SecureAccountManager(it).getAccount() }
            if (acc != null && acc.username.isNotBlank() && acc.server.isNotBlank() && isNetworkConnected(ctx)) {
                log("Post-call SIP re-registration triggered after mid-call network handover...")
                triggerRegister(acc, delayMs = 300L, force = true)
            }
        }
    }

    fun transferCall(targetNumber: String): Boolean {
        val cleanNumber = targetNumber.trim().replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isBlank()) return false
        val account = activeAccount ?: return false
        val referBranch = generateBranch()

        // 1. Outgoing call transfer (atomically increments activeCallCSeq & includes Route headers)
        val outSocket = persistentSipSocket ?: activeCallSocket
        val outTarget = activeTargetAddress
        val outCallId: String?
        val outUri: String?
        val outFromHeader: String?
        val outToTag: String?
        val outRouteSet: List<String>
        val outContact: String?
        val outCSeq: Int
        synchronized(callStateLock) {
            outCallId = currentCallId
            outUri = currentCallUri
            outFromHeader = activeCallFromHeader ?: currentFromTag?.let { "From: <sip:${account.username}@${account.server}>;tag=$it\r\n" }
            outToTag = currentToTag
            outRouteSet = activeCallRouteSet
            outContact = activeCallContactUri
            outCSeq = if (outCallId != null) ++activeCallCSeq else 0
        }

        if (outSocket != null && !outSocket.isClosed && outTarget != null && outCallId != null && outUri != null && outFromHeader != null) {
            engineScope.launch {
                try {
                    val referLocalIp = getLocalAddressForTarget(outTarget)
                    val (viaHost, viaPort) = getViaEndpoint(referLocalIp, outSocket.localPort, outTarget)
                    val (contactHost, contactPort) = getContactEndpoint(referLocalIp, outSocket.localPort, outTarget)
                    val reqUri = outContact ?: outUri
                    val toHeader = if (outToTag != null) "<$outUri>;tag=$outToTag" else "<$outUri>"
                    val routeHeaders = formatRouteHeaders(outRouteSet)
                    val referMsg = "REFER $reqUri SIP/2.0\r\n" +
                            "Via: SIP/2.0/UDP $viaHost:$viaPort;rport;branch=$referBranch\r\n" +
                            "Max-Forwards: 70\r\n" +
                            routeHeaders +
                            "To: $toHeader\r\n" +
                            outFromHeader +
                            "Call-ID: $outCallId\r\n" +
                            "CSeq: $outCSeq REFER\r\n" +
                            "Refer-To: <sip:$cleanNumber@${account.server}>\r\n" +
                            "Referred-By: <sip:${account.username}@${account.server}>\r\n" +
                            "Contact: <sip:${account.username}@$contactHost:$contactPort;transport=udp>\r\n" +
                            "Content-Length: 0\r\n\r\n"
                    val bytes = referMsg.toByteArray(Charsets.ISO_8859_1)
                    val packet = DatagramPacket(bytes, bytes.size, outTarget, activeTargetPort)
                    for (i in 0..1) {
                        try { outSocket.send(packet) } catch (_: Exception) {}
                        if (i < 1) delay(20)
                    }
                    log("SIP REFER sent for outgoing call to $cleanNumber (CSeq=$outCSeq)")
                } catch (e: Exception) {
                    log("Call transfer error: ${e.message}")
                }
            }
            return true
        }

        // 2. Incoming call transfer (atomically increments incomingLocalCSeq & strips existing tag before appending)
        val incSocket = persistentSipSocket
        val incTarget: InetAddress?
        val incCallId: String?
        val incPort: Int
        val incContact: String?
        val incFrom: String?
        val incTo: String?
        val incTag: String?
        val incRouteSet: List<String>
        val incCSeq: Int
        synchronized(callStateLock) {
            incTarget = incomingRemoteAddress
            incCallId = incomingCallId
            incPort = incomingRemotePort
            incContact = incomingContactUri
            incFrom = incomingFrom
            incTo = incomingTo
            incTag = incomingLocalTag
            incRouteSet = incomingRouteSet
            incCSeq = if (incCallId != null) ++incomingLocalCSeq else 0
        }

        if (incSocket != null && !incSocket.isClosed && incTarget != null && incCallId != null) {
            engineScope.launch {
                try {
                    val referLocalIp = getLocalAddressForTarget(incTarget)
                    val (viaHost, viaPort) = getViaEndpoint(referLocalIp, incSocket.localPort, incTarget)
                    val (contactHost, contactPort) = getContactEndpoint(referLocalIp, incSocket.localPort, incTarget)
                    val reqUri = (incContact ?: incFrom?.let { Regex("""<([^>]+)>""").find(it)?.groupValues?.get(1) }
                        ?: "sip:${formatHostForUri(incTarget)}").replace("<", "").replace(">", "").trim()
                    val cleanIncTo = incTo?.replace(Regex(";tag=[^;]+"), "") ?: ""
                    val toTagPart = if (incTag != null) ";tag=$incTag" else ""
                    val routeHeaders = formatRouteHeaders(incRouteSet)
                    val referMsg = "REFER $reqUri SIP/2.0\r\n" +
                            "Via: SIP/2.0/UDP $viaHost:$viaPort;rport;branch=$referBranch\r\n" +
                            "Max-Forwards: 70\r\n" +
                            routeHeaders +
                            "To: $incFrom\r\n" +
                            "From: $cleanIncTo$toTagPart\r\n" +
                            "Call-ID: $incCallId\r\n" +
                            "CSeq: $incCSeq REFER\r\n" +
                            "Refer-To: <sip:$cleanNumber@${account.server}>\r\n" +
                            "Referred-By: <sip:${account.username}@${account.server}>\r\n" +
                            "Contact: <sip:${account.username}@$contactHost:$contactPort;transport=udp>\r\n" +
                            "Content-Length: 0\r\n\r\n"
                    val bytes = referMsg.toByteArray(Charsets.ISO_8859_1)
                    val packet = DatagramPacket(bytes, bytes.size, incTarget, incPort)
                    for (i in 0..1) {
                        try { incSocket.send(packet) } catch (_: Exception) {}
                        if (i < 1) delay(20)
                    }
                    log("SIP REFER sent for incoming call to $cleanNumber (CSeq=$incCSeq)")
                } catch (e: Exception) {
                    log("Call transfer error: ${e.message}")
                }
            }
            return true
        }

        return false
    }

    fun setMute(muted: Boolean) {
        isCallMuted = muted
        rtpStreamer?.setMute(muted)
    }

    fun setLowDataMode(enabled: Boolean) {
        rtpStreamer?.isLowDataWeakNetworkMode = enabled
    }

    @Volatile private var pendingHoldReInviteSync: Boolean = false

    /**
     * Transactional RFC 3261 §14 Hold / Unhold re-INVITE supporting BOTH outgoing and incoming dialogs:
     * increments SDP o= version, includes Route headers, handles 401/407, and sends matching CSeq ACK!
     * Serializes & coalesces rapid Hold/Unhold toggles without cancelling an in-flight SIP transaction mid-way.
     */
    fun setHold(hold: Boolean) {
        if (currentCallState != CallState.CONNECTED) {
            isCallOnHold = false
            return
        }
        isCallOnHold = hold
        rtpStreamer?.setHold(hold)
        val socket = persistentSipSocket ?: activeCallSocket ?: return
        if (socket.isClosed) return

        // Coalesce rapid toggles: if a Hold re-INVITE worker is already active, mark pending so it
        // cleanly sends the latest desired hold state right after the in-flight SIP transaction finishes!
        synchronized(callStateLock) {
            pendingHoldReInviteSync = true
            if (reInviteJob?.isActive == true) {
                return
            }
            reInviteJob = engineScope.launch {
                val myJob = coroutineContext[Job]
                try {
                    while (true) {
                        val desiredHold: Boolean
                        synchronized(callStateLock) {
                            if (!pendingHoldReInviteSync || currentCallState != CallState.CONNECTED || isHangingUp) {
                                pendingHoldReInviteSync = false
                                if (reInviteJob === myJob) reInviteJob = null
                                return@launch
                            }
                            pendingHoldReInviteSync = false
                            desiredHold = isCallOnHold
                        }
                        val liveSock = persistentSipSocket?.takeIf { !it.isClosed } ?: activeCallSocket?.takeIf { !it.isClosed } ?: return@launch
                        val account = activeAccount ?: return@launch
                        val rtpPort = activeRtpSocket?.takeIf { !it.isClosed }?.localPort ?: return@launch
                        val targetAddr = (if (currentCallId != null) activeTargetAddress else incomingRemoteAddress) ?: return@launch
                        val targetPort = if (currentCallId != null) activeTargetPort else incomingRemotePort
                        val mode = if (desiredHold) "sendonly" else "sendrecv"
                        val localIp = getLocalAddressForTarget(targetAddr)
                        sendMidCallHandoverReInvite(
                            socket = liveSock,
                            account = account,
                            newLocalIp = localIp,
                            newRtpPort = rtpPort,
                            targetAddr = targetAddr,
                            targetPort = targetPort,
                            sdpDirection = mode
                        )
                        log("Transactional SIP Re-INVITE completed for Hold=$desiredHold ($mode)")
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    log("Failed to send transactional Hold Re-INVITE: ${e.message}")
                } finally {
                    synchronized(callStateLock) {
                        if (reInviteJob === myJob) reInviteJob = null
                    }
                }
            }
        }
    }

    fun sendDtmf(digit: Char) {
        rtpStreamer?.sendDtmf(digit)
    }

    private fun startCallSessionListener(socket: DatagramSocket) {
        callListeningJob?.cancel()
        callListeningJob = engineScope.launch {
            val buf = ByteArray(8192)
            while (isActive && !isHangingUp) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    if (msg.startsWith("BYE", ignoreCase = true)) {
                        log("Received remote BYE hangup.")
                        val ok = "SIP/2.0 200 OK\r\n" +
                                (extractHeader(msg, "Via")?.let { "Via: $it\r\n" } ?: "") +
                                (extractHeader(msg, "From")?.let { "From: $it\r\n" } ?: "") +
                                (extractHeader(msg, "To")?.let { "To: $it\r\n" } ?: "") +
                                (extractHeader(msg, "Call-ID")?.let { "Call-ID: $it\r\n" } ?: "") +
                                (extractHeader(msg, "CSeq")?.let { "CSeq: $it\r\n" } ?: "") +
                                "Content-Length: 0\r\n\r\n"
                        val okBytes = ok.toByteArray(Charsets.ISO_8859_1)
                        socket.send(DatagramPacket(okBytes, okBytes.size, packet.address, packet.port))
                        hangUp(sendByeOrCancel = false)
                        break
                    } else if (msg.startsWith("INVITE", ignoreCase = true)) {
                        log("Received in-dialog re-INVITE on active call socket. Replying 200 OK...")
                        val localIp = getLocalAddressForTarget(packet.address)
                        val (contactHost, contactPort) = getContactEndpoint(localIp, socket.localPort, packet.address)
                        val rtpPort = activeRtpSocket?.localPort ?: 40000
                        val codec = rtpStreamer?.sendPayloadType ?: 8
                        val codecBlock = "$codec 101\r\n" +
                                (if (codec == 8) "a=rtpmap:8 PCMA/8000\r\n" else "a=rtpmap:0 PCMU/8000\r\n") +
                                "a=rtpmap:101 telephone-event/8000\r\n"
                        val sdp = buildAudioSdp(
                            username = activeAccount?.username ?: "Z",
                            sessionVer = 101,
                            sessionName = SDP_SESSION_NAME,
                            targetAddress = packet.address,
                            localIp = localIp,
                            localRtpPort = rtpPort,
                            rtpStun = null,
                            codecBlock = codecBlock,
                            direction = "sendrecv"
                        )

                        val viaBlock = extractAllViaHeaders(msg).ifBlank {
                            extractHeader(msg, "Via")?.let { "Via: $it\r\n" } ?: ""
                        }
                        val toTagPart = if (currentToTag != null && !(extractHeader(msg, "To")?.contains("tag=") ?: false)) ";tag=$currentToTag" else ""
                        val ok = "SIP/2.0 200 OK\r\n" +
                                viaBlock +
                                (extractHeader(msg, "From")?.let { "From: $it\r\n" } ?: "") +
                                (extractHeader(msg, "To")?.let { "To: $it$toTagPart\r\n" } ?: "") +
                                (extractHeader(msg, "Call-ID")?.let { "Call-ID: $it\r\n" } ?: "") +
                                (extractHeader(msg, "CSeq")?.let { "CSeq: $it\r\n" } ?: "") +
                                "Contact: <sip:${activeAccount?.username}@$contactHost:$contactPort>\r\n" +
                                "Content-Type: application/sdp\r\n" +
                                "Content-Length: ${sdp.toByteArray(Charsets.ISO_8859_1).size}\r\n\r\n$sdp"
                        val okBytes = ok.toByteArray(Charsets.ISO_8859_1)
                        socket.send(DatagramPacket(okBytes, okBytes.size, packet.address, packet.port))

                        val newAddr = resolveRemoteMediaAddress(msg, packet.address)
                        val sdpPort = extractSdpAudioPort(msg)
                        if (newAddr != null && sdpPort > 0) {
                            try {
                                rtpStreamer?.updateRemoteEndpoint(newAddr, sdpPort)
                                log("Updated remote RTP endpoint: ${cleanHostAddress(newAddr)}:$sdpPort")
                            } catch (_: Exception) {}
                        }
                    } else if (msg.startsWith("OPTIONS", ignoreCase = true) || msg.startsWith("UPDATE", ignoreCase = true) || msg.startsWith("INFO", ignoreCase = true)) {
                        val ok = "SIP/2.0 200 OK\r\n" +
                                (extractHeader(msg, "Via")?.let { "Via: $it\r\n" } ?: "") +
                                (extractHeader(msg, "From")?.let { "From: $it\r\n" } ?: "") +
                                (extractHeader(msg, "To")?.let { "To: $it\r\n" } ?: "") +
                                (extractHeader(msg, "Call-ID")?.let { "Call-ID: $it\r\n" } ?: "") +
                                (extractHeader(msg, "CSeq")?.let { "CSeq: $it\r\n" } ?: "") +
                                "Content-Length: 0\r\n\r\n"
                        val okBytes = ok.toByteArray(Charsets.ISO_8859_1)
                        socket.send(DatagramPacket(okBytes, okBytes.size, packet.address, packet.port))
                    }
                } catch (_: Exception) {
                    if (!isActive || isHangingUp) break
                }
            }
        }
    }

    fun sendKeepAlivePing(forceOptions: Boolean = false) {
        val account = activeAccount ?: return
        val socket = persistentSipSocket ?: return
        if (socket.isClosed || currentStatus != RegistrationStatus.REGISTERED) return

        engineScope.launch {
            try {
                val targetAddress = resolveServerAddress(account.server)
                if (forceOptions) {
                    val localIp = getLocalAddressForTarget(targetAddress)
                    val (pingHost, pingPort) = getViaEndpoint(localIp, socket.localPort, targetAddress)
                    val ping = "OPTIONS sip:${account.server} SIP/2.0\r\n" +
                            "Via: SIP/2.0/UDP $pingHost:$pingPort;rport;branch=${generateBranch()}\r\n" +
                            "Max-Forwards: 70\r\n" +
                            "To: <sip:${account.server}>\r\n" +
                            "From: <sip:${account.username}@${account.server}>;tag=ping${Random().nextInt(9999)}\r\n" +
                            "Call-ID: ${generateOpaqueCallId()}\r\n" +
                            "CSeq: 1 OPTIONS\r\n" +
                            "User-Agent: $SIP_USER_AGENT\r\n" +
                            "Content-Length: 0\r\n\r\n"
                    val bytes = ping.toByteArray(Charsets.ISO_8859_1)
                    lastOptionsSentElapsedMs = SystemClock.elapsedRealtime()
                    socket.send(DatagramPacket(bytes, bytes.size, targetAddress, account.port))
                } else {
                    val crlfBytes = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                    socket.send(DatagramPacket(crlfBytes, crlfBytes.size, targetAddress, account.port))
                }
            } catch (e: Exception) {
                log("Keep-alive ping send error: ${e.message}")
            }
        }
    }

    @Volatile
    private var lastResolvedServerAddress: InetAddress? = null

    private fun startKeepAlive(account: SipAccount) {
        keepAliveJob?.cancel()
        keepAliveJob = engineScope.launch {
            var counter = 0
            while (isActive && currentStatus == RegistrationStatus.REGISTERED) {
                delay(10_000) // Keep-alive tick every 10s (keeps BD mobile/Wi-Fi NAT pinholes open 24/7)
                try {
                    val socket = persistentSipSocket
                    if (socket == null || socket.isClosed) {
                        if (isInCall()) continue
                        log("Keep-alive: Socket momentarily unready or closed. Waiting...")
                        delay(2_000)
                        if (!isInCall() && currentStatus == RegistrationStatus.REGISTERED && (persistentSipSocket == null || persistentSipSocket?.isClosed == true)) {
                            log("Keep-alive: Socket closed while REGISTERED. Re-establishing connection...")
                            triggerRegister(account)
                        }
                        continue
                    }
                    val targetAddress = resolveServerAddress(account.server)
                    counter++
                    // During active call setup/conversation, send ONLY lightweight CRLF NAT keepalive (never clutter with OPTIONS)
                    if (isInCall()) {
                        val crlfBytes = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                        socket.send(DatagramPacket(crlfBytes, crlfBytes.size, targetAddress, account.port))
                        continue
                    }

                    // Active network/VPN/underlying-transport shift check every 10s (catches missed OEM callbacks & VPN underlying switches!)
                    val liveFingerprint = getNetworkFingerprint(appContext)
                    if (lastRegisteredNetworkFingerprint != null &&
                        isValidNetworkFingerprint(liveFingerprint) &&
                        liveFingerprint != lastRegisteredNetworkFingerprint &&
                        isNetworkConnected(appContext)
                    ) {
                        log("Keep-alive detected network/transport shift ($lastRegisteredNetworkFingerprint -> $liveFingerprint)! Triggering immediate handover...")
                        onNetworkHandover(appContext)
                        break
                    }

                    // Self-heal if registered with a private Contact IP on an IPv4 network (e.g. STUN was blocked during initial connect):
                    // send a fast STUN probe on persistentSipSocket; when startPersistentSipListener receives the STUN 0x0101 reply
                    // and populates publicIp, trigger a zero-downtime registration renewal to update the server's Contact binding!
                    if (!isIpv6Target(targetAddress) && serverMappedIp == null) {
                        if (publicIp == null || isPrivateIp(publicIp)) {
                            try {
                                val stunHost = InetAddress.getByName("74.125.250.129")
                                val req = ByteArray(20)
                                req[0] = 0x00; req[1] = 0x01
                                req[2] = 0x00; req[3] = 0x00
                                req[4] = 0x21; req[5] = 0x12; req[6] = 0xA4.toByte(); req[7] = 0x42
                                for (i in 8 until 20) req[i] = Random().nextInt(256).toByte()
                                socket.send(DatagramPacket(req, req.size, stunHost, 19302))
                            } catch (_: Exception) {}
                        } else if (stunMappedSocketLocalPort != socket.localPort) {
                            stunMappedSocketLocalPort = socket.localPort
                            log("Keep-alive: Public IP ($publicIp:$publicPort) recovered via STUN; renewing registration Contact...")
                            triggerRegister(account, isRenewal = true)
                        }
                    }

                    // Check whether previous OPTIONS ping received a response (dead-NAT pinhole detection!)
                    val prevSent = lastOptionsSentElapsedMs
                    var sendFastVerifyOptions = false
                    if (prevSent > 0L && lastOptionsResponseElapsedMs < prevSent && (SystemClock.elapsedRealtime() - prevSent) > 6_000L) {
                        // Reset lastOptionsSentElapsedMs immediately so one lost OPTIONS is never counted twice!
                        lastOptionsSentElapsedMs = 0L
                        consecutiveMissedOptions++
                        log("Keep-alive: No OPTIONS reply received ($consecutiveMissedOptions/2 missed). NAT pinhole may be stale.")
                        if (consecutiveMissedOptions >= 2 && isNetworkConnected(appContext)) {
                            log("Keep-alive: Dead NAT pinhole detected (2 missed OPTIONS)! Force re-registering fresh socket...")
                            consecutiveMissedOptions = 0
                            onNetworkHandover(appContext)
                            break
                        } else {
                            // Verify immediately on this tick instead of waiting another 20s (cuts Hotspot/VPN NAT recovery to ~15s!)
                            sendFastVerifyOptions = true
                        }
                    }

                    if (sendFastVerifyOptions || counter % 2 == 0) {
                        // Active SIP OPTIONS health check every 20 seconds (faster than 30s carrier NAT expiry!)
                        val localIp = getLocalAddressForTarget(targetAddress)
                        val (pingHost, pingPort) = getViaEndpoint(localIp, socket.localPort, targetAddress)
                        val ping = "OPTIONS sip:${account.server} SIP/2.0\r\n" +
                                "Via: SIP/2.0/UDP $pingHost:$pingPort;rport;branch=${generateBranch()}\r\n" +
                                "Max-Forwards: 70\r\n" +
                                "To: <sip:${account.server}>\r\n" +
                                "From: <sip:${account.username}@${account.server}>;tag=ping${Random().nextInt(9999)}\r\n" +
                                "Call-ID: ${generateOpaqueCallId()}\r\n" +
                                "CSeq: 1 OPTIONS\r\n" +
                                "User-Agent: $SIP_USER_AGENT\r\n" +
                                "Content-Length: 0\r\n\r\n"
                        val bytes = ping.toByteArray(Charsets.ISO_8859_1)
                        lastOptionsSentElapsedMs = SystemClock.elapsedRealtime()
                        socket.send(DatagramPacket(bytes, bytes.size, targetAddress, account.port))
                    } else {
                        // RFC 5626 double CRLF keepalive
                        val crlfBytes = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                        socket.send(DatagramPacket(crlfBytes, crlfBytes.size, targetAddress, account.port))
                    }
                } catch (e: Exception) {
                    log("Keep-alive warning: ${e.message}")
                    if (!isInCall() && (e is java.net.SocketException || e is java.io.IOException)) {
                        log("Keep-alive detected broken or unreachable socket (${e.message}). Re-establishing connection...")
                        try { persistentSipSocket?.close() } catch (_: Exception) {}
                        persistentSipSocket = null
                        triggerRegister(account, delayMs = 1000L, force = true)
                        break
                    }
                }
            }
        }
    }

    // =========================================================================
    // PROTOCOL & SDP PARSERS (Dual-Stack IPv4 + Native IPv6 NAT64 / DNS64)
    // =========================================================================

    private fun extractNat64PrefixFromLinkProperties(context: Context? = appContext): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val ctx = context ?: appContext ?: return null
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val activeNet = cm.activeNetwork ?: return null
            val lp = cm.getLinkProperties(activeNet) ?: return null
            val prefix = lp.nat64Prefix ?: return null
            val bytes = prefix.address.address
            if (bytes.size == 16 && prefix.prefixLength == 96) bytes.copyOfRange(0, 12) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Truly cancellable DNS lookup helper: runs blocking InetAddress.getAllByName() on a daemon
     * thread pool and races it against withTimeoutOrNull so degraded carrier DNS NEVER freezes
     * coroutines for 15-30s or causes BroadcastReceiver ANRs.
     */
    private suspend fun resolveAddressesWithTimeout(host: String, timeoutMs: Long): Array<InetAddress>? {
        val deferred = CompletableDeferred<Array<InetAddress>?>()
        try {
            dnsExecutor.execute {
                val res = try {
                    InetAddress.getAllByName(host)
                } catch (_: Exception) {
                    null
                }
                deferred.complete(res)
            }
        } catch (_: Exception) {
            return null
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    private suspend fun discoverNat64PrefixBytes(context: Context? = appContext): ByteArray? {
        extractNat64PrefixFromLinkProperties(context)?.let { return it }
        // RFC 7050: Discover carrier NAT64 /96 prefix via ipv4only.arpa (192.0.0.170 / 192.0.0.171)
        return try {
            val addrs = resolveAddressesWithTimeout("ipv4only.arpa", 1800L) ?: return null
            for (addr in addrs) {
                if (addr is java.net.Inet6Address) {
                    val b = addr.address
                    if (b.size == 16 &&
                        (b[12].toInt() and 0xFF == 192) &&
                        (b[13].toInt() and 0xFF == 0) &&
                        (b[14].toInt() and 0xFF == 0) &&
                        ((b[15].toInt() and 0xFF == 170) || (b[15].toInt() and 0xFF == 171))) {
                        return b.copyOfRange(0, 12)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractNat64PrefixFromAddress(addr: InetAddress?, knownIpv4: String? = null): ByteArray? {
        if (addr !is java.net.Inet6Address) return null
        val b = addr.address
        if (b.size != 16) return null
        // 1. Well-known RFC 6052 prefix 64:ff9b::/96
        val isWellKnown = (b[0].toInt() and 0xFF == 0x00) &&
                (b[1].toInt() and 0xFF == 0x64) &&
                (b[2].toInt() and 0xFF == 0xFF) &&
                (b[3].toInt() and 0xFF == 0x9B) &&
                (4..11).all { b[it] == 0.toByte() }
        if (isWellKnown) return b.copyOfRange(0, 12)
        // 2. Custom carrier /96 prefix matching known IPv4 suffix bytes
        if (!knownIpv4.isNullOrBlank()) {
            try {
                val v4 = InetAddress.getByName(knownIpv4).address
                if (v4.size == 4 && b[12] == v4[0] && b[13] == v4[1] && b[14] == v4[2] && b[15] == v4[3]) {
                    return b.copyOfRange(0, 12)
                }
            } catch (_: Exception) {}
        }
        // 3. Android LinkProperties NAT64 prefix match
        val lpPrefix = extractNat64PrefixFromLinkProperties(appContext)
        if (lpPrefix != null && (0 until 12).all { b[it] == lpPrefix[it] }) {
            return lpPrefix
        }
        return null
    }

    private fun synthesizeNat64Address(prefixBytes: ByteArray, ipv4Addr: InetAddress): InetAddress? {
        val v4 = ipv4Addr.address
        if (prefixBytes.size != 12 || v4.size != 4) return null
        val combined = ByteArray(16)
        System.arraycopy(prefixBytes, 0, combined, 0, 12)
        System.arraycopy(v4, 0, combined, 12, 4)
        return try {
            InetAddress.getByAddress(combined)
        } catch (_: Exception) {
            null
        }
    }

    private fun adaptMediaAddressForPeer(currentMediaAddr: InetAddress, peerSignalingAddr: InetAddress): InetAddress {
        if (peerSignalingAddr is java.net.Inet6Address && currentMediaAddr is java.net.Inet4Address) {
            val prefix = activeNat64PrefixBytes ?: extractNat64PrefixFromAddress(peerSignalingAddr)
            if (prefix != null) {
                synthesizeNat64Address(prefix, currentMediaAddr)?.let { return it }
            }
            return peerSignalingAddr
        }
        if (peerSignalingAddr is java.net.Inet4Address && currentMediaAddr is java.net.Inet6Address) {
            extractIpv4FromNat64Ipv6(cleanHostAddress(currentMediaAddr))?.let { v4Str ->
                try { return InetAddress.getByName(v4Str) } catch (_: Exception) {}
            }
            return peerSignalingAddr
        }
        return currentMediaAddr
    }

    private suspend fun resolveServerAddress(
        serverHost: String,
        forceRefresh: Boolean = false,
        forceIpv4: Boolean = false
    ): InetAddress = withContext(Dispatchers.IO) {
        val clean = serverHost.trim()
        val hostWithoutPort = if (clean.startsWith("[")) {
            clean.substringAfter("[").substringBefore("]")
        } else if (clean.count { it == ':' } == 1) {
            clean.substringBefore(":")
        } else {
            clean
        }
        val lowerHost = hostWithoutPort.lowercase()
        val now = SystemClock.elapsedRealtime()

        // Fast in-memory cache hit (avoids redundant DNS queries on 10s keepalive ticks)
        val cached = lastResolvedServerAddress
        if (!forceRefresh && !forceIpv4 && cached != null && cachedResolvedHost == lowerHost && (now - cachedResolvedTimestampMs < 300_000L)) {
            if (!preferIpv4FallbackForCurrentNetwork || cached is java.net.Inet4Address) {
                return@withContext cached
            }
        }

        val mappedIpv4 = knownIpFallbacks[lowerHost]
            ?: if (lowerHost.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) lowerHost else null
        val vpnActive = isVpnActive(appContext)
        val vpnProvidesIpv4 = vpnActive && getLocalIpv4Address(appContext) != "127.0.0.1"

        // Dual-stack Native IPv6 / DNS64 NAT64 resolution:
        // When the active network has a global unicast IPv6 (e.g. Grameenphone/Robi/Banglalink 4G/5G SIM or Hotspot),
        // prefer DNS64/NAT64 native IPv6 (AF_INET6) so SIP + SDP IN IP6 match end-to-end with ZERO clatd translation
        // and ZERO Android kernel nf_conntrack_sip NF_DROP/EPERM!
        // SECURITY: When VPN provides IPv4, strictly route IPv4 through the VPN tunnel so an IPv4-only VPN
        // can never be bypassed over the underlying carrier's IPv6 interface.
        if (!forceIpv4 && !preferIpv4FallbackForCurrentNetwork && !vpnProvidesIpv4 && getLocalIpv6Address(null, appContext) != null) {
            val dnsResults = resolveAddressesWithTimeout(hostWithoutPort, 2500L)

            val dnsIpv6 = dnsResults?.firstOrNull { it is java.net.Inet6Address }
            if (dnsIpv6 != null) {
                val detectedPrefix = extractNat64PrefixFromAddress(dnsIpv6, mappedIpv4)
                activeNat64PrefixBytes = detectedPrefix
                cachedResolvedHost = lowerHost
                cachedResolvedTimestampMs = now
                lastResolvedServerAddress = dnsIpv6
                log("Resolved $hostWithoutPort -> ${cleanHostAddress(dnsIpv6)} (Native IPv6${if (detectedPrefix != null) " NAT64" else ""})")
                return@withContext dnsIpv6
            }

            // DNS returned only IPv4 or timed out (e.g. custom Private DNS):
            // Only probe RFC 7050 ipv4only.arpa when the network has NO native IPv4 (IPv6-only mobile carrier)
            // or LinkProperties already reports a NAT64 prefix — avoids 1.8s delay on dual-stack Wi-Fi!
            val hasNativeIpv4 = getLocalIpv4Address(appContext) != "127.0.0.1"
            val nat64Prefix = if (!hasNativeIpv4) {
                discoverNat64PrefixBytes(appContext)
            } else {
                extractNat64PrefixFromLinkProperties(appContext)
            }
            if (nat64Prefix != null) {
                val v4Target = dnsResults?.firstOrNull { it is java.net.Inet4Address }
                    ?: mappedIpv4?.let { try { InetAddress.getByName(it) } catch (_: Exception) { null } }
                if (v4Target is java.net.Inet4Address) {
                    val synthesized = synthesizeNat64Address(nat64Prefix, v4Target)
                    if (synthesized != null) {
                        activeNat64PrefixBytes = nat64Prefix
                        cachedResolvedHost = lowerHost
                        cachedResolvedTimestampMs = now
                        lastResolvedServerAddress = synthesized
                        log("Synthesized Native IPv6 NAT64 for $hostWithoutPort -> ${cleanHostAddress(synthesized)}")
                        return@withContext synthesized
                    }
                }
            }
        }

        // Standard IPv4 network (or Dual-Stack broadband without NAT64, or explicit IPv4 failover)
        activeNat64PrefixBytes = null
        if (mappedIpv4 != null) {
            val addr = InetAddress.getByName(mappedIpv4)
            cachedResolvedHost = lowerHost
            cachedResolvedTimestampMs = now
            lastResolvedServerAddress = addr
            log("Resolved $hostWithoutPort -> $mappedIpv4 (IPv4)")
            return@withContext addr
        }
        val resolved = try {
            val all = resolveAddressesWithTimeout(hostWithoutPort, 3000L)
                ?: throw java.net.UnknownHostException("DNS lookup timed out for $hostWithoutPort")
            all.firstOrNull { it is java.net.Inet4Address } ?: all.first()
        } catch (e: Exception) {
            if (cached != null && cachedResolvedHost == lowerHost && (!forceIpv4 || cached is java.net.Inet4Address)) {
                log("DNS lookup failed for $hostWithoutPort (${e.message}). Using cached server IP: ${cleanHostAddress(cached)}")
                cached
            } else {
                throw e
            }
        }
        cachedResolvedHost = lowerHost
        cachedResolvedTimestampMs = now
        lastResolvedServerAddress = resolved
        resolved
    }

    private fun extractStatusCode(sipResponse: String): Int {
        val firstLine = sipResponse.lines().firstOrNull() ?: return 0
        val parts = firstLine.split(" ")
        if (parts.size >= 2) {
            return parts[1].toIntOrNull() ?: 0
        }
        return 0
    }

    private fun extractHeader(sipMsg: String, headerName: String): String? {
        val unfolded = sipMsg.replace(HEADER_UNFOLD_REGEX, " ")
        val lowerName = headerName.lowercase()
        val compact = compactHeaderMap[lowerName]

        for (line in unfolded.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            // RFC 3261 §7.3.1 allows optional linear whitespace before ':' (e.g. "Call-ID : ...")
            val key = line.substring(0, colonIdx).trim()
            if (key.equals(lowerName, ignoreCase = true) || (compact != null && key.equals(compact, ignoreCase = true))) {
                return line.substring(colonIdx + 1).trim()
            }
        }
        return null
    }

    private fun extractAllViaHeaders(sipMsg: String): String {
        val unfolded = sipMsg.replace(HEADER_UNFOLD_REGEX, " ")
        val viaLines = unfolded.lines().filter {
            it.startsWith("Via:", ignoreCase = true) || it.startsWith("v:", ignoreCase = true)
        }
        return if (viaLines.isNotEmpty()) {
            viaLines.joinToString("\r\n") + "\r\n"
        } else {
            extractHeader(sipMsg, "Via")?.let { "Via: $it\r\n" } ?: ""
        }
    }

    /**
     * RFC 3581 Section 4: Populates empty `;rport` on the top Via header in SIP responses
     * with `;rport=<remotePort>;received=<remoteIp>` so gateways like SemuxGW/2.0 match transactions cleanly.
     */
    private fun formatResponseViaHeaders(viaBlock: String, remoteAddr: InetAddress, remotePort: Int): String {
        val lines = viaBlock.split("\r\n").filter { it.isNotBlank() }.toMutableList()
        if (lines.isEmpty()) return viaBlock
        var top = lines[0]

        // RFC 3261 §18.2.1: Preserve the server's Via header VERBATIM whenever the packet source IP & port
        // already match the sent-by host:port (prevents breaking gateways like SemuxGW/2.0 and IPv6 NAT64
        // SIP ALGs that send uncompressed [0064:FF9B:0000:0000:0000:0000:67AA:E70A]:5060!).
        val remoteIp = cleanHostAddress(remoteAddr)
        val remoteMappedV4 = extractIpv4FromNat64Ipv6(remoteIp)
        val sentByMatch = Regex("""SIP/2\.0/\S+\s+(\[[^\]]+\]|[^;:\s]+)(?::(\d+))?""", RegexOption.IGNORE_CASE).find(top)
        val sentByHost = sentByMatch?.groupValues?.get(1)?.removePrefix("[")?.removeSuffix("]")?.trim() ?: ""
        val sentByPort = sentByMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5060

        // Compare by parsed IP bytes so uncompressed IPv6 ("0064:FF9B:0000:...") matches compressed ("64:ff9b::...")!
        val sameIpBytes = if (sentByHost.isNotBlank() && (sentByHost.contains(':') || sentByHost.all { it.isDigit() || it == '.' })) {
            try {
                InetAddress.getByName(sentByHost).address.contentEquals(remoteAddr.address)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        val sameHost = sameIpBytes ||
            sentByHost.equals(remoteIp, ignoreCase = true) ||
            (remoteMappedV4 != null && sentByHost.equals(remoteMappedV4, ignoreCase = true))
        val samePort = (sentByPort == remotePort)

        if (!sameHost || !samePort) {
            if (!samePort && Regex(""";\s*rport(?=$|;)""", RegexOption.IGNORE_CASE).containsMatchIn(top)) {
                top = top.replace(Regex(""";\s*rport(?=$|;)""", RegexOption.IGNORE_CASE), ";rport=$remotePort")
            }
            if (!sameHost && !top.contains("received=", ignoreCase = true)) {
                val formattedReceived = if (remoteIp.contains(':')) "[$remoteIp]" else remoteIp
                // Insert ;received= BEFORE ;branch= so naive parsers reading branch= to EOL still match!
                val branchIdx = top.indexOf(";branch=", ignoreCase = true)
                top = if (branchIdx > 0) {
                    top.substring(0, branchIdx) + ";received=$formattedReceived" + top.substring(branchIdx)
                } else {
                    "$top;received=$formattedReceived"
                }
            }
        }
        lines[0] = top
        return lines.joinToString("\r\n") + "\r\n"
    }

    private fun extractTag(headerVal: String): String? {
        return TAG_REGEX.find(headerVal)?.groupValues?.get(1)
    }

    private fun extractAuthParam(authHeader: String, paramName: String): String? {
        val pattern = Regex("""(?:^|[\s,])$paramName\s*=\s*(?:"([^"]*)"|([^,\s]+))""", RegexOption.IGNORE_CASE)
        val match = pattern.find(authHeader) ?: return null
        val quoted = match.groups[1]?.value
        if (quoted != null) return quoted
        return match.groups[2]?.value
    }

    /**
     * Converts an IPv6 NAT64 (RFC 6052 0064:ff9b::/96 or active carrier /96 prefix) or IPv4-mapped (::ffff:0:0/96)
     * address back into a dotted-quad IPv4 string when operating on an IPv4 transport.
     */
    private fun extractIpv4FromNat64Ipv6(v6: String): String? {
        return try {
            val bytes = InetAddress.getByName(v6.substringBefore('%').trim()).address
            if (bytes.size != 16) return null
            val isWellKnownNat64 = (bytes[0].toInt() and 0xFF == 0x00) &&
                    (bytes[1].toInt() and 0xFF == 0x64) &&
                    (bytes[2].toInt() and 0xFF == 0xFF) &&
                    (bytes[3].toInt() and 0xFF == 0x9B) &&
                    (4..11).all { bytes[it] == 0.toByte() }
            val activePrefix = activeNat64PrefixBytes
            val matchesActivePrefix = activePrefix != null && activePrefix.size == 12 &&
                    (0 until 12).all { bytes[it] == activePrefix[it] }
            val isV4Mapped = (0..9).all { bytes[it] == 0.toByte() } &&
                    (bytes[10].toInt() and 0xFF == 0xFF) &&
                    (bytes[11].toInt() and 0xFF == 0xFF)
            if (isWellKnownNat64 || matchesActivePrefix || isV4Mapped) {
                "${bytes[12].toInt() and 0xFF}.${bytes[13].toInt() and 0xFF}.${bytes[14].toInt() and 0xFF}.${bytes[15].toInt() and 0xFF}"
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * RFC 4566 §5.7: Extracts raw media-level `c=IN IP4/IP6` inside `m=audio` first, else session-level `c=`.
     */
    private fun extractRawSdpIp(sdpMsg: String): String? {
        val audioIdx = sdpMsg.indexOf("m=audio", ignoreCase = true)
        if (audioIdx >= 0) {
            val afterAudio = sdpMsg.substring(audioIdx)
            val nextMediaIdx = afterAudio.indexOf("\nm=", startIndex = 7, ignoreCase = true)
            val audioSection = if (nextMediaIdx > 0) afterAudio.substring(0, nextMediaIdx) else afterAudio
            val mediaLevelIp4 = SDP_IP_REGEX.find(audioSection)?.groupValues?.get(1)
            if (!mediaLevelIp4.isNullOrBlank()) return mediaLevelIp4.trim()
            val mediaLevelIp6 = SDP_IP6_REGEX.find(audioSection)?.groupValues?.get(1)
            if (!mediaLevelIp6.isNullOrBlank()) return mediaLevelIp6.substringBefore('%').trim()
        }
        val sessIp4 = SDP_IP_REGEX.find(sdpMsg)?.groupValues?.get(1)
        if (!sessIp4.isNullOrBlank()) return sessIp4.trim()
        val sessIp6 = SDP_IP6_REGEX.find(sdpMsg)?.groupValues?.get(1)
        if (!sessIp6.isNullOrBlank()) return sessIp6.substringBefore('%').trim()
        return null
    }

    /**
     * Dual-stack RTP media endpoint resolver:
     * - When signaling over Native IPv6 NAT64 (AF_INET6): keeps remote `c=IN IP6` as `Inet6Address` directly,
     *   and synthesizes `nat64Prefix + ipv4` if remote SDP contains a public `c=IN IP4`!
     * - When signaling over IPv4 (AF_INET): resolves `c=IN IP4` directly and unwraps any NAT64 IPv6 back to IPv4.
     */
    private fun resolveRemoteMediaAddress(sdpMsg: String, signalingPeerAddress: InetAddress): InetAddress? {
        val raw = extractRawSdpIp(sdpMsg) ?: return null
        if (raw == "0.0.0.0" || raw == "::") return null
        return try {
            if (signalingPeerAddress is java.net.Inet6Address) {
                if (raw.contains(":")) {
                    if (isPrivateIp(raw)) signalingPeerAddress else InetAddress.getByName(raw)
                } else {
                    if (isPrivateIp(raw)) {
                        signalingPeerAddress
                    } else {
                        val v4 = InetAddress.getByName(raw)
                        val prefix = activeNat64PrefixBytes ?: extractNat64PrefixFromAddress(signalingPeerAddress)
                        if (prefix != null && v4 is java.net.Inet4Address) {
                            synthesizeNat64Address(prefix, v4) ?: v4
                        } else {
                            v4
                        }
                    }
                }
            } else {
                if (raw.contains(":")) {
                    val unwrapped = extractIpv4FromNat64Ipv6(raw)
                    if (unwrapped != null && !isPrivateIp(unwrapped)) {
                        InetAddress.getByName(unwrapped)
                    } else {
                        signalingPeerAddress
                    }
                } else {
                    if (isPrivateIp(raw) && !isPrivateIp(signalingPeerAddress.hostAddress)) {
                        signalingPeerAddress
                    } else {
                        InetAddress.getByName(raw)
                    }
                }
            }
        } catch (_: Exception) {
            signalingPeerAddress
        }
    }

    private fun extractSdpAudioPortNullable(sdpMsg: String): Int? {
        return SDP_PORT_REGEX.find(sdpMsg)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractSdpAudioPort(sdpMsg: String): Int {
        return extractSdpAudioPortNullable(sdpMsg) ?: 40000
    }

    private fun extractSdpCodec(sdpMsg: String): Int {
        val match = SDP_CODEC_REGEX.find(sdpMsg)
        if (match != null) {
            val codecs = match.groupValues[1].trim().split(WHITESPACE_REGEX).mapNotNull { it.toIntOrNull() }
            for (c in codecs) {
                if (c == 8) return 8
                if (c == 0) return 0
            }
        }
        return 8 // Standard in Bangladesh (PCMA)
    }

    private fun notifyStatus(status: RegistrationStatus, message: String) {
        currentStatus = status
        for (listener in statusListeners) {
            try {
                listener.invoke(status, message)
            } catch (e: Exception) {
                log("Error invoking status listener: ${e.message}")
            }
        }
    }

    private fun notifyCallState(state: CallState, message: String, expectedGen: Long? = null) {
        val shouldDispatch: Boolean
        synchronized(callStateLock) {
            if (expectedGen != null && callGeneration.get() != expectedGen) {
                log("Dropping stale call state notification ($state / $message) from superseded call generation.")
                return
            }
            shouldDispatch = true
            currentCallState = state
            when (state) {
                CallState.CONNECTED -> {
                    if (callConnectedStartTime == 0L) {
                        callConnectedStartTime = SystemClock.elapsedRealtime()
                    }
                    appContext?.let {
                        com.opentalk.android.services.SipVoipService.startCall(
                            it,
                            activeCallNumber ?: "Unknown",
                            activeCallName ?: activeCallNumber ?: "Unknown"
                        )
                    }
                }
                CallState.CONNECTING, CallState.RINGING -> {
                    if (!isCurrentCallIncoming && incomingCallId == null) {
                        appContext?.let {
                            com.opentalk.android.services.SipVoipService.startCall(
                                it,
                                activeCallNumber ?: "Unknown",
                                activeCallName ?: activeCallNumber ?: "Unknown"
                            )
                        }
                    }
                    Unit
                }
                CallState.ENDED, CallState.FAILED, CallState.IDLE -> {
                    isCallOnHold = false
                    CallProximityManager.forceRelease("SipEngine CallState.$state")
                    hasSessionProgress = false
                    callConnectedStartTime = 0L
                    activeCallNumber = null
                    activeCallName = null
                    appContext?.let {
                        com.opentalk.android.services.SipVoipService.callEnded(it)
                    }
                    Unit
                }
            }
            Unit
        }
        if (!shouldDispatch) return
        for (listener in callStateListeners) {
            try {
                listener.invoke(state, message)
            } catch (e: Exception) {
                log("Error invoking call state listener: ${e.message}")
            }
        }
        try {
            onCallStateListener?.invoke(state, message)
        } catch (e: Exception) {
            log("Error invoking legacy call state listener: ${e.message}")
        }
    }
}

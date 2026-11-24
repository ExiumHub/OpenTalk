package com.opentalk.android.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.opentalk.android.engine.SipEngine
import com.opentalk.android.models.CallRecord
import com.opentalk.android.models.CallType
import com.opentalk.android.notifications.MissedCallNotifier
import com.opentalk.android.storage.CallHistoryDbHelper
import com.opentalk.android.storage.SecureAccountManager
import com.opentalk.android.utils.ContactResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * AmarIP / Sarker Communication Limited (SCL) Automated CDR Sync Manager.
 */
object CdrSyncManager {

    private const val TAG = "CdrSyncManager"
    private const val API_BASE_URL = "https://amarip.net"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val CDR_READ_TIMEOUT_MS = 18_000
    private const val PERIODIC_SYNC_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
    private const val CDR_PAGE_SIZE = 25
    private const val MAX_CDR_PAGES = 2
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 // 2 MB cap against OOM/captive portals

    private val OFFSET_NO_COLON_FLEX_FORMATTER = java.time.format.DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .optionalStart()
        .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 1, 9, true)
        .optionalEnd()
        .appendPattern("XX")
        .toFormatter(Locale.US)
    private val SPACE_BEFORE_OFFSET_REGEX = Regex("""\s+([+-]\d{2}:?\d{2}|Z)$""", RegexOption.IGNORE_CASE)
    private val FIRST_DATE_TIME_SPACE_REGEX = Regex("""^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})""")
    private val BALANCE_NUM_REGEX = Regex("""-?\d+(?:\.\d+)?""")

    sealed class SyncResult {
        object InProgress : SyncResult()
        data class Success(val newCallsCount: Int, val balance: String?) : SyncResult()
        data class Error(val message: String, val code: Int? = null) : SyncResult()
    }

    private sealed class CdrFetchResult {
        data class Success(val records: JSONArray) : CdrFetchResult()
        data class Error(val message: String, val code: Int? = null) : CdrFetchResult()
    }

    private data class LoginOutcome(
        val token: String?,
        val errorMessage: String?,
        val httpCode: Int?
    )

    private data class PendingResync(
        val isManual: Boolean,
        val overrideUser: String?,
        val overridePass: String?
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    private val pendingLock = Any()
    private var syncRunning = false
    private var pendingResync: PendingResync? = null



    // -1L means "never synced yet" so first sync immediately after boot is never blocked by elapsedRealtime()
    @Volatile
    private var lastSyncAttemptTime = -1L

    @Volatile
    private var lastPeriodicSyncTime = -1L

    @Volatile
    private var lastExtractedBalance: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var syncCompleteListener: ((newCallsCount: Int) -> Unit)? = null

    @Volatile
    private var balanceUpdatedListener: ((balance: String) -> Unit)? = null

    @Volatile
    private var syncResultListener: ((SyncResult) -> Unit)? = null

    /**
     * Strict domain/host match for AmarIP / Sarker Communication servers.
     * Prevents leaking third-party SIP credentials to https://amarip.net/api/login.
     */
    fun isAmarIpServer(server: String): Boolean {
        val host = server.trim()
            .substringAfter("://", server.trim())
            .substringBefore("/")
            .substringBefore(":")
            .lowercase(Locale.ROOT)
        if (host.isEmpty()) return false
        return host == "sarkercommunication.com" ||
            host.endsWith(".sarkercommunication.com") ||
            host == "amarip.net" ||
            host.endsWith(".amarip.net") ||
            host == "banglacalls.com" ||
            host.endsWith(".banglacalls.com") ||
            host == "103.170.231.10" ||
            host.startsWith("103.170.231.")
    }

    fun setOnSyncCompleteListener(listener: ((newCallsCount: Int) -> Unit)?) {
        this.syncCompleteListener = listener
    }

    fun setOnBalanceUpdatedListener(listener: ((balance: String) -> Unit)?) {
        this.balanceUpdatedListener = listener
    }

    fun setOnSyncResultListener(listener: ((SyncResult) -> Unit)?) {
        this.syncResultListener = listener
    }

    private fun dispatchResult(result: SyncResult, isManual: Boolean) {
        if (!isManual) return
        mainHandler.post {
            syncResultListener?.invoke(result)
        }
    }

    /**
     * Periodic background trigger called by SipWatchdogReceiver or Background Service.
     * Runs Keystore/prefs check off the Main thread and enforces a 15-minute interval.
     */
    fun maybeTriggerPeriodicSync(context: Context) {
        if (!androidx.core.os.UserManagerCompat.isUserUnlocked(context)) return
        val now = SystemClock.elapsedRealtime()
        val last = lastPeriodicSyncTime
        if (last >= 0L && now - last < PERIODIC_SYNC_INTERVAL_MS) return
        val appCtx = context.applicationContext
        syncScope.launch {
            val accountManager = SecureAccountManager(appCtx)
            if (!accountManager.isOfflineSyncEnabled()) return@launch
            val checkNow = SystemClock.elapsedRealtime()
            val prev = lastPeriodicSyncTime
            if (prev < 0L || checkNow - prev >= PERIODIC_SYNC_INTERVAL_MS) {
                lastPeriodicSyncTime = checkNow
                Log.d(TAG, "Periodic 15-min background CDR sync triggered.")
                syncInternal(appCtx, force = false, isManual = false)
            }
        }
    }

    /**
     * Trigger synchronization on the shared background IO scope.
     * Safe to call from UI thread or background service.
     */
    fun triggerSync(context: Context, force: Boolean = false, isManual: Boolean = false) {
        if (!androidx.core.os.UserManagerCompat.isUserUnlocked(context)) {
            Log.d(TAG, "Device is locked in Direct Boot mode; skipping CDR sync until user unlock.")
            dispatchResult(SyncResult.Error("Device is locked (Direct Boot mode)"), isManual)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = lastSyncAttemptTime
        if (!force && !isManual && last >= 0L && (now - last < 10_000L)) {
            return
        }
        lastSyncAttemptTime = now

        val appCtx = context.applicationContext
        syncScope.launch {
            syncInternal(appCtx, force = force, isManual = isManual)
        }
    }

    /**
     * One-shot credential test without mutating persisted preferences.
     */
    fun testCredentialsAndSync(
        context: Context,
        customUser: String,
        customPass: String
    ) {
        if (!androidx.core.os.UserManagerCompat.isUserUnlocked(context)) {
            dispatchResult(SyncResult.Error("Device is locked (Direct Boot mode)"), isManual = true)
            return
        }
        val appCtx = context.applicationContext
        syncScope.launch {
            syncInternal(
                appCtx,
                force = true,
                isManual = true,
                overridePortalUser = customUser.trim(),
                overridePortalPass = customPass.trim()
            )
        }
    }

    private suspend fun syncInternal(
        context: Context,
        force: Boolean = false,
        isManual: Boolean = false,
        overridePortalUser: String? = null,
        overridePortalPass: String? = null
    ): Int = withContext(Dispatchers.IO) {
        val alreadyRunning = synchronized(pendingLock) {
            if (syncRunning) {
                if (force || isManual) {
                    Log.d(TAG, "Sync already in progress; queueing forced/manual follow-up sync.")
                    val prev = pendingResync
                    pendingResync = PendingResync(
                        isManual = (prev?.isManual == true) || isManual,
                        // Always use the latest caller's credentials so a prior test-credentials sync never leaks into a normal sync
                        overrideUser = overridePortalUser,
                        overridePass = overridePortalPass
                    )
                }
                true
            } else {
                syncRunning = true
                false
            }
        }
        if (alreadyRunning) {
            dispatchResult(SyncResult.InProgress, isManual)
            return@withContext 0
        }

        var insertedCount = 0
        try {
            val accountManager = SecureAccountManager(context)
            if (!isManual && !accountManager.isOfflineSyncEnabled()) {
                Log.d(TAG, "Offline sync is disabled by user.")
                dispatchResult(SyncResult.Error("Auto Sync is disabled in settings"), isManual)
                return@withContext 0
            }

            if (!SipEngine.isNetworkConnected(context)) {
                Log.d(TAG, "No internet connection for CDR sync.")
                dispatchResult(SyncResult.Error("No internet connection"), isManual)
                return@withContext 0
            }

            val currentAccount = accountManager.getAccount()
            val customPortalUser = (overridePortalUser ?: accountManager.getCustomWebPortalUsername()).trim()
            val customPortalPass = (overridePortalPass ?: accountManager.getCustomWebPortalPassword()).trim()
            val isAmarIpHost = isAmarIpServer(currentAccount.server)
            // Require BOTH explicit portal username AND password when using a non-AmarIP SIP host
            val hasExplicitPortalPair = customPortalUser.isNotEmpty() && customPortalPass.isNotEmpty()

            if (!isAmarIpHost && !hasExplicitPortalPair) {
                val msg = "Custom non-AmarIP SIP provider (${currentAccount.server}). Skipping AmarIP CDR/Balance sync."
                Log.d(TAG, msg)
                dispatchResult(SyncResult.Error("Non-AmarIP provider (${currentAccount.server})"), isManual)
                return@withContext 0
            }

            // Never fall back to third-party SIP credentials on non-AmarIP servers!
            val username = if (isAmarIpHost) {
                customPortalUser.ifEmpty { currentAccount.username.trim() }
            } else {
                customPortalUser
            }
            val password = if (isAmarIpHost) {
                customPortalPass.ifEmpty { currentAccount.password.trim() }
            } else {
                customPortalPass
            }

            if (username.isBlank() || password.isBlank()) {
                Log.w(TAG, "Web Portal username or password is empty.")
                dispatchResult(SyncResult.Error("Portal credentials not configured"), isManual)
                return@withContext 0
            }

            dispatchResult(SyncResult.InProgress, isManual)

            // Step 1: Authenticate with https://amarip.net/api/login to get Bearer JWT token
            lastExtractedBalance = null
            val loginOutcome = loginAmarIp(context, username, password)
            val token = loginOutcome.token
            if (token.isNullOrBlank()) {
                val errMessage = loginOutcome.errorMessage ?: "AmarIP authentication failed (Invalid credentials)"
                Log.w(TAG, "AmarIP login failed: $errMessage (code=${loginOutcome.httpCode})")
                dispatchResult(SyncResult.Error(errMessage, loginOutcome.httpCode), isManual)
                return@withContext 0
            }

            // Step 2: Fetch and update latest account balance
            val currentBal = lastExtractedBalance ?: fetchAccountBalance(context, token)
            if (!currentBal.isNullOrBlank()) {
                accountManager.setBalance(currentBal)
                Log.i(TAG, "Updated account balance: BDT $currentBal")
                mainHandler.post {
                    balanceUpdatedListener?.invoke(currentBal)
                }
            }

            // Step 3: Fetch incoming CDR records across up to MAX_CDR_PAGES pages
            val allRecords = mutableListOf<JSONObject>()
            for (page in 1..MAX_CDR_PAGES) {
                when (val fetchRes = fetchIncomingCdr(context, token, page)) {
                    is CdrFetchResult.Error -> {
                        if (page == 1) {
                            dispatchResult(SyncResult.Error(fetchRes.message, fetchRes.code), isManual)
                            return@withContext 0
                        }
                        break
                    }
                    is CdrFetchResult.Success -> {
                        val pageArr = fetchRes.records
                        for (i in 0 until pageArr.length()) {
                            pageArr.optJSONObject(i)?.let { allRecords.add(it) }
                        }
                        if (pageArr.length() < CDR_PAGE_SIZE) break
                    }
                }
            }

            if (allRecords.isEmpty()) {
                Log.d(TAG, "No incoming CDR records returned from AmarIP.")
                dispatchResult(SyncResult.Success(0, currentBal), isManual)
                return@withContext 0
            }

            // Step 4: Persist newly missed calls using singleton DB helper
            val dbHelper = CallHistoryDbHelper.getInstance(context)
            var latestInsertedCaller: String? = null
            var latestInsertedName: String? = null
            var latestInsertedEpoch = Long.MIN_VALUE

            val activeStatuses = setOf("ringing", "initiated", "in_progress", "dialing", "trying", "early")
            val answeredStatuses = setOf("completed", "answered", "answer", "normal_clearing", "connected")
            val missedStatuses = setOf("no_answer", "failed", "busy", "cancelled", "canceled", "timeout", "chanunavail", "congestion")

            for (item in allRecords) {
                val rawCaller = (extractNonNullString(item, "caller_number")
                    ?: extractNonNullString(item, "caller")
                    ?: "").trim()
                val caller = com.opentalk.android.utils.PhoneNumberHelper.toCanonicalE164(
                    ContactResolver.stripOperatorName(rawCaller)
                )
                if (caller.isBlank()) continue

                val status = (extractNonNullString(item, "status") ?: "").lowercase(Locale.ROOT).trim()
                val duration = item.optInt("duration", 0)

                if (status in activeStatuses || status in answeredStatuses) continue
                val isMissed = status in missedStatuses || (duration == 0 && status.isNotEmpty())
                if (!isMissed) continue

                val startTimeStr = (extractNonNullString(item, "start_time") ?: "").trim()
                val epochMs = parseIsoToEpochMs(startTimeStr) ?: continue

                if (dbHelper.callExists(caller, epochMs, listOf(CallType.MISSED, CallType.REJECTED, CallType.INCOMING))) {
                    continue
                }

                val contactName = ContactResolver.resolveContactName(context, caller)
                val displayName = if (!contactName.isNullOrBlank()) contactName else caller

                val isBlockedCaller = com.opentalk.android.storage.BlockListManager.isBlocked(context, caller)
                val record = CallRecord(
                    remoteNumber = caller,
                    displayName = displayName,
                    type = if (isBlockedCaller) CallType.REJECTED else CallType.MISSED,
                    timestamp = epochMs,
                    durationSeconds = 0,
                    accountId = currentAccount.id
                )

                val rowId = dbHelper.insertCall(record)
                if (rowId != -1L && !isBlockedCaller) {
                    insertedCount++
                    if (epochMs >= latestInsertedEpoch) {
                        latestInsertedEpoch = epochMs
                        latestInsertedCaller = caller
                        latestInsertedName = displayName
                    }
                }
            }

            if (insertedCount > 0 && latestInsertedCaller != null) {
                Log.i(TAG, "Successfully synced $insertedCount offline missed call(s) from AmarIP.")
                MissedCallNotifier.postMissedCall(
                    context,
                    latestInsertedCaller,
                    latestInsertedName ?: latestInsertedCaller,
                    increment = insertedCount
                )

                mainHandler.post {
                    syncCompleteListener?.invoke(insertedCount)
                }
            }

            dispatchResult(SyncResult.Success(insertedCount, currentBal), isManual)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing CDR: ${e.message}")
            dispatchResult(SyncResult.Error(e.localizedMessage ?: "CDR sync error"), isManual)
        } finally {
            val next = synchronized(pendingLock) {
                syncRunning = false
                val p = pendingResync
                pendingResync = null
                p
            }
            if (next != null) {
                syncScope.launch {
                    syncInternal(
                        context,
                        force = true,
                        isManual = next.isManual,
                        overridePortalUser = next.overrideUser,
                        overridePortalPass = next.overridePass
                    )
                }
            }
        }

        return@withContext insertedCount
    }

    private fun extractNonNullString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key, "").takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun drainErrorStream(conn: HttpURLConnection): String {
        return try {
            val stream = conn.errorStream ?: return ""
            readStream(stream)
        } catch (_: Exception) {
            ""
        }
    }

    private fun loginAmarIp(context: Context, username: String, pass: String): LoginOutcome {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$API_BASE_URL/api/login")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            }
            activeConnection.set(conn)

            val payload = JSONObject().apply {
                put("username", username)
                put("password", pass)
            }.toString()

            conn.outputStream.use { os: OutputStream ->
                val input = payload.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = readStream(conn.inputStream)
                val json = JSONObject(responseText)
                extractBalanceFromJson(json)?.let { bal ->
                    lastExtractedBalance = bal
                }
                val token = extractNonNullString(json, "token")
                    ?: extractNonNullString(json, "access_token")
                    ?: json.optJSONObject("data")?.let { extractNonNullString(it, "token") ?: extractNonNullString(it, "access_token") }
                LoginOutcome(token, null, responseCode)
            } else {
                val raw = drainErrorStream(conn)
                val defaultErr = if (responseCode == 401) "Invalid credentials (HTTP 401)" else "Server error (HTTP $responseCode)"
                val errorMsg = try {
                    if (raw.isNotBlank()) {
                        val json = JSONObject(raw)
                        extractNonNullString(json, "error")
                            ?: extractNonNullString(json, "message")
                            ?: defaultErr
                    } else {
                        defaultErr
                    }
                } catch (_: Exception) {
                    defaultErr
                }
                Log.w(TAG, "Login failed with HTTP code $responseCode: $errorMsg")
                LoginOutcome(null, errorMsg, responseCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login connection error: ${e.message}")
            LoginOutcome(null, e.localizedMessage ?: "Connection error", null)
        } finally {
            activeConnection.compareAndSet(conn, null)
            conn?.disconnect()
        }
    }

    private fun fetchIncomingCdr(context: Context, token: String, page: Int = 1): CdrFetchResult {
        var conn: HttpURLConnection? = null
        return try {
            val endpoint = "$API_BASE_URL/api/cdr?direction=incoming&page=$page&per_page=$CDR_PAGE_SIZE"
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CDR_READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            }
            activeConnection.set(conn)

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = readStream(conn.inputStream).trim()
                val arr = if (responseText.startsWith("[")) {
                    JSONArray(responseText)
                } else {
                    val root = JSONObject(responseText)
                    root.optJSONArray("data") ?: JSONArray()
                }
                CdrFetchResult.Success(arr)
            } else {
                drainErrorStream(conn)
                Log.w(TAG, "CDR query failed with HTTP code $responseCode")
                CdrFetchResult.Error("CDR fetch failed (HTTP $responseCode)", responseCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CDR fetch error: ${e.message}")
            CdrFetchResult.Error(e.localizedMessage ?: "CDR fetch error", null)
        } finally {
            activeConnection.compareAndSet(conn, null)
            conn?.disconnect()
        }
    }

    private fun fetchAccountBalance(context: Context, token: String): String? {
        val endpoints = arrayOf(
            "$API_BASE_URL/api/clients/me",
            "$API_BASE_URL/api/whoami",
            "$API_BASE_URL/api/clients",
            "$API_BASE_URL/api/sip-users",
            "$API_BASE_URL/api/balance",
            "$API_BASE_URL/api/dashboard",
            "$API_BASE_URL/api/user"
        )
        for (endpoint in endpoints) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(endpoint)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                }
                activeConnection.set(conn)
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val resp = readStream(conn.inputStream).trim()
                    val bal = if (resp.startsWith("[")) {
                        extractBalanceFromArray(JSONArray(resp))
                    } else {
                        extractBalanceFromJson(JSONObject(resp))
                    }
                    if (!bal.isNullOrBlank()) return bal
                } else {
                    drainErrorStream(conn)
                }
            } catch (_: Exception) {
            } finally {
                activeConnection.compareAndSet(conn, null)
                conn?.disconnect()
            }
        }
        return null
    }

    private fun extractBalanceFromArray(arr: JSONArray): String? {
        for (idx in 0 until arr.length()) {
            val item = arr.optJSONObject(idx) ?: continue
            val bal = extractBalanceFromJson(item)
            if (!bal.isNullOrBlank()) return bal
        }
        return null
    }

    private fun extractBalanceFromJson(json: JSONObject): String? {
        if (json.has("balance") && !json.isNull("balance")) {
            json.opt("balance")?.let { formatBalance(it)?.let { bal -> return bal } }
        }
        if (json.has("client_balance") && !json.isNull("client_balance")) {
            json.opt("client_balance")?.let { formatBalance(it)?.let { bal -> return bal } }
        }
        json.optJSONArray("data")?.let { arr ->
            extractBalanceFromArray(arr)?.let { return it }
        }
        json.optJSONObject("data")?.let { dataObj ->
            if (dataObj.has("balance") && !dataObj.isNull("balance")) {
                dataObj.opt("balance")?.let { formatBalance(it)?.let { bal -> return bal } }
            }
            if (dataObj.has("client_balance") && !dataObj.isNull("client_balance")) {
                dataObj.opt("client_balance")?.let { formatBalance(it)?.let { bal -> return bal } }
            }
        }
        json.optJSONObject("user")?.let { userObj ->
            if (userObj.has("balance") && !userObj.isNull("balance")) {
                userObj.opt("balance")?.let { formatBalance(it)?.let { bal -> return bal } }
            }
        }
        json.optJSONObject("account")?.let { accountObj ->
            if (accountObj.has("balance") && !accountObj.isNull("balance")) {
                accountObj.opt("balance")?.let { formatBalance(it)?.let { bal -> return bal } }
            }
        }
        return null
    }

    private fun formatBalance(value: Any): String? {
        return when (value) {
            is Number -> String.format(Locale.US, "%.2f", value.toDouble())
            is String -> {
                val match = BALANCE_NUM_REGEX.find(value.replace(",", ""))?.value
                val num = match?.toDoubleOrNull()
                if (num != null) String.format(Locale.US, "%.2f", num) else null
            }
            else -> null // Ignore JSONObject/JSONArray/Boolean
        }
    }

    private fun readStream(stream: java.io.InputStream): String {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        stream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val remaining = MAX_RESPONSE_BYTES - total
                if (remaining <= 0) break
                val toWrite = minOf(n, remaining)
                baos.write(buf, 0, toWrite)
                total += toWrite
            }
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    /**
     * Parses ISO-8601 timestamps preserving explicit timezone offsets (e.g. +06:00, +0600, Z),
     * falling back to UTC for offset-less server timestamps. Returns null if unparseable.
     */
    private fun parseIsoToEpochMs(dateStr: String): Long? {
        if (dateStr.isBlank()) return null
        val cleaned = dateStr.trim()
            .replace(SPACE_BEFORE_OFFSET_REGEX, "$1")
            .replace(FIRST_DATE_TIME_SPACE_REGEX, "$1T$2")
        try {
            return OffsetDateTime.parse(cleaned).toInstant().toEpochMilli()
        } catch (_: Exception) {}
        try {
            return OffsetDateTime.parse(cleaned, OFFSET_NO_COLON_FLEX_FORMATTER).toInstant().toEpochMilli()
        } catch (_: Exception) {}
        try {
            return Instant.parse(cleaned).toEpochMilli()
        } catch (_: Exception) {}
        try {
            return LocalDateTime.parse(cleaned, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        } catch (_: Exception) {}
        try {
            return LocalDate.parse(cleaned, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {}
        return null
    }
}

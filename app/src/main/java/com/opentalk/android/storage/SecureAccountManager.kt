package com.opentalk.android.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.os.UserManagerCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.opentalk.android.models.SipAccount
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureAccountManager(context: Context) {
    companion object {
        private const val TAG = "SecureAccountManager"
        private const val DE_PREFS_NAME = "sip_direct_boot_prefs"
        private const val LEGACY_MISPLACED_DE_SECURE_NAME = "sip_direct_boot_secure_prefs"
        private const val SECURE_PREFS_NAME = "sip_secure_prefs"
        private const val DE_KEYSTORE_ALIAS = "dial_de_sip_aes_key_v1"
        private const val ENC_V1_PREFIX = "enc_v1:"
        private const val KEY_PASSWORD_ENC = "password_enc"
        private const val KEY_UPDATED_AT = "_account_updated_at"
        private const val KEY_CLEARED_AT = "_account_cleared_at"

        @Volatile
        private var cachedCePrefs: SharedPreferences? = null

        @Volatile
        private var cachedDePrefs: SharedPreferences? = null

        /**
         * Fast, Direct-Boot-safe check (no KeyStore or CE decrypt) used by BootReceiver and
         * SipWatchdogReceiver to avoid starting standby service or alarms when logged out.
         */
        fun hasConfiguredAccount(context: Context): Boolean {
            return try {
                val appCtx = context.applicationContext
                val deCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    appCtx.createDeviceProtectedStorageContext()
                } else {
                    appCtx
                }
                val de = deCtx.getSharedPreferences(DE_PREFS_NAME, Context.MODE_PRIVATE)
                val deUser = de.getString("username", "")?.trim() ?: ""
                val deServer = de.getString("server", "")?.trim() ?: ""
                if (deUser.isNotEmpty() && deServer.isNotEmpty()) return true
                if (UserManagerCompat.isUserUnlocked(appCtx)) {
                    val acc = SecureAccountManager(appCtx).getAccount()
                    return acc.username.isNotBlank() && acc.server.isNotBlank()
                }
                false
            } catch (_: Throwable) {
                false
            }
        }
    }

    private val appContext: Context = context.applicationContext

    // True Direct Boot Device Protected Storage (/data/user_de/0/..., accessible before user unlock)
    private val deContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        appContext.createDeviceProtectedStorageContext()
    } else {
        appContext
    }

    private fun getDePrefs(): SharedPreferences {
        cachedDePrefs?.let { return it }
        synchronized(Companion) {
            cachedDePrefs?.let { return it }
            val dePrefs = deContext.getSharedPreferences(DE_PREFS_NAME, Context.MODE_PRIVATE)
            try {
                // Scrub any legacy plaintext AmarIP web credentials from DE storage
                if (dePrefs.contains("amaripPassword") || dePrefs.contains("amaripUsername")) {
                    dePrefs.edit().remove("amaripUsername").remove("amaripPassword").apply()
                }
                // Migrate any legacy plaintext SIP password in DE to AES-256-GCM ciphertext
                val legacyPlainPass = dePrefs.getString("password", null)
                if (!legacyPlainPass.isNullOrEmpty()) {
                    val encryptedPass = encryptForDe(legacyPlainPass)
                    if (encryptedPass != null) {
                        dePrefs.edit()
                            .putString(KEY_PASSWORD_ENC, encryptedPass)
                            .remove("password")
                            .apply()
                    }
                }
                // Remove old misplaced CE file if created by earlier EncryptedSharedPreferences(deContext)
                if (UserManagerCompat.isUserUnlocked(appContext) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    appContext.deleteSharedPreferences(LEGACY_MISPLACED_DE_SECURE_NAME)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "DE prefs hygiene warning: ${e.message}")
            }
            cachedDePrefs = dePrefs
            return dePrefs
        }
    }

    private fun deleteKeyStoreEntryQuietly(alias: String) {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Read-only DE KeyStore key lookup for decryption.
     * NEVER deletes or regenerates the key on transient KeyStore / Direct-Boot errors!
     */
    private fun getExistingDeKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.getKey(DE_KEYSTORE_ALIAS, null) as? SecretKey
        } catch (e: Throwable) {
            Log.w(TAG, "AndroidKeyStore DE key read unavailable: ${e.message}")
            null
        }
    }

    private fun getOrCreateDeKey(forceRecreate: Boolean = false): SecretKey? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (forceRecreate) {
                try {
                    if (ks.containsAlias(DE_KEYSTORE_ALIAS)) {
                        ks.deleteEntry(DE_KEYSTORE_ALIAS)
                    }
                } catch (_: Throwable) {}
            } else {
                try {
                    (ks.getKey(DE_KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
                } catch (e: Throwable) {
                    Log.w(TAG, "Corrupted DE KeyStore key detected (${e.message}); recreating...")
                    try { ks.deleteEntry(DE_KEYSTORE_ALIAS) } catch (_: Throwable) {}
                }
            }
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                DE_KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        } catch (e: Throwable) {
            Log.w(TAG, "AndroidKeyStore DE key unavailable: ${e.message}")
            null
        }
    }

    private fun encryptWithKey(plaintext: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // 12-byte GCM IV
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return ENC_V1_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun encryptForDe(plaintext: String): String? {
        if (plaintext.isEmpty()) return ""
        val key = getOrCreateDeKey(forceRecreate = false) ?: return null
        return try {
            encryptWithKey(plaintext, key)
        } catch (e: Throwable) {
            Log.w(TAG, "DE password encrypt failed (${e.message}); regenerating KeyStore key and retrying once...")
            val freshKey = getOrCreateDeKey(forceRecreate = true) ?: return null
            try {
                encryptWithKey(plaintext, freshKey)
            } catch (retryErr: Throwable) {
                Log.w(TAG, "DE password encrypt retry failed: ${retryErr.message}")
                null
            }
        }
    }

    /**
     * Returns null when stored ciphertext cannot be decrypted (so callers never mistake a
     * KeyStore failure for an empty user password).
     */
    private fun decryptFromDeOrNull(stored: String?): String? {
        if (stored.isNullOrEmpty()) return ""
        if (!stored.startsWith(ENC_V1_PREFIX)) return null
        val key = getExistingDeKey() ?: return null
        return try {
            val combined = Base64.decode(stored.removePrefix(ENC_V1_PREFIX), Base64.NO_WRAP)
            if (combined.size <= 12) return null
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.w(TAG, "DE password decrypt failed: ${e.message}")
            null
        }
    }

    private fun getCePrefs(): SharedPreferences? {
        if (!UserManagerCompat.isUserUnlocked(appContext)) return null
        cachedCePrefs?.let { return it }
        synchronized(Companion) {
            if (!UserManagerCompat.isUserUnlocked(appContext)) return null
            cachedCePrefs?.let { return it }
            val created = createEncryptedPrefsSafe(appContext, SECURE_PREFS_NAME)
            cachedCePrefs = created
            return created
        }
    }

    private fun createEncryptedPrefsSafe(ctx: Context, prefsName: String): SharedPreferences? {
        return try {
            buildEncryptedPrefs(ctx, prefsName)
        } catch (e: Throwable) {
            Log.w(TAG, "EncryptedSharedPreferences ($prefsName) corrupted or failed: ${e.message}. Attempting clean recovery...")
            try {
                // Delete both the corrupted prefs XML AND the corrupted AndroidKeyStore MasterKey
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ctx.deleteSharedPreferences(prefsName)
                } else {
                    ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
                }
                deleteKeyStoreEntryQuietly(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                buildEncryptedPrefs(ctx, prefsName)
            } catch (retryErr: Throwable) {
                Log.e(TAG, "EncryptedSharedPreferences ($prefsName) recovery failed: ${retryErr.message}")
                null
            }
        }
    }

    private fun buildEncryptedPrefs(ctx: Context, prefsName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun nextMonotonicTimestamp(de: SharedPreferences, ce: SharedPreferences?): Long {
        val maxExisting = maxOf(
            de.getLong(KEY_UPDATED_AT, 0L),
            de.getLong(KEY_CLEARED_AT, 0L),
            ce?.getLong(KEY_UPDATED_AT, 0L) ?: 0L,
            ce?.getLong(KEY_CLEARED_AT, 0L) ?: 0L
        )
        return maxOf(System.currentTimeMillis(), maxExisting + 1L)
    }

    init {
        try {
            reconcileStores()
        } catch (e: Throwable) {
            Log.w(TAG, "Init reconciliation warning: ${e.message}")
        }
    }

    /**
     * Synchronized monotonic CE <-> DE reconciliation.
     * Prevents deleted-account resurrection, legacy timestamp inversion, and password wipes.
     */
    private fun reconcileStores() {
        synchronized(Companion) {
            val ce = getCePrefs() ?: return
            val de = getDePrefs()
            val deClearedAt = de.getLong(KEY_CLEARED_AT, 0L)
            val ceClearedAt = ce.getLong(KEY_CLEARED_AT, 0L)
            val ceUpdatedAt = ce.getLong(KEY_UPDATED_AT, 0L)
            val deUpdatedAt = de.getLong(KEY_UPDATED_AT, 0L)

            // Propagate clear tombstones symmetrically
            if (deClearedAt > 0L && deClearedAt >= ceUpdatedAt && deClearedAt > ceClearedAt) {
                ce.edit().clear().putLong(KEY_CLEARED_AT, deClearedAt).apply()
                return
            }
            if (ceClearedAt > 0L && ceClearedAt >= deUpdatedAt && ceClearedAt > deClearedAt) {
                de.edit().clear().putLong(KEY_CLEARED_AT, ceClearedAt).apply()
                return
            }

            // Migrate one-time non-sensitive prefs from CE -> DE if DE lacks them
            if (!de.contains("offlineSyncEnabled") && ce.contains("offlineSyncEnabled")) {
                de.edit().putBoolean("offlineSyncEnabled", ce.getBoolean("offlineSyncEnabled", true)).apply()
            }
            if (!de.contains("accountBalance") && ce.contains("accountBalance")) {
                de.edit().putString("accountBalance", ce.getString("accountBalance", "0.00") ?: "0.00").apply()
            }

            val ceUser = ce.getString("username", "")?.trim() ?: ""
            val deUser = de.getString("username", "")?.trim() ?: ""
            val cePass = ce.getString("password", "") ?: ""
            val deEncStored = de.getString(KEY_PASSWORD_ENC, null)
            val deDecrypted = if (!deEncStored.isNullOrEmpty()) decryptFromDeOrNull(deEncStored) else null
            // Heal missing OR undecryptable DE password from valid CE ONLY when CE is at least as fresh as DE
            // and belongs to the same account (or DE is empty), preventing stale CE from clobbering newer DE!
            val ceCanHealDe = ceUser.isNotEmpty() &&
                cePass.isNotEmpty() &&
                ceUpdatedAt >= deUpdatedAt &&
                (deUser.isEmpty() || ceUser == deUser)
            val needsDePasswordSync = ceCanHealDe && (deDecrypted != cePass)

            if (ceUser.isNotEmpty() && (deUser.isEmpty() || ceUpdatedAt > deUpdatedAt || needsDePasswordSync)) {
                val acc = getAccountFromPrefs(ce, isDe = false)
                val syncTs = maxOf(ceUpdatedAt, deUpdatedAt, 1L)
                // Also stamp CE if it was a legacy 0L account so getAccount() never prefers stale DE over CE!
                if (ceUpdatedAt < syncTs) {
                    ce.edit().putLong(KEY_UPDATED_AT, syncTs).apply()
                }
                writeAccountToPrefs(de, acc, syncTs, isDe = true)
                Log.i(TAG, "Synced SIP account (${acc.username}) to encrypted DE storage for Direct Boot.")
            } else if (deUser.isNotEmpty() && deUpdatedAt > ceUpdatedAt) {
                val decryptedPass = if (!deEncStored.isNullOrEmpty()) deDecrypted else de.getString("password", "") ?: ""
                // Never poison CE with an empty password & fresh timestamp on a transient DE KeyStore failure!
                if (decryptedPass == null) {
                    Log.w(TAG, "Skipping DE -> CE sync because DE password decrypt failed; will retry when KeyStore recovers.")
                    return
                }
                val acc = getAccountFromPrefs(de, isDe = true)
                writeAccountToPrefs(ce, acc, deUpdatedAt, isDe = false)
            }
            Unit
        }
    }

    private fun writeAccountToPrefs(
        prefs: SharedPreferences,
        account: SipAccount,
        timestamp: Long,
        isDe: Boolean
    ) {
        val editor = prefs.edit()
            .putString("id", account.id)
            .putString("displayName", account.displayName)
            .putString("username", account.username)
            .putString("authUsername", account.authUsername)
            .putString("server", account.server)
            .putInt("port", account.port)
            .putString("transport", account.transport)
            .putString("outboundProxy", account.outboundProxy)
            .putString("stunServer", account.stunServer)
            .putInt("registerExpiry", account.registerExpiry)
            .putBoolean("isAutoRegister", account.isAutoRegister)
            .putLong(KEY_UPDATED_AT, timestamp)
            .remove(KEY_CLEARED_AT)

        if (isDe) {
            val enc = encryptForDe(account.password)
            if (enc != null) {
                editor.putString(KEY_PASSWORD_ENC, enc).remove("password")
            } else {
                // Never persist plaintext SIP password if AndroidKeyStore is temporarily unavailable
                editor.remove("password").remove(KEY_PASSWORD_ENC)
            }
            // Ensure AmarIP portal credentials never reside in DE storage
            editor.remove("amaripUsername").remove("amaripPassword")
        } else {
            editor.putString("password", account.password)
        }
        editor.apply()
    }

    fun saveAccount(account: SipAccount) {
        synchronized(Companion) {
            val de = getDePrefs()
            val ce = getCePrefs()
            val now = nextMonotonicTimestamp(de, ce)
            writeAccountToPrefs(de, account, now, isDe = true)
            ce?.let { writeAccountToPrefs(it, account, now, isDe = false) }
        }
    }

    fun getAccount(): SipAccount {
        synchronized(Companion) {
            reconcileStores()
            val ce = getCePrefs()
            val de = getDePrefs()
            val ceUser = ce?.getString("username", "")?.trim() ?: ""
            val deUser = de.getString("username", "")?.trim() ?: ""
            val useCe = when {
                ce != null && ceUser.isNotEmpty() && ce.getLong(KEY_UPDATED_AT, 0L) >= de.getLong(KEY_UPDATED_AT, 0L) -> true
                deUser.isNotEmpty() -> false
                ce != null -> true
                else -> false
            }
            return if (useCe && ce != null) {
                getAccountFromPrefs(ce, isDe = false)
            } else {
                val deAcc = getAccountFromPrefs(de, isDe = true)
                // Defensive fallback: if DE password decrypt failed while unlocked & CE still has it, preserve CE password
                if (deAcc.password.isEmpty() && ce != null && ceUser == deAcc.username) {
                    val cePass = ce.getString("password", "") ?: ""
                    if (cePass.isNotEmpty()) deAcc.copy(password = cePass) else deAcc
                } else {
                    deAcc
                }
            }
        }
    }

    private fun getAccountFromPrefs(prefs: SharedPreferences, isDe: Boolean): SipAccount {
        val username = prefs.getString("username", "")?.trim() ?: ""
        val server = if (username.isNotEmpty()) {
            prefs.getString("server", "sip.sarkercommunication.com")?.trim() ?: "sip.sarkercommunication.com"
        } else {
            prefs.getString("server", "")?.trim() ?: ""
        }
        val defaultName = if (server.contains("sarkercommunication.com", ignoreCase = true) || server.contains("banglacalls.com", ignoreCase = true) || server == "103.170.231.10" || com.opentalk.android.sync.CdrSyncManager.isAmarIpServer(server)) {
            "BanglaCalls"
        } else {
            "OpenTalk"
        }
        val displayName = if (username.isNotEmpty()) {
            prefs.getString("displayName", defaultName)?.trim()?.ifEmpty { defaultName } ?: defaultName
        } else {
            prefs.getString("displayName", "")?.trim() ?: ""
        }
        val password = if (isDe) {
            val enc = prefs.getString(KEY_PASSWORD_ENC, null)
            if (!enc.isNullOrEmpty()) {
                decryptFromDeOrNull(enc) ?: ""
            } else {
                prefs.getString("password", "") ?: ""
            }
        } else {
            prefs.getString("password", "") ?: ""
        }

        return SipAccount(
            id = prefs.getString("id", "default") ?: "default",
            displayName = displayName,
            username = username,
            authUsername = prefs.getString("authUsername", "") ?: "",
            password = password,
            server = server,
            port = prefs.getInt("port", 5060).takeIf { it in 1..65535 } ?: 5060,
            transport = prefs.getString("transport", "UDP") ?: "UDP",
            outboundProxy = prefs.getString("outboundProxy", "") ?: "",
            stunServer = prefs.getString("stunServer", "stun.l.google.com:19302") ?: "stun.l.google.com:19302",
            registerExpiry = prefs.getInt("registerExpiry", 600).coerceIn(60, 3600),
            isAutoRegister = prefs.getBoolean("isAutoRegister", true)
        )
    }

    fun getCustomWebPortalUsername(): String {
        return getCePrefs()?.getString("amaripUsername", "")?.trim() ?: ""
    }

    fun getCustomWebPortalPassword(): String {
        return getCePrefs()?.getString("amaripPassword", "")?.trim() ?: ""
    }

    fun getWebPortalUsername(): String {
        val custom = getCustomWebPortalUsername()
        return if (custom.isNotEmpty()) custom else getAccount().username
    }

    fun getWebPortalPassword(): String {
        val custom = getCustomWebPortalPassword()
        return if (custom.isNotEmpty()) custom else getAccount().password
    }

    fun setWebPortalCredentials(username: String, pass: String) {
        val u = username.trim()
        val p = pass.trim()
        getCePrefs()?.edit()
            ?.putString("amaripUsername", u)
            ?.putString("amaripPassword", p)
            ?.apply()
    }

    // Non-sensitive flags use DE storage as the single source of truth (zero lock/unlock split-brain)
    fun isOfflineSyncEnabled(): Boolean {
        return getDePrefs().getBoolean("offlineSyncEnabled", true)
    }

    fun setOfflineSyncEnabled(enabled: Boolean) {
        getDePrefs().edit().putBoolean("offlineSyncEnabled", enabled).apply()
    }

    fun getBalance(): String {
        return getDePrefs().getString("accountBalance", "0.00") ?: "0.00"
    }

    fun setBalance(balance: String) {
        val clean = balance.trim()
        getDePrefs().edit().putString("accountBalance", clean).apply()
    }

    fun clear() {
        synchronized(Companion) {
            val de = getDePrefs()
            val ce = getCePrefs()
            val now = nextMonotonicTimestamp(de, ce)
            de.edit().clear().putLong(KEY_CLEARED_AT, now).apply()
            ce?.edit()?.clear()?.putLong(KEY_CLEARED_AT, now)?.apply()
        }
    }
}

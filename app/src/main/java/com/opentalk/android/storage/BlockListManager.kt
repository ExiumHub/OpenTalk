package com.opentalk.android.storage

import android.content.Context
import android.os.Build
import android.util.Log
import com.opentalk.android.utils.PhoneNumberHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BlockedEntry(
    val normalizedNumber: String,
    val displayNumber: String,
    val label: String,
    val blockedAt: Long
) {
    val formattedDate: String
        get() = try {
            SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(blockedAt))
        } catch (_: Exception) {
            ""
        }
}

/**
 * Direct-Boot-safe, thread-safe Call Block List Manager.
 *
 * Uses Device-Protected Storage so incoming blocked SIP calls are rejected silently
 * even after a reboot before first unlock, with O(1) in-memory cache lookup and
 * cross-format Bangladesh (+880/880/0) / international E.164 matching.
 */
object BlockListManager {
    private const val TAG = "BlockListManager"
    private const val PREFS_NAME = "dial_block_list_prefs"
    private const val KEY_BLOCKED_JSON = "blocked_entries_json"

    private val lock = Any()

    @Volatile
    private var loaded = false

    @Volatile
    private var cachedEntries: List<BlockedEntry> = emptyList()

    @Volatile
    private var cachedNormalizedSet: Set<String> = emptySet()

    private fun getSharedPrefs(context: Context): android.content.SharedPreferences {
        val deCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }
        return deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ensureLoadedLocked(context: Context) {
        if (loaded) return
        val list = mutableListOf<BlockedEntry>()
        var needsMigrationPersist = false
        try {
            val rawJson = getSharedPrefs(context).getString(KEY_BLOCKED_JSON, null)
            if (!rawJson.isNullOrBlank()) {
                val arr = JSONArray(rawJson)
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rawNorm = obj.optString("norm", "").trim()
                    if (rawNorm.isBlank()) continue
                    val canonical = PhoneNumberHelper.toCanonicalE164(rawNorm).ifBlank { rawNorm }
                    if (!seen.add(canonical)) {
                        needsMigrationPersist = true
                        continue
                    }
                    val formattedDisp = PhoneNumberHelper.formatForDisplay(canonical)
                    val oldDisp = obj.optString("disp", "").trim()
                    if (canonical != rawNorm || oldDisp != formattedDisp) {
                        needsMigrationPersist = true
                    }
                    val label = obj.optString("label", "").trim()
                    val ts = obj.optLong("ts", System.currentTimeMillis())
                    list.add(BlockedEntry(canonical, formattedDisp, label, ts))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading block list: ${e.message}")
        }
        if (needsMigrationPersist) {
            persistLocked(context, list)
        } else {
            updateCachesLocked(list)
        }
        loaded = true
    }

    private fun updateCachesLocked(entries: List<BlockedEntry>) {
        val sorted = entries.sortedByDescending { it.blockedAt }
        cachedEntries = sorted
        val normSet = HashSet<String>(sorted.size * 2)
        for (e in sorted) {
            normSet.add(e.normalizedNumber)
        }
        cachedNormalizedSet = normSet
    }

    private fun persistLocked(context: Context, entries: List<BlockedEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            val obj = JSONObject().apply {
                put("norm", e.normalizedNumber)
                put("disp", e.displayNumber)
                put("label", e.label)
                put("ts", e.blockedAt)
            }
            arr.put(obj)
        }
        getSharedPrefs(context).edit().putString(KEY_BLOCKED_JSON, arr.toString()).apply()
        updateCachesLocked(entries)
    }

    fun getBlockedNormalizedSet(context: Context): Set<String> {
        if (!loaded) {
            synchronized(lock) {
                ensureLoadedLocked(context)
            }
        }
        return cachedNormalizedSet
    }

    fun isBlocked(context: Context, phoneNumber: String?): Boolean {
        if (phoneNumber.isNullOrBlank()) return false
        val norm = PhoneNumberHelper.toCanonicalE164(phoneNumber)
        if (norm.isBlank()) return false

        return getBlockedNormalizedSet(context).contains(norm)
    }

    fun blockNumber(context: Context, phoneNumber: String, label: String = ""): Boolean {
        val raw = phoneNumber.trim()
        val norm = PhoneNumberHelper.toCanonicalE164(raw)
        if (norm.isBlank()) return false

        synchronized(lock) {
            ensureLoadedLocked(context)
            val formattedDisp = PhoneNumberHelper.formatForDisplay(norm)
            val cleanLabel = label.trim().takeIf {
                it.isNotBlank() && it != norm && it != raw && it != formattedDisp && it.any { c -> c.isLetter() }
            } ?: ""

            val filtered = cachedEntries.filterNot { entry ->
                PhoneNumberHelper.areSameNumber(entry.normalizedNumber, norm)
            }.toMutableList()

            filtered.add(
                0,
                BlockedEntry(
                    normalizedNumber = norm,
                    displayNumber = formattedDisp,
                    label = cleanLabel,
                    blockedAt = System.currentTimeMillis()
                )
            )
            persistLocked(context, filtered)
            Log.i(TAG, "Blocked number: $norm (label=$cleanLabel)")
            return true
        }
    }

    fun unblockNumber(context: Context, phoneNumber: String): Boolean {
        val norm = PhoneNumberHelper.toCanonicalE164(phoneNumber)
        if (norm.isBlank()) return false

        synchronized(lock) {
            ensureLoadedLocked(context)
            val before = cachedEntries.size
            val updated = cachedEntries.filterNot { entry ->
                PhoneNumberHelper.areSameNumber(entry.normalizedNumber, norm)
            }
            if (updated.size != before) {
                persistLocked(context, updated)
                Log.i(TAG, "Unblocked number: $norm")
                return true
            }
            return false
        }
    }

    fun getBlockedNumbers(context: Context): List<BlockedEntry> {
        synchronized(lock) {
            ensureLoadedLocked(context)
            return cachedEntries
        }
    }

    fun getBlockedCount(context: Context): Int {
        if (!loaded) {
            synchronized(lock) {
                ensureLoadedLocked(context)
            }
        }
        return cachedEntries.size
    }

    fun clearAll(context: Context) {
        synchronized(lock) {
            ensureLoadedLocked(context)
            persistLocked(context, emptyList())
        }
    }
}

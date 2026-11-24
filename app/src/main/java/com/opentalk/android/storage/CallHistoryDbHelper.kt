package com.opentalk.android.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.util.Log
import androidx.core.os.UserManagerCompat
import com.opentalk.android.models.CallRecord
import com.opentalk.android.models.CallType
import com.opentalk.android.utils.ContactResolver
import com.opentalk.android.utils.PhoneNumberHelper
import org.json.JSONArray
import org.json.JSONObject

class CallHistoryDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext: Context = context.applicationContext
    private val deQueueLock = Any()

    companion object {
        private const val TAG = "CallHistoryDbHelper"
        private const val DB_NAME = "my_ip_phone.db"
        private const val DB_VERSION = 3
        private const val MAX_STORED_ROWS = 1000
        private const val QUERY_LIMIT = "200"
        private const val DE_PENDING_PREFS = "dial_direct_boot_pending_calls"
        private const val KEY_PENDING_JSON = "pending_calls_json"

        @Volatile
        private var instance: CallHistoryDbHelper? = null

        fun getInstance(context: Context): CallHistoryDbHelper {
            return instance ?: synchronized(this) {
                instance ?: CallHistoryDbHelper(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    init {
        try {
            setWriteAheadLoggingEnabled(true)
        } catch (_: Exception) {}
    }

    private fun isDbAccessible(): Boolean {
        return UserManagerCompat.isUserUnlocked(appContext)
    }

    private fun getDePendingPrefs(): android.content.SharedPreferences {
        val deCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.createDeviceProtectedStorageContext()
        } else {
            appContext
        }
        return deCtx.getSharedPreferences(DE_PENDING_PREFS, Context.MODE_PRIVATE)
    }

    private fun stageDirectBootCall(record: CallRecord) {
        synchronized(deQueueLock) {
            try {
                val prefs = getDePendingPrefs()
                val arr = JSONArray(prefs.getString(KEY_PENDING_JSON, "[]") ?: "[]")
                val obj = JSONObject().apply {
                    put("remoteNumber", record.remoteNumber)
                    put("displayName", record.displayName)
                    put("type", record.type.name)
                    put("timestamp", record.timestamp)
                    put("durationSeconds", record.durationSeconds)
                    put("accountId", record.accountId ?: "")
                }
                arr.put(obj)
                // Keep staging bounded to latest 50 Direct-Boot calls
                val trimmed = if (arr.length() > 50) {
                    JSONArray().apply {
                        for (i in (arr.length() - 50) until arr.length()) {
                            put(arr.get(i))
                        }
                    }
                } else {
                    arr
                }
                prefs.edit().putString(KEY_PENDING_JSON, trimmed.toString()).apply()
                Log.i(TAG, "Staged Direct-Boot call record (${record.type}) for ${record.remoteNumber}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed staging Direct-Boot call: ${e.message}")
            }
        }
    }

    private fun flushPendingDirectBootCallsIfUnlocked() {
        if (!isDbAccessible()) return
        synchronized(deQueueLock) {
            try {
                val prefs = getDePendingPrefs()
                val raw = prefs.getString(KEY_PENDING_JSON, null)
                if (raw.isNullOrBlank() || raw == "[]") return
                val arr = JSONArray(raw)
                val pendingRecords = mutableListOf<CallRecord>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val num = obj.optString("remoteNumber", "").trim()
                    if (num.isBlank()) continue
                    val type = try {
                        CallType.valueOf(obj.optString("type", CallType.MISSED.name))
                    } catch (_: Exception) {
                        CallType.MISSED
                    }
                    pendingRecords.add(
                        CallRecord(
                            remoteNumber = num,
                            displayName = obj.optString("displayName", num),
                            type = type,
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            durationSeconds = obj.optInt("durationSeconds", 0),
                            accountId = obj.optString("accountId", "").ifBlank { null }
                        )
                    )
                }
                if (pendingRecords.isEmpty()) {
                    prefs.edit().remove(KEY_PENDING_JSON).commit()
                    return
                }
                // Pre-resolve contact names & ContentValues BEFORE opening the SQLite write transaction,
                // then use insertOrThrow() so any SQLite error rolls back and retains KEY_PENDING_JSON for retry!
                val valuesToInsert = ArrayList<ContentValues>(pendingRecords.size)
                for (rec in pendingRecords) {
                    val normalizedNum = PhoneNumberHelper.toCanonicalE164(rec.remoteNumber).ifBlank { rec.remoteNumber.trim() }
                    if (normalizedNum.isBlank()) continue
                    var name = ContactResolver.stripOperatorName(rec.displayName)
                    val nameIsJustNumber = name.isBlank() ||
                        name == rec.remoteNumber ||
                        name == normalizedNum ||
                        (PhoneNumberHelper.looksLikePhoneNumber(name) && PhoneNumberHelper.areSameNumber(name, normalizedNum))
                    if (nameIsJustNumber) {
                        val resolved = ContactResolver.resolveContactName(appContext, normalizedNum)
                        name = if (!resolved.isNullOrBlank()) resolved else normalizedNum
                    }
                    val finalDisplayName = if (name.isNotBlank()) name else normalizedNum
                    valuesToInsert.add(
                        ContentValues().apply {
                            put("remote_number", normalizedNum)
                            put("display_name", finalDisplayName)
                            put("call_type", rec.type.name)
                            put("timestamp", rec.timestamp)
                            put("duration", maxOf(0, rec.durationSeconds))
                            put("account_id", rec.accountId)
                        }
                    )
                }
                if (valuesToInsert.isNotEmpty()) {
                    val db = writableDatabase
                    db.beginTransaction()
                    try {
                        for (cv in valuesToInsert) {
                            db.insertOrThrow("calls", null, cv)
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
                prefs.edit().remove(KEY_PENDING_JSON).commit()
            } catch (e: Exception) {
                Log.w(TAG, "Failed flushing Direct-Boot call queue (will retry): ${e.message}")
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                remote_number TEXT NOT NULL,
                display_name TEXT NOT NULL,
                call_type TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                duration INTEGER NOT NULL,
                account_id TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_calls_timestamp ON calls(timestamp DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_calls_remote_number ON calls(remote_number)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // SQLiteOpenHelper already wraps onUpgrade() in a transaction; let exceptions propagate so a failed upgrade rolls back cleanly.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_calls_timestamp ON calls(timestamp DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_calls_remote_number ON calls(remote_number)")
        if (oldVersion < 2) {
            cleanOperatorNamesFromHistory(db)
        }
        if (oldVersion < 3) {
            migrateNumbersToE164(db)
        }
    }

    /**
     * One-time non-destructive migration converting legacy local/mixed remote_number rows
     * (e.g. "01967554137", "8801967554137") to canonical E.164 ("+8801967554137") while
     * preserving all human display names and foreign numbers intact.
     */
    private fun migrateNumbersToE164(db: SQLiteDatabase) {
        val updates = mutableListOf<Triple<Long, String, String?>>()
        db.rawQuery("SELECT id, remote_number, display_name FROM calls", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val oldNum = cursor.getString(1) ?: continue
                val oldName = cursor.getString(2) ?: ""
                val canonical = PhoneNumberHelper.toCanonicalE164(oldNum).ifBlank { oldNum.trim() }
                val newName = if (oldName.isBlank() ||
                    (PhoneNumberHelper.looksLikePhoneNumber(oldName) &&
                        PhoneNumberHelper.areSameNumber(oldName, canonical))
                ) {
                    canonical
                } else {
                    null
                }
                if (canonical != oldNum || (newName != null && newName != oldName)) {
                    updates.add(Triple(id, canonical, newName))
                }
            }
        }
        if (updates.isNotEmpty()) {
            val manageTx = !db.inTransaction()
            if (manageTx) db.beginTransaction()
            try {
                for ((id, canonical, newName) in updates) {
                    val cv = ContentValues().apply {
                        put("remote_number", canonical)
                        if (newName != null) {
                            put("display_name", newName)
                        }
                    }
                    db.update("calls", cv, "id = ?", arrayOf(id.toString()))
                }
                if (manageTx) db.setTransactionSuccessful()
                Log.i(TAG, "Migrated ${updates.size} call history rows to canonical E.164.")
            } finally {
                if (manageTx) db.endTransaction()
            }
        }
    }

    fun insertCall(record: CallRecord): Long {
        if (!isDbAccessible()) {
            stageDirectBootCall(record)
            return -1L
        }
        flushPendingDirectBootCallsIfUnlocked()
        return insertCallInternal(record)
    }

    private fun insertCallInternal(record: CallRecord): Long {
        return try {
            val normalizedNum = PhoneNumberHelper.toCanonicalE164(record.remoteNumber).ifBlank { record.remoteNumber.trim() }
            if (normalizedNum.isBlank()) return -1L
            var name = ContactResolver.stripOperatorName(record.displayName)
            val nameIsJustNumber = name.isBlank() ||
                name == record.remoteNumber ||
                name == normalizedNum ||
                (PhoneNumberHelper.looksLikePhoneNumber(name) && PhoneNumberHelper.areSameNumber(name, normalizedNum))
            if (nameIsJustNumber) {
                val resolved = ContactResolver.resolveContactName(appContext, normalizedNum)
                name = if (!resolved.isNullOrBlank()) resolved else normalizedNum
            }
            val finalDisplayName = if (name.isNotBlank()) name else normalizedNum
            val db = writableDatabase
            val values = ContentValues().apply {
                put("remote_number", normalizedNum)
                put("display_name", finalDisplayName)
                put("call_type", record.type.name)
                put("timestamp", record.timestamp)
                put("duration", maxOf(0, record.durationSeconds))
                put("account_id", record.accountId)
            }
            val rowId = db.insert("calls", null, values)
            if (rowId != -1L && rowId % 50L == 0L) {
                pruneOldCalls(db)
            }
            rowId
        } catch (e: Exception) {
            Log.e(TAG, "insertCall failed: ${e.message}")
            -1L
        }
    }

    private fun pruneOldCalls(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "DELETE FROM calls WHERE id NOT IN (SELECT id FROM calls ORDER BY timestamp DESC LIMIT $MAX_STORED_ROWS)"
            )
        } catch (_: Exception) {}
    }

    private fun buildNumberVariants(number: String): Set<String> {
        return PhoneNumberHelper.buildLookupVariants(number)
    }

    /**
     * Checks if a call of matching type(s) from the same remote phone number within a 90-second
     * window already exists in the local database using canonical E.164 / libphonenumber matching.
     */
    fun callExists(
        remoteNumber: String,
        timestamp: Long,
        matchTypes: Collection<CallType> = listOf(CallType.MISSED, CallType.REJECTED)
    ): Boolean {
        if (!isDbAccessible()) return false
        flushPendingDirectBootCallsIfUnlocked()
        return try {
            val normalized = PhoneNumberHelper.toCanonicalE164(remoteNumber)
            if (normalized.isBlank()) return false

            val db = readableDatabase
            val typeClause = if (matchTypes.isNotEmpty()) {
                val placeholders = matchTypes.joinToString(",") { "?" }
                " AND call_type IN ($placeholders)"
            } else {
                ""
            }

            val args = mutableListOf<String>()
            args.add(timestamp.toString())
            matchTypes.forEach { args.add(it.name) }

            val sql = "SELECT remote_number FROM calls WHERE ABS(timestamp - ?) < 90000$typeClause"
            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val existingRaw = cursor.getString(0) ?: continue
                    if (PhoneNumberHelper.areSameNumber(existingRaw, normalized)) return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    fun getCalls(): List<CallRecord> {
        if (!isDbAccessible()) return emptyList()
        flushPendingDirectBootCallsIfUnlocked()
        val list = mutableListOf<CallRecord>()
        return try {
            val db = readableDatabase
            val cursor = db.query(
                "calls",
                null,
                null,
                null,
                null,
                null,
                "timestamp DESC",
                QUERY_LIMIT
            )
            cursor.use {
                while (it.moveToNext()) {
                    val remoteNum = it.getString(it.getColumnIndexOrThrow("remote_number"))
                    var displayName = it.getString(it.getColumnIndexOrThrow("display_name"))
                    displayName = ContactResolver.stripOperatorName(displayName)

                    list.add(
                        CallRecord(
                            id = it.getLong(it.getColumnIndexOrThrow("id")),
                            remoteNumber = remoteNum,
                            displayName = if (displayName.isNotBlank()) displayName else remoteNum,
                            type = try {
                                CallType.valueOf(it.getString(it.getColumnIndexOrThrow("call_type")))
                            } catch (_: Exception) {
                                CallType.OUTGOING
                            },
                            timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                            durationSeconds = maxOf(0, it.getInt(it.getColumnIndexOrThrow("duration"))),
                            accountId = it.getString(it.getColumnIndexOrThrow("account_id"))
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "getCalls failed: ${e.message}")
            emptyList()
        }
    }

    fun deleteCall(id: Long): Int {
        if (!isDbAccessible()) return 0
        return try {
            writableDatabase.delete("calls", "id = ?", arrayOf(id.toString()))
        } catch (_: Exception) { 0 }
    }

    fun deleteCallsForNumber(number: String): Int {
        if (!isDbAccessible()) return 0
        return try {
            deleteCallsForNumbers(buildNumberVariants(number))
        } catch (_: Exception) { 0 }
    }

    fun deleteCallsForNumbers(numbers: Collection<String>): Int {
        if (!isDbAccessible() || numbers.isEmpty()) return 0
        val expanded = LinkedHashSet<String>()
        for (n in numbers) {
            expanded.addAll(buildNumberVariants(n))
        }
        val cleanNumbers = expanded.filter { it.isNotBlank() }
        if (cleanNumbers.isEmpty()) return 0
        return try {
            var totalDeleted = 0
            val db = writableDatabase
            for (chunk in cleanNumbers.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                totalDeleted += db.delete("calls", "remote_number IN ($placeholders)", chunk.toTypedArray())
            }
            totalDeleted
        } catch (_: Exception) { 0 }
    }

    fun deleteCallsByIds(ids: Collection<Long>): Int {
        if (!isDbAccessible() || ids.isEmpty()) return 0
        return try {
            var totalDeleted = 0
            val db = writableDatabase
            for (chunk in ids.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                totalDeleted += db.delete("calls", "id IN ($placeholders)", chunk.map { it.toString() }.toTypedArray())
            }
            totalDeleted
        } catch (_: Exception) { 0 }
    }

    fun clearAll() {
        synchronized(deQueueLock) {
            try { getDePendingPrefs().edit().remove(KEY_PENDING_JSON).commit() } catch (_: Exception) {}
            if (!isDbAccessible()) return
            try {
                writableDatabase.delete("calls", null, null)
            } catch (_: Exception) {}
        }
    }

    /**
     * One-time migration cleanup for legacy records that contain telecom operator tags in display_name,
     * e.g., "+8801979908689 (Banglalink)" -> "+8801979908689", without mutating legitimate names like "John (Work)".
     */
    fun cleanOperatorNamesFromHistory(targetDb: SQLiteDatabase? = null) {
        val db = targetDb ?: if (isDbAccessible()) writableDatabase else return
        val cursor = db.rawQuery("SELECT id, display_name, remote_number FROM calls WHERE display_name LIKE '%(%)%'", null)
        val updates = mutableListOf<Pair<Long, String>>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val displayName = it.getString(1) ?: ""
                val remoteNumber = it.getString(2) ?: ""
                val cleaned = ContactResolver.stripOperatorName(displayName)
                if (cleaned != displayName) {
                    val replacement = cleaned.ifBlank { remoteNumber }
                    if (replacement.isNotBlank() && replacement != displayName) {
                        updates.add(Pair(id, replacement))
                    }
                }
            }
        }
        if (updates.isNotEmpty()) {
            val manageTx = !db.inTransaction()
            if (manageTx) db.beginTransaction()
            try {
                for ((id, cleanName) in updates) {
                    val cv = ContentValues().apply { put("display_name", cleanName) }
                    db.update("calls", cv, "id = ?", arrayOf(id.toString()))
                }
                if (manageTx) db.setTransactionSuccessful()
            } finally {
                if (manageTx) db.endTransaction()
            }
        }
    }
}

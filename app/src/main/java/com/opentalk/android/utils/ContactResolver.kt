package com.opentalk.android.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.core.os.UserManagerCompat

/**
 * Universal Contact Name Resolver & Cleaner for OpenTalk.
 *
 * Provides fast, indexed contact resolution using Android's ContactsContract
 * PhoneLookup filter, Bangladesh (+880/00880/880/01/096/02) & international E.164
 * normalization, alphanumeric SIP username preservation, and safe operator-tag sanitization.
 */
object ContactResolver {

    data class ContactItem(
        val name: String,
        val number: String,
        val t9Key: String,
        val normalizedNumber: String = normalizePhoneNumber(number),
        val displayNumber: String = PhoneNumberHelper.formatForDisplay(normalizedNumber),
        val nationalNumber: String = PhoneNumberHelper.toSipDialString(normalizedNumber),
        val initials: String = computeInitials(name, normalizedNumber),
        val nameLower: String = name.lowercase(),
        val displayLower: String = displayNumber.lowercase(),
        val digitsOnly: String = normalizedNumber.filter { it.isDigit() }
    )

    private val WHITESPACE_SPLIT_REGEX = Regex("\\s+")

    fun computeInitials(name: String, fallbackNumber: String = ""): String {
        val trimmed = name.trim()
        val parts = if (trimmed.isNotEmpty()) trimmed.split(WHITESPACE_SPLIT_REGEX).filter { it.isNotBlank() } else emptyList()
        val res = when {
            parts.size >= 2 -> "${parts[0].take(1)}${parts[1].take(1)}".uppercase()
            parts.isNotEmpty() -> parts[0].take(2).uppercase()
            fallbackNumber.isNotEmpty() -> fallbackNumber.filter { it.isDigit() }.take(2)
            else -> "#"
        }
        return res.ifEmpty { "#" }
    }

    fun isCacheLoaded(): Boolean = isContactsLoaded

    fun getCachedContacts(): List<ContactItem> = allContactsCache

    @Volatile
    private var nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val contactsLoadLock = Any()

    @Volatile
    private var allContactsCache: List<ContactItem> = emptyList()

    @Volatile
    private var isContactsLoaded: Boolean = false

    @Volatile
    private var lastContactsProviderFingerprint: String = ""

    @Volatile
    private var observerRegistered: Boolean = false

    /**
     * Lightweight O(1) ContactsProvider version probe (~2ms on IO) that detects external
     * contact edits, additions, or deletions even on OEMs that delay ContentObserver callbacks,
     * without re-parsing all phone numbers when nothing changed.
     */
    private fun queryContactsProviderFingerprint(context: Context): String? {
        if (!UserManagerCompat.isUserUnlocked(context)) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            var count = 0
            var maxUpdated = 0L
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP),
                null,
                null,
                null
            )?.use { cursor ->
                count = cursor.count
                while (cursor.moveToNext()) {
                    val ts = cursor.getLong(0)
                    if (ts > maxUpdated) maxUpdated = ts
                }
            } ?: return null
            "$count:$maxUpdated"
        } catch (_: Exception) {
            null
        }
    }

    fun hasContactsChanged(context: Context): Boolean {
        if (!isContactsLoaded) return true
        val fp = queryContactsProviderFingerprint(context) ?: return false
        return fp != lastContactsProviderFingerprint
    }

    private fun ensureContactsObserverRegistered(context: Context) {
        if (observerRegistered) return
        synchronized(contactsLoadLock) {
            if (observerRegistered) return
            try {
                val appCtx = context.applicationContext
                val observer = object : android.database.ContentObserver(
                    android.os.Handler(android.os.Looper.getMainLooper())
                ) {
                    override fun onChange(selfChange: Boolean) {
                        // Lock-free stale-while-revalidate: never block the Main Thread on contactsLoadLock
                        // and never wipe active T9 dialer suggestions mid-typing!
                        isContactsLoaded = false
                        lastContactsProviderFingerprint = ""
                    }
                }
                appCtx.contentResolver.registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI,
                    true,
                    observer
                )
                observerRegistered = true
            } catch (_: Exception) {}
        }
    }

    private val DIGITS_PLUS_REGEX = Regex("[^0-9+*#]")
    private val KNOWN_OPERATOR_REGEX = Regex(
        """\s*\((?:Banglalink|Grameenphone|Grameen|GP|Robi|Airtel|Teletalk|BTCL|AmarIP|Sarker|Citycell|RanksTel|BracNet|IPTSP|Bangladesh)\b[^\)]*\)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Converts a string (e.g. contact name) to T9 dialpad digits:
     * 2=ABC, 3=DEF, 4=GHI, 5=JKL, 6=MNO, 7=PQRS, 8=TUV, 9=WXYZ
     */
    fun getT9KeyForString(str: String): String {
        val sb = StringBuilder()
        for (ch in str.lowercase()) {
            when (ch) {
                'a', 'b', 'c' -> sb.append('2')
                'd', 'e', 'f' -> sb.append('3')
                'g', 'h', 'i' -> sb.append('4')
                'j', 'k', 'l' -> sb.append('5')
                'm', 'n', 'o' -> sb.append('6')
                'p', 'q', 'r', 's' -> sb.append('7')
                't', 'u', 'v' -> sb.append('8')
                'w', 'x', 'y', 'z' -> sb.append('9')
                '0', '+' -> sb.append('0')
                '1' -> sb.append('1')
                in '2'..'9' -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Loads and atomically caches all contacts with precomputed T9 keys & normalized numbers.
     * Deduplicates by normalized phone number so +88017... and 017... don't duplicate.
     */
    fun loadAllContacts(context: Context, forceReload: Boolean = false): List<ContactItem> {
        if (!UserManagerCompat.isUserUnlocked(context)) return emptyList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        ensureContactsObserverRegistered(context)
        if (isContactsLoaded && !forceReload) return allContactsCache
        synchronized(contactsLoadLock) {
            if (isContactsLoaded && !forceReload) return allContactsCache
            val items = mutableListOf<ContactItem>()
            try {
                // Capture provider fingerprint BEFORE querying so edits occurring mid-query are NEVER missed!
                val preQueryFingerprint = queryContactsProviderFingerprint(context) ?: ""
                val cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )
                // Only mark loaded when ContactsProvider actually returned a valid cursor (never lock empty on transient null!)
                cursor?.use {
                    val seenNumbers = HashSet<String>()
                    val freshMap = java.util.concurrent.ConcurrentHashMap<String, String>()
                    while (it.moveToNext()) {
                        val name = (it.getString(0) ?: "").trim()
                        val rawTrimmed = (it.getString(1) ?: "").trim()
                        // Pass rawTrimmed directly to normalizePhoneNumber so alphanumeric SIP usernames (e.g. "support.desk") keep dots/hyphens!
                        val normKey = normalizePhoneNumber(rawTrimmed)
                        if (rawTrimmed.isNotEmpty() && normKey.isNotEmpty() && name.isNotBlank() && seenNumbers.add(normKey)) {
                            val t9 = getT9KeyForString(name)
                            val displayNum = PhoneNumberHelper.formatForDisplay(normKey)
                            val nationalNum = PhoneNumberHelper.toSipDialString(normKey)
                            items.add(ContactItem(name, normKey, t9, normKey, displayNum, nationalNum))
                            freshMap[normKey] = name
                        }
                    }
                    // Atomic reference swaps eliminate transient empty-map reads from UI/SIP threads!
                    nameCache = freshMap
                    allContactsCache = items
                    lastContactsProviderFingerprint = preQueryFingerprint
                    isContactsLoaded = true
                }
            } catch (_: Exception) {}
            return allContactsCache
        }
    }

    /**
     * Searches in-memory cached contacts by T9 letters or precomputed normalized phone digits.
     * Ranks prefix matches ahead of substring matches for instant, accurate dialer suggestions.
     * Never blocks the UI thread with synchronous ContactsContract queries.
     */
    fun findT9Matches(queryDigits: String, limit: Int = 5): List<ContactItem> {
        val cleanQuery = queryDigits.replace(DIGITS_PLUS_REGEX, "")
        if (cleanQuery.isEmpty()) return emptyList()
        val normQuery = normalizePhoneNumber(cleanQuery)
        val digitsOnlyQuery = cleanQuery.filter { it.isDigit() }
        val contacts = allContactsCache
        if (contacts.isEmpty()) return emptyList()

        val scored = ArrayList<Pair<Int, ContactItem>>()
        for (contact in contacts) {
            val e164Digits = contact.digitsOnly
            val rank = when {
                contact.nationalNumber.startsWith(cleanQuery) ||
                    contact.normalizedNumber.startsWith(cleanQuery) ||
                    (digitsOnlyQuery.isNotEmpty() && e164Digits.startsWith(digitsOnlyQuery)) ||
                    (normQuery.isNotEmpty() && contact.normalizedNumber.startsWith(normQuery)) -> 0
                contact.t9Key.startsWith(cleanQuery) -> 1
                contact.nationalNumber.contains(cleanQuery) ||
                    contact.normalizedNumber.contains(cleanQuery) ||
                    (digitsOnlyQuery.isNotEmpty() && e164Digits.contains(digitsOnlyQuery)) ||
                    (normQuery.isNotEmpty() && contact.normalizedNumber.contains(normQuery)) -> 2
                contact.t9Key.contains(cleanQuery) -> 3
                else -> -1
            }
            if (rank >= 0) {
                scored.add(rank to contact)
            }
        }
        return scored.sortedBy { it.first }.take(limit).map { it.second }
    }

    fun clearContactsCache() {
        synchronized(contactsLoadLock) {
            allContactsCache = emptyList()
            isContactsLoaded = false
            lastContactsProviderFingerprint = ""
            nameCache = java.util.concurrent.ConcurrentHashMap()
        }
    }

    /**
     * Fast in-memory cached name lookup (zero IPC) for UI list rendering.
     */
    fun getCachedContactName(phoneNumber: String): String? {
        val trimmed = phoneNumber.trim()
        if (trimmed.isBlank() || trimmed.equals("Unknown", ignoreCase = true)) return null
        val normPhone = normalizePhoneNumber(trimmed)
        val cleanPhone = cleanPhoneNumber(trimmed)
        val cacheKey = normPhone.ifBlank { cleanPhone }
        if (cacheKey.isBlank()) return null
        nameCache[cacheKey]?.let { return it }
        val contacts = allContactsCache
        if (contacts.isNotEmpty()) {
            val found = contacts.firstOrNull {
                it.normalizedNumber == normPhone || it.nationalNumber == cleanPhone || it.number == cleanPhone
            }?.name
            if (!found.isNullOrBlank()) {
                nameCache[cacheKey] = found
                return found
            }
        }
        return null
    }

    /**
     * Resolves the saved contact name for a given phone number from the Android address book.
     * Supports cross-format Bangladesh numbers (01.../8801.../+8801.../008801.../096.../02...) & E.164 international.
     */
    fun resolveContactName(context: Context, phoneNumber: String): String? {
        val trimmed = phoneNumber.trim()
        if (trimmed.isBlank() || trimmed.equals("Unknown", ignoreCase = true)) return null

        val normPhone = normalizePhoneNumber(trimmed)
        val cleanPhone = cleanPhoneNumber(trimmed)
        val cacheKey = normPhone.ifBlank { cleanPhone }
        if (cacheKey.isBlank()) return null

        if (!UserManagerCompat.isUserUnlocked(context)) return nameCache[cacheKey]
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        // Always verify live against ContactsProvider on single-call lookup so a just-deleted
        // or just-renamed contact is reflected immediately with zero stale cache!
        val candidates = PhoneNumberHelper.buildLookupVariants(trimmed)

        for (candidate in candidates) {
            val found = queryPhoneLookup(context, candidate)
            if (!found.isNullOrBlank()) {
                nameCache[cacheKey] = found
                return found
            }
        }
        nameCache.remove(cacheKey)
        return null
    }

    private fun queryPhoneLookup(context: Context, lookupNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(lookupNumber)
            )
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(0)?.trim()?.takeIf { n -> n.isNotBlank() }
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Strips whitespace, dashes, parentheses, brackets, and dots from phone numbers.
     */
    fun cleanPhoneNumber(number: String): String {
        return PhoneNumberHelper.cleanRaw(number)
    }

    /**
     * Normalizes phone numbers to canonical E.164 format via PhoneNumberHelper (libphonenumber).
     * - Bangladesh local ("01967554137") / bare ("8801967554137") -> "+8801967554137".
     * - Foreign numbers preserve their own country code (never converted to "+880").
     * - Short codes, USSD/feature codes, and alphanumeric SIP usernames are preserved intact.
     */
    fun normalizePhoneNumber(number: String): String {
        return PhoneNumberHelper.toCanonicalE164(number)
    }

    /**
     * Strips known telecom operator suffixes like "(Banglalink)", "(Grameenphone)", "(Robi)",
     * while preserving legitimate user contact labels like "(Work)", "(Robiul)", "(GPS)".
     */
    fun stripOperatorName(text: String): String {
        return text.replace(KNOWN_OPERATOR_REGEX, "").trim()
    }
}

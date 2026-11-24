package com.opentalk.android.utils

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber

/**
 * Shared Global Phone Number Standard powered by Google libphonenumber.
 *
 * Enforces a clean 3-layer contract across the entire calling app:
 * 1. Canonical Storage & Matching (`toCanonicalE164`): E.164 format (e.g. "+8801967554137", "+14155552671").
 * 2. Consistent UI Display (`formatForDisplay`): libphonenumber INTERNATIONAL format (e.g. "+880 1967-554137").
 * 3. SIP Wire / Trunk Dialing (`toSipDialString`): National format for local BD trunk ("01967554137")
 *    and E.164 for international numbers ("+14155552671"), preserving short codes, USSD, and SIP usernames.
 */
object PhoneNumberHelper {

    const val DEFAULT_REGION: String = "BD"

    private val phoneUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }
    private val defaultCountryCode: Int by lazy { phoneUtil.getCountryCodeForRegion(DEFAULT_REGION) }

    private const val MAX_CACHE_ENTRIES = 1024

    // Lock-free ConcurrentHashMap caches prevent UI-thread vs Dispatchers.IO contention on reads
    private val canonicalCache = java.util.concurrent.ConcurrentHashMap<String, String>(256)
    private val displayCache = java.util.concurrent.ConcurrentHashMap<String, String>(256)
    private val sipDialCache = java.util.concurrent.ConcurrentHashMap<String, String>(256)

    private fun putBounded(cache: java.util.concurrent.ConcurrentHashMap<String, String>, key: String, value: String) {
        if (cache.size >= MAX_CACHE_ENTRIES) {
            cache.clear()
        }
        cache[key] = value
    }

    private fun cacheKey(trimmed: String, defaultRegion: String): String {
        return if (defaultRegion == DEFAULT_REGION) trimmed else "$defaultRegion|$trimmed"
    }

    /**
     * Preloads libphonenumber region metadata off the UI thread during Application startup
     * so the first Recents/Dialer/Contacts render never stalls on metadata I/O.
     */
    fun warmUp() {
        try {
            phoneUtil
            defaultCountryCode
            formatForDisplay("+8801700000000")
        } catch (_: Exception) {}
    }

    private val CLEAN_PHONE_REGEX = Regex("""[\s\-\(\)\[\]\.]""")
    private val DIGITS_FEATURE_REGEX = Regex("""[^0-9+*#]""")
    private val NON_DIGIT_OR_PLUS_REGEX = Regex("""[^\d+]""")
    private val ALPHANUMERIC_SIP_USER_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9._\-]{1,63}$""")
    private val DTMF_AND_EXTENSION_SUFFIX_REGEX = Regex("""(?:[,;].*|\s*(?:ext\.?|x)\s*\d+.*)$""", RegexOption.IGNORE_CASE)

    /**
     * Strips trailing DTMF pause/wait sequences (e.g., ",123#", ";2") and phone extensions
     * (e.g., "ext. 89", "x100") so post-dial digits are never fused into the phone number.
     */
    private fun stripDialSuffix(raw: String): String {
        val trimmed = raw.trim()
        if (isAlphanumericSipUser(trimmed)) return trimmed
        return trimmed.replace(DTMF_AND_EXTENSION_SUFFIX_REGEX, "").trim()
    }

    /**
     * Strips formatting whitespace, hyphens, parentheses, brackets, and dots.
     */
    fun cleanRaw(raw: String): String {
        return raw.trim().replace(CLEAN_PHONE_REGEX, "")
    }

    /**
     * Returns true if the input is an alphanumeric SIP username (e.g., "alice", "support.desk")
     * rather than a numeric telephone number.
     */
    fun isAlphanumericSipUser(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.isNotEmpty() && ALPHANUMERIC_SIP_USER_REGEX.matches(trimmed)
    }

    /**
     * Returns true if the input is a USSD or SIP star/hash feature code (e.g., "*97", "#31#").
     */
    fun isFeatureCode(raw: String): Boolean {
        val cleaned = cleanRaw(stripDialSuffix(raw))
        return cleaned.contains('*') || cleaned.contains('#')
    }

    /**
     * Returns true if the string represents a phone number (or formatted phone number) rather than
     * a human contact name containing alphabetic words.
     */
    fun looksLikePhoneNumber(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it.isLetter() }) return false
        return trimmed.any { it.isDigit() }
    }

    /**
     * Parses a telephone number via libphonenumber without ever forcing foreign numbers
     * into the Bangladesh (+880) region.
     */
    fun parseNumber(raw: String, defaultRegion: String = DEFAULT_REGION): Phonenumber.PhoneNumber? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() ||
            trimmed.equals("Unknown", ignoreCase = true) ||
            trimmed.equals("Unknown Caller", ignoreCase = true) ||
            trimmed.equals("null", ignoreCase = true)
        ) {
            return null
        }
        val stripped = stripDialSuffix(trimmed)
        if (isAlphanumericSipUser(stripped) || isFeatureCode(stripped)) {
            return null
        }

        val onlyDigitsPlus = cleanRaw(stripped).replace(NON_DIGIT_OR_PLUS_REGEX, "")
        if (onlyDigitsPlus.isEmpty()) return null

        val hasLeadingPlus = onlyDigitsPlus.startsWith("+")
        val digitsOnly = onlyDigitsPlus.replace("+", "")
        if (digitsOnly.isEmpty()) return null

        // Case 1: Explicit international format starting with '+' — NEVER fall back to defaultRegion!
        if (hasLeadingPlus) {
            return tryParseInternational("+$digitsOnly")
        }

        // Case 2: International dialing prefix "00..." (e.g. 00880..., 0044..., 001...)
        if (digitsOnly.startsWith("00") && digitsOnly.length >= 6) {
            return tryParseInternational("+" + digitsOnly.drop(2))
        }

        // Case 3: Local national number starting with single '0' (e.g. 01967554137, 09649100444, 029876543)
        if (digitsOnly.startsWith("0")) {
            return tryParseForRegion(digitsOnly, defaultRegion, allowPossibleForRegion = true)
        }

        // Case 4: Bare digits without '+' or leading '0' (e.g. SIP From "8801967554137", "8809649100444", "442079460958")
        // First try international parsing with '+' so foreign country codes are recognized natively
        if (digitsOnly.length >= 8) {
            val intl = tryParseInternational("+$digitsOnly", requireStrictValid = true, defaultRegion = defaultRegion)
            if (intl != null) return intl
        }

        // Fallback: valid national number missing leading trunk '0' in defaultRegion (e.g. "1967554137" -> BD)
        return tryParseForRegion(digitsOnly, defaultRegion, allowPossibleForRegion = false)
    }

    private fun isPossibleBangladeshIptsp(
        proto: Phonenumber.PhoneNumber,
        region: String
    ): Boolean {
        if (region != DEFAULT_REGION || proto.countryCode != defaultCountryCode) return false
        if (!phoneUtil.isPossibleNumber(proto)) return false
        val nsn = phoneUtil.getNationalSignificantNumber(proto)
        return nsn.length == 10 && nsn.startsWith("96")
    }

    private fun tryParseInternational(
        plusNumber: String,
        requireStrictValid: Boolean = false,
        defaultRegion: String = DEFAULT_REGION
    ): Phonenumber.PhoneNumber? {
        return try {
            val proto = phoneUtil.parse(plusNumber, null)
            if (phoneUtil.isValidNumber(proto)) {
                proto
            } else if (!requireStrictValid && proto.countryCode > 0 && phoneUtil.isPossibleNumber(proto)) {
                proto
            } else if (requireStrictValid && isPossibleBangladeshIptsp(proto, defaultRegion)) {
                // Covers newly allocated BD IPTSP 096xx blocks (e.g. 8809649...) whose length matches libphonenumber metadata
                proto
            } else {
                null
            }
        } catch (_: NumberParseException) {
            null
        }
    }

    private fun tryParseForRegion(
        digits: String,
        region: String,
        allowPossibleForRegion: Boolean
    ): Phonenumber.PhoneNumber? {
        return try {
            val proto = phoneUtil.parse(digits, region)
            if (phoneUtil.isValidNumberForRegion(proto, region)) {
                proto
            } else if (allowPossibleForRegion && isPossibleBangladeshIptsp(proto, region)) {
                // Strictly covers newly allocated BD IPTSP 096xx blocks without misconverting foreign 0-prefixed numbers
                proto
            } else {
                null
            }
        } catch (_: NumberParseException) {
            null
        }
    }

    /**
     * Canonical Storage & Matching representation (E.164).
     * - Bangladesh local "01967554137" -> "+8801967554137"
     * - Bangladesh bare "8801967554137" / "008801967554137" -> "+8801967554137"
     * - Foreign "+1 415-555-2671" / "00442079460958" -> "+14155552671" / "+442079460958"
     * - Short codes ("121", "999"), USSD ("*97"), and SIP usernames ("alice") are preserved intact.
     * - Unrecognized foreign strings are NEVER misconverted to "+880".
     */
    fun toCanonicalE164(raw: String, defaultRegion: String = DEFAULT_REGION): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() ||
            trimmed.equals("Unknown", ignoreCase = true) ||
            trimmed.equals("Unknown Caller", ignoreCase = true) ||
            trimmed.equals("null", ignoreCase = true)
        ) {
            return ""
        }
        val key = cacheKey(trimmed, defaultRegion)
        canonicalCache[key]?.let { return it }

        val computed = computeCanonicalE164(trimmed, defaultRegion)
        putBounded(canonicalCache, key, computed)
        return computed
    }

    private fun computeCanonicalE164(trimmed: String, defaultRegion: String): String {
        val stripped = stripDialSuffix(trimmed)
        if (isAlphanumericSipUser(stripped)) {
            return stripped
        }
        if (isFeatureCode(stripped)) {
            return cleanRaw(stripped).replace(DIGITS_FEATURE_REGEX, "")
        }

        val parsed = parseNumber(stripped, defaultRegion)
        if (parsed != null) {
            return phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        }

        // Preserve short codes, partial dialpad queries, or unknown international inputs safely without mangling
        val onlyDigitsPlus = cleanRaw(stripped).replace(NON_DIGIT_OR_PLUS_REGEX, "")
        if (onlyDigitsPlus.isEmpty()) return ""
        val hasLeadingPlus = onlyDigitsPlus.startsWith("+")
        val digitsOnly = onlyDigitsPlus.replace("+", "")
        if (digitsOnly.isEmpty()) return ""
        return if (hasLeadingPlus) "+$digitsOnly" else digitsOnly
    }

    /**
     * Consistent International UI Display Format powered by Google libphonenumber.
     * - "+8801967554137" or "01967554137" -> "+880 1967-554137"
     * - "+14155552671" -> "+1 415-555-2671"
     * - "+442079460958" -> "+44 20 7946 0958"
     * - Short codes ("121"), USSD ("*97"), SIP usernames ("alice"), and "Unknown Caller" pass through cleanly.
     */
    fun formatForDisplay(raw: String, defaultRegion: String = DEFAULT_REGION): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() ||
            trimmed.equals("Unknown", ignoreCase = true) ||
            trimmed.equals("Unknown Caller", ignoreCase = true)
        ) {
            return trimmed
        }
        val key = cacheKey(trimmed, defaultRegion)
        displayCache[key]?.let { return it }

        val computed = if (isAlphanumericSipUser(trimmed) || isFeatureCode(trimmed)) {
            trimmed
        } else {
            val parsed = parseNumber(trimmed, defaultRegion)
            if (parsed != null) {
                phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
            } else {
                trimmed
            }
        }
        putBounded(displayCache, key, computed)
        return computed
    }

    private fun formatNationalDialDigits(
        parsed: Phonenumber.PhoneNumber,
        region: String
    ): String {
        val rawNational = phoneUtil
            .format(parsed, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
            .filter { it.isDigit() }
        val nddPrefix = phoneUtil.getNddPrefixForRegion(region, true).orEmpty()
        return if (rawNational.isNotEmpty() && nddPrefix.isNotEmpty() && !rawNational.startsWith(nddPrefix)) {
            "$nddPrefix$rawNational"
        } else {
            rawNational
        }
    }

    /**
     * Converts any canonical/display/local number into the exact wire format expected by the SIP trunk:
     * - Local Bangladesh (+880) numbers -> national dialing digits ("01967554137", "09649100444", "029876543")
     *   via libphonenumber NATIONAL format, preserving full AmarIP / BD IPTSP trunk compatibility.
     * - Foreign international numbers -> E.164 ("+14155552671").
     * - Short codes ("121"), USSD ("*97"), and SIP usernames ("alice") -> preserved intact.
     */
    fun toSipDialString(raw: String, defaultRegion: String = DEFAULT_REGION): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val key = cacheKey(trimmed, defaultRegion)
        sipDialCache[key]?.let { return it }

        val computed = computeSipDialString(trimmed, defaultRegion)
        putBounded(sipDialCache, key, computed)
        return computed
    }

    private fun computeSipDialString(trimmed: String, defaultRegion: String): String {
        val stripped = stripDialSuffix(trimmed)
        if (isAlphanumericSipUser(stripped)) return stripped
        if (isFeatureCode(stripped)) {
            return cleanRaw(stripped).replace(DIGITS_FEATURE_REGEX, "")
        }

        val parsed = parseNumber(stripped, defaultRegion)
        if (parsed != null) {
            val targetCc = if (defaultRegion == DEFAULT_REGION) defaultCountryCode else phoneUtil.getCountryCodeForRegion(defaultRegion)
            return if (parsed.countryCode == targetCc) {
                val nationalDigits = formatNationalDialDigits(parsed, defaultRegion)
                if (nationalDigits.isNotEmpty()) nationalDigits
                else phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            } else {
                phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
        }
        return toCanonicalE164(stripped, defaultRegion)
    }

    /**
     * Returns true when two phone strings represent the same phone number across
     * local, bare country-code, international, or formatted representations.
     */
    fun areSameNumber(a: String?, b: String?, defaultRegion: String = DEFAULT_REGION): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val canonA = toCanonicalE164(a, defaultRegion)
        val canonB = toCanonicalE164(b, defaultRegion)
        if (canonA.isNotBlank() && canonA == canonB) return true

        val parsedA = parseNumber(a, defaultRegion)
        val parsedB = parseNumber(b, defaultRegion)
        if (parsedA != null && parsedB != null) {
            val match = phoneUtil.isNumberMatch(parsedA, parsedB)
            return match == PhoneNumberUtil.MatchType.EXACT_MATCH ||
                match == PhoneNumberUtil.MatchType.NSN_MATCH
        }
        return false
    }

    /**
     * Generates all lookup / deletion variants for a phone number (E.164, national digits,
     * bare country-code, "00"+country-code, formatted display, and raw cleaned) using libphonenumber
     * fields — zero hardcoded country-prefix substrings.
     */
    fun buildLookupVariants(raw: String, defaultRegion: String = DEFAULT_REGION): Set<String> {
        val variants = LinkedHashSet<String>()
        val trimmed = raw.trim()
        val canonical = toCanonicalE164(trimmed, defaultRegion)
        if (canonical.isNotBlank()) variants.add(canonical)

        val parsed = parseNumber(trimmed, defaultRegion)
        if (parsed != null) {
            val cc = parsed.countryCode.toString()
            val nsn = phoneUtil.getNationalSignificantNumber(parsed)
            variants.add(phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164))
            val nationalDigits = formatNationalDialDigits(
                parsed,
                phoneUtil.getRegionCodeForNumber(parsed) ?: defaultRegion
            )
            if (nationalDigits.isNotBlank()) variants.add(nationalDigits)
            if (nsn.isNotBlank()) {
                variants.add("$cc$nsn")
                variants.add("00$cc$nsn")
            }
        }

        val cleaned = cleanRaw(trimmed)
        if (cleaned.isNotBlank()) variants.add(cleaned)
        if (trimmed.isNotBlank()) variants.add(trimmed)
        return variants
    }

    /**
     * Returns true if a parsed number belongs to the default region (Bangladesh).
     */
    fun isLocalDefaultRegion(raw: String, defaultRegion: String = DEFAULT_REGION): Boolean {
        val parsed = parseNumber(raw, defaultRegion) ?: return false
        val targetCc = if (defaultRegion == DEFAULT_REGION) defaultCountryCode else phoneUtil.getCountryCodeForRegion(defaultRegion)
        return parsed.countryCode == targetCc
    }
}

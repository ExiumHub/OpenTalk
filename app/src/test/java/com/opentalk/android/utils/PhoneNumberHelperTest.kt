package com.opentalk.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberHelperTest {

    @Test
    fun bangladeshMobileFormats_canonicalizeToE164AndDisplayConsistently() {
        val inputs = listOf(
            "01967554137",
            "+8801967554137",
            "8801967554137",
            "008801967554137",
            "+880 1967-554137",
            "01967-554137",
            "(01967) 554137",
            "+88001967554137",
            "88001967554137",
            "1967554137"
        )
        for (input in inputs) {
            assertEquals("E.164 failed for '$input'", "+8801967554137", PhoneNumberHelper.toCanonicalE164(input))
            assertEquals("Display failed for '$input'", "+880 1967-554137", PhoneNumberHelper.formatForDisplay(input))
            assertEquals("SIP wire failed for '$input'", "01967554137", PhoneNumberHelper.toSipDialString(input))
        }
    }

    @Test
    fun bangladeshAllOperatorsIptspAndLandline() {
        val cases = mapOf(
            "01312345678" to "+8801312345678",
            "01404004877" to "+8801404004877",
            "01512345678" to "+8801512345678",
            "01612345678" to "+8801612345678",
            "01712345678" to "+8801712345678",
            "01812345678" to "+8801812345678",
            "09649100444" to "+8809649100444",
            "+8809649100444" to "+8809649100444",
            "8809649100444" to "+8809649100444",
            "029876543" to "+88029876543",
            "+88029876543" to "+88029876543"
        )
        for ((input, expectedE164) in cases) {
            assertEquals("E.164 failed for '$input'", expectedE164, PhoneNumberHelper.toCanonicalE164(input))
            assertTrue("Display should start with +880 for '$input'", PhoneNumberHelper.formatForDisplay(input).startsWith("+880"))
            assertTrue("SIP wire should start with 0 for BD '$input'", PhoneNumberHelper.toSipDialString(input).startsWith("0"))
        }
        assertEquals("09649100444", PhoneNumberHelper.toSipDialString("+8809649100444"))
        assertEquals("029876543", PhoneNumberHelper.toSipDialString("+88029876543"))
    }

    @Test
    fun foreignNumbers_neverMisconvertedToBangladesh() {
        val foreignCases = listOf(
            Triple("+14155552671", "+14155552671", "+1 415-555-2671"),
            Triple("+1 (415) 555-2671", "+14155552671", "+1 415-555-2671"),
            Triple("0014155552671", "+14155552671", "+1 415-555-2671"),
            Triple("14155552671", "+14155552671", "+1 415-555-2671"),
            Triple("+442079460958", "+442079460958", "+44 20 7946 0958"),
            Triple("00442079460958", "+442079460958", "+44 20 7946 0958"),
            Triple("442079460958", "+442079460958", "+44 20 7946 0958"),
            Triple("+919876543210", "+919876543210", "+91 98765 43210"),
            Triple("00919876543210", "+919876543210", "+91 98765 43210"),
            Triple("919876543210", "+919876543210", "+91 98765 43210"),
            Triple("+971501234567", "+971501234567", "+971 50 123 4567"),
            Triple("+966501234567", "+966501234567", "+966 50 123 4567"),
            Triple("+6591234567", "+6591234567", "+65 9123 4567")
        )
        for ((input, expectedE164, expectedDisplay) in foreignCases) {
            val e164 = PhoneNumberHelper.toCanonicalE164(input)
            val display = PhoneNumberHelper.formatForDisplay(input)
            val sipWire = PhoneNumberHelper.toSipDialString(input)
            assertFalse("Foreign '$input' was misconverted to +880!", e164.startsWith("+880"))
            assertEquals("Foreign E.164 failed for '$input'", expectedE164, e164)
            assertEquals("Foreign display failed for '$input'", expectedDisplay, display)
            assertEquals("Foreign SIP wire should stay E.164 for '$input'", expectedE164, sipWire)
        }

        // Unrecognized international '+' number must NEVER be forced to +880
        assertEquals("+99912345678", PhoneNumberHelper.toCanonicalE164("+99912345678"))
        assertEquals("+99912345678", PhoneNumberHelper.formatForDisplay("+99912345678"))

        // Foreign local 0-prefixed numbers (e.g. UK 077..., India 098..., UAE 050...) must NEVER be misconverted to +880
        val foreignLocalZeroPrefixed = listOf("07700900123", "09876543210", "0501234567", "09999999999")
        for (foreignLocal in foreignLocalZeroPrefixed) {
            val result = PhoneNumberHelper.toCanonicalE164(foreignLocal)
            assertFalse("Foreign 0-prefixed '$foreignLocal' was misconverted to +880 ($result)!", result.startsWith("+880"))
            assertEquals(foreignLocal, result)
        }
    }

    @Test
    fun shortCodesUssdSipUsersAndPartialInputs_preservedSafely() {
        val passthrough = listOf("121", "999", "16247", "28282", "*97", "#31#01967554137", "*121#", "alice", "support.desk")
        for (code in passthrough) {
            assertEquals("Passthrough E.164 failed for '$code'", code, PhoneNumberHelper.toCanonicalE164(code))
            assertEquals("Passthrough display failed for '$code'", code, PhoneNumberHelper.formatForDisplay(code))
            assertEquals("Passthrough SIP wire failed for '$code'", code, PhoneNumberHelper.toSipDialString(code))
        }

        // Partial dialpad prefix must not be converted prematurely
        assertEquals("01967", PhoneNumberHelper.toCanonicalE164("01967"))
        assertEquals("01967", PhoneNumberHelper.formatForDisplay("01967"))

        // Empty / Unknown sentinels
        assertEquals("", PhoneNumberHelper.toCanonicalE164(""))
        assertEquals("", PhoneNumberHelper.toCanonicalE164("Unknown"))
        assertEquals("", PhoneNumberHelper.toCanonicalE164("Unknown Caller"))
        assertEquals("Unknown Caller", PhoneNumberHelper.formatForDisplay("Unknown Caller"))
    }

    @Test
    fun numberMatchingAndLookupVariants() {
        assertTrue(PhoneNumberHelper.areSameNumber("01967554137", "+8801967554137"))
        assertTrue(PhoneNumberHelper.areSameNumber("8801967554137", "+880 1967-554137"))
        assertTrue(PhoneNumberHelper.areSameNumber("008801967554137", "01967-554137"))
        assertTrue(PhoneNumberHelper.areSameNumber("+14155552671", "0014155552671"))

        assertFalse(PhoneNumberHelper.areSameNumber("01712345678", "01912345678"))
        assertFalse(PhoneNumberHelper.areSameNumber("+8801967554137", "+91967554137"))

        val variants = PhoneNumberHelper.buildLookupVariants("01967554137")
        assertTrue(variants.contains("+8801967554137"))
        assertTrue(variants.contains("01967554137"))
        assertTrue(variants.contains("8801967554137"))
        assertTrue(variants.contains("008801967554137"))
    }

    @Test
    fun dtmfPauseWaitAndExtensions_strippedWithoutCorruptingNumber() {
        val bdCases = listOf(
            "01967554137,1",
            "01967554137;2",
            "+880 1967-554137,123#",
            "01967554137 ext. 89",
            "01967554137 x42"
        )
        for (input in bdCases) {
            assertEquals("BD DTMF/ext E.164 failed for '$input'", "+8801967554137", PhoneNumberHelper.toCanonicalE164(input))
            assertEquals("BD DTMF/ext display failed for '$input'", "+880 1967-554137", PhoneNumberHelper.formatForDisplay(input))
            assertEquals("BD DTMF/ext SIP wire failed for '$input'", "01967554137", PhoneNumberHelper.toSipDialString(input))
        }

        assertEquals("+14155552671", PhoneNumberHelper.toCanonicalE164("+1 415-555-2671 ext 100"))
        assertEquals("+1 415-555-2671", PhoneNumberHelper.formatForDisplay("+1 415-555-2671,9#"))
        assertEquals("+14155552671", PhoneNumberHelper.toSipDialString("+1 415-555-2671;5"))

        // Feature codes with post-dial DTMF must not fuse post-dial digits into the SIP wire string
        assertEquals("*97", PhoneNumberHelper.toSipDialString("*97,123#"))
        assertEquals("*121#", PhoneNumberHelper.toSipDialString("*121#;2"))
    }
}

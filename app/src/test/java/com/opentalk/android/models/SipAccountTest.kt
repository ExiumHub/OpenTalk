package com.opentalk.android.models

import org.junit.Assert.assertEquals
import org.junit.Test

class SipAccountTest {

    @Test
    fun sipAccount_defaultsAndEffectiveAuthUser() {
        val accDefault = SipAccount(username = "09649100444")
        assertEquals("OpenTalk", accDefault.displayName)
        assertEquals("09649100444", accDefault.effectiveAuthUser)
        assertEquals("sip:09649100444@sip.sarkercommunication.com:5060", accDefault.sipUri)

        val accCustomAuth = SipAccount(username = "09649100444", authUsername = "custom_auth")
        assertEquals("custom_auth", accCustomAuth.effectiveAuthUser)
    }

    @Test
    fun sipAccount_customIpAndDomainUris() {
        val ipAcc = SipAccount(username = "1001", server = "103.170.231.10", port = 5060, transport = "UDP")
        assertEquals("sip:1001@103.170.231.10:5060", ipAcc.sipUri)

        val tlsAcc = SipAccount(username = "1001", server = "sip.provider.com", port = 5061, transport = "TLS")
        assertEquals("sip:1001@sip.provider.com:5061", tlsAcc.sipUri)
    }
}

package com.opentalk.android.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CdrSyncManagerTest {

    @Test
    fun isAmarIpServer_identifiesAmarIpAndBanglaCallsHosts() {
        val validServers = listOf(
            "sip.sarkercommunication.com",
            "sarkercommunication.com",
            "voice.sarkercommunication.com",
            "banglacalls.com",
            "sip.banglacalls.com",
            "amarip.net",
            "sip.amarip.net",
            "103.170.231.10",
            "103.170.231.12",
            "103.170.231.254",
            "sip://103.170.231.10:5060",
            "sips://sip.sarkercommunication.com:5061"
        )
        for (server in validServers) {
            assertTrue("Expected isAmarIpServer to return true for '$server'", CdrSyncManager.isAmarIpServer(server))
        }
    }

    @Test
    fun isAmarIpServer_rejectsThirdPartyCustomServers() {
        val thirdPartyServers = listOf(
            "sip.provider.com",
            "sip.linphone.org",
            "sip.antisip.com",
            "192.168.1.1",
            "10.0.0.1",
            "103.170.230.10",
            "",
            "   "
        )
        for (server in thirdPartyServers) {
            assertFalse("Expected isAmarIpServer to return false for third-party '$server'", CdrSyncManager.isAmarIpServer(server))
        }
    }
}

package com.opentalk.android.models

data class SipAccount(
    val id: String = "default",
    val displayName: String = "OpenTalk",
    val username: String = "",
    val authUsername: String = "",
    val password: String = "",
    val server: String = "sip.sarkercommunication.com",
    val port: Int = 5060,
    val transport: String = "UDP",
    val outboundProxy: String = "",
    val stunServer: String = "stun.l.google.com:19302",
    val registerExpiry: Int = 600,
    val isAutoRegister: Boolean = true
) {
    val effectiveAuthUser: String
        get() = if (authUsername.trim().isNotEmpty()) authUsername.trim() else username.trim()

    val sipUri: String
        get() {
            val user = username.trim()
            val host = server.trim()
            val formattedHost = if (host.contains(':') && !host.startsWith("[") && host.count { it == ':' } > 1) {
                "[$host]"
            } else {
                host
            }
            return if (formattedHost.contains(':') && !formattedHost.endsWith("]")) {
                "sip:$user@$formattedHost"
            } else {
                "sip:$user@$formattedHost:$port"
            }
        }

    override fun toString(): String {
        return "SipAccount(id='$id', displayName='$displayName', username='$username', authUsername='$authUsername', password='***', server='$server', port=$port, transport='$transport')"
    }
}

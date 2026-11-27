package com.zevarc.pigo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The endpoint is free-form text typed on a phone keyboard, so the URL builder
 * has to absorb everything a user realistically pastes.
 *
 * Regression: only `ws://` and `wss://` used to be stripped. A user who checked
 * `http://mac-pi:8080/health` in a browser then pasted that same string into
 * settings produced `ws://http://mac-pi:8080/ws`, which resolves the hostname
 * "http" on port 80. Nothing ever reached the bridge and the only symptom was a
 * permanently red indicator.
 */
class SocketUrlTest {

    private val token = "tok123"

    private fun url(endpoint: String) = BridgeSettings.buildSocketUrl(endpoint, token)

    @Test
    fun `bare host and port becomes ws`() {
        assertEquals("ws://mac-pi:8080/ws?token=$token", url("mac-pi:8080"))
    }

    @Test
    fun `http prefix is stripped instead of becoming the hostname`() {
        assertEquals("ws://mac-pi:8080/ws?token=$token", url("http://mac-pi:8080"))
    }

    @Test
    fun `http prefix with a path keeps only the authority`() {
        assertEquals("ws://mac-pi:8080/ws?token=$token", url("http://mac-pi:8080/health"))
    }

    @Test
    fun `https maps to wss`() {
        assertEquals("wss://mac-pi:8080/ws?token=$token", url("https://mac-pi:8080"))
    }

    @Test
    fun `ws and wss keep their scheme`() {
        assertEquals("ws://mac-pi:8787/ws?token=$token", url("ws://mac-pi:8787"))
        assertEquals("wss://mac-pi:8787/ws?token=$token", url("wss://mac-pi:8787"))
    }

    @Test
    fun `scheme is matched case-insensitively`() {
        assertEquals("wss://mac-pi:8080/ws?token=$token", url("HTTPS://mac-pi:8080"))
    }

    @Test
    fun `trailing slash and stray whitespace are dropped`() {
        assertEquals("ws://mac-pi:8080/ws?token=$token", url("  mac-pi:8080/  "))
    }

    @Test
    fun `magicdns host with a path and query keeps the authority`() {
        assertEquals(
            "ws://mac-pi.tail715acd.ts.net:8080/ws?token=$token",
            url("http://mac-pi.tail715acd.ts.net:8080/health?token=x"),
        )
    }

    @Test
    fun `tailnet ip works`() {
        assertEquals("ws://100.80.193.46:8080/ws?token=$token", url("http://100.80.193.46:8080"))
    }

    @Test
    fun `missing endpoint or token yields null`() {
        assertNull(BridgeSettings.buildSocketUrl("", token))
        assertNull(BridgeSettings.buildSocketUrl("   ", token))
        assertNull(BridgeSettings.buildSocketUrl("mac-pi:8080", ""))
        assertNull(BridgeSettings.buildSocketUrl("http://", token))
    }

    @Test
    fun `token is trimmed so a trailing newline cannot corrupt the query`() {
        assertEquals(
            "ws://mac-pi:8080/ws?token=$token",
            BridgeSettings.buildSocketUrl("mac-pi:8080", "  $token\n"),
        )
    }
}

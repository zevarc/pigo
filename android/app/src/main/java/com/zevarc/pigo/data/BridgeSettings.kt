package com.zevarc.pigo.data

import android.content.Context
import androidx.core.content.edit

/**
 * Persisted connection settings. Deliberately tiny: one host, one token.
 *
 * The bridge URL is assembled here so the ViewModel never string-builds it.
 */
class BridgeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("pigo.settings", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TOKEN, value.trim()) }

    /** `home-pc:8787` or `100.64.0.1:8787`, optionally with a scheme. */
    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ENDPOINT, value.trim()) }

    /**
     * Builds the WebSocket URL from whatever the user typed.
     *
     * Every HTTP-family scheme must be stripped, not just `ws://`: a user who
     * verified the host with `http://host:port/health` in a browser naturally
     * pastes that exact string here. Stripping only `ws://`/`wss://` left
     * `ws://http://host:port/ws`, which OkHttp resolves as the hostname "http"
     * on port 80 — a failure that never reaches the bridge and shows up only as
     * a red dot that occasionally blinks amber.
     *
     * `https://` maps to `wss://`; anything else (including a bare `host:port`)
     * is plain `ws://`, which is correct inside the Tailscale tunnel.
     */
    fun socketUrl(): String? = buildSocketUrl(endpoint, token)

    internal companion object {
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_TOKEN = "token"

        /**
         * Turns a user-typed endpoint plus token into a WebSocket URL.
         *
         * Pure so it can be unit-tested without an Android Context: this string
         * building is what silently broke the connection, so it is the part
         * that deserves a test rather than the SharedPreferences wrapper.
         *
         * Returns null when there is not enough to connect with.
         */
        fun buildSocketUrl(endpoint: String, token: String): String? {
            val trimmed = endpoint.trim()
            val trimmedToken = token.trim()
            if (trimmed.isEmpty() || trimmedToken.isEmpty()) return null

            val scheme = when {
                trimmed.startsWith("wss://", ignoreCase = true) -> "wss"
                trimmed.startsWith("https://", ignoreCase = true) -> "wss"
                else -> "ws"
            }

            // Keep host:port only. A pasted `http://mac-pi:8080/health` must not
            // turn into a request for `/health/ws`.
            val authority = trimmed
                .replaceFirst(SCHEME_PREFIX, "")
                .substringBefore('/')
                .substringBefore('?')
                .trim()
            if (authority.isEmpty()) return null

            return "$scheme://$authority/ws?token=$trimmedToken"
        }

        /** Any scheme the user may have copied from a browser or docs. */
        private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    }
}

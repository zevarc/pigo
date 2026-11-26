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

    fun socketUrl(): String? {
        if (endpoint.isBlank() || token.isBlank()) return null
        val base = endpoint
            .removePrefix("ws://")
            .removePrefix("wss://")
            .trimEnd('/')
        val scheme = if (endpoint.startsWith("wss://")) "wss" else "ws"
        return "$scheme://$base/ws?token=$token"
    }

    private companion object {
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TOKEN = "token"
    }
}

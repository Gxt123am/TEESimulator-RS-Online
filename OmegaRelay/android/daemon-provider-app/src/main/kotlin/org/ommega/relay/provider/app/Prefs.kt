package org.ommega.relay.provider.app

import android.content.Context
import android.content.SharedPreferences
import org.ommega.relay.provider.ProviderConfig

/**
 * SharedPreferences-backed config store. This is the on-device counterpart to
 * the file-based config used by the desktop CLI.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("omega_provider", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_URL, v).apply()

    var deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, "") ?: ""
        set(v) = sp.edit().putString(KEY_DEVICE_ID, v).apply()

    var psk: String
        get() = sp.getString(KEY_PSK, "") ?: ""
        set(v) = sp.edit().putString(KEY_PSK, v).apply()

    var tlsInsecure: Boolean
        get() = sp.getBoolean(KEY_TLS_INSECURE, false)
        set(v) = sp.edit().putBoolean(KEY_TLS_INSECURE, v).apply()

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Returns null if config is invalid or not yet set up. */
    fun toProviderConfig(): ProviderConfig? {
        val u = url.trim()
        val id = deviceId.trim()
        val key = psk
        if (u.isBlank() || id.isBlank() || key.length < 16) return null
        if (!u.startsWith("ws://") && !u.startsWith("wss://")) return null
        return ProviderConfig(
            url = u,
            psk = key.toByteArray(Charsets.UTF_8),
            deviceId = id,
            tlsInsecure = tlsInsecure,
            caPem = null,
        )
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PSK = "psk"
        private const val KEY_TLS_INSECURE = "tls_insecure"
        private const val KEY_ENABLED = "enabled"
    }
}

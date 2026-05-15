package org.ommega.relay.provider

import java.io.File

/**
 * Daemon configuration. Loaded from a simple key=value file (no full TOML
 * dependency to keep the on-device fat-jar small).
 *
 * Example:
 *   url = wss://relay.example.com:8443/
 *   psk = your-strong-pre-shared-key-here
 *   device_id = device-b-1
 *   tls_insecure = false
 *   ca_pem = /data/adb/omega/ca.pem
 */
data class ProviderConfig(
    val url: String,
    val psk: ByteArray,
    val deviceId: String,
    val tlsInsecure: Boolean,
    val caPem: String?,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        fun load(path: File): ProviderConfig {
            require(path.isFile) { "config file not found: $path" }
            // Use plain InputStream/Reader rather than `Files.readString` —
            // the latter is Java 11 and unavailable on Android core-oj.jar.
            val text = path.readText(Charsets.UTF_8)
            val map = parseKv(text)

            val url = map["url"] ?: error("config missing 'url'")
            val psk = (map["psk"] ?: error("config missing 'psk'")).toByteArray(Charsets.UTF_8)
            val deviceId = map["device_id"] ?: error("config missing 'device_id'")
            val tlsInsecure = map["tls_insecure"]?.toBoolean() ?: false
            val caPem = map["ca_pem"]

            require(psk.size >= 16) { "psk must be at least 16 chars" }
            require(url.startsWith("ws://") || url.startsWith("wss://")) {
                "url must start with ws:// or wss://"
            }

            return ProviderConfig(url, psk, deviceId, tlsInsecure, caPem)
        }

        private fun parseKv(text: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (rawLine in text.lines()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val eq = line.indexOf('=')
                require(eq > 0) { "bad config line: $rawLine" }
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim().trim('"')
                result[key] = value
            }
            return result
        }
    }
}

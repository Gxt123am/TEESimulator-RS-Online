package org.matrix.TEESimulator.relay

import org.matrix.TEESimulator.config.ConfigurationManager
import org.matrix.TEESimulator.logging.SystemLogger
import java.io.File

/**
 * Reads `omega-relay.conf` from the same /data/adb/tricky_store/ directory
 * the rest of the project uses. Returns null if the file is missing or
 * incomplete — RELAY mode then degrades gracefully.
 *
 * File format (simple `key = value`, # for comments):
 *
 *   url = wss://relay.example.com:8443/
 *   psk = your-pre-shared-key-at-least-16-chars
 *   device_id = device-a-1
 *   tls_insecure = false
 */
object RelayConfigLoader {
    private const val CONFIG_FILE_NAME = "omega-relay.conf"

    fun load(): RelayConfig? {
        val file = File(ConfigurationManager.CONFIG_PATH, CONFIG_FILE_NAME)
        if (!file.isFile) {
            SystemLogger.info("RelayConfig: $CONFIG_FILE_NAME not present; RELAY mode disabled")
            return null
        }

        val map = mutableMapOf<String, String>()
        try {
            file.readLines().forEach { rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEach
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEach
                map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim().trim('"')
            }
        } catch (e: Exception) {
            SystemLogger.error("RelayConfig: failed to read $CONFIG_FILE_NAME", e)
            return null
        }

        val url = map["url"]?.takeIf { it.isNotEmpty() }
            ?: run { SystemLogger.warning("RelayConfig: 'url' missing"); return null }
        val pskStr = map["psk"]?.takeIf { it.length >= 16 }
            ?: run { SystemLogger.warning("RelayConfig: 'psk' missing or too short (min 16)"); return null }
        val deviceId = map["device_id"]?.takeIf { it.isNotEmpty() }
            ?: run { SystemLogger.warning("RelayConfig: 'device_id' missing"); return null }
        val tlsInsecure = map["tls_insecure"]?.equals("true", ignoreCase = true) == true

        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            SystemLogger.warning("RelayConfig: 'url' must start with ws:// or wss://")
            return null
        }

        return RelayConfig(
            url = url,
            psk = pskStr.toByteArray(Charsets.UTF_8),
            deviceId = deviceId,
            tlsInsecure = tlsInsecure,
        )
    }
}

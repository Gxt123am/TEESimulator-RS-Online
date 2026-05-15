package org.ommega.relay.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authentication helpers (HMAC-based pre-shared key auth).
 * Mirrors `omega-protocol::auth` in Rust byte-for-byte.
 */
object Auth {

    /** Maximum allowed clock skew (in seconds) between client and server. */
    const val MAX_CLOCK_SKEW_SECS: Long = 60

    /**
     * Compute the auth token for a Hello message.
     * `format!("{device_id}:{nonce_hex}:{timestamp}")` signed with HMAC-SHA256.
     */
    fun computeAuthToken(psk: ByteArray, deviceId: String, nonce: ByteArray, timestamp: Long): ByteArray {
        val nonceHex = nonce.joinToString("") { "%02x".format(it) }
        val payload = "$deviceId:$nonceHex:$timestamp"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(psk, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    }

    /** Constant-time compare. */
    fun verifyAuthToken(
        psk: ByteArray,
        deviceId: String,
        nonce: ByteArray,
        timestamp: Long,
        token: ByteArray,
    ): Boolean {
        val expected = computeAuthToken(psk, deviceId, nonce, timestamp)
        if (expected.size != token.size) return false
        var diff = 0
        for (i in expected.indices) {
            diff = diff or (expected[i].toInt() xor token[i].toInt())
        }
        return diff == 0
    }

    fun checkTimestamp(timestamp: Long, now: Long): Long? {
        val diff = now - timestamp
        return if (kotlin.math.abs(diff) <= MAX_CLOCK_SKEW_SECS) null else diff
    }

    /** Generate a random 16-byte nonce. */
    fun randomNonce(): ByteArray {
        val nonce = ByteArray(16)
        java.security.SecureRandom().nextBytes(nonce)
        return nonce
    }
}

package org.matrix.TEESimulator.relay.protocol

import java.util.UUID

/** Current protocol version. Bump on breaking changes. */
const val PROTOCOL_VERSION: Int = 1

// ----------------------------------------------------------------------------
// Envelope & Payload
// ----------------------------------------------------------------------------

data class Envelope(
    val msgId: UUID,
    val inReplyTo: UUID? = null,
    val timestamp: Long,
    val payload: Payload,
) {
    companion object {
        fun new(payload: Payload): Envelope =
            Envelope(UUID.randomUUID(), null, nowSecs(), payload)

        fun replyTo(inReplyTo: UUID, payload: Payload): Envelope =
            Envelope(UUID.randomUUID(), inReplyTo, nowSecs(), payload)
    }
}

sealed interface Payload {
    data class Hello(val hello: HelloMsg) : Payload
    data class HelloAck(val ack: HelloAckMsg) : Payload
    object Ping : Payload
    object Pong : Payload
    data class SubmitTask(val task: Task) : Payload
    data class DispatchTask(val task: Task) : Payload
    data class TaskResult(val result: TaskResultMsg) : Payload
    data class Error(val error: ErrorMsg) : Payload
}

// ----------------------------------------------------------------------------
// Handshake
// ----------------------------------------------------------------------------

enum class Role(val wire: String) {
    CONSUMER("consumer"),
    PROVIDER("provider");

    companion object {
        fun fromWire(s: String): Role = entries.firstOrNull { it.wire == s }
            ?: throw IllegalArgumentException("unknown role: $s")
    }
}

data class HelloMsg(
    val role: Role,
    val deviceId: String,
    val authToken: ByteArray,
    val nonce: ByteArray,
    val timestamp: Long,
    val protocolVersion: Int,
    val capabilities: List<String> = emptyList(),
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

data class HelloAckMsg(
    val success: Boolean,
    val sessionId: UUID? = null,
    val serverTime: Long,
    val error: String? = null,
)

// ----------------------------------------------------------------------------
// Tasks
// ----------------------------------------------------------------------------

data class Task(
    val id: UUID,
    val taskType: TaskType,
    val timeoutMs: Int,
    val metadata: Map<String, String> = emptyMap(),
)

sealed interface TaskType {
    data class Attest(
        val challenge: ByteArray,
        val aliasHint: String = "",
        val algorithm: KeyAlgorithm,
        val purpose: KeyPurpose,
        val attestationApplicationId: ByteArray? = null,
        val deviceContext: DeviceContext? = null,
    ) : TaskType {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class Sign(
        val alias: String,
        val data: ByteArray,
        val algorithm: SignAlgorithm,
    ) : TaskType {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class AttestExternalKey(
        val challenge: ByteArray,
        val externalPublicKeyDer: ByteArray,
        val attestationApplicationId: ByteArray? = null,
        val deviceContext: DeviceContext? = null,
    ) : TaskType {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }
}

enum class KeyAlgorithm(val wire: String) {
    EC_P256("ec_p256"),
    EC_P384("ec_p384"),
    RSA_2048("rsa_2048"),
    RSA_3072("rsa_3072"),
    RSA_4096("rsa_4096");

    companion object {
        fun fromWire(s: String): KeyAlgorithm = entries.firstOrNull { it.wire == s }
            ?: throw IllegalArgumentException("unknown algorithm: $s")
    }
}

enum class KeyPurpose(val wire: String) {
    SIGN("sign"),
    ATTEST_KEY("attest_key");

    companion object {
        fun fromWire(s: String): KeyPurpose = entries.firstOrNull { it.wire == s }
            ?: throw IllegalArgumentException("unknown purpose: $s")
    }
}

enum class SignAlgorithm(val wire: String) {
    SHA256_WITH_ECDSA("sha256_with_ecdsa"),
    SHA384_WITH_ECDSA("sha384_with_ecdsa"),
    SHA256_WITH_RSA("sha256_with_rsa");

    companion object {
        fun fromWire(s: String): SignAlgorithm = entries.firstOrNull { it.wire == s }
            ?: throw IllegalArgumentException("unknown sign algorithm: $s")
    }
}

data class DeviceContext(
    val brand: String? = null,
    val device: String? = null,
    val product: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: Int? = null,
    val osPatchLevel: Int? = null,
    val vendorPatchLevel: Int? = null,
    val bootPatchLevel: Int? = null,
    val verifiedBootKey: ByteArray? = null,
    val verifiedBootHash: ByteArray? = null,
    val verifiedBootState: Int? = null,
    val deviceLocked: Boolean? = null,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

// ----------------------------------------------------------------------------
// Task result
// ----------------------------------------------------------------------------

data class TaskResultMsg(
    val taskId: UUID,
    val success: Boolean,
    val payload: TaskResultPayload? = null,
    val error: String? = null,
    val serverTiming: TimingInfo? = null,
)

sealed interface TaskResultPayload {
    data class Attest(
        val certChain: List<ByteArray>,
        val publicKeyDer: ByteArray,
    ) : TaskResultPayload {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class Sign(val signature: ByteArray) : TaskResultPayload {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class AttestExternalKey(val certChain: List<ByteArray>) : TaskResultPayload {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }
}

data class TimingInfo(
    val receivedAtServerMs: Long = 0,
    val dispatchedToProviderMs: Long = 0,
    val receivedFromProviderMs: Long = 0,
)

// ----------------------------------------------------------------------------
// Errors
// ----------------------------------------------------------------------------

data class ErrorMsg(
    val code: ErrorCode,
    val message: String,
    val relatedMsgId: UUID? = null,
)

enum class ErrorCode(val wire: String) {
    AUTH_FAILED("auth_failed"),
    INVALID_PROTOCOL_VERSION("invalid_protocol_version"),
    NO_PROVIDER_AVAILABLE("no_provider_available"),
    TASK_TIMEOUT("task_timeout"),
    INVALID_TASK("invalid_task"),
    PROVIDER_ERROR("provider_error"),
    INTERNAL_ERROR("internal_error"),
    RATE_LIMITED("rate_limited");

    companion object {
        fun fromWire(s: String): ErrorCode = entries.firstOrNull { it.wire == s }
            ?: throw IllegalArgumentException("unknown error code: $s")
    }
}

// ----------------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------------

private fun nowSecs(): Long = System.currentTimeMillis() / 1000

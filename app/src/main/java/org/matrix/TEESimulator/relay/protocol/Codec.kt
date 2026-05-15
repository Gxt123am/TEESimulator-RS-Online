package org.matrix.TEESimulator.relay.protocol

import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.value.Value
import org.msgpack.value.ValueType
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * MessagePack codec for [Envelope]. Wire format mirrors `rmp-serde::to_vec_named`:
 * structs become maps with named keys, enums become string-tagged maps.
 *
 * UUIDs are encoded as 16-byte msgpack `bin` blobs.
 *
 * Decoding uses msgpack-core's [Value] tree to handle out-of-order map keys
 * cleanly. Encoding is direct streaming for efficiency.
 */
object Codec {

    fun encode(env: Envelope): ByteArray {
        val out = ByteArrayOutputStream()
        MessagePack.newDefaultPacker(out).use { p ->
            packEnvelope(p, env)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Envelope {
        val value = MessagePack.newDefaultUnpacker(bytes).use { it.unpackValue() }
        return parseEnvelope(value)
    }

    // ========================================================================
    // ENCODING (streaming)
    // ========================================================================

    private fun packEnvelope(p: MessagePacker, env: Envelope) {
        var fields = 3 // msg_id, timestamp, payload
        if (env.inReplyTo != null) fields++
        p.packMapHeader(fields)
        p.packString("msg_id"); packUuid(p, env.msgId)
        if (env.inReplyTo != null) {
            p.packString("in_reply_to"); packUuid(p, env.inReplyTo)
        }
        p.packString("timestamp"); p.packLong(env.timestamp)
        p.packString("payload"); packPayload(p, env.payload)
    }

    private fun packPayload(p: MessagePacker, payload: Payload) {
        when (payload) {
            is Payload.Hello -> {
                p.packMapHeader(2)
                p.packString("type"); p.packString("hello")
                p.packString("hello"); packHello(p, payload.hello)
            }
            is Payload.HelloAck -> {
                p.packMapHeader(2)
                p.packString("type"); p.packString("hello_ack")
                p.packString("ack"); packHelloAck(p, payload.ack)
            }
            Payload.Ping -> {
                p.packMapHeader(1)
                p.packString("type"); p.packString("ping")
            }
            Payload.Pong -> {
                p.packMapHeader(1)
                p.packString("type"); p.packString("pong")
            }
            is Payload.SubmitTask -> {
                p.packMapHeader(2)
                p.packString("type"); p.packString("submit_task")
                p.packString("task"); packTask(p, payload.task)
            }
            is Payload.DispatchTask -> {
                p.packMapHeader(2)
                p.packString("type"); p.packString("dispatch_task")
                p.packString("task"); packTask(p, payload.task)
            }
            is Payload.TaskResult -> {
                p.packMapHeader(2)
                p.packString("type"); p.packString("task_result")
                p.packString("result"); packTaskResult(p, payload.result)
            }
            is Payload.Error -> {
                p.packMapHeader(2)
                p.packString("type"); p.packString("error")
                p.packString("error"); packError(p, payload.error)
            }
        }
    }

    private fun packHello(p: MessagePacker, h: HelloMsg) {
        p.packMapHeader(7)
        p.packString("role"); p.packString(h.role.wire)
        p.packString("device_id"); p.packString(h.deviceId)
        p.packString("auth_token"); packBin(p, h.authToken)
        p.packString("nonce"); packBin(p, h.nonce)
        p.packString("timestamp"); p.packLong(h.timestamp)
        p.packString("protocol_version"); p.packInt(h.protocolVersion)
        p.packString("capabilities"); p.packArrayHeader(h.capabilities.size)
        for (c in h.capabilities) p.packString(c)
    }

    private fun packHelloAck(p: MessagePacker, a: HelloAckMsg) {
        var fields = 2 // success, server_time
        if (a.sessionId != null) fields++
        if (a.error != null) fields++
        p.packMapHeader(fields)
        p.packString("success"); p.packBoolean(a.success)
        if (a.sessionId != null) {
            p.packString("session_id"); packUuid(p, a.sessionId)
        }
        p.packString("server_time"); p.packLong(a.serverTime)
        if (a.error != null) {
            p.packString("error"); p.packString(a.error)
        }
    }

    private fun packTask(p: MessagePacker, t: Task) {
        p.packMapHeader(4)
        p.packString("id"); packUuid(p, t.id)
        p.packString("task_type"); packTaskType(p, t.taskType)
        p.packString("timeout_ms"); p.packInt(t.timeoutMs)
        p.packString("metadata"); p.packMapHeader(t.metadata.size)
        for ((k, v) in t.metadata) {
            p.packString(k); p.packString(v)
        }
    }

    private fun packTaskType(p: MessagePacker, tt: TaskType) {
        when (tt) {
            is TaskType.Attest -> {
                var fields = 5
                if (tt.attestationApplicationId != null) fields++
                if (tt.deviceContext != null) fields++
                p.packMapHeader(fields)
                p.packString("kind"); p.packString("attest")
                p.packString("challenge"); packBin(p, tt.challenge)
                p.packString("alias_hint"); p.packString(tt.aliasHint)
                p.packString("algorithm"); p.packString(tt.algorithm.wire)
                p.packString("purpose"); p.packString(tt.purpose.wire)
                if (tt.attestationApplicationId != null) {
                    p.packString("attestation_application_id"); packBin(p, tt.attestationApplicationId)
                }
                if (tt.deviceContext != null) {
                    p.packString("device_context"); packDeviceContext(p, tt.deviceContext)
                }
            }
            is TaskType.Sign -> {
                p.packMapHeader(4)
                p.packString("kind"); p.packString("sign")
                p.packString("alias"); p.packString(tt.alias)
                p.packString("data"); packBin(p, tt.data)
                p.packString("algorithm"); p.packString(tt.algorithm.wire)
            }
            is TaskType.AttestExternalKey -> {
                var fields = 3
                if (tt.attestationApplicationId != null) fields++
                if (tt.deviceContext != null) fields++
                p.packMapHeader(fields)
                p.packString("kind"); p.packString("attest_external_key")
                p.packString("challenge"); packBin(p, tt.challenge)
                p.packString("external_public_key_der"); packBin(p, tt.externalPublicKeyDer)
                if (tt.attestationApplicationId != null) {
                    p.packString("attestation_application_id"); packBin(p, tt.attestationApplicationId)
                }
                if (tt.deviceContext != null) {
                    p.packString("device_context"); packDeviceContext(p, tt.deviceContext)
                }
            }
        }
    }

    private fun packDeviceContext(p: MessagePacker, dc: DeviceContext) {
        val entries = mutableListOf<Pair<String, Any?>>()
        dc.brand?.let { entries += "brand" to it }
        dc.device?.let { entries += "device" to it }
        dc.product?.let { entries += "product" to it }
        dc.manufacturer?.let { entries += "manufacturer" to it }
        dc.model?.let { entries += "model" to it }
        dc.osVersion?.let { entries += "os_version" to it }
        dc.osPatchLevel?.let { entries += "os_patch_level" to it }
        dc.vendorPatchLevel?.let { entries += "vendor_patch_level" to it }
        dc.bootPatchLevel?.let { entries += "boot_patch_level" to it }
        dc.verifiedBootKey?.let { entries += "verified_boot_key" to it }
        dc.verifiedBootHash?.let { entries += "verified_boot_hash" to it }
        dc.verifiedBootState?.let { entries += "verified_boot_state" to it }
        dc.deviceLocked?.let { entries += "device_locked" to it }
        p.packMapHeader(entries.size)
        for ((k, v) in entries) {
            p.packString(k)
            when (v) {
                is String -> p.packString(v)
                is Int -> p.packInt(v)
                is Boolean -> p.packBoolean(v)
                is ByteArray -> packBin(p, v)
                else -> throw IllegalArgumentException("device_context bad type: ${v?.javaClass}")
            }
        }
    }

    private fun packTaskResult(p: MessagePacker, r: TaskResultMsg) {
        var fields = 2
        if (r.payload != null) fields++
        if (r.error != null) fields++
        if (r.serverTiming != null) fields++
        p.packMapHeader(fields)
        p.packString("task_id"); packUuid(p, r.taskId)
        p.packString("success"); p.packBoolean(r.success)
        if (r.payload != null) {
            p.packString("payload"); packTaskResultPayload(p, r.payload)
        }
        if (r.error != null) {
            p.packString("error"); p.packString(r.error)
        }
        if (r.serverTiming != null) {
            p.packString("server_timing"); packTimingInfo(p, r.serverTiming)
        }
    }

    private fun packTaskResultPayload(p: MessagePacker, tp: TaskResultPayload) {
        when (tp) {
            is TaskResultPayload.Attest -> {
                p.packMapHeader(3)
                p.packString("kind"); p.packString("attest")
                p.packString("cert_chain"); p.packArrayHeader(tp.certChain.size)
                for (c in tp.certChain) packBin(p, c)
                p.packString("public_key_der"); packBin(p, tp.publicKeyDer)
            }
            is TaskResultPayload.Sign -> {
                p.packMapHeader(2)
                p.packString("kind"); p.packString("sign")
                p.packString("signature"); packBin(p, tp.signature)
            }
            is TaskResultPayload.AttestExternalKey -> {
                p.packMapHeader(2)
                p.packString("kind"); p.packString("attest_external_key")
                p.packString("cert_chain"); p.packArrayHeader(tp.certChain.size)
                for (c in tp.certChain) packBin(p, c)
            }
        }
    }

    private fun packTimingInfo(p: MessagePacker, t: TimingInfo) {
        p.packMapHeader(3)
        p.packString("received_at_server_ms"); p.packLong(t.receivedAtServerMs)
        p.packString("dispatched_to_provider_ms"); p.packLong(t.dispatchedToProviderMs)
        p.packString("received_from_provider_ms"); p.packLong(t.receivedFromProviderMs)
    }

    private fun packError(p: MessagePacker, e: ErrorMsg) {
        var fields = 2
        if (e.relatedMsgId != null) fields++
        p.packMapHeader(fields)
        p.packString("code"); p.packString(e.code.wire)
        p.packString("message"); p.packString(e.message)
        if (e.relatedMsgId != null) {
            p.packString("related_msg_id"); packUuid(p, e.relatedMsgId)
        }
    }

    private fun packUuid(p: MessagePacker, uuid: UUID) {
        val buf = ByteArray(16)
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        for (i in 0..7) buf[i] = (msb ushr (56 - i * 8)).toByte()
        for (i in 0..7) buf[8 + i] = (lsb ushr (56 - i * 8)).toByte()
        packBin(p, buf)
    }

    private fun packBin(p: MessagePacker, bytes: ByteArray) {
        p.packBinaryHeader(bytes.size)
        p.writePayload(bytes)
    }

    // ========================================================================
    // DECODING (Value tree based)
    // ========================================================================

    private fun parseEnvelope(v: Value): Envelope {
        val map = v.asMapValue().map()
        return Envelope(
            msgId = map.requireKey("msg_id").asUuid(),
            inReplyTo = map.optKey("in_reply_to")?.asUuid(),
            timestamp = map.requireKey("timestamp").asNumberValue().toLong(),
            payload = parsePayload(map.requireKey("payload")),
        )
    }

    private fun parsePayload(v: Value): Payload {
        val map = v.asMapValue().map()
        val type = map.requireKey("type").asStringValue().asString()
        return when (type) {
            "ping" -> Payload.Ping
            "pong" -> Payload.Pong
            "hello" -> Payload.Hello(parseHello(map.requireKey("hello")))
            "hello_ack" -> Payload.HelloAck(parseHelloAck(map.requireKey("ack")))
            "submit_task" -> Payload.SubmitTask(parseTask(map.requireKey("task")))
            "dispatch_task" -> Payload.DispatchTask(parseTask(map.requireKey("task")))
            "task_result" -> Payload.TaskResult(parseTaskResult(map.requireKey("result")))
            "error" -> Payload.Error(parseError(map.requireKey("error")))
            else -> throw IllegalArgumentException("unknown payload type: $type")
        }
    }

    private fun parseHello(v: Value): HelloMsg {
        val map = v.asMapValue().map()
        return HelloMsg(
            role = Role.fromWire(map.requireKey("role").asStringValue().asString()),
            deviceId = map.requireKey("device_id").asStringValue().asString(),
            authToken = map.requireKey("auth_token").asBytes(),
            nonce = map.requireKey("nonce").asBytes(),
            timestamp = map.requireKey("timestamp").asNumberValue().toLong(),
            protocolVersion = map.requireKey("protocol_version").asNumberValue().toInt(),
            capabilities = map.optKey("capabilities")?.asArrayValue()?.list()
                ?.map { it.asStringValue().asString() }
                ?: emptyList(),
        )
    }

    private fun parseHelloAck(v: Value): HelloAckMsg {
        val map = v.asMapValue().map()
        return HelloAckMsg(
            success = map.requireKey("success").asBooleanValue().boolean,
            sessionId = map.optKey("session_id")?.asUuid(),
            serverTime = map.requireKey("server_time").asNumberValue().toLong(),
            error = map.optKey("error")?.asStringValue()?.asString(),
        )
    }

    private fun parseTask(v: Value): Task {
        val map = v.asMapValue().map()
        return Task(
            id = map.requireKey("id").asUuid(),
            taskType = parseTaskType(map.requireKey("task_type")),
            timeoutMs = map.requireKey("timeout_ms").asNumberValue().toInt(),
            metadata = map.optKey("metadata")?.asMapValue()?.map()?.entries
                ?.associate {
                    it.key.asStringValue().asString() to it.value.asStringValue().asString()
                } ?: emptyMap(),
        )
    }

    private fun parseTaskType(v: Value): TaskType {
        val map = v.asMapValue().map()
        return when (val kind = map.requireKey("kind").asStringValue().asString()) {
            "attest" -> TaskType.Attest(
                challenge = map.requireKey("challenge").asBytes(),
                aliasHint = map.optKey("alias_hint")?.asStringValue()?.asString() ?: "",
                algorithm = KeyAlgorithm.fromWire(
                    map.requireKey("algorithm").asStringValue().asString()
                ),
                purpose = KeyPurpose.fromWire(
                    map.requireKey("purpose").asStringValue().asString()
                ),
                attestationApplicationId = map.optKey("attestation_application_id")?.asBytes(),
                deviceContext = map.optKey("device_context")?.let { parseDeviceContext(it) },
            )
            "sign" -> TaskType.Sign(
                alias = map.requireKey("alias").asStringValue().asString(),
                data = map.requireKey("data").asBytes(),
                algorithm = SignAlgorithm.fromWire(
                    map.requireKey("algorithm").asStringValue().asString()
                ),
            )
            "attest_external_key" -> TaskType.AttestExternalKey(
                challenge = map.requireKey("challenge").asBytes(),
                externalPublicKeyDer = map.requireKey("external_public_key_der").asBytes(),
                attestationApplicationId = map.optKey("attestation_application_id")?.asBytes(),
                deviceContext = map.optKey("device_context")?.let { parseDeviceContext(it) },
            )
            else -> throw IllegalArgumentException("unknown task kind: $kind")
        }
    }

    private fun parseDeviceContext(v: Value): DeviceContext {
        val map = v.asMapValue().map()
        return DeviceContext(
            brand = map.optKey("brand")?.asStringValue()?.asString(),
            device = map.optKey("device")?.asStringValue()?.asString(),
            product = map.optKey("product")?.asStringValue()?.asString(),
            manufacturer = map.optKey("manufacturer")?.asStringValue()?.asString(),
            model = map.optKey("model")?.asStringValue()?.asString(),
            osVersion = map.optKey("os_version")?.asNumberValue()?.toInt(),
            osPatchLevel = map.optKey("os_patch_level")?.asNumberValue()?.toInt(),
            vendorPatchLevel = map.optKey("vendor_patch_level")?.asNumberValue()?.toInt(),
            bootPatchLevel = map.optKey("boot_patch_level")?.asNumberValue()?.toInt(),
            verifiedBootKey = map.optKey("verified_boot_key")?.asBytes(),
            verifiedBootHash = map.optKey("verified_boot_hash")?.asBytes(),
            verifiedBootState = map.optKey("verified_boot_state")?.asNumberValue()?.toInt(),
            deviceLocked = map.optKey("device_locked")?.asBooleanValue()?.boolean,
        )
    }

    private fun parseTaskResult(v: Value): TaskResultMsg {
        val map = v.asMapValue().map()
        return TaskResultMsg(
            taskId = map.requireKey("task_id").asUuid(),
            success = map.requireKey("success").asBooleanValue().boolean,
            payload = map.optKey("payload")?.let { parseTaskResultPayload(it) },
            error = map.optKey("error")?.asStringValue()?.asString(),
            serverTiming = map.optKey("server_timing")?.let { parseTimingInfo(it) },
        )
    }

    private fun parseTaskResultPayload(v: Value): TaskResultPayload {
        val map = v.asMapValue().map()
        return when (val kind = map.requireKey("kind").asStringValue().asString()) {
            "attest" -> TaskResultPayload.Attest(
                certChain = map.requireKey("cert_chain").asArrayValue().list()
                    .map { it.asBytes() },
                publicKeyDer = map.requireKey("public_key_der").asBytes(),
            )
            "sign" -> TaskResultPayload.Sign(
                signature = map.requireKey("signature").asBytes(),
            )
            "attest_external_key" -> TaskResultPayload.AttestExternalKey(
                certChain = map.requireKey("cert_chain").asArrayValue().list()
                    .map { it.asBytes() },
            )
            else -> throw IllegalArgumentException("unknown result kind: $kind")
        }
    }

    private fun parseTimingInfo(v: Value): TimingInfo {
        val map = v.asMapValue().map()
        return TimingInfo(
            receivedAtServerMs = map.requireKey("received_at_server_ms").asNumberValue().toLong(),
            dispatchedToProviderMs = map.requireKey("dispatched_to_provider_ms").asNumberValue().toLong(),
            receivedFromProviderMs = map.requireKey("received_from_provider_ms").asNumberValue().toLong(),
        )
    }

    private fun parseError(v: Value): ErrorMsg {
        val map = v.asMapValue().map()
        return ErrorMsg(
            code = ErrorCode.fromWire(map.requireKey("code").asStringValue().asString()),
            message = map.requireKey("message").asStringValue().asString(),
            relatedMsgId = map.optKey("related_msg_id")?.asUuid(),
        )
    }

    // ========================================================================
    // Value helpers 鈥?make working with the Value tree less verbose.
    // ========================================================================

    private fun Map<Value, Value>.requireKey(key: String): Value {
        for ((k, v) in this) {
            if (k.valueType == ValueType.STRING && k.asStringValue().asString() == key) {
                return v
            }
        }
        throw NoSuchElementException("missing required key: $key")
    }

    private fun Map<Value, Value>.optKey(key: String): Value? {
        for ((k, v) in this) {
            if (k.valueType == ValueType.STRING && k.asStringValue().asString() == key) {
                return if (v.isNilValue) null else v
            }
        }
        return null
    }

    /** Decode any binary-or-string Value to bytes. */
    private fun Value.asBytes(): ByteArray = when (valueType) {
        ValueType.BINARY -> asBinaryValue().asByteArray()
        ValueType.STRING -> asStringValue().asByteArray()
        else -> throw IllegalArgumentException("expected bin/str, got $valueType")
    }

    /** Decode a 16-byte binary blob into a UUID. */
    private fun Value.asUuid(): UUID {
        val bytes = asBytes()
        require(bytes.size == 16) { "uuid must be 16 bytes, got ${bytes.size}" }
        var msb = 0L
        var lsb = 0L
        for (i in 0..7) msb = (msb shl 8) or (bytes[i].toLong() and 0xff)
        for (i in 0..7) lsb = (lsb shl 8) or (bytes[8 + i].toLong() and 0xff)
        return UUID(msb, lsb)
    }
}

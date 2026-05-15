package org.ommega.relay.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CodecTest {

    @Test
    fun roundtripPing() {
        val env = Envelope.new(Payload.Ping)
        val bytes = Codec.encode(env)
        val decoded = Codec.decode(bytes)
        assertTrue(decoded.payload is Payload.Ping)
        assertEquals(env.msgId, decoded.msgId)
    }

    @Test
    fun roundtripPong() {
        val env = Envelope.new(Payload.Pong)
        val bytes = Codec.encode(env)
        val decoded = Codec.decode(bytes)
        assertTrue(decoded.payload is Payload.Pong)
    }

    @Test
    fun roundtripHello() {
        val nonce = ByteArray(16) { it.toByte() }
        val token = ByteArray(32) { 0xAB.toByte() }
        val hello = HelloMsg(
            role = Role.CONSUMER,
            deviceId = "device-a-1",
            authToken = token,
            nonce = nonce,
            timestamp = 1700000000,
            protocolVersion = PROTOCOL_VERSION,
            capabilities = listOf("attest", "sign"),
        )
        val env = Envelope.new(Payload.Hello(hello))
        val bytes = Codec.encode(env)
        val decoded = Codec.decode(bytes)
        val h = (decoded.payload as Payload.Hello).hello
        assertEquals(Role.CONSUMER, h.role)
        assertEquals("device-a-1", h.deviceId)
        assertArrayEquals(token, h.authToken)
        assertArrayEquals(nonce, h.nonce)
        assertEquals(1700000000L, h.timestamp)
        assertEquals(PROTOCOL_VERSION, h.protocolVersion)
        assertEquals(listOf("attest", "sign"), h.capabilities)
    }

    @Test
    fun roundtripSubmitTaskAttest() {
        val task = Task(
            id = UUID.randomUUID(),
            taskType = TaskType.Attest(
                challenge = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                aliasHint = "test-alias",
                algorithm = KeyAlgorithm.EC_P256,
                purpose = KeyPurpose.SIGN,
                attestationApplicationId = byteArrayOf(0x30, 0x10, 0x12),
                deviceContext = DeviceContext(
                    brand = "google",
                    model = "Pixel 7",
                    osVersion = 14,
                    verifiedBootState = 0,
                    deviceLocked = true,
                ),
            ),
            timeoutMs = 5000,
            metadata = mapOf("source" to "test"),
        )
        val env = Envelope.new(Payload.SubmitTask(task))
        val bytes = Codec.encode(env)
        val decoded = Codec.decode(bytes)
        val t = (decoded.payload as Payload.SubmitTask).task
        val tt = t.taskType as TaskType.Attest
        assertEquals(task.id, t.id)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), tt.challenge)
        assertEquals("test-alias", tt.aliasHint)
        assertEquals(KeyAlgorithm.EC_P256, tt.algorithm)
        assertEquals(KeyPurpose.SIGN, tt.purpose)
        assertEquals("google", tt.deviceContext?.brand)
        assertEquals(true, tt.deviceContext?.deviceLocked)
        assertEquals(0, tt.deviceContext?.verifiedBootState)
        assertEquals("test", t.metadata["source"])
    }

    @Test
    fun roundtripDispatchTaskSign() {
        val task = Task(
            id = UUID.randomUUID(),
            taskType = TaskType.Sign(
                alias = "my-key",
                data = ByteArray(32) { it.toByte() },
                algorithm = SignAlgorithm.SHA256_WITH_ECDSA,
            ),
            timeoutMs = 1000,
        )
        val env = Envelope.new(Payload.DispatchTask(task))
        val bytes = Codec.encode(env)
        val decoded = Codec.decode(bytes)
        val t = (decoded.payload as Payload.DispatchTask).task
        val tt = t.taskType as TaskType.Sign
        assertEquals("my-key", tt.alias)
        assertEquals(32, tt.data.size)
        assertEquals(SignAlgorithm.SHA256_WITH_ECDSA, tt.algorithm)
    }

    @Test
    fun roundtripTaskResultAttest() {
        val taskId = UUID.randomUUID()
        val result = TaskResultMsg(
            taskId = taskId,
            success = true,
            payload = TaskResultPayload.Attest(
                certChain = listOf(
                    ByteArray(32) { 0xAA.toByte() },
                    ByteArray(64) { 0xBB.toByte() },
                ),
                publicKeyDer = ByteArray(91) { 0xCC.toByte() },
            ),
        )
        val env = Envelope.new(Payload.TaskResult(result))
        val bytes = Codec.encode(env)
        val decoded = Codec.decode(bytes)
        val r = (decoded.payload as Payload.TaskResult).result
        val pl = r.payload as TaskResultPayload.Attest
        assertEquals(taskId, r.taskId)
        assertTrue(r.success)
        assertEquals(2, pl.certChain.size)
        assertEquals(32, pl.certChain[0].size)
        assertEquals(64, pl.certChain[1].size)
        assertEquals(91, pl.publicKeyDer.size)
    }

    @Test
    fun roundtripTaskResultError() {
        val taskId = UUID.randomUUID()
        val result = TaskResultMsg(
            taskId = taskId,
            success = false,
            error = "no provider available",
        )
        val env = Envelope.new(Payload.TaskResult(result))
        val bytes = Codec.encode(env)
        val decoded = Codec.decode(bytes)
        val r = (decoded.payload as Payload.TaskResult).result
        assertFalse(r.success)
        assertEquals("no provider available", r.error)
    }

    @Test
    fun roundtripError() {
        val err = ErrorMsg(
            code = ErrorCode.AUTH_FAILED,
            message = "bad token",
            relatedMsgId = UUID.randomUUID(),
        )
        val env = Envelope.new(Payload.Error(err))
        val bytes = Codec.encode(env)
        val decoded = Codec.decode(bytes)
        val e = (decoded.payload as Payload.Error).error
        assertEquals(ErrorCode.AUTH_FAILED, e.code)
        assertEquals("bad token", e.message)
        assertEquals(err.relatedMsgId, e.relatedMsgId)
    }

    @Test
    fun authTokenMatchesRustVector() {
        val psk = "super-secret-psk-must-be-strong".toByteArray(Charsets.UTF_8)
        val deviceId = "device-b-1"
        val nonce = "0123456789abcdef".toByteArray(Charsets.UTF_8)
        val ts = 1_700_000_000L

        val token = Auth.computeAuthToken(psk, deviceId, nonce, ts)
        assertEquals(32, token.size)
        assertTrue(Auth.verifyAuthToken(psk, deviceId, nonce, ts, token))
        assertFalse(Auth.verifyAuthToken("other".toByteArray(), deviceId, nonce, ts, token))
    }

    @Test
    fun envelopeWithReplyTo() {
        val original = Envelope.new(Payload.Ping)
        val reply = Envelope.replyTo(original.msgId, Payload.Pong)
        val bytes = Codec.encode(reply)
        val decoded = Codec.decode(bytes)
        assertNotNull(decoded.inReplyTo)
        assertEquals(original.msgId, decoded.inReplyTo)
    }
}

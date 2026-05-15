package org.matrix.TEESimulator.relay

import org.matrix.TEESimulator.logging.SystemLogger
import org.matrix.TEESimulator.relay.protocol.DeviceContext
import org.matrix.TEESimulator.relay.protocol.Task
import org.matrix.TEESimulator.relay.protocol.TaskResultPayload
import org.matrix.TEESimulator.relay.protocol.TaskType
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID

/**
 * High-level façade for the RELAY mode. Owns a singleton [RelayClient]
 * connected to the configured server, and exposes [relayCertChain] which the
 * keystore interceptor calls during `getKeyEntry` post-transact.
 *
 * The flow:
 *   1. Caller passes the original cert chain (returned by the device's real
 *      KeyMint) plus the calling UID.
 *   2. We pull the *public key* out of the original leaf and submit an
 *      [TaskType.AttestExternalKey] task to the Provider.
 *   3. Provider's TEE generates a fresh signer key (via setAttestKeyAlias),
 *      hand-builds a leaf cert that wraps our pubkey, and returns the chain.
 *   4. We parse the returned DER blobs back into [X509Certificate] objects
 *      and hand them to the caller, which writes them into the binder reply.
 *
 * If anything goes wrong (no config, not connected, server timeout, etc.),
 * we return null. The caller should fall back to PATCH or SkipTransaction.
 */
object RelayEngine {

    private const val DEFAULT_TASK_TIMEOUT_MS = 5_000L

    @Volatile private var client: RelayClient? = null
    @Volatile private var configLoadedAtMs: Long = 0
    private val initLock = Any()

    /**
     * Initialize the relay client. Called from [App.main] right after the
     * existing TEES-RS init steps. Returns true if we have a valid config and
     * the client started; false otherwise.
     */
    fun initialize(): Boolean = synchronized(initLock) {
        if (client != null) return true
        val config = RelayConfigLoader.load() ?: return false
        val newClient = RelayClient(config)
        newClient.start()
        client = newClient
        configLoadedAtMs = System.currentTimeMillis()
        SystemLogger.info("RelayEngine: initialized client for ${config.deviceId} -> ${config.url}")
        return true
    }

    fun shutdown() = synchronized(initLock) {
        client?.stop()
        client = null
    }

    fun isReady(): Boolean = client?.isConnected() == true

    /**
     * Forward an attestation request to the relay Provider and return the
     * resulting cert chain, or null on failure.
     *
     * @param originalChain  the chain we got from the local KeyMint (we use
     *                       its leaf's public key as the external pubkey to
     *                       attest)
     * @param challenge      the attestation challenge from the request
     * @param appId          optional attestation_application_id bytes
     */
    fun relayCertChain(
        originalChain: Array<Certificate>,
        challenge: ByteArray,
        appId: ByteArray? = null,
    ): Array<Certificate>? {
        val c = client
        if (c == null) {
            SystemLogger.warning("RelayEngine: not initialized, skipping")
            return null
        }
        if (!c.isConnected()) {
            SystemLogger.warning("RelayEngine: client not connected, skipping")
            return null
        }
        if (originalChain.isEmpty()) {
            SystemLogger.warning("RelayEngine: original chain empty")
            return null
        }

        val leafPubKeyDer = (originalChain[0] as X509Certificate).publicKey.encoded

        val task = Task(
            id = UUID.randomUUID(),
            taskType = TaskType.AttestExternalKey(
                challenge = challenge,
                externalPublicKeyDer = leafPubKeyDer,
                attestationApplicationId = appId,
                deviceContext = null, // Provider uses its own device context
            ),
            timeoutMs = DEFAULT_TASK_TIMEOUT_MS.toInt(),
        )

        val started = System.nanoTime()
        val result = c.submit(task, DEFAULT_TASK_TIMEOUT_MS) ?: run {
            SystemLogger.warning("RelayEngine: submit returned null for task ${task.id}")
            return null
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        if (!result.success) {
            SystemLogger.warning("RelayEngine: task ${task.id} failed (${elapsedMs}ms): ${result.error}")
            return null
        }

        val chainDer = (result.payload as? TaskResultPayload.AttestExternalKey)?.certChain
            ?: run {
                SystemLogger.warning("RelayEngine: unexpected payload type")
                return null
            }

        if (chainDer.isEmpty()) {
            SystemLogger.warning("RelayEngine: returned chain empty")
            return null
        }

        val cf = CertificateFactory.getInstance("X.509")
        val certs = chainDer.map { der ->
            cf.generateCertificate(der.inputStream()) as X509Certificate
        }

        SystemLogger.info(
            "RelayEngine: task ${task.id} OK in ${elapsedMs}ms, chain len=${certs.size}"
        )
        return certs.toTypedArray<Certificate>()
    }
}

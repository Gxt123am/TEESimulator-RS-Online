package org.matrix.TEESimulator.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.matrix.TEESimulator.logging.SystemLogger
import org.matrix.TEESimulator.relay.protocol.Auth
import org.matrix.TEESimulator.relay.protocol.Codec
import org.matrix.TEESimulator.relay.protocol.Envelope
import org.matrix.TEESimulator.relay.protocol.HelloMsg
import org.matrix.TEESimulator.relay.protocol.PROTOCOL_VERSION
import org.matrix.TEESimulator.relay.protocol.Payload
import org.matrix.TEESimulator.relay.protocol.Role
import org.matrix.TEESimulator.relay.protocol.Task
import org.matrix.TEESimulator.relay.protocol.TaskResultMsg
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Consumer-side WebSocket client. Maintains a long-lived authenticated session
 * to the relay server and exposes a synchronous [submit] API that hook code
 * can call from the keystore2 binder thread.
 *
 * Threading: the WebSocket runs on its own connect/reconnect thread plus
 * OkHttp's internal pools. Multiple [submit] calls can be in flight; each one
 * blocks its caller on a per-task latch until the matching `TaskResult`
 * arrives or the timeout expires.
 */
class RelayClient(private val config: RelayConfig) {

    /** Pending task → latch + slot for the response. */
    private class PendingTask {
        val latch = CountDownLatch(1)
        @Volatile
        var result: TaskResultMsg? = null
    }

    private val running = AtomicBoolean(false)
    private val authenticated = AtomicBoolean(false)
    private val currentSocket = AtomicReference<WebSocket?>(null)
    private val pending = ConcurrentHashMap<UUID, PendingTask>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "omega-relay-client").apply { isDaemon = true }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.submit { runReconnectLoop() }
    }

    fun stop() {
        running.set(false)
        currentSocket.get()?.close(NORMAL_CLOSURE, "shutdown")
        executor.shutdownNow()
        // Wake every blocked caller with an error.
        pending.values.forEach {
            it.result = TaskResultMsg(taskId = UUID(0, 0), success = false, error = "client stopped")
            it.latch.countDown()
        }
        pending.clear()
    }

    fun isConnected(): Boolean = authenticated.get()

    /**
     * Submit a [Task] and block up to [timeoutMs] ms waiting for the result.
     * Returns null if the call timed out or the connection isn't usable.
     *
     * Safe to call from any thread, including the keystore2 binder thread.
     * The call is synchronous, but does not hold any lock that would block
     * other concurrent tasks.
     */
    fun submit(task: Task, timeoutMs: Long): TaskResultMsg? {
        if (!authenticated.get()) {
            SystemLogger.warning("RelayClient: not authenticated, dropping task ${task.id}")
            return null
        }
        val socket = currentSocket.get()
        if (socket == null) {
            SystemLogger.warning("RelayClient: no socket, dropping task ${task.id}")
            return null
        }

        val pendingEntry = PendingTask()
        pending[task.id] = pendingEntry
        try {
            val env = Envelope.new(Payload.SubmitTask(task))
            val bytes = Codec.encode(env)
            if (!socket.send(bytes.toByteString())) {
                SystemLogger.warning("RelayClient: send queue full for task ${task.id}")
                return null
            }
            val ok = pendingEntry.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!ok) {
                SystemLogger.warning("RelayClient: task ${task.id} timed out after ${timeoutMs}ms")
                return null
            }
            return pendingEntry.result
        } finally {
            pending.remove(task.id)
        }
    }

    // ------------------------------------------------------------------------
    // Internal: connect/reconnect loop
    // ------------------------------------------------------------------------

    private fun runReconnectLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (running.get()) {
            try {
                connectAndServe()
                backoffMs = INITIAL_BACKOFF_MS
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                SystemLogger.warning("RelayClient: connection failed: ${e.message}")
            }
            if (!running.get()) return
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                return
            }
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun connectAndServe() {
        val client = buildHttpClient()
        val request = Request.Builder().url(config.url).build()

        val connectionDone = CountDownLatch(1)
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                SystemLogger.info("RelayClient: websocket open: ${response.code}")
                currentSocket.set(webSocket)
                sendHello(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    handleEnvelope(webSocket, Codec.decode(bytes.toByteArray()))
                } catch (e: Exception) {
                    SystemLogger.error("RelayClient: message handling failed", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                SystemLogger.warning("RelayClient: ignoring text frame")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                SystemLogger.info("RelayClient: websocket closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                SystemLogger.info("RelayClient: websocket closed: $code $reason")
                onSocketGone(webSocket)
                connectionDone.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                SystemLogger.warning("RelayClient: websocket failed: ${t.message}")
                onSocketGone(webSocket)
                connectionDone.countDown()
            }
        })

        connectionDone.await()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun onSocketGone(webSocket: WebSocket) {
        currentSocket.compareAndSet(webSocket, null)
        authenticated.set(false)
        // Wake all in-flight callers — they'll fall back per their own policy.
        pending.values.forEach {
            it.result = TaskResultMsg(taskId = UUID(0, 0), success = false, error = "socket dropped")
            it.latch.countDown()
        }
        pending.clear()
    }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SECS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived
            .connectTimeout(10, TimeUnit.SECONDS)

        if (config.tlsInsecure) {
            SystemLogger.warning("RelayClient: TLS verification DISABLED — only for local dev")
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, trustAll, SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, trustAll[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    private fun sendHello(socket: WebSocket) {
        val nonce = Auth.randomNonce()
        val ts = System.currentTimeMillis() / 1000
        val token = Auth.computeAuthToken(config.psk, config.deviceId, nonce, ts)

        val hello = HelloMsg(
            role = Role.CONSUMER,
            deviceId = config.deviceId,
            authToken = token,
            nonce = nonce,
            timestamp = ts,
            protocolVersion = PROTOCOL_VERSION,
            capabilities = listOf("attest_external_key"),
        )
        val env = Envelope.new(Payload.Hello(hello))
        socket.send(Codec.encode(env).toByteString())
        SystemLogger.info("RelayClient: Hello sent for device_id=${config.deviceId}")
    }

    private fun handleEnvelope(socket: WebSocket, env: Envelope) {
        when (val p = env.payload) {
            is Payload.HelloAck -> {
                if (p.ack.success) {
                    SystemLogger.info("RelayClient: authenticated, session_id=${p.ack.sessionId}")
                    authenticated.set(true)
                } else {
                    SystemLogger.error("RelayClient: auth rejected: ${p.ack.error}")
                    socket.close(NORMAL_CLOSURE, "auth rejected")
                }
            }
            is Payload.TaskResult -> {
                val pendingEntry = pending[p.result.taskId]
                if (pendingEntry != null) {
                    pendingEntry.result = p.result
                    pendingEntry.latch.countDown()
                } else {
                    SystemLogger.warning("RelayClient: task ${p.result.taskId} result arrived but no pending caller")
                }
            }
            Payload.Ping -> {
                socket.send(Codec.encode(Envelope.replyTo(env.msgId, Payload.Pong)).toByteString())
            }
            Payload.Pong -> { /* swallow */ }
            is Payload.Error -> {
                SystemLogger.warning("RelayClient: server error: ${p.error.code} ${p.error.message}")
            }
            else -> {
                SystemLogger.warning("RelayClient: unexpected payload: ${p.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val PING_INTERVAL_SECS = 25L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val NORMAL_CLOSURE = 1000
    }
}

/**
 * Minimal config carrier for the consumer-side relay client.
 * Loaded by [RelayConfigLoader] from /data/adb/tricky_store/omega-relay.conf.
 */
data class RelayConfig(
    val url: String,
    val psk: ByteArray,
    val deviceId: String,
    val tlsInsecure: Boolean = false,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

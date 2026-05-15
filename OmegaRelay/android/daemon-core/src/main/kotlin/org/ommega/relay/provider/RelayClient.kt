package org.ommega.relay.provider

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.ommega.relay.protocol.Auth
import org.ommega.relay.protocol.Codec
import org.ommega.relay.protocol.Envelope
import org.ommega.relay.protocol.HelloMsg
import org.ommega.relay.protocol.PROTOCOL_VERSION
import org.ommega.relay.protocol.Payload
import org.ommega.relay.protocol.Role
import org.ommega.relay.provider.attest.AttestationEngine
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Long-lived WebSocket client. Connects to the relay server, authenticates,
 * then loops handling [Payload.DispatchTask] messages by delegating to an
 * [AttestationEngine].
 *
 * The client owns its own thread + reconnect loop. Call [start] once and then
 * [stop] to shut down.
 */
class RelayClient(
    private val config: ProviderConfig,
    private val engine: AttestationEngine,
) {
    private val running = AtomicBoolean(false)
    private val currentSocket = AtomicReference<WebSocket?>(null)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "omega-relay-client").apply { isDaemon = true }
    }
    /** Engine work runs on a small bounded pool so a slow attest can't block reads. */
    private val workerPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "omega-engine-worker").apply { isDaemon = true }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.submit { runReconnectLoop() }
    }

    fun stop() {
        running.set(false)
        currentSocket.get()?.close(NORMAL_CLOSURE, "shutdown")
        executor.shutdownNow()
        workerPool.shutdown()
    }

    // ------------------------------------------------------------------------
    // Internal: reconnect loop
    // ------------------------------------------------------------------------

    private fun runReconnectLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (running.get()) {
            try {
                connectAndServe()
                // Clean disconnect: reset backoff.
                backoffMs = INITIAL_BACKOFF_MS
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w("connection failed: ${e.message}")
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

        val connectionDone = java.util.concurrent.CountDownLatch(1)
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("websocket open: ${response.code}")
                currentSocket.set(webSocket)
                sendHello(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                workerPool.submit {
                    try {
                        handleEnvelope(webSocket, Codec.decode(bytes.toByteArray()))
                    } catch (e: Exception) {
                        Log.e("message handling failed", e)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.w("ignoring text frame")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("websocket closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("websocket closed: $code $reason")
                currentSocket.compareAndSet(webSocket, null)
                connectionDone.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("websocket failed: ${t.message}")
                currentSocket.compareAndSet(webSocket, null)
                connectionDone.countDown()
            }
        })

        // Wait for the socket to close.
        connectionDone.await()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SECS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived
            .connectTimeout(10, TimeUnit.SECONDS)

        if (config.tlsInsecure) {
            Log.w("TLS verification DISABLED — only for local dev")
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
        // TODO: support config.caPem for proper cert pinning.

        return builder.build()
    }

    private fun sendHello(socket: WebSocket) {
        val nonce = Auth.randomNonce()
        val ts = System.currentTimeMillis() / 1000
        val token = Auth.computeAuthToken(config.psk, config.deviceId, nonce, ts)

        val hello = HelloMsg(
            role = Role.PROVIDER,
            deviceId = config.deviceId,
            authToken = token,
            nonce = nonce,
            timestamp = ts,
            protocolVersion = PROTOCOL_VERSION,
            capabilities = listOf("attest", "sign", "attest_external_key"),
        )
        sendEnvelope(socket, Envelope.new(Payload.Hello(hello)))
        Log.i("Hello sent for device_id=${config.deviceId}")
    }

    private fun handleEnvelope(socket: WebSocket, env: Envelope) {
        when (val p = env.payload) {
            is Payload.HelloAck -> {
                if (p.ack.success) {
                    Log.i("authenticated, session_id=${p.ack.sessionId}")
                } else {
                    Log.e("auth rejected: ${p.ack.error}")
                    socket.close(NORMAL_CLOSURE, "auth rejected")
                }
            }
            is Payload.DispatchTask -> {
                val task = p.task
                Log.i("dispatch task=${task.id} kind=${task.taskType.javaClass.simpleName}")
                val started = System.nanoTime()
                val result = try {
                    engine.handle(task)
                } catch (e: Exception) {
                    Log.e("engine threw on task=${task.id}", e)
                    org.ommega.relay.protocol.TaskResultMsg(
                        taskId = task.id,
                        success = false,
                        error = "engine error: ${e.message}",
                    )
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                Log.i("task=${task.id} done in ${elapsedMs}ms success=${result.success}")
                sendEnvelope(socket, Envelope.new(Payload.TaskResult(result)))
            }
            Payload.Ping -> {
                sendEnvelope(socket, Envelope.replyTo(env.msgId, Payload.Pong))
            }
            Payload.Pong -> { /* swallow */ }
            is Payload.Error -> {
                Log.w("server error: ${p.error.code} ${p.error.message}")
            }
            else -> {
                Log.w("unexpected payload: ${p.javaClass.simpleName}")
            }
        }
    }

    private fun sendEnvelope(socket: WebSocket, env: Envelope) {
        val bytes = Codec.encode(env)
        if (!socket.send(bytes.toByteString())) {
            Log.w("send queue full or socket closed")
        }
    }

    companion object {
        private const val PING_INTERVAL_SECS = 25L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val NORMAL_CLOSURE = 1000
    }
}

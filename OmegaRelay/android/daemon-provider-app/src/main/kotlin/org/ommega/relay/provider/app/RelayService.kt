package org.ommega.relay.provider.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.ommega.relay.provider.RelayClient
import org.ommega.relay.provider.attest.AttestationEngine
import org.ommega.relay.provider.attest.KeystoreEngine
import org.ommega.relay.provider.attest.StubEngine

/**
 * Foreground service that hosts the WebSocket client. Foreground status keeps
 * Android (and OEM-specific aggressive killers) from reaping us when the app
 * is in the background.
 */
class RelayService : Service() {

    private var client: RelayClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground("connecting…")

        if (client == null) {
            val cfg = Prefs(this).toProviderConfig()
            if (cfg == null) {
                Log.w(TAG, "no valid config; service will idle")
                ensureForeground("not configured — open the app to set up")
                return START_STICKY
            }

            val engine: AttestationEngine = KeystoreEngine().let {
                if (it.isAvailable()) {
                    Log.i(TAG, "using AndroidKeyStore engine")
                    it
                } else {
                    Log.w(TAG, "AndroidKeyStore unavailable — using stub")
                    StubEngine()
                }
            }
            client = RelayClient(cfg, engine).also { it.start() }
            ensureForeground("running — device_id=${cfg.deviceId}")
            Log.i(TAG, "client started for ${cfg.deviceId} -> ${cfg.url}")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.stop()
        client = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        Log.i(TAG, "service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = android.content.Intent(this, MainActivity::class.java)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OmegaRelay Provider")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Relay Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Status of the OmegaRelay relay connection"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:relay",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    companion object {
        private const val TAG = "OmegaRelay/Service"
        private const val NOTIF_ID = 0xCAFE
        private const val CHANNEL_ID = "omega_relay"

        fun start(context: android.content.Context) {
            val intent = Intent(context, RelayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}

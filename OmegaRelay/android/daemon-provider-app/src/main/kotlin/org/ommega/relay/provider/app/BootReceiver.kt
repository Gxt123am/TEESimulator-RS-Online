package org.ommega.relay.provider.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-start the relay service after a reboot, but only if the user has
 * enabled it in the UI.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        val prefs = Prefs(context)
        if (prefs.enabled && prefs.toProviderConfig() != null) {
            Log.i("OmegaRelay/Boot", "auto-starting relay service")
            RelayService.start(context)
        }
    }
}

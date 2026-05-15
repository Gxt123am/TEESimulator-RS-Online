package org.ommega.relay.provider.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.ommega.relay.provider.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) tryStartService()
        else binding.statusText.text = "Notification permission denied — service cannot run as foreground"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        // Hydrate UI.
        binding.urlInput.setText(prefs.url)
        binding.deviceIdInput.setText(prefs.deviceId)
        binding.pskInput.setText(prefs.psk)
        binding.tlsInsecureCheckbox.isChecked = prefs.tlsInsecure
        binding.enabledSwitch.isChecked = prefs.enabled

        binding.saveButton.setOnClickListener { onSave() }
        binding.startStopButton.setOnClickListener { onStartStop() }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun onSave() {
        prefs.url = binding.urlInput.text.toString().trim()
        prefs.deviceId = binding.deviceIdInput.text.toString().trim()
        prefs.psk = binding.pskInput.text.toString()
        prefs.tlsInsecure = binding.tlsInsecureCheckbox.isChecked

        val cfg = prefs.toProviderConfig()
        if (cfg == null) {
            binding.statusText.text = "Config invalid: URL must be ws/wss, " +
                "PSK ≥ 16 chars, device_id non-empty"
            return
        }
        binding.statusText.text = "Saved."
    }

    private fun onStartStop() {
        if (prefs.enabled) {
            // Stop.
            prefs.enabled = false
            RelayService.stop(this)
            updateStatus()
            return
        }

        if (prefs.toProviderConfig() == null) {
            binding.statusText.text = "Save a valid config first"
            return
        }

        // Need POST_NOTIFICATIONS on Android 13+ for the foreground service notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        tryStartService()
    }

    private fun tryStartService() {
        prefs.enabled = true
        RelayService.start(this)
        updateStatus()
    }

    private fun updateStatus() {
        binding.enabledSwitch.isChecked = prefs.enabled
        binding.startStopButton.text = if (prefs.enabled) "Stop" else "Start"
        binding.statusText.text = if (prefs.enabled) {
            "Service: enabled (running in background)"
        } else {
            "Service: stopped"
        }
    }
}

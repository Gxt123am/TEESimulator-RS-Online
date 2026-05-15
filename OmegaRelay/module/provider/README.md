# OmegaRelay Provider — KSU/Magisk Module

This module turns Device B (a clean phone with TEE/StrongBox available) into
a remote attestation provider that other devices on the same OmegaRelay
deployment can use.

## What it does

On boot, the module:

1. Spawns a JVM via `app_process` (no APK required).
2. Connects to your relay server over WebSocket.
3. Listens for `Attest` / `Sign` / `AttestExternalKey` tasks.
4. Performs the requested operation against `AndroidKeyStore` (real TEE).
5. Returns the cert chain / signature back through the relay.

Process name appears as `org.ommega.relay.provider` in `ps`.

## Layout

```
/data/adb/modules/omega_provider/
├── module.prop
├── service.sh                # boot hook (post-fs-data)
├── daemon.sh                 # JVM restart loop
├── customize.sh              # installer
├── sepolicy.rule
├── classes.dex               # compiled daemon (~3.6 MB)
├── provider.example.conf
└── webroot/
    ├── index.html            # KSU WebUI
    ├── style.css
    ├── ksu.js
    └── app.js

/data/adb/omega/              # config + state
├── provider.conf             # YOUR config (mode 600)
├── provider.pid
└── logs/
    └── provider.log
```

## Build

Requires:
- Java 17 (or JBR bundled with Android Studio)
- Android SDK with `build-tools/<latest>` (for d8)
- Cargo unnecessary on this side (only the server uses Rust)

From the repo root:

```powershell
cd OmegaRelay\module\provider
.\package.ps1
```

Output: `OmegaRelay\dist\omega-provider-v0.1.0.zip`

## Install

1. `adb push omega-provider-v0.1.0.zip /sdcard/Download/`
2. In KernelSU Manager (or Magisk Manager): **Modules → Install from storage** → pick the ZIP
3. Reboot
4. Open the module's WebUI (KSU Manager → Modules → OmegaRelay Provider → web icon)
5. Fill in:
   - Server URL (e.g. `wss://relay.example.com:8443/`)
   - Device ID (must match `[devices.device-b-1]` in the server config)
   - Pre-shared key (same as the server's `psk`)
6. Save & Restart

## Verify it's working

On your relay server:
```
RUST_LOG=info cargo run --bin omega-server -- server/config.toml
# ... watch for `session authenticated peer=... device_id=device-b-1 role=Provider`
```

On the phone:
```
adb shell su -c 'tail -f /data/adb/omega/logs/provider.log'
# ... should show:
#   starting JVM
#   websocket open: 101
#   Hello sent for device_id=device-b-1
#   authenticated, session_id=...
```

## Notes on TEE

`AndroidKeyStore` operations are real (not stubbed) on a device with a working
TEE/StrongBox. The Daemon will use `PURPOSE_ATTEST_KEY` (Android 12+) for the
`AttestExternalKey` flow, which is the preferred mode in production: Device A
generates the keypair locally and sends only the public key here for cert
issuance.

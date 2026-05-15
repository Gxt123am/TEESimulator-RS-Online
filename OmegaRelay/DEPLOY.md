# Deployment Guide

End-to-end checklist for getting the OmegaRelay provider running on Device B.

## Prerequisites

- **Server host**: a publicly reachable Linux box (or your home VPS / a port-forwarded LAN host). Rust 1.75+ to build, or use a pre-built binary.
- **Device B**: KernelSU or Magisk installed, BL locked, `Play Integrity` returns STRONG. Accessible via `adb` for the initial install.
- **Network**: server reachable from Device B over WSS (port 8443 by default).

## 1. Build the server

```powershell
# On your build host (Windows shown; Linux is similar with `cargo` from rustup).
cd OmegaRelay
$env:Path = "C:\msys64\mingw64\bin;$env:USERPROFILE\.cargo\bin;$env:Path"
$env:CARGO_TARGET_DIR = "C:\Temp\omega-target"
cargo +stable-x86_64-pc-windows-gnu build --release -p omega-server

# Binary lives at:
#   C:\Temp\omega-target\release\omega-server.exe (Windows)
#   target/release/omega-server (Linux)
```

## 2. Configure & start the server

Copy `server/config.example.toml` to `config.toml` and edit:

```toml
bind_addr = "0.0.0.0:8443"
psk = "<generate a long random string, e.g. openssl rand -hex 32>"

[tls]
enabled = true
cert_path = "/etc/letsencrypt/live/relay.example.com/fullchain.pem"
key_path = "/etc/letsencrypt/live/relay.example.com/privkey.pem"
auto_generate_self_signed = false   # set true for self-signed dev only

[devices.device-b-1]
role = "provider"

[devices.device-a-1]
role = "consumer"
paired_with = "device-b-1"
```

Run the server (suggest under systemd / supervisord for production):

```bash
RUST_LOG=info ./omega-server config.toml
```

Verify it logs `listening addr=0.0.0.0:8443 tls=true`.

## 3. Build the provider module

```powershell
# Set ANDROID_HOME if not already.
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

cd OmegaRelay\module\provider
.\package.ps1
# Output: ..\..\dist\omega-provider-v0.1.0.zip
```

## 4. Install on Device B

```powershell
adb push ..\..\dist\omega-provider-v0.1.0.zip /sdcard/Download/
```

In **KernelSU Manager** → **Modules** → **Install from storage** → pick the ZIP → reboot.

## 5. Configure on the device

After reboot, open the module's WebUI from KernelSU Manager:

1. **Server URL**: `wss://relay.example.com:8443/`
2. **Device ID**: `device-b-1`  (must match the server's config)
3. **Pre-shared key**: same as server's `psk`
4. **Skip TLS verification**: leave OFF unless you're using a self-signed cert
5. Tap **Save & Restart**

Within ~5 seconds the status pill should turn green ("running").

## 6. Verify end-to-end

On the server, watch logs:

```
2026-... INFO session authenticated peer=... device_id=device-b-1 role=Provider
```

On the device (in `adb shell` as root):

```
tail -f /data/adb/omega/logs/provider.log
# look for:
#   websocket open: 101
#   Hello sent for device_id=device-b-1
#   authenticated, session_id=...
```

Once Phase 2 (Device A consumer) is built, you'll be able to test a real
attestation round-trip with the `fake_consumer` Rust binary or the consumer
module.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `websocket failed: Failed to connect to /...` | server unreachable | verify firewall, port forward, DNS |
| `auth rejected: invalid auth token` | PSK mismatch | re-paste PSK (no trailing whitespace) |
| `auth rejected: device not registered` | `device_id` not in server's `[devices.*]` | add it to server config |
| `auth rejected: clock skew too large` | phone clock off | enable network time |
| `Could not load class org.ommega.relay.provider.App` | dex didn't ship | rebuild module ZIP from scratch |
| WebUI shows "stopped" but logs show repeated restarts | config invalid | check `provider.conf` has all required keys |

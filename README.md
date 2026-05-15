# TEESimulator-RS-Online

**Remote attestation relay for Android devices** — forward hardware key attestation from a BL-locked device (Provider) to a BL-unlocked device (Consumer) over WebSocket.

基于 [TEESimulator-RS](https://github.com/Enginex0/TEESimulator-RS) 的远程证书链转发方案。将 BL 锁定设备（Provider）的真实硬件 attestation 通过 WebSocket 中继到 BL 解锁设备（Consumer），实现 6 段 Google 硬件认证链。

---

## Architecture / 架构

```
┌─────────────────┐         WebSocket + PSK         ┌─────────────────┐
│   Device A      │◄──────────────────────────────►│   Device B      │
│   (Consumer)    │                                 │   (Provider)    │
│   BL Unlocked   │         ┌───────────┐          │   BL Locked     │
│   KSU + Module  │◄───────►│  VPS Relay │◄────────►│   Provider APK  │
│                 │         │  (Rust)    │          │   Real TEE      │
└─────────────────┘         └───────────┘          └─────────────────┘
```

**EN**: Device A intercepts `generateKey` calls via binder hook. When attestation is needed, it sends the public key + challenge to Device B through the relay server. Device B uses its real TEE to sign the attestation, producing a valid 6-cert chain rooted at Google Hardware Attestation Root. The chain is returned to Device A and injected into the keystore response.

**中文**: 设备 A 通过 binder hook 拦截 `generateKey` 调用。需要 attestation 时，将公钥 + challenge 经中继服务器发送给设备 B。设备 B 使用真实 TEE 签发 attestation，产生以 Google 硬件认证根证书为根的 6 段有效证书链，返回给设备 A 注入 keystore 响应。

---

## Components / 组件

| Directory | Description |
|---|---|
| `app/` | TEESimulator-RS with OmegaRelay Consumer integration (KSU/Magisk module) |
| `OmegaRelay/server/` | Rust WebSocket relay server |
| `OmegaRelay/android/` | Provider APK (runs on Device B) |
| `OmegaRelay/protocol/` | Shared protocol library (Rust, msgpack) |
| `OmegaRelay/vps/` | One-click VPS deployment script |
| `OmegaRelay/module/provider/` | KSU module packaging for Provider |

---

## Quick Start / 快速开始

### 1. Deploy Relay Server / 部署中继服务器

```bash
# On your VPS (Debian/Ubuntu/RHEL):
sudo OMEGA_PSK="$(openssl rand -hex 32)" \
     OMEGA_DOMAIN=relay.example.com \
     bash OmegaRelay/vps/install.sh
```

Or without TLS (put behind nginx):
```bash
sudo OMEGA_PSK="your-secret-key" OMEGA_NO_TLS=1 bash OmegaRelay/vps/install.sh
```

### 2. Setup Provider (Device B) / 配置 Provider（设备 B）

Download `OmegaRelay-Provider-v1.0.0.apk` from [Releases](https://github.com/Andrea-lyz/TEESimulator-RS-Online/releases) and install on a **BL-locked** device.

从 [Releases](https://github.com/Andrea-lyz/TEESimulator-RS-Online/releases) 下载 `OmegaRelay-Provider-v1.0.0.apk`，安装到 **BL 锁定**的设备上。

Open the app and configure:
- URL: `ws://your-vps-ip:8443/` or `wss://relay.example.com:8443/`
- PSK: (same as server)
- Device ID: `device-b-1`

The app supports both `ws://` (cleartext) and `wss://` (TLS) to any address — no IP whitelist needed.

应用支持 `ws://`（明文）和 `wss://`（TLS）连接任意地址，无需 IP 白名单。

### 3. Setup Consumer (Device A) / 配置 Consumer（设备 A）

Download `TEESimulator-RS-v6.0.0-165-Release.zip` from [Releases](https://github.com/Andrea-lyz/TEESimulator-RS-Online/releases).

从 [Releases](https://github.com/Andrea-lyz/TEESimulator-RS-Online/releases) 下载 `TEESimulator-RS-v6.0.0-165-Release.zip`。

Flash via KSU/Magisk/APatch on Device A (BL unlocked):
```bash
# KSU example:
adb push TEESimulator-RS-v6.0.0-165-Release.zip /data/local/tmp/
adb shell "su -c 'ksud module install /data/local/tmp/TEESimulator-RS-v6.0.0-165-Release.zip'"
adb reboot
```

After reboot, edit the relay config:
```bash
adb shell "su -c 'vi /data/adb/modules/tricky_store/omega-relay.conf'"
```

```ini
url = ws://your-vps-ip:8443/
psk = your-secret-key
device_id = device-a-1
```

Reboot again for the config to take effect:
```bash
adb reboot
```

重启后编辑中继配置文件，填入你的 VPS 地址、PSK 和设备 ID。**修改配置后需要再次重启**才能生效。

### 4. Verify / 验证

Use [Key Attestation](https://github.com/nicholaschum/KeyAttestation) app on Device A:
- Should show "Google Hardware Attestation Root Certificate" / 应显示"Google 硬件认证根证书"
- Chain length: 6 / 链长度：6
- End-to-end latency: 200-500ms / 端到端延迟：200-500ms

Check relay logs on Device A:
```bash
adb logcat -s TEESimulator:V | grep -i relay
```

---

## Debugging & Troubleshooting / 调试与排障

### View Consumer logs / 查看 Consumer 日志

```bash
# All TEESimulator logs (verbose)
adb logcat -s TEESimulator:V

# Only relay-related logs
adb logcat -s TEESimulator:V | grep -iE "relay|RELAY|task|chain"

# Clear logcat and start fresh
adb logcat -c && adb logcat -s TEESimulator:V
```

### View Provider logs / 查看 Provider 日志

```bash
# Provider APK logs
adb logcat -s OmegaRelay:V

# Or filter by package
adb logcat --pid=$(adb shell pidof org.ommega.relay.provider.app)
```

### View VPS server logs / 查看 VPS 服务器日志

```bash
# Live logs
journalctl -u omega-relay -f

# Last 100 lines
journalctl -u omega-relay -n 100 --no-pager

# Restart server
sudo systemctl restart omega-relay
```

### Force reconnect relay (Consumer) / 强制重连中继（Consumer 端）

The relay client lives inside the `keystore2` process. To force reconnect:

中继客户端运行在 `keystore2` 进程内。强制重连方法：

```bash
# Method 1: Kill keystore2 (system will auto-restart it, module re-injects)
adb shell "su -c 'killall keystore2'"

# Method 2: Full reboot (most reliable)
adb reboot
```

### Force reconnect relay (Provider) / 强制重连中继（Provider 端）

```bash
# Restart the Provider app service
adb shell "su -c 'am force-stop org.ommega.relay.provider.app'"
# Then reopen the app, or it will auto-restart via BootReceiver on next boot
```

### Check relay connection status / 检查中继连接状态

```bash
# Consumer side: look for "authenticated" or "connected"
adb logcat -d -s TEESimulator:V | grep -iE "authenticated|connected|disconnect"

# Provider side:
adb logcat -d -s OmegaRelay:V | grep -iE "authenticated|connected|disconnect"

# VPS side: check active connections
journalctl -u omega-relay --since "5 min ago" | grep -i "hello\|disconnect"
```

### Test attestation manually / 手动测试 attestation

```bash
# Trigger a key attestation and watch the relay in action
adb logcat -c
# Open Key Attestation app on Device A, tap "Attest"
adb logcat -s TEESimulator:V | grep -iE "relay|task|chain|OK"
```

Expected output / 期望输出:
```
RelayEngine: task <uuid> OK in <N>ms, chain len=6
RELAY: installed remote chain (len=6) over placeholder for <alias>
```

### Common issues / 常见问题

| Symptom / 现象 | Cause / 原因 | Fix / 解决 |
|---|---|---|
| `RelayEngine: connect failed` | Wrong URL/PSK or server down | Check config, `curl ws://ip:8443/` from PC |
| `RelayEngine: task timeout` | Provider offline or sleeping | Wake Device B, check Provider app is running |
| `RELAY: no provider available` | Provider not connected to server | Check Provider logs, restart Provider app |
| `chain len=0` | Provider TEE refused to sign | Check Device B is BL-locked, keybox present |
| No relay logs at all | Config not loaded | Verify `omega-relay.conf` exists and reboot |

| 无任何 relay 日志 | 配置未加载 | 确认 `omega-relay.conf` 存在并重启 |
| 连接失败 | URL/PSK 错误或服务器宕机 | 检查配置，从电脑 `curl ws://ip:8443/` 测试 |
| 任务超时 | Provider 离线或休眠 | 唤醒设备 B，确认 Provider 应用在运行 |
| 无 Provider 可用 | Provider 未连接服务器 | 检查 Provider 日志，重启 Provider 应用 |
| chain len=0 | Provider TEE 拒绝签名 | 确认设备 B BL 锁定，keybox 存在 |

---

## Building / 构建

### Prerequisites / 前置条件

- Android SDK (API 36)
- NDK 27.3+
- Rust toolchain + `cargo-ndk`
- JDK 21

### Build Consumer Module / 构建 Consumer 模块

```powershell
# Windows (paths with non-ASCII need these env vars):
$env:CARGO_TARGET_DIR = "C:\Temp\teesim-rust-target"
.\gradlew.bat :app:zipRelease
# Output: out/TEESimulator-RS-*.zip
```

### Build Relay Server / 构建中继服务器

```bash
cd OmegaRelay
cargo build --release -p omega-server
# Binary: target/release/omega-server
```

### Build Provider APK / 构建 Provider APK

```bash
cd OmegaRelay/android
./gradlew :daemon-provider-app:assembleRelease
```

---

## Protocol / 协议

See [OmegaRelay/docs/protocol.md](OmegaRelay/docs/protocol.md) for the full protocol specification.

协议详情见 [OmegaRelay/docs/protocol.md](OmegaRelay/docs/protocol.md)。

---

## Security Notes / 安全说明

- All communication is authenticated via PSK (pre-shared key)
- TLS is strongly recommended for production deployments
- The relay server never sees private keys — it only forwards opaque attestation tasks
- Provider device must remain BL-locked for valid attestation

- 所有通信通过 PSK（预共享密钥）认证
- 生产环境强烈建议启用 TLS
- 中继服务器不接触私钥——仅转发不透明的 attestation 任务
- Provider 设备必须保持 BL 锁定才能产生有效 attestation

---

## Limitations / 限制

- **Cannot pass Play Integrity**: Google's backend cross-references device identity through side-channels (GMS login, device registration) that detect the mismatch between Device A's real identity and Device B's attestation. This is a structural limitation.
- **Can pass**: vvb2060 KeyAttestation, Duck Detector (Tamper score ≤ 8), apps that only verify the X.509 chain locally.

- **无法通过 Play Integrity**：Google 后端通过侧信道（GMS 登录、设备注册）交叉验证设备身份，能检测到设备 A 真实身份与设备 B attestation 的不匹配。这是架构性限制。
- **可以通过**：vvb2060 KeyAttestation、Duck Detector（Tamper score ≤ 8）、仅本地验证 X.509 链的应用。

---

## Duck Detector Fix / Duck Detector 修复

This fork includes a fix for the "TEE Simulator generate-mode fingerprint" detection:

Changed `SecurityLevel.SOFTWARE` → `SecurityLevel.KEYSTORE` in `createSwAuth()` to match real hardware behavior. See [OMEGA_INTEGRATION.md](OMEGA_INTEGRATION.md) for details.

本 fork 包含对 "TEE Simulator generate-mode fingerprint" 检测的修复：将 `createSwAuth()` 中的 `SecurityLevel.SOFTWARE` 改为 `SecurityLevel.KEYSTORE` 以匹配真实硬件行为。

---

## Credits / 致谢

- [TEESimulator-RS](https://github.com/Enginex0/TEESimulator-RS) by JingMatrix & Enginex0
- [LSPlt](https://github.com/LSPosed/LSPlt) for native hooking
- Protocol inspired by [Ommega](https://github.com/nicholaschum/ommega)

---

## License / 许可证

GPLv3. See [LICENSE](LICENSE).

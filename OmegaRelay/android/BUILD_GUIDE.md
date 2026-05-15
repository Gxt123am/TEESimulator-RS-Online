# 用 Android Studio 构建

## 打开

**File → Open** → 选择 `OmegaRelay/android` 目录（不是子目录）→ 等 Sync 完成。

首次 Sync 会下载 Gradle 8.10.2 + Android Gradle Plugin 8.7.2 + 依赖，约 5-10 分钟。

## 模块说明

| 模块 | 类型 | 用途 |
|------|------|------|
| `:protocol` | Kotlin JVM | 协议定义 + MessagePack 编解码 + HMAC（与 Rust 端字节兼容） |
| `:daemon-core` | Kotlin JVM | RelayClient + AttestationEngine 接口 + Stub/Keystore 实现（用反射访问 Android API，所以 JVM 也能编译） |
| `:daemon-provider` | Kotlin JVM CLI | 桌面命令行测试器，依赖 daemon-core |
| `:daemon-provider-app` | Android APK | **真正部署到 Device B 的 APK** |

## 构建/运行

### 跑协议测试
```
.\gradlew.bat :protocol:test
```

### 桌面 CLI 调试
```
.\gradlew.bat :daemon-provider:installShadowDist
# 输出在 daemon-provider/build/install/daemon-provider-shadow/
.\gradlew.bat :daemon-provider:run --args="--stub provider.conf"
```

### 构建 + 安装 APK 到 Device B（最重要）
1. 在 Android Studio 顶部的 Run 配置选 `daemon-provider-app`
2. 用 USB 连接 Device B（必须开启开发者选项 + USB 调试）
3. 点 ▶ Run（或按 Shift+F10）
4. APK 会自动 build → 装到手机 → 启动 MainActivity

或者命令行：
```
.\gradlew.bat :daemon-provider-app:installDebug
adb shell am start -n org.ommega.relay.provider.debug/org.ommega.relay.provider.app.MainActivity
```

## 测试本地联调（PC 当 server，手机当 provider）

1. **PC 启动 server**（跟之前一样）：
   ```
   cd ../  # 回到 OmegaRelay 根目录
   $env:Path = "C:\msys64\mingw64\bin;$env:USERPROFILE\.cargo\bin;$env:Path"
   $env:CARGO_TARGET_DIR = "C:\Temp\omega-target"
   $env:RUST_LOG = "info"
   cargo +stable-x86_64-pc-windows-gnu run --bin omega-server -- server/config.toml
   ```

2. **建反向隧道**（使手机 127.0.0.1 能到 PC 的 server）：
   ```
   adb reverse tcp:18443 tcp:18443
   ```

3. **APK 安装后**，在手机 UI 里填：
   - URL: `ws://127.0.0.1:18443/`
   - Device ID: `device-b-1`
   - PSK: `smoke-test-psk-1234567890abcdef-must-be-strong`
   - Skip TLS：不勾（反正是 ws 不是 wss）
   - Save → Start

4. 看通知栏前台服务通知应该变成 "running — device_id=device-b-1"

5. **PC server 日志**：应该看到 `session authenticated peer=... device_id=device-b-1 role=Provider`

6. **手机 logcat**：
   ```
   adb logcat -s OmegaProvider:* OmegaRelay/Service:*
   ```

7. 触发一个真 attest（PC 上）：
   ```
   cargo run --bin fake_consumer -- ws://127.0.0.1:18443 "smoke-test-psk-1234567890abcdef-must-be-strong"
   ```

   预期：fake_consumer 输出 `chain_len=4` 或 5 这样的真 TEE 证书链长度，标志成功。

## 排错

| 现象 | 原因 | 修复 |
|------|------|------|
| Sync 失败：找不到 AGP | 网络问题 | 重试 / 换镜像（settings.gradle.kts 里加 mirror） |
| 编译报 `KeyGenParameterSpec` 反射失败 | 这是预期的；只在 Android 设备运行时才能 work | 桌面运行用 `--stub` 强制 stub engine |
| APK 启动后日志立刻消失 | OPPO 后台杀手 | 在系统设置里把 OmegaRelay Provider 加到"自启动"和"省电策略"白名单 |
| 通知栏服务通知不显示 | Android 13+ 没给 POST_NOTIFICATIONS 权限 | UI 第一次点 Start 会弹权限申请，允许 |

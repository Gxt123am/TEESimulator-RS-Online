# OmegaRelay Integration Notes

This is a fork of [TEESimulator-RS](https://github.com/Enginex0/TEESimulator-RS)
that adds a **RELAY** engine: instead of synthesising a certificate chain
locally, attestation requests can be forwarded to a remote OmegaRelay Provider
device that has a working TEE/StrongBox, and the chain it returns is injected
into the keystore reply.

The local PATCH and GENERATE engines from upstream remain functional. RELAY
is an additional opt-in mode.

## Current integration state

| Phase | Item | Status |
|-------|------|--------|
| 3.A | `module/module.prop`: rename to `OmegaRelay Consumer`, keep `id=tricky_store` | ✅ |
| 3.B | `ConfigurationManager.Mode.RELAY`, `@`-suffix parser, `shouldRelay`/`getResolvedModeForUid` | ✅ |
| 3.C | Kotlin protocol port (`Auth`/`Codec`/`Messages` re-packaged under `relay.protocol`) | ✅ |
| 3.D | `RelayClient` + `RelayConfigLoader` + `RelayEngine` + `App.main` lifecycle | ✅ |
| 3.D.2 | `Keystore2Interceptor.onPostTransact` RELAY branch with PATCH fallback | ✅ |
| 3.D.3 | Gradle deps (`msgpack-core`, `okhttp`), `KeyMintAttestation.attestationApplicationId` | ✅ |
| 3.D.4 | Packaging (LSPlt submodule, cargo-ndk, CRLF→LF, `omega-relay.example.conf`) | ✅ |
| 3.D.5 | RELAY swap inside `doSoftwareKeyGen` so the BL-unlocked path actually replaces chains | ✅ |
| 3.E | Real-device validation on Device A (BL-unlocked + KSU/Magisk) | ✅ |
| 4 | AUTO mode: prefer RELAY when configured & connected | 🟡 not started |

## Config layout

All config stays under `/data/adb/tricky_store/`:

```
/data/adb/tricky_store/
├── keybox.xml              # upstream (used by PATCH/GENERATE)
├── target.txt              # upstream + new '@' suffix
├── omega-relay.conf        # new (relay client config)
└── omega-relay.example.conf  # template dropped on first install
```

### `target.txt` — per-app routing

```text
# unchanged: original modes
io.github.vvb2060.keyattestation!   # force PATCH
com.example.bar                     # AUTO (resolves to PATCH/GENERATE today)

# new: force RELAY
com.google.android.gms@
com.android.vending@
```

The file is hot-reloaded via `FileObserver` — edit, save, no reboot.

### `omega-relay.conf` — relay client config

```text
url = wss://relay.example.com:8443/
psk = REPLACE_WITH_SHARED_SECRET_AT_LEAST_16_CHARS
device_id = device-a-1
tls_insecure = false
```

Read once at process start (Phase 5 will add hot-reload). Missing or invalid →
`RelayEngine.initialize()` returns `false` and any `@`-flagged package falls
back to PATCH at `getKeyEntry` time.

## Code touchpoints

New package `org.matrix.TEESimulator.relay`:

- `relay/RelayClient.kt` — OkHttp WebSocket, blocking `submit(task, timeoutMs)`,
  in-flight task map, auto-reconnect, ping-pong, optional `tls_insecure`
- `relay/RelayConfigLoader.kt` — reads `omega-relay.conf`
- `relay/RelayEngine.kt` — façade: `initialize()` and
  `relayCertChain(originalChain, challenge, appId)`
- `relay/protocol/{Auth,Codec,Messages}.kt` — copy of `daemon-core`'s protocol,
  re-packaged so the consumer doesn't pull in the whole daemon module

Modified upstream files:

- `config/ConfigurationManager.kt` — `Mode.RELAY`, parser, helpers
- `interception/keystore/Keystore2Interceptor.kt` — RELAY branch in
  `getKeyEntry` post-transact (used when the TEE successfully signs a chain
  that we want to swap; less common on BL-unlocked devices)
- `interception/keystore/shim/KeyMintSecurityLevelInterceptor.kt` — two
  RELAY-aware changes:
    - `forceGenerate` now triggers on `shouldRelay(uid)` so RELAY mode
      always uses the software keygen path
    - `doSoftwareKeyGen` swaps the locally-signed chain for the
      `RelayEngine.relayCertChain()` result inline before short-circuiting
      the response. This is the path that actually fires on Device A
      (BL-unlocked, TEE rejects `generateKey`).
- `attestation/KeyMintAttestation.kt` — added `attestationApplicationId`
  (`null` default, parsed from `Tag.ATTESTATION_APPLICATION_ID`)
- `App.kt` — `RelayEngine.initialize()` after `NativeCertGen.initialize`
- `gradle/libs.versions.toml` + `app/build.gradle.kts` — `msgpack-core` +
  `okhttp`, plus a CRLF→LF post-step in the `prepareModuleFiles` Sync task
- `proguard-rules.pro` — keep `org.msgpack.core.**` /
  `org.msgpack.value.**` / `org.matrix.TEESimulator.relay.**`
- `module/customize.sh` — drops `omega-relay.example.conf` on first install
- `module/target.txt` + `module/module.prop` — updated defaults

## Known non-targets

- **"Use Attest Key" toggle** in vvb2060 KeyAttestation: this asks for a
  `PURPOSE_ATTEST_KEY` user key, which the upstream interceptor handles in
  its own ATTEST_KEY branch. Our RELAY swap intentionally skips
  `isAttestKeyRequest` because the chain-replacement semantics don't apply
  (the returned key *is* the attest key itself). That toggle therefore
  falls back to PATCH and the AOSP keybox root will be flagged "untrusted"
  by the test app. This is expected.
- BL-unlocked StrongBox: works because StrongBox SecurityLevel goes through
  the same `doSoftwareKeyGen` path as TEE on this device, so the swap fires.

## Build

```powershell
# One-time: install Rust toolchain + Android target + cargo-ndk
$env:Path = "$env:USERPROFILE\.cargo\bin;C:\msys64\mingw64\bin;$env:Path"
rustup target add --toolchain stable-x86_64-pc-windows-gnu aarch64-linux-android
cargo +stable-x86_64-pc-windows-gnu install cargo-ndk --locked

# One-time: pull the LSPlt submodule
git submodule update --init --recursive

# Per build: keep Rust off the unicode-named workspace path on Windows
$env:RUSTUP_TOOLCHAIN  = "stable-x86_64-pc-windows-gnu"
$env:CARGO_TARGET_DIR  = "C:\Temp\teesim-rust-target"

.\gradlew.bat :app:zipRelease
# → out/TEESimulator-RS-vX.Y.Z-NNN-Release.zip
```

Tested with AGP 8.13.2, Kotlin 2.3.0, NDK 27.3.13750724, JDK 21.

## Step E: real-device smoke test (passed 2026-05-15)

Quick run-book for validating end-to-end on Device A. The numbers below are
from the actual run on a OnePlus, BL-unlocked, KSU越狱, vvb2060
KeyAttestation 2.0.2.

```bash
# 1. Push and install the consumer module on Device A
adb push out/TEESimulator-RS-*.zip /data/local/tmp/
adb shell su -c "ksud module install /data/local/tmp/TEESimulator-RS-*.zip"
adb reboot

# 2. After boot, write the relay config (Device A side)
adb shell su -c "cat > /data/adb/tricky_store/omega-relay.conf <<'EOF'
url = ws://178.22.26.156:8443/        # or wss://your.domain/ in production
psk = <PSK from server's [auth] section, ≥16 chars>
device_id = device-a-1                # must match a [devices.*] entry on the server
tls_insecure = false
EOF"

# 3. Mark the test app for RELAY in target.txt (hot-reloaded)
adb shell "su -c 'sed -i \"s/^io.github.vvb2060.keyattestation.*$/io.github.vvb2060.keyattestation@/\" /data/adb/tricky_store/target.txt'"

# 4. Watch the engines
adb logcat -s TEESimulator:V

# 5. On Device A, open KeyAttestation, leave all menu checkboxes off,
#    tap the regenerate button. Expected log lines:
#      I RelayClient: authenticated, session_id=<uuid>
#      I Generating software key for KeyAttestation[...]
#      I NativeCertGen: generated key pair successfully (3 certs)
#      I RelayEngine: task <uuid> OK in 4xxms, chain len=6
#      I RELAY: replaced software chain (len=3) with remote chain (len=6) for KeyAttestation
#
#    Expected in the KeyAttestation UI:
#      "Google 硬件认证根证书" / "Google Hardware Attestation Root"
#      6-cert chain
```

If RELAY fails (server unreachable, Provider asleep, timeout, etc.) the
engine logs `RELAY: failed for ..., keeping local software chain` and the
app sees the AOSP keybox software chain as a graceful fallback. The request
is never dropped.

End-to-end latency observed (public WSS, Frankfurt VPS, hot connection):
166 / 298 / 329 / 364 / 423 / 446 ms across consecutive runs.

## Working with upstream

RELAY additions are isolated under `org.matrix.TEESimulator.relay.*` plus
one extra `Mode` enum value and one new field on `KeyMintAttestation`. To
pull upstream changes:

```bash
git remote add upstream https://github.com/Enginex0/TEESimulator-RS.git
git fetch upstream
git merge upstream/main
```

Conflicts are typically limited to `ConfigurationManager.kt`,
`Keystore2Interceptor.kt`, `App.kt`, `module.prop`, `customize.sh`,
`target.txt`, `KeyMintAttestation.kt`, and the Gradle dep file.

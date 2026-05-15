# OmegaRelay v2 — Build Status

Last updated: 2026-05-15

## ✅ Phase 0: Protocol & Server (COMPLETE)

- 9/9 Rust protocol tests + 10/10 Kotlin codec tests
- Wire format byte-for-byte compatible across languages
- HMAC-PSK auth, role-based session management
- TLS via rustls (ring backend), self-signed cert generation supported

## ✅ Phase 1: Device B Provider Daemon (COMPLETE)

### Done

- Kotlin protocol port + msgpack-core codec
- daemon-core: WebSocket client, reconnect, heartbeat
- Two attestation engines (Stub + KeystoreEngine)
- Android APK with foreground service
- MainActivity UI for setup
- BootReceiver for auto-start
- Network security config (cleartext exception list)
- **AttestExternalKey path implemented and validated on real hardware**

### Hardware validation (OnePlus 11, Snapdragon 8 Gen 2, ColorOS 16)

```
=== AttestExternalKey verified chain (6 certs) ===
cert0 (forged leaf, contains Device A pubkey)
  ↓ signed by signer's TEE-resident private key
cert1 (signer leaf, TEE-issued via setAttestKeyAlias)
  ↓ signed by attest_key (PURPOSE_ATTEST_KEY)
cert2 (attest_key leaf, TEE-issued)
  ↓ signed by Qualcomm intermediate
cert3 (intermediate, title=TEE)
  ↓ signed by Qualcomm batch
cert4 (Qualcomm batch root)
  ↓ signed by Google
cert5 (Google Hardware Attestation Root, self-signed)
```

## ✅ Phase 2: VPS Deployment (COMPLETE — production validated)

- `vps/install.sh` — one-shot installer (rustup → build → certbot → systemd)
- `vps/nginx-snippet.conf` — optional reverse-proxy template
- **Deployed to live VPS** (Debian 12, Frankfurt) and verified end-to-end

End-to-end AttestExternalKey latency over public internet:
- min: 362 ms / avg: 561 ms / max: 663 ms

### Remaining VPS hardening

- [ ] Replace WS with WSS (need a domain name + Let's Encrypt)
- [ ] Remove the public-IP cleartext exception from `network_security_config.xml`
- [ ] Move from password SSH to key-only

## ✅ Phase 3: Device A Consumer (COMPLETE — real-device validated)

Implemented as a **fork of TEESimulator-RS** at `../TEESimulator-RS-Online/`.
Strategy: keep all upstream code intact (Sim/PATCH engines), add a new
**RELAY** mode that forwards attestation requests to our Provider over the
relay server.

### Done

- ✅ **Step A: Module identity** — `module.prop` renamed to "OmegaRelay
  Consumer", `id=tricky_store` kept for config-dir compatibility, upstream
  `updateJson` URL cleared so we don't pull upstream releases.
- ✅ **Step B: RELAY enum** — `ConfigurationManager.Mode.RELAY` added,
  `target.txt` parser handles `@` suffix, `shouldRelay(uid)` /
  `getResolvedModeForUid(uid)` exposed.
- ✅ **Step C: Protocol port** — `Auth.kt`, `Codec.kt`, `Messages.kt` copied
  from `daemon-core` and re-packaged under
  `org.matrix.TEESimulator.relay.protocol`.
- ✅ **Step D: Client + Engine**
    - `RelayClient.kt` — consumer-flavoured (request/response, blocking
      `submit(task, timeoutMs)` API, in-flight task map, OkHttp WebSocket,
      auto-reconnect, ping-pong, optional TLS-insecure for dev)
    - `RelayConfigLoader.kt` — reads `/data/adb/tricky_store/omega-relay.conf`
      (`url`, `psk`, `device_id`, `tls_insecure`)
    - `RelayEngine.kt` — high-level façade (`initialize` / `relayCertChain`)
      that pulls the leaf pubkey from the original chain, submits an
      `AttestExternalKey` task, and parses the returned DER chain back into
      `X509Certificate[]`
- ✅ **Step D.1: Lifecycle** — `App.main` calls `RelayEngine.initialize()`
  after `NativeCertGen.initialize`. Missing config → engine stays disabled
  and RELAY mode no-ops.
- ✅ **Step D.2: Interceptor wiring** — `Keystore2Interceptor.onPostTransact`
  for `getKeyEntry`:
    - early gate now lets RELAY mode pass through, not just PATCH
    - new branch: if `shouldRelay(callingUid)`, attempt `RelayEngine.relayCertChain(...)`
      *before* falling back to local PATCH; on failure the chain is patched
      locally as a safe fallback

### TODO (remaining for Phase 3)

- ✅ **Step D.3: Compilation fix**
    - `app/build.gradle.kts` now declares `org.msgpack:msgpack-core:0.9.8`
      and `com.squareup.okhttp3:okhttp:4.12.0` (the latter was also missing
      for `RelayClient`); both registered in `gradle/libs.versions.toml`.
    - Added `attestationApplicationId` to `KeyMintAttestation` (parsed from
      `Tag.ATTESTATION_APPLICATION_ID`) so the interceptor's RELAY branch
      can pass it through. Defaulted to `null` to keep the legacy
      `KeystoreInterceptor.toKeyMintAttestation()` and
      `KeyMintSecurityLevelInterceptor` positional constructions working.
    - `:app:compileReleaseKotlin` and `:app:minifyReleaseWithR8` both pass.
- ✅ **Step D.4: Packaging**
    - LSPlt git submodule pulled in (`app/src/main/cpp/external/LSPlt`).
    - `cargo-ndk` + `aarch64-linux-android` target installed under the
      `stable-x86_64-pc-windows-gnu` toolchain.
    - `:app:zipRelease` produces a working flashable module ZIP
      (~2.9 MB, all four ABIs).
    - `module/omega-relay.example.conf` template added; `module/customize.sh`
      drops it into `/data/adb/tricky_store/` on first install (never
      overwriting an existing `omega-relay.conf`).
    - `module/target.txt` defaults updated: testers pinned to PATCH (`!`),
      `gms`/`vending` left on AUTO with comment pointing at the `@` suffix.
    - `OMEGA_INTEGRATION.md` rewritten as a current build + smoke-test
      run-book.
    - CRLF→LF stripper added to the Gradle Sync (Windows checkouts) plus a
      `scripts/fix-line-endings.ps1` helper. Without this, KSU/Magisk
      busybox `sh` choked on `customize.sh\r` and aborted the install.
- ✅ **Step D.5: RELAY actually wired into the keygen short-circuit**
    - First install on Device A revealed that `Keystore2Interceptor`'s
      `getKeyEntry`-post RELAY branch never fired: with BL unlocked, the TEE
      rejected `generateKey` (KeyMint -49 `SECURE_HW_COMMUNICATION_FAILED`),
      so upstream fell through to `KeyMintSecurityLevelInterceptor.doSoftwareKeyGen`
      which **synchronously short-circuits the response**. The app got the
      software-only chain back from `generateKey` itself and never issued a
      follow-up `getKeyEntry`.
    - Fix lives in two places:
        - `KeyMintSecurityLevelInterceptor.kt`: `forceGenerate` now also
          triggers when `shouldRelay(uid)` is true, so RELAY mode never tries
          to ride the TEE path.
        - In the same file's `doSoftwareKeyGen`: after the local software
          chain is signed, if the caller is in RELAY mode and a challenge is
          present (and it's not an `ATTEST_KEY` request), the locally-signed
          chain is replaced inline with the chain returned by
          `RelayEngine.relayCertChain()`. Failure keeps the software chain
          (graceful degrade to PATCH-equivalent).
    - R8/proguard: added explicit `-keep` for `org.msgpack.core.**` /
      `org.msgpack.value.**` / `org.matrix.TEESimulator.relay.**` after the
      first run hit `ClassNotFoundException: org.msgpack.core.buffer.MessageBufferU`
      (msgpack-core picks the unsafe-vs-safe buffer impl via `Class.forName`,
      which R8 can't see statically).
- ✅ **Step E: Real-device validation** — passed end-to-end on Device A
    - vvb2060 KeyAttestation 2.0.2, all checkboxes off, EC P-256.
    - logcat:
      ```
      I RelayClient: authenticated, session_id=54f86946-...
      I Generating software key for KeyAttestation[...]
      I NativeCertGen: generated key pair successfully (3 certs)
      I RelayEngine: task <uuid> OK in 423ms, chain len=6
      I RELAY: replaced software chain (len=3) with remote chain (len=6) for KeyAttestation
      ```
    - App UI shows "Google 硬件认证根证书" (Google Hardware Attestation Root),
      6-cert chain accepted.
    - Subsequent generations: 446 / 166 / 298 / 329 / 364 ms — comparable
      to the Phase 2 PC baseline (~561 ms cold over public internet).
    - Confirmed working with StrongBox toggle on (the StrongBox SecurityLevel
      goes through the same `doSoftwareKeyGen` path, so the swap fires there
      too).
    - "Use Attest Key" toggle is a **deliberate non-target**: that path makes
      the app first generate a `PURPOSE_ATTEST_KEY` and chain user keys off
      it; the RELAY swap correctly skips `isAttestKeyRequest` and falls back
      to PATCH. Documented as expected behaviour.

## 🟡 Phase 4: Auto-mode policy upgrade (NOT STARTED)

Make `Mode.AUTO` prefer RELAY over PATCH/GENERATE when the relay client is
connected and the keybox is suspect (cheap heuristic: AOSP root cert =
software-only). Until then, users must explicitly opt-in with `@`.

## 🟡 Phase 5: Hardening / nice-to-haves (NOT STARTED)

- Cert-chain caching per (uid, alias)
- Provider load balancing (multiple Device B's behind one server)
- WSS for production VPS
- Hot-reload of `omega-relay.conf` via `FileObserver` (mirroring
  `target.txt` behaviour)

## Build commands

### Rust (server + tests)

```powershell
$env:Path = "C:\msys64\mingw64\bin;$env:USERPROFILE\.cargo\bin;$env:Path"
$env:CARGO_TARGET_DIR = "C:\Temp\omega-target"
cargo +stable-x86_64-pc-windows-gnu test -p omega-protocol
cargo +stable-x86_64-pc-windows-gnu build -p omega-server
```

### OmegaRelay Provider APK (Device B)

```powershell
cd OmegaRelay/android
.\gradlew.bat :daemon-provider-app:assembleDebug
```

Open the project in Android Studio for one-click install.

### OmegaRelay Consumer module (Device A) — fork of TEES-RS

```powershell
cd TEESimulator-RS-Online
# One-time setup
git submodule update --init --recursive
$env:Path = "$env:USERPROFILE\.cargo\bin;C:\msys64\mingw64\bin;$env:Path"
rustup target add --toolchain stable-x86_64-pc-windows-gnu aarch64-linux-android
cargo +stable-x86_64-pc-windows-gnu install cargo-ndk --locked

# Per-build env (keep cargo off the unicode-named workspace path)
$env:RUSTUP_TOOLCHAIN = "stable-x86_64-pc-windows-gnu"
$env:CARGO_TARGET_DIR = "C:\Temp\teesim-rust-target"

.\gradlew.bat :app:zipRelease   # → out/TEESimulator-RS-*.zip
```

## End-to-end smoke test (live VPS)

```powershell
& "C:\Temp\omega-target\debug\fake_external.exe" `
    "ws://178.22.26.156:8443" `
    "<your-PSK-from-.deploy_psk>"
```

Expect: 6-cert chain with `✅ root cert5 is self-signed (Google attest root)`.

## Key files

```
server/                           Rust relay server
protocol/                         Rust protocol crate
android/protocol                  Kotlin protocol port
android/daemon-core               Daemon shared logic (WebSocket + Engines + LeafBuilder)
android/daemon-provider           Desktop CLI test harness (JVM)
android/daemon-provider-app       Android APK (Provider, on Device B)  ✅
vps/install.sh                    One-shot VPS installer               ✅

../TEESimulator-RS-Online/        Fork of TEES-RS (Consumer side)
  app/src/main/java/.../relay/    OmegaRelay extension package
    RelayClient.kt
    RelayConfigLoader.kt
    RelayEngine.kt
    protocol/                     Auth/Codec/Messages (re-packaged)
  app/.../config/ConfigurationManager.kt   ← Mode.RELAY added
  app/.../interception/keystore/Keystore2Interceptor.kt  ← RELAY branch
  module/module.prop                       ← renamed
  OMEGA_INTEGRATION.md                     ← integration notes
```

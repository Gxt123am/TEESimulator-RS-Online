# OmegaRelay Protocol v2 RFC — Live KeyMint Session Forwarding

> Status: **DRAFT** — pending implementation and field testing.
> Authors: Andrea-lyz et al.
> Target: Pass Google Play Integrity STRONG_INTEGRITY on a BL-unlocked Consumer
> by forwarding the **entire keystore session** to a BL-locked Provider whose
> TEE legitimately reports `verifiedBootState=Verified` (achieved via KSU
> jailbreak mode + Magica + properly hidden environment).

> 中文版本：[protocol-v2-rfc.zh.md](./protocol-v2-rfc.zh.md)

---

## 1. Background

### 1.1 Why v1 cannot pass Play Integrity

OmegaRelay v1 forwards a **single attestation task** at a time:

```
Consumer asks Provider to "sign a cert chain for THIS public key with THIS challenge."
Provider signs once. Done.
```

This works for any verifier that only inspects the **X.509 chain** in isolation
(vvb2060 KeyAttestation, Duck Detector, simple banking apps with local checks).

It **fails** for Play Integrity because GMS DroidGuard does not just inspect the
chain. After `generateKey`, GMS keeps using the same alias to perform additional
operations:

```
1. generateKey(alias=integrity.api.key.alias, challenge=N, app_id=GMS) → returns chain
2. getKeyEntry(alias) → returns chain again, possibly with metadata
3. begin/update/finish over Sign(data) → returns signature
4. (sometimes) deleteKey(alias) → cleanup
```

If the private key never lived on Consumer A (it lives in Provider B's TEE),
step 3 fails on A — there is nothing to sign with.

### 1.2 The opportunity

Provider B (BL locked, KSU jailbreak mode) can:

- Generate keys in its real TEE
- Have those keys attested with `verifiedBootState=Verified` by a real RKP key
- Pass Play Integrity STRONG_INTEGRITY **on B itself**

What we need: a way for **Consumer A's GMS** to use B's TEE **as if** the keys
existed on A. Every keystore operation A performs must be transparently routed
to B.

---

## 2. Goals & Non-Goals

### Goals

- G1. GMS DroidGuard on Consumer A passes STRONG_INTEGRITY.
- G2. Operations transparent to GMS (no API change).
- G3. Provider can serve multiple Consumers (still 1:1 in v2.0; multi-tenant later).
- G4. Provider stays online with KSU jailbreak mode (no permanent BL unlock).
- G5. Backwards compatible with v1 (`AttestExternalKey` task type still works).

### Non-Goals

- Hide root from DroidGuard (assumed solved by KSU jailbreak + Zygisk Next + susfs).
- Bypass app-level checks beyond DroidGuard (banking apps with their own challenges).
- Avoid the "Provider must be online" requirement (no offline mode).

---

## 3. Architecture Overview

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Consumer A (BL UNLOCKED)                                                   │
│                                                                             │
│   GMS Unstable                                                              │
│       │  generateKey / sign / getKeyEntry / deleteKey                       │
│       ▼                                                                     │
│   Hooked keystore2 (libTEESimulator)                                        │
│       │                                                                     │
│       │  classify caller_uid:                                               │
│       │     - if uid is GMS or in relay-target list: forward                │
│       │     - else: passthrough to local TEE (or v1 forge)                  │
│       ▼                                                                     │
│   RelayClient ── KeyMintSession ──► WSS ──► Server ──► Provider            │
└────────────────────────────────────────────────────────────────────────────┘

                                                       ┌─────────────────────┐
                                                       │  Provider B         │
                                                       │  (BL LOCKED + KSU   │
                                                       │   jailbreak mode)   │
                                                       │                     │
                                                       │   RelayProvider     │
                                                       │       │             │
                                                       │       ▼             │
                                                       │   keystore2 native  │
                                                       │       │             │
                                                       │       ▼             │
                                                       │   real KeyMint TEE  │
                                                       │   (verifiedBoot=    │
                                                       │   Verified, real    │
                                                       │   RKP key)          │
                                                       └─────────────────────┘
```

### Key invariant

**The private key for any forwarded alias never leaves Provider B's TEE.**
Every operation that requires the private key (sign, agree-key) is forwarded
back to B. Only the **public key, attestation chain, and signed outputs**
travel over the wire.

---

## 4. Wire Protocol Changes

### 4.1 Compatibility

Protocol version bumps to `2`. Consumer announces `protocol_version: 2` and
`capabilities: ["attest", "attest_external_key", "kmsession"]` in `Hello`.

A v1 Consumer talking to a v2 server still works (no `KeySession*` messages).

### 4.2 New top-level Payload variants

```rust
enum Payload {
    // ... existing v1 variants ...

    // v2 additions:
    OpenKmSession(OpenKmSession),
    KmSessionOpened(KmSessionOpened),
    KmRequest(KmRequest),
    KmResponse(KmResponse),
    CloseKmSession(CloseKmSession),
    KmSessionEvent(KmSessionEvent),  // unsolicited from provider (e.g. key invalidated)
}
```

### 4.3 Session lifecycle

```
Consumer → Server: OpenKmSession {
    consumer_alias: String,           // alias as seen by GMS on Consumer
    forwarded_uid: u32,               // caller uid on Consumer (GMS = 10000+)
    caller_app_id: Vec<u8>,           // GMS package signature digest
    pin_provider_device_id: Option<String>, // if Consumer wants a specific Provider
}

Server → Provider: OpenKmSession (forwarded with session_id assigned by server)
Provider → Server → Consumer: KmSessionOpened {
    session_id: Uuid,                 // unique per (consumer_alias, opened_at)
    provider_alias: String,           // internal alias on Provider B (different from consumer_alias)
    capabilities: KmCaps,             // what KeyMint version, supported algos, has_strongbox, etc.
}
```

After `KmSessionOpened`, the Consumer maps `consumer_alias` → `session_id`.
All subsequent operations on `consumer_alias` are tagged with `session_id` and
forwarded.

```
Consumer → ... → Provider: KmRequest { session_id, op: KmOp }
Provider → ... → Consumer: KmResponse { session_id, result: KmResult }
```

### 4.4 KmOp / KmResult

`KmOp` is a tagged enum mirroring the AIDL `IKeystoreSecurityLevel` /
`IKeystoreOperation` interfaces. Core ops:

```rust
enum KmOp {
    GenerateKey {
        params: Vec<KmParameter>,        // tags: PURPOSE, ALGORITHM,
                                         // ATTESTATION_CHALLENGE,
                                         // ATTESTATION_APPLICATION_ID, etc.
        attestation_key_alias: Option<String>,
    },
    GetKeyEntry,                          // returns chain + metadata
    UpdateSubcomponent { public_cert: Option<Vec<u8>>, cert_chain: Option<Vec<u8>> },
    Begin {
        purpose: KeyPurpose,
        params: Vec<KmParameter>,        // operation params (e.g. PADDING, DIGEST)
    },
    Update {
        op_handle: u64,
        input: Vec<u8>,
        aad: Option<Vec<u8>>,
    },
    Finish {
        op_handle: u64,
        input: Option<Vec<u8>>,
        signature: Option<Vec<u8>>,
        aad: Option<Vec<u8>>,
    },
    Abort {
        op_handle: u64,
    },
    DeleteKey,
}

enum KmResult {
    GenerateKey {
        cert_chain: Vec<Vec<u8>>,
        public_key_der: Vec<u8>,
        key_metadata: KmKeyMetadata,
    },
    GetKeyEntry {
        cert_chain: Vec<Vec<u8>>,
        public_key_der: Vec<u8>,
        key_metadata: KmKeyMetadata,
    },
    UpdateSubcomponent,                   // ack
    Begin {
        op_handle: u64,
        params: Vec<KmParameter>,        // sometimes the HAL adds back nonce / iv
    },
    Update {
        output: Vec<u8>,
    },
    Finish {
        output: Vec<u8>,                  // signature for sign ops, ciphertext for crypt
    },
    Abort,                                // ack
    DeleteKey,                            // ack
    Error {
        error_code: i32,                  // KeyMint ErrorCode (negative)
        message: Option<String>,
    },
}
```

### 4.5 Operation handles

`Begin` returns a Provider-side `op_handle`. The Consumer **must** store the
mapping `(session_id, local_op_id) → (session_id, provider_op_handle)` and
translate handles on every Update/Finish/Abort.

Local handles never leak out of the Consumer keystore boundary; this prevents
a malicious Provider from probing op handles outside its sessions.

### 4.6 Server responsibilities

- Maintain `session_id → (consumer_conn, provider_conn)` routing table.
- Enforce per-consumer rate limit on `OpenKmSession` (default: 16 concurrent).
- Drop the entire session and notify both ends if either side disconnects.
- Forward `KmSessionEvent` (e.g. provider's TEE invalidated all keys after
  reboot, provider went offline temporarily) to consumer so Consumer can
  surface a clean error.

### 4.7 Heartbeat & timeouts

- Each `KmRequest` carries `timeout_ms` (default 8000ms).
- Server times out individual requests; session stays open.
- If no `KmRequest` for 90s, server emits `KmSessionEvent::Idle` and either
  side may close.
- After Provider reboot or KSU jailbreak loss, all sessions are invalidated.
  Consumer gets `KmSessionEvent::ProviderRekeyRequired` on next op and must
  reissue `OpenKmSession`.

---

## 5. Consumer-Side Implementation Sketch

### 5.1 Hook points (existing in v1)

```kotlin
Keystore2Interceptor.onPreTransact / onPostTransact
  ├─ getKeyEntry      → already hooked, redirect via session
  ├─ deleteKey        → already hooked, redirect via session
  ├─ updateSubcomponent → new
  └─ listEntries      → already hooked, inject relay-managed aliases

KeyMintSecurityLevelInterceptor
  ├─ generateKey      → already hooked, route to KmSession
  ├─ importKey        → new (less common, but GMS may use it)
  ├─ deleteKey        → new
  └─ createOperation  → new (entry to begin/update/finish)

KeystoreOperationInterceptor (NEW)
  ├─ updateAad        → forward
  ├─ update           → forward
  ├─ finish           → forward
  └─ abort            → forward
```

### 5.2 Caller filter

The relay must not eat operations from non-target callers. v2 introduces a
**relay scope** in `omega-relay.conf`:

```ini
relay_scope = gms-only           # or "all", "uid-list:10000,10001"
```

For `gms-only`: only forward when caller_uid resolves to one of:
- `com.google.android.gms` (especially `:unstable`, `:droidguard`)
- `com.google.android.gsf`

Other callers go through v1 forge path or local TEE passthrough.

### 5.3 Session manager state

```kotlin
class KmSessionManager {
    // alias-on-consumer → live session
    val sessions = ConcurrentHashMap<String, KmSession>()

    data class KmSession(
        val sessionId: UUID,
        val consumerAlias: String,
        val providerAlias: String,
        val openedAt: Long,
        val callerUid: Int,
        val opHandles: ConcurrentHashMap<Long, Long>, // local → provider
    )

    fun openOrReuse(consumerAlias: String, callerUid: Int, appId: ByteArray): KmSession
    fun close(consumerAlias: String)
    fun forwardOp(sessionId: UUID, op: KmOp): KmResult
}
```

Sessions persist across activity lifecycle but reset on:
- App that owns the alias is uninstalled (caught by `uid_remove` broadcast)
- Provider reboot signal received from server
- Network outage > 30s (Consumer triggers `OpenKmSession` retry on next op)

### 5.4 Latency budget

GMS DroidGuard observed timeouts (approximate, from community testing):

| Operation | GMS soft timeout |
|---|---|
| generateKey | 3s |
| Sign (per finish) | 1s |
| getKeyEntry | 1s |

WSS RTT to a regional VPS is 50-150ms typical. Provider TEE op time 50-200ms.
End-to-end target: under 600ms per op. Achievable; v1 already runs at 200-450ms
for full attest cycle.

---

## 6. Provider-Side Implementation Sketch

### 6.1 Required Provider state

Provider must run in **KSU jailbreak mode** (or any equivalent setup that
yields root + verifiedBootState=Verified TEE). Provider verifies its own
status at startup:

```kotlin
fun selfCheck(): SelfCheckResult {
    val keypair = keyStore.generateKeyPair("__omega_selfcheck__", attest=true)
    val chain = keyStore.getCertificateChain("__omega_selfcheck__")
    val leaf = X509Certificate.parse(chain[0])
    val ext = leaf.getExtension(KeyDescription.OID)
    return SelfCheckResult(
        verifiedBootState = ext.teeEnforced.rootOfTrust.verifiedBootState,
        rkpAvailable = chain.last().issuer.matches(GoogleRkpRoot),
        // ...
    )
}
```

If `verifiedBootState != Verified`, Provider refuses to serve sessions and
emits a clear error in its UI. (Prevents footguns where user reboots and
loses jailbreak without noticing.)

### 6.2 Real keystore call

Provider uses the standard Android Keystore APIs (with KSU root to call
hidden APIs if needed):

```kotlin
class RealKeyMintEngine : AttestationEngine {
    override fun handleGenerateKey(req: KmRequest.GenerateKey): KmResult {
        val params = KeyGenParameterSpec.Builder(
            providerAlias(req.sessionId),
            KeyProperties.PURPOSE_SIGN,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setAttestationChallenge(req.attestationChallenge)
            // CRITICAL: forward the GMS attestation_application_id verbatim
            //           so the leaf cert binds to GMS, not to Provider's app
            .also { applyAaid(it, req.attestationApplicationId) }
            .build()

        val kpg = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
        kpg.initialize(params)
        val kp = kpg.generateKeyPair()

        val chain = keyStore.getCertificateChain(providerAlias(req.sessionId))
        return KmResult.GenerateKey(
            certChain = chain.map { it.encoded },
            publicKeyDer = kp.public.encoded,
            keyMetadata = readMetadata(kp),
        )
    }
}
```

The ATTESTATION_APPLICATION_ID forwarding is essential. The leaf cert must
bind to the Consumer's GMS package, not the Provider app, otherwise Google's
backend mismatches.

### 6.3 Key cleanup

Provider keeps keys per session. On `CloseKmSession` or session timeout,
Provider deletes the corresponding `providerAlias`. After Provider reboot
(KSU jailbreak loss → root loss → Provider app dies), the keys remain in
Provider keystore but become unreachable; cleanup runs on next jailbreak
restart.

---

## 7. Security Considerations

### 7.1 PSK auth still required
v2 does not weaken transport-level auth. Same PSK + HMAC-Hello as v1.

### 7.2 Provider can refuse sessions
Provider operator may set `allow_consumers = ["device-a-1"]` in its config to
restrict which Consumers can open sessions, even if they have the PSK.

### 7.3 Server cannot forge attestation
Server only routes opaque KmRequest/KmResponse blobs. It cannot sign anything;
Provider's TEE is the sole signer.

### 7.4 Replay protection
Each session_id + msg_id is unique. Server rejects duplicate msg_ids within
a session.

### 7.5 What about a malicious Consumer?
A Consumer with valid PSK can ask Provider to attest arbitrary public keys.
This is by design — Provider's PSK holder is trusted to use B's TEE.

---

## 8. Open questions

| # | Question | Answer plan |
|---|---|---|
| Q1 | Does Play Integrity check `verifiedBootKey` matches device model? | Test on first prototype. If yes, may need PIF-style brand spoof to claim Provider's brand. |
| Q2 | Does GMS DroidGuard tolerate 200-500ms per Sign? | Test. If not, consider running Provider on LAN. |
| Q3 | Does importKey (instead of generateKey) appear in GMS flow? | Instrument; if yes, support it in v2.1. |
| Q4 | Can multiple GMS aliases share a session? | v2.0: one session per alias. v2.1: investigate session pooling. |
| Q5 | What happens if Provider's KSU jailbreak survives soft-reboot but loses on cold reboot? | Server must distinguish "Provider temporarily offline" from "Provider needs re-jailbreak"; expose to Consumer. |

---

## 9. Phased Rollout

### Phase 1 — Protocol scaffolding (1-2 weeks)
- Wire-level encoding for `KmOp`/`KmResult` in Rust (server) and Kotlin (Consumer + Provider)
- Server routing table for sessions
- v1 + v2 both supported in parallel

### Phase 2 — Consumer hook expansion (1-2 weeks)
- Hook `IKeystoreOperation` (Begin/Update/Finish/Abort)
- Caller-uid filter (gms-only by default)
- Op handle translation

### Phase 3 — Provider real engine (1 week)
- `RealKeyMintEngine` using real Android Keystore APIs
- Self-check at startup
- Session cleanup

### Phase 4 — Field test (open-ended)
- Try Play Integrity on Consumer A with Provider B in KSU jailbreak mode
- Iterate on Q1-Q5

---

## 10. Backwards compatibility

v2 server speaks both v1 and v2. v1 Consumer (the existing `omega-relay.conf`
based module) keeps working unchanged. v2 capabilities are negotiated through
`Hello.protocol_version` and `Hello.capabilities`.

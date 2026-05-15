# OmegaRelay 协议 v2 RFC —— KeyMint 实时会话转发

> 状态：**草案** —— 待实施和真机验证。
> 作者：Andrea-lyz 等
> 目标：让 BL 解锁的 Consumer 通过 Google Play Integrity STRONG_INTEGRITY，方法
> 是把**整个 keystore 会话**转发到一台 BL 锁定的 Provider，该 Provider 的 TEE
> 真实地报告 `verifiedBootState=Verified`（通过 KSU 越狱模式 + Magica + 干净的
> 环境隐藏实现）。

> English version: [protocol-v2-rfc.md](./protocol-v2-rfc.md)

---

## 1. 背景

### 1.1 为什么 v1 过不了 Play Integrity

OmegaRelay v1 一次只转发**一个 attestation 任务**：

```
Consumer 让 Provider "用这个公钥 + 这个 challenge 签一条证书链"
Provider 签一次。结束。
```

这种模式对**只检查 X.509 链**的验证器有效（vvb2060 KeyAttestation、Duck
Detector、做本地链校验的简单银行 APP）。

但对 Play Integrity **失效**，因为 GMS DroidGuard 不只是检查链。`generateKey`
之后，GMS 会持续用同一个 alias 做更多操作：

```
1. generateKey(alias=integrity.api.key.alias, challenge=N, app_id=GMS) → 拿到链
2. getKeyEntry(alias) → 再次返回链 + 元数据
3. begin/update/finish 做 Sign(data) → 拿到签名
4. （有时）deleteKey(alias) → 清理
```

如果私钥从未存在于 Consumer A 上（私钥在 Provider B 的 TEE 里），第 3 步在 A
上就失败了——A 没有任何东西可以用来签名。

### 1.2 机会所在

Provider B（BL 锁定，KSU 越狱模式）能够：

- 在真实 TEE 内部生成密钥
- 用真实 RKP 密钥签 attestation，extension 写入 `verifiedBootState=Verified`
- **B 自己**通过 Play Integrity STRONG_INTEGRITY

我们要做的是：让 **Consumer A 的 GMS** 把 B 的 TEE **当作**自己本地的 TEE 用。
A 上每一次 keystore 操作都透明地路由到 B。

---

## 2. 目标与非目标

### 目标

- G1. Consumer A 上的 GMS DroidGuard 通过 STRONG_INTEGRITY。
- G2. 对 GMS 透明（不改 API）。
- G3. Provider 可服务多个 Consumer（v2.0 仍 1:1，多租户后续）。
- G4. Provider 通过 KSU 越狱模式保持在线（无需永久解锁 BL）。
- G5. 向后兼容 v1（`AttestExternalKey` 任务类型继续可用）。

### 非目标

- 对 DroidGuard 隐藏 root（假设 KSU 越狱 + Zygisk Next + susfs 已经搞定）。
- 绕过 DroidGuard 之外的应用层检查（自带 challenge 的银行 APP）。
- 解决"Provider 必须在线"的需求（不做离线模式）。

---

## 3. 架构总览

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Consumer A (BL 已解锁)                                                     │
│                                                                             │
│   GMS Unstable                                                              │
│       │  generateKey / sign / getKeyEntry / deleteKey                       │
│       ▼                                                                     │
│   被 hook 的 keystore2 (libTEESimulator)                                    │
│       │                                                                     │
│       │  按 caller_uid 分类：                                                │
│       │     - 如果 uid 是 GMS 或在中继目标列表里：转发                       │
│       │     - 否则：透传给本机 TEE（或 v1 forge）                            │
│       ▼                                                                     │
│   RelayClient ── KeyMintSession ──► WSS ──► 服务器 ──► Provider            │
└────────────────────────────────────────────────────────────────────────────┘

                                                       ┌─────────────────────┐
                                                       │  Provider B         │
                                                       │  (BL 锁定 + KSU     │
                                                       │   越狱模式)         │
                                                       │                     │
                                                       │   RelayProvider     │
                                                       │       │             │
                                                       │       ▼             │
                                                       │   keystore2 原生    │
                                                       │       │             │
                                                       │       ▼             │
                                                       │   真 KeyMint TEE    │
                                                       │   (verifiedBoot=    │
                                                       │   Verified, 真     │
                                                       │   RKP key)          │
                                                       └─────────────────────┘
```

### 核心不变量

**任何被转发 alias 的私钥永远不离开 Provider B 的 TEE。** 所有需要私钥的操作
（sign、agree-key）都转发回 B。线上传输的只有**公钥、attestation 链、签名输出**。

---

## 4. Wire 协议变更

### 4.1 兼容性

协议版本号升到 `2`。Consumer 在 `Hello` 中声明
`protocol_version: 2` 与 `capabilities: ["attest", "attest_external_key", "kmsession"]`。

v1 Consumer 与 v2 服务器仍能正常工作（不发送 `KmSession*` 消息即可）。

### 4.2 新增顶层 Payload 类型

```rust
enum Payload {
    // ... v1 原有类型 ...

    // v2 新增：
    OpenKmSession(OpenKmSession),
    KmSessionOpened(KmSessionOpened),
    KmRequest(KmRequest),
    KmResponse(KmResponse),
    CloseKmSession(CloseKmSession),
    KmSessionEvent(KmSessionEvent),  // Provider 主动发出（如密钥已失效）
}
```

### 4.3 会话生命周期

```
Consumer → Server: OpenKmSession {
    consumer_alias: String,           // GMS 在 Consumer 上看到的 alias
    forwarded_uid: u32,               // Consumer 上的 caller uid（GMS = 10000+）
    caller_app_id: Vec<u8>,           // GMS 包名签名摘要
    pin_provider_device_id: Option<String>, // Consumer 想指定特定 Provider
}

Server → Provider: OpenKmSession（带服务器分配的 session_id 转发过去）
Provider → Server → Consumer: KmSessionOpened {
    session_id: Uuid,                 // 每个 (consumer_alias, opened_at) 唯一
    provider_alias: String,           // Provider B 内部的 alias（与 consumer_alias 不同）
    capabilities: KmCaps,             // KeyMint 版本、支持的算法、是否有 strongbox 等
}
```

`KmSessionOpened` 之后，Consumer 维护 `consumer_alias → session_id` 映射。
之后所有针对 `consumer_alias` 的操作都打上 `session_id` 标签转发。

```
Consumer → ... → Provider: KmRequest { session_id, op: KmOp }
Provider → ... → Consumer: KmResponse { session_id, result: KmResult }
```

### 4.4 KmOp / KmResult

`KmOp` 是 tagged enum，对应 AIDL 的 `IKeystoreSecurityLevel` /
`IKeystoreOperation` 接口。核心操作：

```rust
enum KmOp {
    GenerateKey {
        params: Vec<KmParameter>,        // tag: PURPOSE, ALGORITHM,
                                         // ATTESTATION_CHALLENGE,
                                         // ATTESTATION_APPLICATION_ID 等
        attestation_key_alias: Option<String>,
    },
    GetKeyEntry,                          // 返回链 + 元数据
    UpdateSubcomponent { public_cert: Option<Vec<u8>>, cert_chain: Option<Vec<u8>> },
    Begin {
        purpose: KeyPurpose,
        params: Vec<KmParameter>,        // 操作参数（PADDING、DIGEST 等）
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
        params: Vec<KmParameter>,        // HAL 有时会回写 nonce/iv 等
    },
    Update {
        output: Vec<u8>,
    },
    Finish {
        output: Vec<u8>,                  // sign 操作的签名，加密的密文
    },
    Abort,                                // ack
    DeleteKey,                            // ack
    Error {
        error_code: i32,                  // KeyMint ErrorCode（负值）
        message: Option<String>,
    },
}
```

### 4.5 Operation handle 处理

`Begin` 返回的 `op_handle` 来自 Provider 端。Consumer **必须**保存映射
`(session_id, local_op_id) → (session_id, provider_op_handle)`，并在每次
Update/Finish/Abort 时翻译。

本地 op handle 永远不会泄漏到 Consumer keystore 边界之外，可以防止恶意 Provider
探测自己 session 之外的 op handle。

### 4.6 服务器职责

- 维护 `session_id → (consumer_conn, provider_conn)` 路由表
- 对 `OpenKmSession` 做按 Consumer 限频（默认每 Consumer 同时 16 个）
- 任一端断开时丢弃整个会话并通知另一端
- 把 `KmSessionEvent`（如 Provider 重启后 TEE 失效全部密钥、Provider 暂时
  离线）转发给 Consumer，便于 Consumer 给出干净的错误提示

### 4.7 心跳与超时

- 每个 `KmRequest` 携带 `timeout_ms`（默认 8000ms）
- 服务器对单个 request 计时；session 保持开放
- 90s 内没有 `KmRequest` 则服务器发出 `KmSessionEvent::Idle`，任一端可关闭
- Provider 重启或 KSU 越狱失效后，所有 session 失效。Consumer 在下一次操作时
  收到 `KmSessionEvent::ProviderRekeyRequired`，需重新 `OpenKmSession`

---

## 5. Consumer 端实现要点

### 5.1 Hook 点（v1 已有）

```kotlin
Keystore2Interceptor.onPreTransact / onPostTransact
  ├─ getKeyEntry      → 已 hook，按 session 重定向
  ├─ deleteKey        → 已 hook，按 session 重定向
  ├─ updateSubcomponent → 新增
  └─ listEntries      → 已 hook，注入中继管理的 alias

KeyMintSecurityLevelInterceptor
  ├─ generateKey      → 已 hook，路由到 KmSession
  ├─ importKey        → 新增（少见，但 GMS 可能用）
  ├─ deleteKey        → 新增
  └─ createOperation  → 新增（begin/update/finish 的入口）

KeystoreOperationInterceptor（新文件）
  ├─ updateAad        → 转发
  ├─ update           → 转发
  ├─ finish           → 转发
  └─ abort            → 转发
```

### 5.2 Caller 过滤

中继不能"吃掉"非目标进程的操作。v2 在 `omega-relay.conf` 中引入**中继作用域**：

```ini
relay_scope = gms-only           # 或 "all" / "uid-list:10000,10001"
```

`gms-only` 模式：仅当 caller_uid 解析为下面之一时转发：
- `com.google.android.gms`（特别是 `:unstable`、`:droidguard`）
- `com.google.android.gsf`

其他 caller 走 v1 forge 路径或本机 TEE 透传。

### 5.3 Session 管理状态

```kotlin
class KmSessionManager {
    // Consumer 端 alias → 活跃会话
    val sessions = ConcurrentHashMap<String, KmSession>()

    data class KmSession(
        val sessionId: UUID,
        val consumerAlias: String,
        val providerAlias: String,
        val openedAt: Long,
        val callerUid: Int,
        val opHandles: ConcurrentHashMap<Long, Long>, // 本地 → Provider
    )

    fun openOrReuse(consumerAlias: String, callerUid: Int, appId: ByteArray): KmSession
    fun close(consumerAlias: String)
    fun forwardOp(sessionId: UUID, op: KmOp): KmResult
}
```

Session 跨 Activity 生命周期保持，但下列情况会重置：
- 持有 alias 的应用被卸载（监听 `uid_remove` 广播）
- 收到服务器发来的 Provider 重启信号
- 网络中断 > 30s（Consumer 在下一次操作时重试 `OpenKmSession`）

### 5.4 延迟预算

GMS DroidGuard 实测软超时（社区估计）：

| 操作 | GMS 软超时 |
|---|---|
| generateKey | 3s |
| Sign（每次 finish）| 1s |
| getKeyEntry | 1s |

到本地区域 VPS 的 WSS RTT 一般 50-150ms。Provider TEE 操作 50-200ms。
端到端目标：每次操作 600ms 以内。可达成；v1 完整 attest 周期已经做到 200-450ms。

---

## 6. Provider 端实现要点

### 6.1 Provider 必须的状态

Provider 必须运行在 **KSU 越狱模式**（或任何能让设备同时拿到 root 且 TEE 报告
`verifiedBootState=Verified` 的方案）。Provider 启动时自检：

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

如果 `verifiedBootState != Verified`，Provider 拒绝服务任何 session 并在 UI
上明确报错。（避免用户重启后越狱失效却没注意，导致后续 attestation 出错。）

### 6.2 调用真实 keystore

Provider 用标准 Android Keystore API（如有需要可借助 KSU root 调用隐藏 API）：

```kotlin
class RealKeyMintEngine : AttestationEngine {
    override fun handleGenerateKey(req: KmRequest.GenerateKey): KmResult {
        val params = KeyGenParameterSpec.Builder(
            providerAlias(req.sessionId),
            KeyProperties.PURPOSE_SIGN,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setAttestationChallenge(req.attestationChallenge)
            // 关键：原样转发 GMS 的 attestation_application_id，
            //       这样 leaf cert 绑定的是 GMS，不是 Provider 自己的 APP
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

ATTESTATION_APPLICATION_ID 的转发至关重要。leaf cert 必须绑定到 Consumer 的
GMS 包，不能是 Provider 应用自己，否则 Google 后端会判不一致。

### 6.3 密钥清理

Provider 按 session 维护密钥。`CloseKmSession` 或 session 超时时，Provider
删除对应的 `providerAlias`。Provider 重启后（KSU 越狱失效 → root 丢失 →
Provider 应用退出），密钥仍在 Provider keystore 里但变得不可达；下次越狱重启
时清理。

---

## 7. 安全考虑

### 7.1 PSK 认证保留
v2 不削弱传输层认证。仍是 v1 的 PSK + HMAC-Hello。

### 7.2 Provider 可拒绝 session
Provider 运营者可在 config 里设
`allow_consumers = ["device-a-1"]`，限制可以开 session 的 Consumer，即使对方
有 PSK。

### 7.3 服务器无法伪造 attestation
服务器只路由不透明的 KmRequest/KmResponse 数据，无法签任何东西；唯一的签名者
是 Provider 的 TEE。

### 7.4 重放保护
session_id + msg_id 唯一。服务器在同一 session 内拒绝重复 msg_id。

### 7.5 恶意 Consumer 怎么办？
持有有效 PSK 的 Consumer 可以让 Provider 给任意公钥签 attestation。这是设计
预期——持有 PSK 即被信任使用 B 的 TEE。

---

## 8. 待回答问题

| # | 问题 | 解答计划 |
|---|---|---|
| Q1 | Play Integrity 会检查 `verifiedBootKey` 是否匹配设备型号吗？| 第一版原型上实测。如果会，可能需要 PIF 风格的 brand 伪装来声称是 Provider 的品牌。|
| Q2 | GMS DroidGuard 能容忍每次 Sign 200-500ms 吗？| 实测。如果不行，考虑 Provider 放局域网。|
| Q3 | GMS 流程中是否会出现 `importKey`（而非 generateKey）？| 监测；如果有，v2.1 支持。|
| Q4 | 多个 GMS alias 可以共享一个 session 吗？| v2.0：每个 alias 一个 session。v2.1：研究 session 池化。|
| Q5 | Provider KSU 越狱在软重启后存活但冷启动后丢失，怎么处理？| 服务器需要区分"Provider 暂时离线"与"Provider 需要重新越狱"，并暴露给 Consumer。|

---

## 9. 分阶段实施

### 阶段 1 —— 协议脚手架（1-2 周）
- Rust（服务器）和 Kotlin（Consumer + Provider）三端的 wire 编码（`KmOp`/`KmResult`）
- 服务器 session 路由表
- v1 + v2 并行支持

### 阶段 2 —— Consumer hook 扩展（1-2 周）
- Hook `IKeystoreOperation`（Begin/Update/Finish/Abort）
- caller-uid 过滤（默认 gms-only）
- Op handle 翻译

### 阶段 3 —— Provider 真实引擎（1 周）
- 用标准 Android Keystore API 实现 `RealKeyMintEngine`
- 启动自检
- session 清理

### 阶段 4 —— 真机测试（开放结束）
- Consumer A 上跑 Play Integrity，Provider B 处于 KSU 越狱模式
- 围绕 Q1-Q5 迭代

---

## 10. 向后兼容

v2 服务器同时讲 v1 和 v2。现有 v1 Consumer（基于 `omega-relay.conf` 的模块）
不变即可继续工作。v2 能力通过 `Hello.protocol_version` 与 `Hello.capabilities`
协商。

# OmegaRelay Wire Protocol v1

## Transport

- **WebSocket over TLS 1.3** (`wss://`)
- **Binary frames only** (no text frames)
- **Encoding**: MessagePack

## Authentication

### Connection Handshake

When a client connects, it MUST send a `Hello` message as the first frame within 5 seconds. The server validates and responds with `HelloAck`.

```
Client → Server: Hello {
    role: "consumer" | "provider",
    device_id: String,
    auth_token: String,        // HMAC of (device_id + nonce + timestamp) using PSK
    nonce: [u8; 16],
    timestamp: u64,            // Unix seconds, must be within ±60s of server time
    protocol_version: u16,     // currently 1
    capabilities: Vec<String>, // e.g. ["attest", "sign", "attest_key_pool"]
}

Server → Client: HelloAck {
    success: bool,
    session_id: Uuid,
    server_time: u64,
    error: Option<String>,
}
```

If `success == false`, server closes the connection.

## Message Types

All messages are wrapped in an `Envelope`:

```rust
struct Envelope {
    msg_id: Uuid,           // unique per message, for correlation
    in_reply_to: Option<Uuid>, // set if this is a response
    timestamp: u64,
    payload: Payload,
}

enum Payload {
    Hello(Hello),
    HelloAck(HelloAck),
    Ping,
    Pong,
    SubmitTask(Task),       // consumer → server
    DispatchTask(Task),     // server → provider
    TaskResult(TaskResult), // provider → server, server → consumer
    Error(ErrorMsg),
}
```

## Tasks

### Task Types

```rust
struct Task {
    id: Uuid,
    task_type: TaskType,
    timeout_ms: u32,        // max wait time on consumer side
    metadata: HashMap<String, String>,
}

enum TaskType {
    /// Generate a new key with attestation
    Attest {
        challenge: Vec<u8>,            // raw bytes
        alias_hint: String,            // optional, used for telemetry
        algorithm: KeyAlgorithm,       // EC_P256 | RSA_2048
        purpose: KeyPurpose,           // SIGN | ATTEST_KEY
        attestation_application_id: Option<Vec<u8>>,
        device_context: Option<DeviceContext>,
    },
    
    /// Sign data using a key referenced by alias (rare, usually keys live on Device A)
    Sign {
        alias: String,
        data: Vec<u8>,
        algorithm: SignAlgorithm,
    },
    
    /// Attest an externally provided public key (Android 12+, PURPOSE_ATTEST_KEY)
    /// This is the preferred mode: key lives on Device A, cert chain comes from Device B
    AttestExternalKey {
        challenge: Vec<u8>,
        external_public_key_der: Vec<u8>,  // SubjectPublicKeyInfo DER
        attestation_application_id: Option<Vec<u8>>,
        device_context: Option<DeviceContext>,
    },
}

enum KeyAlgorithm {
    EcP256,
    EcP384,
    Rsa2048,
    Rsa3072,
    Rsa4096,
}

enum KeyPurpose {
    Sign,
    AttestKey,  // PURPOSE_ATTEST_KEY (API 31+)
}

enum SignAlgorithm {
    Sha256WithEcdsa,
    Sha256WithRsa,
}
```

### Device Context

Used by ReLeaf to forge attestation extension fields:

```rust
struct DeviceContext {
    brand: Option<String>,
    device: Option<String>,
    product: Option<String>,
    manufacturer: Option<String>,
    model: Option<String>,
    os_version: Option<u32>,
    os_patch_level: Option<u32>,
    vendor_patch_level: Option<u32>,
    boot_patch_level: Option<u32>,
    verified_boot_key: Option<Vec<u8>>,
    verified_boot_hash: Option<Vec<u8>>,
    verified_boot_state: Option<u8>,    // 0=Verified, 1=SelfSigned, 2=Unverified, 3=Failed
    device_locked: Option<bool>,
}
```

## Task Result

```rust
struct TaskResult {
    task_id: Uuid,
    success: bool,
    payload: Option<TaskResultPayload>,
    error: Option<String>,
    server_timing: Option<TimingInfo>,
}

enum TaskResultPayload {
    Attest {
        cert_chain: Vec<Vec<u8>>,  // DER-encoded X.509, leaf first
        public_key_der: Vec<u8>,
    },
    Sign {
        signature: Vec<u8>,
    },
    AttestExternalKey {
        cert_chain: Vec<Vec<u8>>,
    },
}

struct TimingInfo {
    received_at_server_ms: u64,
    dispatched_to_provider_ms: u64,
    received_from_provider_ms: u64,
}
```

## Error Messages

```rust
struct ErrorMsg {
    code: ErrorCode,
    message: String,
    related_msg_id: Option<Uuid>,
}

enum ErrorCode {
    AuthFailed,
    InvalidProtocolVersion,
    NoProviderAvailable,
    TaskTimeout,
    InvalidTask,
    ProviderError,
    InternalError,
    RateLimited,
}
```

## Heartbeat

- Client sends `Ping` every 25 seconds
- Server responds with `Pong`
- If no response in 60 seconds, client reconnects
- Server closes connections idle for > 90 seconds

## Versioning

Protocol version is currently `1`. Future versions will be negotiated via `Hello.protocol_version`. The server will reject connections with unsupported versions.

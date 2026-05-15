//! Wire message definitions.
//!
//! All messages are serialized as MessagePack maps. Polymorphic types use an
//! internally-tagged form: a `"type"` (or `"kind"`) discriminator field plus
//! a wrapper field carrying the variant's payload. Example:
//!
//! ```text
//! { "type": "submit_task", "task": { ... } }
//! { "type": "ping" }
//! ```
//!
//! This keeps the wire format predictable and easy to mirror in Kotlin.

use serde::{Deserialize, Serialize};
use serde_bytes::ByteBuf;
use std::collections::HashMap;
use uuid::Uuid;

// ----------------------------------------------------------------------------
// Envelope
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Envelope {
    pub msg_id: Uuid,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub in_reply_to: Option<Uuid>,
    pub timestamp: u64,
    pub payload: Payload,
}

impl Envelope {
    pub fn new(payload: Payload) -> Self {
        Self {
            msg_id: Uuid::new_v4(),
            in_reply_to: None,
            timestamp: now_secs(),
            payload,
        }
    }

    pub fn reply_to(in_reply_to: Uuid, payload: Payload) -> Self {
        Self {
            msg_id: Uuid::new_v4(),
            in_reply_to: Some(in_reply_to),
            timestamp: now_secs(),
            payload,
        }
    }
}

// ----------------------------------------------------------------------------
// Payload
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Payload {
    Hello { hello: Hello },
    HelloAck { ack: HelloAck },
    Ping,
    Pong,
    SubmitTask { task: Task },
    DispatchTask { task: Task },
    TaskResult { result: TaskResult },
    Error { error: ErrorMsg },
}

// ----------------------------------------------------------------------------
// Handshake
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Role {
    Consumer,
    Provider,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Hello {
    pub role: Role,
    pub device_id: String,
    pub auth_token: ByteBuf,
    pub nonce: ByteBuf,
    pub timestamp: u64,
    pub protocol_version: u16,
    #[serde(default)]
    pub capabilities: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HelloAck {
    pub success: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<Uuid>,
    pub server_time: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

// ----------------------------------------------------------------------------
// Tasks
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Task {
    pub id: Uuid,
    pub task_type: TaskType,
    pub timeout_ms: u32,
    #[serde(default)]
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum TaskType {
    Attest {
        challenge: ByteBuf,
        #[serde(default)]
        alias_hint: String,
        algorithm: KeyAlgorithm,
        purpose: KeyPurpose,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        attestation_application_id: Option<ByteBuf>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        device_context: Option<DeviceContext>,
    },
    Sign {
        alias: String,
        data: ByteBuf,
        algorithm: SignAlgorithm,
    },
    AttestExternalKey {
        challenge: ByteBuf,
        external_public_key_der: ByteBuf,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        attestation_application_id: Option<ByteBuf>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        device_context: Option<DeviceContext>,
    },
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum KeyAlgorithm {
    EcP256,
    EcP384,
    Rsa2048,
    Rsa3072,
    Rsa4096,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum KeyPurpose {
    Sign,
    AttestKey,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SignAlgorithm {
    Sha256WithEcdsa,
    Sha384WithEcdsa,
    Sha256WithRsa,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct DeviceContext {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub brand: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub product: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub manufacturer: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub os_version: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub os_patch_level: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub vendor_patch_level: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub boot_patch_level: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub verified_boot_key: Option<ByteBuf>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub verified_boot_hash: Option<ByteBuf>,
    /// 0 = Verified, 1 = SelfSigned, 2 = Unverified, 3 = Failed
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub verified_boot_state: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_locked: Option<bool>,
}

// ----------------------------------------------------------------------------
// Task result
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResult {
    pub task_id: Uuid,
    pub success: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub payload: Option<TaskResultPayload>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server_timing: Option<TimingInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum TaskResultPayload {
    Attest {
        cert_chain: Vec<ByteBuf>,
        public_key_der: ByteBuf,
    },
    Sign {
        signature: ByteBuf,
    },
    AttestExternalKey {
        cert_chain: Vec<ByteBuf>,
    },
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct TimingInfo {
    pub received_at_server_ms: u64,
    pub dispatched_to_provider_ms: u64,
    pub received_from_provider_ms: u64,
}

// ----------------------------------------------------------------------------
// Errors
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorMsg {
    pub code: ErrorCode,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub related_msg_id: Option<Uuid>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    AuthFailed,
    InvalidProtocolVersion,
    NoProviderAvailable,
    TaskTimeout,
    InvalidTask,
    ProviderError,
    InternalError,
    RateLimited,
}

// ----------------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------------

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

// ----------------------------------------------------------------------------
// Tests: pin the wire format so Kotlin can mirror it byte-for-byte.
// ----------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use rmp_serde::decode::from_slice as rmp_decode;
    use serde_bytes::ByteBuf;

    /// Decode the MessagePack into a generic value to inspect structure.
    /// We use rmpv-style traversal but rmpv isn't a workspace dep, so use
    /// a HashMap<String, rmp_serde::Value>... Actually simplest: re-encode
    /// as JSON-like via a custom probe.
    fn payload_type_tag(env_bytes: &[u8]) -> String {
        // Decode the envelope back into a map and look at the payload's "type".
        #[derive(Deserialize)]
        struct Probe {
            payload: ProbePayload,
        }
        #[derive(Deserialize)]
        struct ProbePayload {
            #[serde(rename = "type")]
            ty: String,
        }
        let probe: Probe = rmp_decode(env_bytes).unwrap();
        probe.payload.ty
    }

    #[test]
    fn ping_wire_format() {
        let env = Envelope {
            msg_id: Uuid::nil(),
            in_reply_to: None,
            timestamp: 0,
            payload: Payload::Ping,
        };
        let bytes = rmp_serde::to_vec_named(&env).unwrap();
        assert_eq!(payload_type_tag(&bytes), "ping");
    }

    #[test]
    fn pong_wire_format() {
        let env = Envelope::new(Payload::Pong);
        let bytes = rmp_serde::to_vec_named(&env).unwrap();
        assert_eq!(payload_type_tag(&bytes), "pong");
    }

    #[test]
    fn submit_task_wire_format() {
        let env = Envelope {
            msg_id: Uuid::nil(),
            in_reply_to: None,
            timestamp: 0,
            payload: Payload::SubmitTask {
                task: Task {
                    id: Uuid::nil(),
                    task_type: TaskType::Attest {
                        challenge: ByteBuf::from(vec![1, 2, 3]),
                        alias_hint: "x".into(),
                        algorithm: KeyAlgorithm::EcP256,
                        purpose: KeyPurpose::Sign,
                        attestation_application_id: None,
                        device_context: None,
                    },
                    timeout_ms: 1000,
                    metadata: Default::default(),
                },
            },
        };
        let bytes = rmp_serde::to_vec_named(&env).unwrap();
        assert_eq!(payload_type_tag(&bytes), "submit_task");

        // Roundtrip through the real type.
        let decoded: Envelope = rmp_serde::from_slice(&bytes).unwrap();
        match decoded.payload {
            Payload::SubmitTask { task } => match task.task_type {
                TaskType::Attest {
                    algorithm, purpose, ..
                } => {
                    assert_eq!(algorithm, KeyAlgorithm::EcP256);
                    assert_eq!(purpose, KeyPurpose::Sign);
                }
                _ => panic!("expected Attest"),
            },
            _ => panic!("expected SubmitTask"),
        }
    }

    #[test]
    fn roundtrip_envelope() {
        let original = Envelope::new(Payload::Ping);
        let bytes = rmp_serde::to_vec_named(&original).unwrap();
        let decoded: Envelope = rmp_serde::from_slice(&bytes).unwrap();
        assert!(matches!(decoded.payload, Payload::Ping));
        assert_eq!(decoded.msg_id, original.msg_id);
    }

    #[test]
    fn roundtrip_task_result() {
        let r = TaskResult {
            task_id: Uuid::new_v4(),
            success: true,
            payload: Some(TaskResultPayload::Attest {
                cert_chain: vec![ByteBuf::from(vec![1, 2]), ByteBuf::from(vec![3, 4])],
                public_key_der: ByteBuf::from(vec![5, 6, 7]),
            }),
            error: None,
            server_timing: None,
        };
        let env = Envelope::new(Payload::TaskResult { result: r.clone() });
        let bytes = rmp_serde::to_vec_named(&env).unwrap();
        let decoded: Envelope = rmp_serde::from_slice(&bytes).unwrap();
        match decoded.payload {
            Payload::TaskResult { result } => {
                assert_eq!(result.task_id, r.task_id);
                assert!(result.success);
            }
            _ => panic!("wrong variant"),
        }
    }

    #[test]
    fn dispatch_task_wire_format() {
        let env = Envelope::new(Payload::DispatchTask {
            task: Task {
                id: Uuid::new_v4(),
                task_type: TaskType::Sign {
                    alias: "k".into(),
                    data: ByteBuf::from(vec![0; 8]),
                    algorithm: SignAlgorithm::Sha256WithEcdsa,
                },
                timeout_ms: 500,
                metadata: Default::default(),
            },
        });
        let bytes = rmp_serde::to_vec_named(&env).unwrap();
        assert_eq!(payload_type_tag(&bytes), "dispatch_task");
    }

    #[test]
    fn task_result_wire_format() {
        let env = Envelope::new(Payload::TaskResult {
            result: TaskResult {
                task_id: Uuid::new_v4(),
                success: false,
                payload: None,
                error: Some("oops".into()),
                server_timing: None,
            },
        });
        let bytes = rmp_serde::to_vec_named(&env).unwrap();
        assert_eq!(payload_type_tag(&bytes), "task_result");
    }
}

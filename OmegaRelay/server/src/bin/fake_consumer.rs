//! Fake consumer client. Connects as `device-a-1`, submits one task, prints
//! the result. Used to validate end-to-end routing through a real provider.
//!
//! Usage:
//!     fake_consumer <ws-url> <psk>

use std::time::Duration;

use anyhow::Context;
use futures_util::{SinkExt, StreamExt};
use omega_protocol::{
    auth, decode, encode, Envelope, Hello, KeyAlgorithm, KeyPurpose, Payload, Role, Task,
    TaskType, PROTOCOL_VERSION,
};
use serde_bytes::ByteBuf;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;
use uuid::Uuid;

#[tokio::main(flavor = "current_thread")]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt::init();

    let url = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "ws://127.0.0.1:18443".into());
    let psk = std::env::args()
        .nth(2)
        .context("usage: fake_consumer <url> <psk>")?;

    let (ws_stream, _) = connect_async(&url).await?;
    let (mut writer, mut reader) = ws_stream.split();

    // Hello
    let device_id = "device-a-1".to_string();
    let nonce: [u8; 16] = {
        use std::time::{SystemTime, UNIX_EPOCH};
        let mut buf = [0u8; 16];
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0) as u128;
        buf.copy_from_slice(&now.to_le_bytes());
        buf
    };
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_secs();
    let token = auth::compute_auth_token(psk.as_bytes(), &device_id, &nonce, timestamp);

    let hello = Hello {
        role: Role::Consumer,
        device_id: device_id.clone(),
        auth_token: ByteBuf::from(token),
        nonce: ByteBuf::from(nonce.to_vec()),
        timestamp,
        protocol_version: PROTOCOL_VERSION,
        capabilities: vec!["attest".into()],
    };
    writer
        .send(Message::Binary(
            encode(&Envelope::new(Payload::Hello { hello }))?.into(),
        ))
        .await?;
    tracing::info!("hello sent");

    // HelloAck
    let ack = reader.next().await.unwrap()?;
    let ack_env: Envelope = match ack {
        Message::Binary(b) => decode(&b)?,
        other => anyhow::bail!("expected binary, got {:?}", other),
    };
    match ack_env.payload {
        Payload::HelloAck { ack } if ack.success => {
            tracing::info!(session_id = ?ack.session_id, "authenticated");
        }
        Payload::HelloAck { ack } => anyhow::bail!("auth failed: {:?}", ack.error),
        other => anyhow::bail!("unexpected reply: {:?}", other),
    }

    // SubmitTask
    let task_id = Uuid::new_v4();
    let task = Task {
        id: task_id,
        task_type: TaskType::Attest {
            challenge: ByteBuf::from(vec![0xCA, 0xFE, 0xBA, 0xBE, 0xDE, 0xAD, 0xBE, 0xEF]),
            alias_hint: "x-lang-test".into(),
            algorithm: KeyAlgorithm::EcP256,
            purpose: KeyPurpose::Sign,
            attestation_application_id: None,
            device_context: None,
        },
        timeout_ms: 5000,
        metadata: Default::default(),
    };
    writer
        .send(Message::Binary(
            encode(&Envelope::new(Payload::SubmitTask { task }))?.into(),
        ))
        .await?;
    tracing::info!(task_id = %task_id, "task submitted");

    // Wait for TaskResult
    let result_msg = tokio::time::timeout(Duration::from_secs(10), reader.next())
        .await
        .context("timed out waiting for result")?
        .unwrap()?;
    let result_env: Envelope = match result_msg {
        Message::Binary(b) => decode(&b)?,
        other => anyhow::bail!("expected binary, got {:?}", other),
    };
    match result_env.payload {
        Payload::TaskResult { result } => {
            tracing::info!(
                task_id = %result.task_id,
                success = result.success,
                error = ?result.error,
                "got result"
            );
            if let Some(payload) = result.payload {
                match payload {
                    omega_protocol::TaskResultPayload::Attest { cert_chain, public_key_der } => {
                        tracing::info!(
                            chain_len = cert_chain.len(),
                            cert0 = cert_chain.get(0).map(|c| c.len()).unwrap_or(0),
                            cert1 = cert_chain.get(1).map(|c| c.len()).unwrap_or(0),
                            pubkey = public_key_der.len(),
                            "Attest payload received"
                        );
                    }
                    other => tracing::warn!("unexpected payload kind: {:?}", other),
                }
            }
            if result.success {
                println!("✅ x-language end-to-end OK");
            } else {
                println!("❌ task failed: {:?}", result.error);
                std::process::exit(1);
            }
        }
        other => anyhow::bail!("expected TaskResult, got {:?}", other),
    }

    Ok(())
}

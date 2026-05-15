//! Standalone smoke test for the OmegaRelay server.
//!
//! Spawns two WebSocket clients (a fake consumer and a fake provider),
//! sends a dummy task end-to-end, and verifies the result is routed correctly.
//!
//! Run with:
//!   cargo run --bin smoke_test -- ws://127.0.0.1:8443 <PSK>

use std::time::Duration;

use anyhow::Context;
use futures_util::{SinkExt, StreamExt};
use omega_protocol::{
    auth, decode, encode, Envelope, Hello, KeyAlgorithm, KeyPurpose, Payload, Role, Task,
    TaskResult, TaskResultPayload, TaskType, PROTOCOL_VERSION,
};
use serde_bytes::ByteBuf;
use tokio::sync::mpsc;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;
use uuid::Uuid;

#[tokio::main(flavor = "current_thread")]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt::init();

    let url = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "ws://127.0.0.1:8443".into());
    let psk = std::env::args()
        .nth(2)
        .context("usage: smoke_test <url> <psk>")?;

    let mut provider = spawn_client(&url, &psk, Role::Provider, "device-b-1".into()).await?;
    let mut consumer = spawn_client(&url, &psk, Role::Consumer, "device-a-1".into()).await?;

    let task_id = Uuid::new_v4();
    let task = Task {
        id: task_id,
        task_type: TaskType::Attest {
            challenge: ByteBuf::from(vec![1, 2, 3, 4, 5, 6, 7, 8]),
            alias_hint: "smoke".into(),
            algorithm: KeyAlgorithm::EcP256,
            purpose: KeyPurpose::Sign,
            attestation_application_id: None,
            device_context: None,
        },
        timeout_ms: 5000,
        metadata: Default::default(),
    };

    consumer
        .send
        .send(Envelope::new(Payload::SubmitTask { task }))
        .await?;
    tracing::info!(task_id = %task_id, "task submitted");

    let dispatched = provider.recv_with_timeout(Duration::from_secs(2)).await?;
    let provider_task = match dispatched.payload {
        Payload::DispatchTask { task } => task,
        other => anyhow::bail!("provider expected DispatchTask, got {:?}", other),
    };
    assert_eq!(provider_task.id, task_id);
    tracing::info!("provider received DispatchTask");

    let fake_result = TaskResult {
        task_id,
        success: true,
        payload: Some(TaskResultPayload::Attest {
            cert_chain: vec![ByteBuf::from(vec![0xAA; 32]), ByteBuf::from(vec![0xBB; 32])],
            public_key_der: ByteBuf::from(vec![0xCC; 64]),
        }),
        error: None,
        server_timing: None,
    };
    provider
        .send
        .send(Envelope::new(Payload::TaskResult {
            result: fake_result,
        }))
        .await?;

    let final_env = consumer.recv_with_timeout(Duration::from_secs(2)).await?;
    match final_env.payload {
        Payload::TaskResult { result } => {
            assert_eq!(result.task_id, task_id);
            assert!(result.success, "task failed: {:?}", result.error);
            tracing::info!("✅ end-to-end task round-trip OK");
        }
        other => anyhow::bail!("consumer expected TaskResult, got {:?}", other),
    }

    Ok(())
}

struct FakeClient {
    send: mpsc::Sender<Envelope>,
    recv: mpsc::Receiver<Envelope>,
}

impl FakeClient {
    async fn recv_with_timeout(&mut self, dur: Duration) -> anyhow::Result<Envelope> {
        tokio::time::timeout(dur, self.recv.recv())
            .await?
            .ok_or_else(|| anyhow::anyhow!("client recv channel closed"))
    }
}

async fn spawn_client(
    url: &str,
    psk: &str,
    role: Role,
    device_id: String,
) -> anyhow::Result<FakeClient> {
    let (ws_stream, _) = connect_async(url).await?;
    let (mut writer, mut reader) = ws_stream.split();

    let nonce: [u8; 16] = rand_nonce();
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_secs();
    let token = auth::compute_auth_token(psk.as_bytes(), &device_id, &nonce, timestamp);

    let hello = Hello {
        role,
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

    let ack = reader
        .next()
        .await
        .context("connection closed before HelloAck")??;
    let ack_env: Envelope = match ack {
        Message::Binary(b) => decode(&b)?,
        other => anyhow::bail!("expected binary, got {:?}", other),
    };
    match ack_env.payload {
        Payload::HelloAck { ack } if ack.success => {
            tracing::info!(device_id, role = ?role, "authenticated");
        }
        Payload::HelloAck { ack } => anyhow::bail!("auth rejected: {:?}", ack.error),
        other => anyhow::bail!("expected HelloAck, got {:?}", other),
    }

    let (tx_out, mut rx_out) = mpsc::channel::<Envelope>(16);
    let (tx_in, rx_in) = mpsc::channel::<Envelope>(16);

    tokio::spawn(async move {
        while let Some(env) = rx_out.recv().await {
            if let Ok(bytes) = encode(&env) {
                if writer.send(Message::Binary(bytes.into())).await.is_err() {
                    break;
                }
            }
        }
    });

    tokio::spawn(async move {
        while let Some(Ok(msg)) = reader.next().await {
            if let Message::Binary(b) = msg {
                if let Ok(env) = decode::<Envelope>(&b) {
                    if tx_in.send(env).await.is_err() {
                        break;
                    }
                }
            }
        }
    });

    Ok(FakeClient {
        send: tx_out,
        recv: rx_in,
    })
}

fn rand_nonce() -> [u8; 16] {
    // Quick-and-dirty: time-based nonce. Smoke test only.
    use std::time::{SystemTime, UNIX_EPOCH};
    let mut buf = [0u8; 16];
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0) as u128;
    buf[..16].copy_from_slice(&now.to_le_bytes());
    buf
}

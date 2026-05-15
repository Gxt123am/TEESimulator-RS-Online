//! Submits a real attest task and dumps the resulting certificate chain
//! to disk + prints a hexdump of each cert's DER. Useful for verifying that
//! the chain is a real TEE-issued one (look for the Google Key Attestation
//! OID 1.3.6.1.4.1.11129.2.1.17 inside the leaf).

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

    let url = std::env::args().nth(1).unwrap_or_else(|| "ws://127.0.0.1:18443".into());
    let psk = std::env::args().nth(2).context("usage: dump_chain <url> <psk>")?;

    let (ws, _) = connect_async(&url).await?;
    let (mut wr, mut rd) = ws.split();

    // Hello
    let device_id = "device-a-1".to_string();
    let nonce: [u8; 16] = {
        let mut b = [0u8; 16];
        let n = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)?
            .as_nanos() as u128;
        b.copy_from_slice(&n.to_le_bytes());
        b
    };
    let ts = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_secs();
    let token = auth::compute_auth_token(psk.as_bytes(), &device_id, &nonce, ts);
    let hello = Hello {
        role: Role::Consumer,
        device_id: device_id.clone(),
        auth_token: ByteBuf::from(token),
        nonce: ByteBuf::from(nonce.to_vec()),
        timestamp: ts,
        protocol_version: PROTOCOL_VERSION,
        capabilities: vec!["attest".into()],
    };
    wr.send(Message::Binary(encode(&Envelope::new(Payload::Hello { hello }))?.into())).await?;
    let _ack = rd.next().await.unwrap()?;

    // Submit task
    let task_id = Uuid::new_v4();
    let task = Task {
        id: task_id,
        task_type: TaskType::Attest {
            challenge: ByteBuf::from(b"OmegaRelay-real-attest-2026-05-14".to_vec()),
            alias_hint: "dump".into(),
            algorithm: KeyAlgorithm::EcP256,
            purpose: KeyPurpose::Sign,
            attestation_application_id: None,
            device_context: None,
        },
        timeout_ms: 10_000,
        metadata: Default::default(),
    };
    wr.send(Message::Binary(encode(&Envelope::new(Payload::SubmitTask { task }))?.into())).await?;

    // Wait for result
    let msg = tokio::time::timeout(Duration::from_secs(15), rd.next())
        .await
        .context("timed out waiting for result")?
        .ok_or_else(|| anyhow::anyhow!("connection closed"))??;
    let env: Envelope = match msg {
        Message::Binary(b) => decode(&b)?,
        _ => anyhow::bail!("not binary"),
    };
    let result = match env.payload {
        Payload::TaskResult { result } => result,
        _ => anyhow::bail!("not a task result"),
    };
    if !result.success {
        anyhow::bail!("attest failed: {:?}", result.error);
    }

    let chain = match result.payload.unwrap() {
        omega_protocol::TaskResultPayload::Attest { cert_chain, public_key_der } => {
            println!("public key: {} bytes", public_key_der.len());
            cert_chain
        }
        other => anyhow::bail!("unexpected payload: {:?}", other),
    };

    println!("chain length: {}", chain.len());
    std::fs::create_dir_all("dist/chain").ok();
    for (i, cert) in chain.iter().enumerate() {
        let path = format!("dist/chain/cert{}.der", i);
        std::fs::write(&path, cert)?;
        println!("  cert{}: {} bytes -> {}", i, cert.len(), path);

        // Cheap heuristic: look for the Google Key Attestation extension OID.
        // OID 1.3.6.1.4.1.11129.2.1.17 encodes as
        //   06 09 2B 06 01 04 01 D6 79 02 01 11
        let oid_bytes: [u8; 12] = [0x06, 0x09, 0x2B, 0x06, 0x01, 0x04, 0x01, 0xD6, 0x79, 0x02, 0x01, 0x11];
        if cert.windows(oid_bytes.len()).any(|w| w == oid_bytes) {
            println!("    ✅ contains Google Key Attestation extension (OID 1.3.6.1.4.1.11129.2.1.17)");
        }
    }

    println!();
    println!("Done. To inspect:");
    println!("  openssl x509 -in dist/chain/cert0.der -inform DER -text -noout");
    println!("Or with Bouncy Castle: java -jar bcprov.jar ... ");

    Ok(())
}

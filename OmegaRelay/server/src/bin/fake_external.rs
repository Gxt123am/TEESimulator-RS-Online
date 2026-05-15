//! Fake consumer that exercises the AttestExternalKey path.
//!
//! Steps:
//!   1. Generate an EC P-256 keypair locally on the PC.
//!   2. Serialize the public key as DER (SubjectPublicKeyInfo).
//!   3. Submit a `TaskType::AttestExternalKey` task with that public key.
//!   4. Receive a cert chain whose leaf encloses our public key.
//!   5. Save the chain to disk for openssl inspection.
//!
//! Sign-and-verify on the consumer side is handled by a small shell script
//! after this run; this binary just confirms the round-trip.

use std::time::Duration;

use anyhow::Context;
use futures_util::{SinkExt, StreamExt};
use omega_protocol::{
    auth, decode, encode, Envelope, Hello, Payload, Role, Task, TaskType,
    PROTOCOL_VERSION,
};
use rcgen::{KeyPair, PKCS_ECDSA_P256_SHA256};
use serde_bytes::ByteBuf;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;
use uuid::Uuid;

#[tokio::main(flavor = "current_thread")]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt::init();

    let url = std::env::args().nth(1).unwrap_or_else(|| "ws://127.0.0.1:18443".into());
    let psk = std::env::args().nth(2).context("usage: fake_external <url> <psk>")?;

    // 1) Generate consumer-side EC P-256 keypair.
    let kp = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256)?;
    let pub_der = kp.public_key_der();
    println!("generated EC P-256 keypair, pub key DER: {} bytes", pub_der.len());

    // Save the keypair for later use (signing demos).
    std::fs::create_dir_all("dist/external")?;
    std::fs::write("dist/external/private_key.pem", kp.serialize_pem())?;
    std::fs::write("dist/external/public_key.der", &pub_der)?;
    println!("saved keypair to dist/external/");

    // 2) Connect & authenticate.
    let (ws, _) = connect_async(&url).await?;
    let (mut wr, mut rd) = ws.split();
    let device_id = "device-a-1".to_string();
    let nonce: [u8; 16] = {
        let mut b = [0u8; 16];
        let n = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)?
            .as_nanos() as u128;
        b.copy_from_slice(&n.to_le_bytes());
        b
    };
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_secs();
    let token = auth::compute_auth_token(psk.as_bytes(), &device_id, &nonce, ts);
    let hello = Hello {
        role: Role::Consumer,
        device_id: device_id.clone(),
        auth_token: ByteBuf::from(token),
        nonce: ByteBuf::from(nonce.to_vec()),
        timestamp: ts,
        protocol_version: PROTOCOL_VERSION,
        capabilities: vec!["attest_external_key".into()],
    };
    wr.send(Message::Binary(encode(&Envelope::new(Payload::Hello { hello }))?.into())).await?;
    let _ack = rd.next().await.unwrap()?;
    println!("authenticated to relay");

    // 3) Submit AttestExternalKey task.
    let task_id = Uuid::new_v4();
    let task = Task {
        id: task_id,
        task_type: TaskType::AttestExternalKey {
            challenge: ByteBuf::from(b"OmegaRelay-extkey-2026-05-14".to_vec()),
            external_public_key_der: ByteBuf::from(pub_der.to_vec()),
            attestation_application_id: None,
            device_context: None,
        },
        timeout_ms: 10_000,
        metadata: Default::default(),
    };
    wr.send(Message::Binary(
        encode(&Envelope::new(Payload::SubmitTask { task }))?.into(),
    )).await?;
    println!("task {} submitted", task_id);

    // 4) Receive result.
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
        anyhow::bail!("attest_external_key failed: {:?}", result.error);
    }
    let chain = match result.payload.unwrap() {
        omega_protocol::TaskResultPayload::AttestExternalKey { cert_chain } => cert_chain,
        other => anyhow::bail!("unexpected payload: {:?}", other),
    };

    println!("\n✅ Got cert chain with {} certs", chain.len());
    for (i, cert) in chain.iter().enumerate() {
        let path = format!("dist/external/cert{}.der", i);
        std::fs::write(&path, cert)?;
        println!("  cert{}: {} bytes -> {}", i, cert.len(), path);
    }

    // Programmatic verification of the chain. openssl gets confused by
    // Android's identical Subject DN ("CN=Android Keystore Key") on every
    // leaf, so do it ourselves.
    println!("\n=== Chain analysis ===");
    verify_chain_locally(&chain, &pub_der)?;

    println!("\nNext steps to verify:");
    println!("  # 1. Inspect leaf:");
    println!("  openssl x509 -in dist/external/cert0.der -inform DER -text -noout");
    println!();
    println!("  # 2. Verify chain signs to Google attest root:");
    println!("  openssl verify -CAfile dist/external/cert{}.der \\", chain.len() - 1);
    let mut intermediates = Vec::new();
    for i in 1..chain.len() - 1 {
        intermediates.push(format!("    -untrusted dist/external/cert{}.der", i));
    }
    for line in &intermediates {
        println!("{} \\", line);
    }
    println!("    dist/external/cert0.der");
    println!();
    println!("  # 3. Confirm leaf SPKI matches our consumer-side public key:");
    println!("  openssl x509 -in dist/external/cert0.der -inform DER -pubkey -noout > leaf_pub.pem");
    println!("  openssl ec -in dist/external/private_key.pem -pubout > our_pub.pem");
    println!("  diff leaf_pub.pem our_pub.pem    # must be identical");

    Ok(())
}

/// Verifies the chain ourselves: for each adjacent pair (child, parent),
/// confirms child's signature is valid under parent's public key. Also checks
/// that the leaf's SPKI exactly matches the public key we sent up.
fn verify_chain_locally(
    chain: &[serde_bytes::ByteBuf],
    expected_leaf_pubkey_der: &[u8],
) -> anyhow::Result<()> {
    use x509_parser::prelude::*;

    if chain.is_empty() {
        anyhow::bail!("empty chain");
    }

    // 1. Parse all certs.
    let parsed: Vec<X509Certificate> = chain
        .iter()
        .enumerate()
        .map(|(i, der)| {
            let (_rest, cert) = X509Certificate::from_der(der)
                .map_err(|e| anyhow::anyhow!("parse cert{}: {:?}", i, e))?;
            Ok::<_, anyhow::Error>(cert)
        })
        .collect::<Result<_, _>>()?;

    // 2. Print Subject + Issuer for visibility.
    for (i, cert) in parsed.iter().enumerate() {
        println!(
            "  cert{}: subject={}  issuer={}",
            i,
            cert.subject(),
            cert.issuer(),
        );
    }

    // 3. Confirm leaf SPKI matches expected.
    let leaf_spki_der = parsed[0]
        .public_key()
        .raw;
    if leaf_spki_der == expected_leaf_pubkey_der {
        println!("  ✅ leaf SPKI matches the public key we submitted");
    } else {
        println!(
            "  ❌ leaf SPKI mismatch: chain leaf is {} bytes, we sent {} bytes",
            leaf_spki_der.len(),
            expected_leaf_pubkey_der.len(),
        );
    }

    // 4. Verify signature of each cert against the next one's pubkey.
    for i in 0..parsed.len() - 1 {
        let child = &parsed[i];
        let parent_pub = parsed[i + 1].public_key();
        match child.verify_signature(Some(parent_pub)) {
            Ok(()) => {
                println!("  ✅ cert{}.signature verified by cert{}.pubkey", i, i + 1);
            }
            Err(e) => {
                println!("  ❌ cert{}.signature FAILS under cert{}.pubkey: {:?}", i, i + 1, e);
            }
        }
    }

    // 5. Last cert should be self-signed (root).
    let last = parsed.last().unwrap();
    match last.verify_signature(Some(last.public_key())) {
        Ok(()) => println!("  ✅ root cert{} is self-signed (Google attest root)", parsed.len() - 1),
        Err(e) => println!("  ❌ root cert{} self-signature failed: {:?}", parsed.len() - 1, e),
    }

    Ok(())
}

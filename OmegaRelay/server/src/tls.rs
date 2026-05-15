//! TLS setup helpers.

use std::path::Path;
use std::sync::Arc;

use anyhow::Context;
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use rustls::ServerConfig;
use tokio_rustls::TlsAcceptor;

use crate::config::TlsConfig;

pub fn build_acceptor(cfg: &TlsConfig) -> anyhow::Result<TlsAcceptor> {
    let (certs, key) = load_or_generate(cfg)?;

    rustls::crypto::ring::default_provider()
        .install_default()
        .ok(); // ignore "already installed"

    let server_config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .context("invalid TLS cert/key combination")?;

    Ok(TlsAcceptor::from(Arc::new(server_config)))
}

fn load_or_generate(
    cfg: &TlsConfig,
) -> anyhow::Result<(Vec<CertificateDer<'static>>, PrivateKeyDer<'static>)> {
    if let (Some(cert_path), Some(key_path)) = (&cfg.cert_path, &cfg.key_path) {
        if cert_path.exists() && key_path.exists() {
            tracing::info!("loading TLS cert from {}", cert_path.display());
            return load_pem(cert_path, key_path);
        }
    }

    if cfg.auto_generate_self_signed {
        tracing::warn!(
            "TLS cert/key not found; generating self-signed cert (NOT for production)"
        );
        return generate_self_signed(cfg);
    }

    anyhow::bail!("no TLS cert configured and auto_generate_self_signed is false")
}

fn load_pem(
    cert_path: &Path,
    key_path: &Path,
) -> anyhow::Result<(Vec<CertificateDer<'static>>, PrivateKeyDer<'static>)> {
    let cert_bytes = std::fs::read(cert_path)?;
    let key_bytes = std::fs::read(key_path)?;

    let certs: Vec<CertificateDer<'static>> = rustls_pemfile::certs(&mut cert_bytes.as_slice())
        .collect::<Result<_, _>>()
        .context("parsing cert PEM")?;
    if certs.is_empty() {
        anyhow::bail!("no certificates found in {}", cert_path.display());
    }

    let key = rustls_pemfile::private_key(&mut key_bytes.as_slice())?
        .ok_or_else(|| anyhow::anyhow!("no private key found in {}", key_path.display()))?;

    Ok((certs, key))
}

fn generate_self_signed(
    cfg: &TlsConfig,
) -> anyhow::Result<(Vec<CertificateDer<'static>>, PrivateKeyDer<'static>)> {
    let mut sans = cfg.self_signed_sans.clone();
    if sans.is_empty() {
        sans.push("localhost".to_string());
    }

    let cert = rcgen::generate_simple_self_signed(sans)?;
    let cert_der = cert.cert.der().clone();
    let key_der = PrivateKeyDer::try_from(cert.key_pair.serialize_der())
        .map_err(|e| anyhow::anyhow!("failed to serialize private key: {e}"))?;

    // Persist if paths are configured so subsequent runs reuse the same cert.
    if let (Some(cp), Some(kp)) = (&cfg.cert_path, &cfg.key_path) {
        if let Some(parent) = cp.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        let cert_pem = cert.cert.pem();
        let key_pem = cert.key_pair.serialize_pem();
        std::fs::write(cp, cert_pem).ok();
        std::fs::write(kp, key_pem).ok();
        tracing::info!("self-signed cert persisted to {} / {}", cp.display(), kp.display());
    }

    Ok((vec![cert_der], key_der))
}

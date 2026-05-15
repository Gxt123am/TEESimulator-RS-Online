//! Server configuration.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};

use serde::Deserialize;

#[derive(Debug, Deserialize)]
pub struct Config {
    /// Address to bind, e.g. "0.0.0.0:8443".
    pub bind_addr: SocketAddr,

    /// Pre-shared key for client authentication. MUST be strong & random.
    pub psk: String,

    #[serde(default)]
    pub tls: TlsConfig,

    /// Map of device_id → DeviceProfile. Devices not listed here are rejected.
    #[serde(default)]
    pub devices: HashMap<String, DeviceProfile>,

    /// Limits & timeouts.
    #[serde(default)]
    pub limits: Limits,
}

#[derive(Debug, Deserialize, Default)]
pub struct TlsConfig {
    /// If false, server runs plain WebSocket (development only).
    #[serde(default = "default_true")]
    pub enabled: bool,
    /// Path to PEM-encoded server certificate chain.
    #[serde(default)]
    pub cert_path: Option<PathBuf>,
    /// Path to PEM-encoded server private key.
    #[serde(default)]
    pub key_path: Option<PathBuf>,
    /// If true and cert/key are missing, generate a self-signed cert at startup.
    #[serde(default)]
    pub auto_generate_self_signed: bool,
    /// Subject Alternative Names for self-signed cert.
    #[serde(default)]
    pub self_signed_sans: Vec<String>,
}

#[derive(Debug, Deserialize, Default)]
pub struct DeviceProfile {
    pub role: DeviceRole,
    /// Optional: paired device_id this consumer is allowed to dispatch tasks to.
    /// If None, any compatible provider may be used.
    #[serde(default)]
    pub paired_with: Option<String>,
    #[serde(default)]
    pub note: String,
}

#[derive(Debug, Deserialize, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum DeviceRole {
    Consumer,
    Provider,
}

impl Default for DeviceRole {
    fn default() -> Self {
        DeviceRole::Consumer
    }
}

#[derive(Debug, Deserialize)]
pub struct Limits {
    pub max_task_timeout_ms: u32,
    pub default_task_timeout_ms: u32,
    pub max_in_flight_tasks_per_consumer: usize,
    pub idle_timeout_secs: u64,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_task_timeout_ms: 10_000,
            default_task_timeout_ms: 3_000,
            max_in_flight_tasks_per_consumer: 16,
            idle_timeout_secs: 90,
        }
    }
}

fn default_true() -> bool {
    true
}

impl Config {
    pub fn load(path: &Path) -> anyhow::Result<Self> {
        let text = std::fs::read_to_string(path)?;
        let cfg: Config = toml::from_str(&text)?;
        cfg.validate()?;
        Ok(cfg)
    }

    fn validate(&self) -> anyhow::Result<()> {
        if self.psk.len() < 16 {
            anyhow::bail!("psk must be at least 16 characters");
        }
        if self.tls.enabled && !self.tls.auto_generate_self_signed {
            if self.tls.cert_path.is_none() || self.tls.key_path.is_none() {
                anyhow::bail!("tls.enabled but cert_path/key_path not set");
            }
        }
        if self.devices.is_empty() {
            tracing::warn!("no devices configured; all connections will be rejected");
        }
        Ok(())
    }

    pub fn psk_bytes(&self) -> &[u8] {
        self.psk.as_bytes()
    }
}

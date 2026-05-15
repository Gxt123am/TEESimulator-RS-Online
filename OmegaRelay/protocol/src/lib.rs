//! OmegaRelay wire protocol.
//!
//! This crate defines the message types exchanged between Device A,
//! Device B, and the relay server. Messages are serialized using
//! MessagePack and transmitted as binary WebSocket frames.

pub mod auth;
pub mod messages;

pub use messages::*;

/// Current protocol version. Bump on breaking changes.
pub const PROTOCOL_VERSION: u16 = 1;

/// Convenience: encode a message to MessagePack bytes.
pub fn encode<T: serde::Serialize>(value: &T) -> Result<Vec<u8>, rmp_serde::encode::Error> {
    rmp_serde::to_vec_named(value)
}

/// Convenience: decode a MessagePack message.
pub fn decode<T: serde::de::DeserializeOwned>(
    bytes: &[u8],
) -> Result<T, rmp_serde::decode::Error> {
    rmp_serde::from_slice(bytes)
}

#[derive(Debug, thiserror::Error)]
pub enum ProtocolError {
    #[error("encode failed: {0}")]
    Encode(#[from] rmp_serde::encode::Error),
    #[error("decode failed: {0}")]
    Decode(#[from] rmp_serde::decode::Error),
    #[error("unsupported protocol version: {0}")]
    UnsupportedVersion(u16),
    #[error("authentication failed")]
    AuthFailed,
    #[error("clock skew too large: {0}s")]
    ClockSkew(i64),
}

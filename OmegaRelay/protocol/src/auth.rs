//! Authentication helpers (HMAC-based pre-shared key auth).

use hmac::{Hmac, Mac};
use sha2::Sha256;
use subtle::ConstantTimeEq;

type HmacSha256 = Hmac<Sha256>;

/// Maximum allowed clock skew (in seconds) between client and server.
pub const MAX_CLOCK_SKEW_SECS: i64 = 60;

/// Compute the auth token for a Hello message.
///
/// `format!("{device_id}:{nonce_hex}:{timestamp}")` is signed with HMAC-SHA256
/// using the pre-shared key.
pub fn compute_auth_token(psk: &[u8], device_id: &str, nonce: &[u8], timestamp: u64) -> Vec<u8> {
    let nonce_hex = hex::encode(nonce);
    let payload = format!("{}:{}:{}", device_id, nonce_hex, timestamp);
    let mut mac = HmacSha256::new_from_slice(psk).expect("HMAC accepts any key length");
    mac.update(payload.as_bytes());
    mac.finalize().into_bytes().to_vec()
}

/// Constant-time verification of an auth token.
pub fn verify_auth_token(
    psk: &[u8],
    device_id: &str,
    nonce: &[u8],
    timestamp: u64,
    token: &[u8],
) -> bool {
    let expected = compute_auth_token(psk, device_id, nonce, timestamp);
    expected.ct_eq(token).into()
}

/// Verify that a timestamp is within acceptable clock skew of `now`.
pub fn check_timestamp(timestamp: u64, now: u64) -> Result<(), i64> {
    let diff = (now as i64) - (timestamp as i64);
    if diff.abs() <= MAX_CLOCK_SKEW_SECS {
        Ok(())
    } else {
        Err(diff)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_token_roundtrip() {
        let psk = b"super-secret-psk-must-be-strong";
        let device_id = "device-b-1";
        let nonce = b"0123456789abcdef";
        let ts = 1_700_000_000u64;

        let token = compute_auth_token(psk, device_id, nonce, ts);
        assert!(verify_auth_token(psk, device_id, nonce, ts, &token));

        // wrong PSK should fail
        assert!(!verify_auth_token(b"other", device_id, nonce, ts, &token));
        // wrong device_id should fail
        assert!(!verify_auth_token(psk, "other", nonce, ts, &token));
        // wrong nonce should fail
        assert!(!verify_auth_token(psk, device_id, b"deadbeefdeadbeef", ts, &token));
        // wrong timestamp should fail
        assert!(!verify_auth_token(psk, device_id, nonce, ts + 1, &token));
    }

    #[test]
    fn clock_skew() {
        assert!(check_timestamp(100, 100).is_ok());
        assert!(check_timestamp(100, 159).is_ok());
        assert!(check_timestamp(159, 100).is_ok());
        assert!(check_timestamp(100, 200).is_err());
    }
}

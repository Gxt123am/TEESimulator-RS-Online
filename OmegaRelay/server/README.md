# OmegaRelay Server

Rust-based WebSocket relay server.

## Build

Requires Rust 1.75+ (install via [rustup](https://rustup.rs/)).

```bash
cd server
cargo build --release
# Binary: ../target/release/omega-server[.exe]
```

## Configure

```bash
cp config.example.toml config.toml
# Generate a strong PSK:
#   openssl rand -hex 32
# Or PowerShell:
#   -join ((48..57)+(65..90)+(97..122) | Get-Random -Count 48 | % {[char]$_})
# Edit config.toml: set psk and bind_addr.
```

## Run

```bash
# In dev (auto-generates self-signed cert on first run):
cargo run --release -- config.toml

# With explicit log level:
RUST_LOG=omega_server=debug,omega_protocol=debug cargo run -- config.toml
```

## Smoke test

In one terminal, start the server:

```bash
cargo run --release -- config.toml
```

In another, run the smoke test (matches the PSK in your config.toml):

```bash
cargo run --release --bin smoke_test -- ws://127.0.0.1:8443 <YOUR_PSK>
```

The test:
1. Connects two fake clients (one consumer, one provider)
2. Submits a dummy attestation task from the consumer
3. Verifies the provider receives `DispatchTask`
4. Sends a fake result back from the provider
5. Verifies the consumer receives `TaskResult`

If you see `✅ end-to-end task round-trip OK`, the relay logic works.

For TLS testing (`wss://`), the smoke test currently does NOT skip cert
verification, so use `enabled = false` in `[tls]` for the smoke test.
A `--insecure` flag is on the TODO list.

## Production deployment

For internet-facing deployment, do **NOT** use a self-signed cert. Get a real
cert from Let's Encrypt:

```bash
# example with certbot
certbot certonly --standalone -d relay.example.com
# then point cert_path/key_path in config.toml to /etc/letsencrypt/live/...
```

A `systemd` unit and Dockerfile are TODO items.

## Logging

The server uses [`tracing`](https://docs.rs/tracing). Control verbosity via
`RUST_LOG`:

```
RUST_LOG=info                        # default
RUST_LOG=omega_server=debug          # debug for our crate, info for deps
RUST_LOG=trace                       # very noisy
```

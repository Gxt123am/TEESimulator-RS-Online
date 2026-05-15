# OmegaRelay v2

A self-hosted, secure remote Key Attestation relay system.

## Architecture

```
Device A (主力, 解BL/Root)  ◄──WSS──►  Relay Server  ◄──WSS──►  Device B (备用, KSU越狱/锁BL)
       │                                                                  │
   被 hook 的 keystore                                                   真实 TEE
```

## Project Structure

```
OmegaRelay/
├── server/           # Rust relay server
├── protocol/         # Shared protocol definitions (Rust crate + spec)
├── device-a-module/  # KSU/Magisk module for Device A (consumer)
├── device-b-module/  # KSU module for Device B (provider)
├── tools/            # Helper scripts (key generation, testing)
└── docs/             # Documentation
```

## Components

### 1. Relay Server (`server/`)
- Language: Rust (tokio + tungstenite + rustls)
- Role: Routes attestation tasks between Device A and Device B
- Stateless, in-memory task queue, supports multiple device pairs

### 2. Protocol (`protocol/`)
- Wire format: MessagePack
- Transport: WebSocket over TLS 1.3
- Auth: Pre-shared key + HMAC

### 3. Device A Module (`device-a-module/`)
- Hooks the keystore process to intercept attestation requests
- Forwards challenges to Device B via the relay server
- Replaces local certificate chain with the remote TEE chain

### 4. Device B Module (`device-b-module/`)
- Lightweight Kotlin daemon (runs via app_process)
- Listens for tasks, performs real TEE attestation
- Returns certificate chains to Device A

## Status

- [x] Phase 0: Architecture & Technical Report
- [ ] Phase 1: Protocol + Server skeleton
- [ ] Phase 2: Device B daemon
- [ ] Phase 3: Device A daemon
- [ ] Phase 4: Hook layer (libOmegaTee)
- [ ] Phase 5: Module packaging

## License

Personal use. Not for redistribution.

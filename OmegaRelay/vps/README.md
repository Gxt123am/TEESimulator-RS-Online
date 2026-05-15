# OmegaRelay VPS deployment

## TL;DR

```bash
# On your VPS:
sudo OMEGA_PSK="$(openssl rand -hex 32)" \
     OMEGA_DOMAIN=relay.example.com \
     bash <(curl -sSL https://raw.githubusercontent.com/Andrea-tb/OmegaRelay/main/vps/install.sh)
```

Done. The script does everything: rustup → clone → build → certbot →
systemd unit → start.

## Detailed flow

| Step | Action |
|------|--------|
| 1 | Install build tools (gcc/git/curl/etc) via apt or yum |
| 2 | Install rustup (stable) at `/root/.cargo` |
| 3 | Clone repo to `/usr/local/lib/omega-relay/src` |
| 4 | `cargo build --release -p omega-server` — output binary copied to `/usr/local/bin/omega-server` |
| 5 | Create system user `omega` |
| 6 | If `$OMEGA_DOMAIN` set: certbot standalone → cert at `/etc/letsencrypt/live/$DOMAIN/` |
| 7 | Write `/etc/omega-relay/config.toml` |
| 8 | Install + enable systemd unit `omega-relay.service` |

## Required env vars

| Variable | Default | Description |
|----------|---------|-------------|
| `OMEGA_PSK` | — (required) | Pre-shared key, ≥16 chars. Tell your phones/clients the same value. |
| `OMEGA_DOMAIN` | — | Public hostname for Let's Encrypt. If unset, server runs plaintext WS. |
| `OMEGA_NO_TLS` | 0 | Set to 1 to skip Let's Encrypt even with `OMEGA_DOMAIN` set. |
| `OMEGA_BIND_ADDR` | 0.0.0.0:8443 (TLS) or 0.0.0.0:18443 (plain) | Override bind address. |
| `OMEGA_DEVICE_A_ID` | device-a-1 | Consumer device id. |
| `OMEGA_DEVICE_B_ID` | device-b-1 | Provider device id. |
| `OMEGA_REPO` | this repo's URL | Override source repo. |
| `OMEGA_REF` | main | Git ref to build. |
| `OMEGA_SRC` | — | Use existing local source dir (skips git clone). |
| `OMEGA_LETSENCRYPT_EMAIL` | noreply@$OMEGA_DOMAIN | Contact email for the cert. |

## Common deployments

### A. Direct TLS (simplest)

```bash
sudo OMEGA_PSK="..." OMEGA_DOMAIN=relay.example.com bash install.sh
```

Phone connects to `wss://relay.example.com:8443/`.

### B. Behind existing nginx

```bash
sudo OMEGA_PSK="..." OMEGA_NO_TLS=1 OMEGA_BIND_ADDR=127.0.0.1:18443 bash install.sh
```

Then drop `nginx-snippet.conf` into `/etc/nginx/sites-enabled/` (edit hostname).

### C. Local file build (no internet for git)

If your VPS can't reach GitHub:

```bash
# On your laptop:
tar czf omega.tgz OmegaRelay/
scp omega.tgz vps:/tmp/

# On the VPS:
cd /tmp && tar xzf omega.tgz
sudo OMEGA_PSK="..." OMEGA_SRC=/tmp/OmegaRelay bash /tmp/OmegaRelay/vps/install.sh
```

## Operations

```bash
# Status
systemctl status omega-relay

# Logs
journalctl -u omega-relay -f

# Restart after editing /etc/omega-relay/config.toml
systemctl restart omega-relay

# Reload only (NB: server doesn't currently support reload, so this restarts)
systemctl reload-or-restart omega-relay
```

## Updating

To pull a new version:

```bash
sudo OMEGA_PSK="$(grep ^psk /etc/omega-relay/config.toml | cut -d'"' -f2)" \
     OMEGA_DOMAIN=relay.example.com \
     bash <(curl -sSL https://raw.githubusercontent.com/Andrea-tb/OmegaRelay/main/vps/install.sh)
```

The script is idempotent: it won't recreate the user, won't request a new
cert if one exists, and won't change your config (apart from the build
artifact and the systemd unit, which it overwrites).

To preserve a hand-edited config, set `OMEGA_NO_OVERWRITE_CONFIG=1` (TODO: not
yet implemented; for now back up `/etc/omega-relay/config.toml` before
re-running).

## Uninstall

```bash
sudo systemctl disable --now omega-relay
sudo rm -f /etc/systemd/system/omega-relay.service
sudo systemctl daemon-reload
sudo rm -rf /usr/local/lib/omega-relay /etc/omega-relay /usr/local/bin/omega-server
sudo userdel omega
# Optionally also revoke the cert:
sudo certbot delete --cert-name relay.example.com
```

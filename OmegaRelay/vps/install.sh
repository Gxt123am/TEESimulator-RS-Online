#!/usr/bin/env bash
# OmegaRelay VPS one-shot installer.
#
# What this script does:
#   1. Verify we're on a supported Linux (Debian/Ubuntu or RHEL family).
#   2. Install rustup if missing (so we can build the server).
#   3. Clone the repo (or use $OMEGA_SRC if you already have it).
#   4. Build omega-server in release mode.
#   5. Create a system user `omega` and a config file at /etc/omega-relay/.
#   6. Optionally fetch a Let's Encrypt cert (if $OMEGA_DOMAIN is set).
#   7. Install a systemd unit and start the service.
#
# Required environment variables (or pass via flags):
#   OMEGA_PSK           Pre-shared key (≥16 chars). Required.
#   OMEGA_DOMAIN        Public hostname for TLS via Let's Encrypt. Optional;
#                       if unset, the server runs on plaintext ws:// 0.0.0.0:18443
#                       and you must terminate TLS yourself (e.g. behind nginx).
#   OMEGA_BIND_ADDR     Override bind addr. Default 0.0.0.0:18443 (plain) or
#                       0.0.0.0:8443 (TLS).
#   OMEGA_DEVICE_A_ID   Consumer device id. Default device-a-1.
#   OMEGA_DEVICE_B_ID   Provider device id. Default device-b-1.
#   OMEGA_REPO          Git URL (default https://github.com/.../OmegaRelay).
#   OMEGA_REF           Git ref to check out. Default main.
#   OMEGA_SRC           Use this dir as source instead of cloning (skips git).
#   OMEGA_NO_TLS        Set to 1 to skip Let's Encrypt even if OMEGA_DOMAIN set.
#
# Examples:
#   sudo OMEGA_PSK="$(openssl rand -hex 32)" \
#        OMEGA_DOMAIN=relay.example.com \
#        bash install.sh
#
#   sudo OMEGA_PSK="..." OMEGA_NO_TLS=1 bash install.sh   # plain WS, terminate TLS at nginx

set -euo pipefail

# ------------------------------------------------------------------- args
say()  { echo "==> $*"; }
warn() { echo "WARN: $*" >&2; }
die()  { echo "ERROR: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "must be run as root (try sudo)"

OMEGA_PSK="${OMEGA_PSK:-}"
[[ -n "$OMEGA_PSK" && ${#OMEGA_PSK} -ge 16 ]] || die "OMEGA_PSK is required (≥16 chars)"

OMEGA_DOMAIN="${OMEGA_DOMAIN:-}"
OMEGA_NO_TLS="${OMEGA_NO_TLS:-0}"
OMEGA_DEVICE_A_ID="${OMEGA_DEVICE_A_ID:-device-a-1}"
OMEGA_DEVICE_B_ID="${OMEGA_DEVICE_B_ID:-device-b-1}"
OMEGA_REPO="${OMEGA_REPO:-https://github.com/Andrea-lyz/TEESimulator-RS-Online.git}"
OMEGA_REF="${OMEGA_REF:-main}"
OMEGA_SRC="${OMEGA_SRC:-}"

if [[ -n "$OMEGA_DOMAIN" && "$OMEGA_NO_TLS" != "1" ]]; then
    USE_TLS=1
    OMEGA_BIND_ADDR="${OMEGA_BIND_ADDR:-0.0.0.0:8443}"
else
    USE_TLS=0
    OMEGA_BIND_ADDR="${OMEGA_BIND_ADDR:-0.0.0.0:18443}"
fi

INSTALL_PREFIX=/usr/local/lib/omega-relay
ETC_DIR=/etc/omega-relay
BIN_PATH=/usr/local/bin/omega-server
SYSTEMD_UNIT=/etc/systemd/system/omega-relay.service
SVC_USER=omega

# ------------------------------------------------------------ os detection
if [[ -f /etc/os-release ]]; then
    . /etc/os-release
    case "$ID" in
        debian|ubuntu) FAMILY=debian ;;
        rhel|centos|rocky|almalinux|fedora) FAMILY=rhel ;;
        *) FAMILY=unknown ;;
    esac
else
    FAMILY=unknown
fi

say "OS family: $FAMILY"

# ------------------------------------------------------------ dependencies
install_packages() {
    case "$FAMILY" in
        debian)
            apt-get update -qq
            apt-get install -y -qq curl git build-essential pkg-config ca-certificates
            ;;
        rhel)
            yum install -y -q curl git gcc make pkgconfig ca-certificates
            ;;
        *)
            warn "unknown OS family; assuming required packages are present"
            ;;
    esac
}

install_packages

# rustup (per-user but we'll use it from this script's environment)
RUST_HOME=/root/.cargo
if [[ ! -x $RUST_HOME/bin/cargo ]]; then
    say "installing Rust toolchain via rustup"
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable
fi
export PATH="$RUST_HOME/bin:$PATH"

# If a previous run aborted before installing the toolchain, rustup may exist
# without any default toolchain set. Make sure stable is installed and default.
if ! rustup default 2>/dev/null | grep -q stable; then
    say "ensuring stable toolchain is installed and default"
    rustup install --profile minimal stable
    rustup default stable
fi

# ----------------------------------------------------------------- source
if [[ -z "$OMEGA_SRC" ]]; then
    SRC_DIR="$INSTALL_PREFIX/src"
    say "fetching source from $OMEGA_REPO@$OMEGA_REF"
    if [[ -d "$SRC_DIR/.git" ]]; then
        git -C "$SRC_DIR" fetch --depth 1 origin "$OMEGA_REF"
        git -C "$SRC_DIR" checkout FETCH_HEAD
    else
        rm -rf "$SRC_DIR"
        git clone --depth 1 --branch "$OMEGA_REF" "$OMEGA_REPO" "$SRC_DIR"
    fi
else
    SRC_DIR="$OMEGA_SRC"
    [[ -d "$SRC_DIR" ]] || die "OMEGA_SRC=$OMEGA_SRC does not exist"
    say "using local source at $SRC_DIR"
fi

# ----------------------------------------------------------------- build
say "building omega-server (release)"
cd "$SRC_DIR"
cargo build --release -p omega-server
install -m 0755 target/release/omega-server "$BIN_PATH"
say "binary installed to $BIN_PATH"

# ----------------------------------------------------------- service user
if ! id -u "$SVC_USER" >/dev/null 2>&1; then
    say "creating system user $SVC_USER"
    useradd --system --no-create-home --shell /usr/sbin/nologin "$SVC_USER"
fi

mkdir -p "$ETC_DIR" "$ETC_DIR/certs"
chown -R "$SVC_USER:$SVC_USER" "$ETC_DIR"
chmod 750 "$ETC_DIR"
chmod 700 "$ETC_DIR/certs"

# ---------------------------------------------------------- TLS via certbot
if [[ "$USE_TLS" == "1" ]]; then
    say "obtaining TLS cert for $OMEGA_DOMAIN via Let's Encrypt"
    case "$FAMILY" in
        debian) apt-get install -y -qq certbot ;;
        rhel)   yum install -y -q certbot ;;
        *)      die "Let's Encrypt automation requires apt or yum" ;;
    esac

    if [[ ! -f /etc/letsencrypt/live/$OMEGA_DOMAIN/fullchain.pem ]]; then
        certbot certonly --standalone --non-interactive --agree-tos \
            -m "${OMEGA_LETSENCRYPT_EMAIL:-noreply@$OMEGA_DOMAIN}" \
            -d "$OMEGA_DOMAIN"
    fi
    CERT_PATH=/etc/letsencrypt/live/$OMEGA_DOMAIN/fullchain.pem
    KEY_PATH=/etc/letsencrypt/live/$OMEGA_DOMAIN/privkey.pem

    # Hook for renewal: the cert/key paths above are stable; certbot's renewal
    # timer takes care of refreshing them. Reload systemd unit on cert renew.
    mkdir -p /etc/letsencrypt/renewal-hooks/deploy
    cat >/etc/letsencrypt/renewal-hooks/deploy/omega-relay-reload.sh <<'EOF'
#!/usr/bin/env bash
systemctl reload-or-restart omega-relay.service
EOF
    chmod 755 /etc/letsencrypt/renewal-hooks/deploy/omega-relay-reload.sh

    # Allow our service user to read the cert and key. Letsencrypt sets
    # /etc/letsencrypt/{archive,live} as 0700 root; the cleanest fix is to
    # join those dirs via a setgid group.
    addgroup --system letsencrypt 2>/dev/null || groupadd -r letsencrypt 2>/dev/null || true
    chgrp -R letsencrypt /etc/letsencrypt/archive /etc/letsencrypt/live
    chmod -R g+rX /etc/letsencrypt/archive /etc/letsencrypt/live
    usermod -a -G letsencrypt "$SVC_USER"
fi

# ----------------------------------------------------------- config
TLS_BLOCK=
if [[ "$USE_TLS" == "1" ]]; then
    TLS_BLOCK=$(cat <<EOF
enabled = true
cert_path = "$CERT_PATH"
key_path = "$KEY_PATH"
auto_generate_self_signed = false
EOF
)
else
    TLS_BLOCK="enabled = false"
fi

cat >"$ETC_DIR/config.toml" <<EOF
# OmegaRelay server configuration.
# Edit and then \`systemctl restart omega-relay\` to apply changes.

bind_addr = "$OMEGA_BIND_ADDR"
psk = "$OMEGA_PSK"

[tls]
$TLS_BLOCK

[limits]
max_task_timeout_ms = 10000
default_task_timeout_ms = 3000
max_in_flight_tasks_per_consumer = 16
idle_timeout_secs = 90

[devices.$OMEGA_DEVICE_A_ID]
role = "consumer"
paired_with = "$OMEGA_DEVICE_B_ID"

[devices.$OMEGA_DEVICE_B_ID]
role = "provider"
EOF

chown "$SVC_USER:$SVC_USER" "$ETC_DIR/config.toml"
chmod 600 "$ETC_DIR/config.toml"
say "wrote $ETC_DIR/config.toml"

# ----------------------------------------------------------- systemd unit
cat >"$SYSTEMD_UNIT" <<EOF
[Unit]
Description=OmegaRelay attestation relay server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SVC_USER
Group=$SVC_USER
ExecStart=$BIN_PATH $ETC_DIR/config.toml
Restart=on-failure
RestartSec=3
LimitNOFILE=65535
Environment=RUST_LOG=info

# Hardening
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictRealtime=true
RestrictSUIDSGID=true
LockPersonality=true
PrivateTmp=true
ReadWritePaths=$ETC_DIR
ReadOnlyPaths=/etc/letsencrypt

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now omega-relay.service
sleep 2
systemctl status omega-relay.service --no-pager --lines=10 || true

say "==================== SUMMARY ===================="
say "Server bind:    $OMEGA_BIND_ADDR"
say "TLS:            $([[ $USE_TLS == 1 ]] && echo "yes ($OMEGA_DOMAIN)" || echo no)"
say "Consumer id:    $OMEGA_DEVICE_A_ID"
say "Provider id:    $OMEGA_DEVICE_B_ID"
say "Config file:    $ETC_DIR/config.toml"
say "Service:        systemctl {status,restart,stop} omega-relay"
say "Logs:           journalctl -u omega-relay -f"
say ""
if [[ "$USE_TLS" == "1" ]]; then
    say "On the phone, set:"
    say "  URL:        wss://$OMEGA_DOMAIN:${OMEGA_BIND_ADDR##*:}/"
else
    say "On the phone, set:"
    say "  URL:        ws://<this-host-ip>:${OMEGA_BIND_ADDR##*:}/"
    say "  (you'll want to put a TLS proxy in front of this for production)"
fi
say "  Device ID:  $OMEGA_DEVICE_B_ID"
say "  PSK:        (the OMEGA_PSK you supplied)"

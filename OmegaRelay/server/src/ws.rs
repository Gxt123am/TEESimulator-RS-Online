//! WebSocket listener: TCP accept → (optional) TLS → WS upgrade → session.

use std::sync::Arc;

use tokio::net::TcpListener;
use tokio_tungstenite::accept_async;

use crate::config::Config;
use crate::registry::Registry;
use crate::session;
use crate::tls;

pub async fn run(config: Arc<Config>, registry: Arc<Registry>) -> anyhow::Result<()> {
    let listener = TcpListener::bind(config.bind_addr).await?;
    tracing::info!(addr = %config.bind_addr, tls = config.tls.enabled, "listening");

    let acceptor = if config.tls.enabled {
        Some(tls::build_acceptor(&config.tls)?)
    } else {
        tracing::warn!("TLS is DISABLED — only use this for local development");
        None
    };

    loop {
        let (stream, peer) = match listener.accept().await {
            Ok(p) => p,
            Err(e) => {
                tracing::warn!(error = %e, "accept failed");
                continue;
            }
        };
        let _ = stream.set_nodelay(true);

        let config = Arc::clone(&config);
        let registry = Arc::clone(&registry);
        let acceptor = acceptor.clone();

        tokio::spawn(async move {
            let peer_str = peer.to_string();
            match acceptor {
                Some(acc) => {
                    let tls_stream = match acc.accept(stream).await {
                        Ok(s) => s,
                        Err(e) => {
                            tracing::debug!(peer = %peer_str, error = %e, "TLS handshake failed");
                            return;
                        }
                    };
                    let ws = match accept_async(tls_stream).await {
                        Ok(ws) => ws,
                        Err(e) => {
                            tracing::debug!(peer = %peer_str, error = %e, "WS upgrade failed");
                            return;
                        }
                    };
                    session::handle_connection(ws, config, registry, peer_str).await;
                }
                None => {
                    let ws = match accept_async(stream).await {
                        Ok(ws) => ws,
                        Err(e) => {
                            tracing::debug!(peer = %peer_str, error = %e, "WS upgrade failed");
                            return;
                        }
                    };
                    session::handle_connection(ws, config, registry, peer_str).await;
                }
            }
        });
    }
}

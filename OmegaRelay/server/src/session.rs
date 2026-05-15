//! Per-connection session handling: handshake, message routing, dispatch.

use std::sync::Arc;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use omega_protocol::{
    auth, decode, encode, Envelope, ErrorCode, ErrorMsg, Hello, HelloAck, Payload, Role, Task,
    TaskResult, PROTOCOL_VERSION,
};
use tokio::sync::{mpsc, oneshot};
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::WebSocketStream;
use uuid::Uuid;

use crate::config::{Config, DeviceRole};
use crate::registry::{PendingTask, Registry, Session};

/// Outbound queue capacity per session.
const OUTBOX_CAPACITY: usize = 64;

/// Initial handshake timeout.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

pub async fn handle_connection<S>(
    stream: WebSocketStream<S>,
    config: Arc<Config>,
    registry: Arc<Registry>,
    peer_addr: String,
) where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    if let Err(e) = handle_inner(stream, config, registry, &peer_addr).await {
        tracing::warn!(peer = %peer_addr, error = %e, "session terminated");
    }
}

async fn handle_inner<S>(
    stream: WebSocketStream<S>,
    config: Arc<Config>,
    registry: Arc<Registry>,
    peer_addr: &str,
) -> anyhow::Result<()>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let (mut writer, mut reader) = stream.split();

    // ---- Handshake ----------------------------------------------------------
    let hello_env = tokio::time::timeout(HANDSHAKE_TIMEOUT, reader.next())
        .await
        .map_err(|_| anyhow::anyhow!("handshake timeout"))?
        .ok_or_else(|| anyhow::anyhow!("connection closed before hello"))??;

    let hello_env: Envelope = match hello_env {
        Message::Binary(b) => decode(&b)?,
        Message::Close(_) => return Ok(()),
        _ => anyhow::bail!("expected binary frame for hello"),
    };

    let hello = match hello_env.payload {
        Payload::Hello { hello } => hello,
        _ => anyhow::bail!("first message was not Hello"),
    };

    let session_id = match validate_hello(&hello, &config) {
        Ok(id) => id,
        Err((code, msg)) => {
            let ack = Envelope::reply_to(
                hello_env.msg_id,
                Payload::HelloAck {
                    ack: HelloAck {
                        success: false,
                        session_id: None,
                        server_time: now_secs(),
                        error: Some(msg.clone()),
                    },
                },
            );
            let _ = writer.send(Message::Binary(encode(&ack)?.into())).await;
            anyhow::bail!("hello rejected ({:?}): {}", code, msg);
        }
    };

    let role = match hello.role {
        Role::Consumer => DeviceRole::Consumer,
        Role::Provider => DeviceRole::Provider,
    };

    // Outbox channel for this session's writer task.
    let (tx, mut rx) = mpsc::channel::<Envelope>(OUTBOX_CAPACITY);

    // Send HelloAck.
    let ack = Envelope::reply_to(
        hello_env.msg_id,
        Payload::HelloAck {
            ack: HelloAck {
                success: true,
                session_id: Some(session_id),
                server_time: now_secs(),
                error: None,
            },
        },
    );
    writer.send(Message::Binary(encode(&ack)?.into())).await?;

    let session = Session {
        session_id,
        device_id: hello.device_id.clone(),
        role,
        outbox: tx.clone(),
    };

    // Replace any pre-existing session for this device_id.
    if let Some(prev) = registry.register(session.clone()) {
        tracing::warn!(
            device_id = %prev.device_id,
            "evicting previous session for same device_id"
        );
        drop(prev.outbox);
    }

    tracing::info!(
        peer = %peer_addr,
        device_id = %hello.device_id,
        role = ?role,
        session_id = %session_id,
        "session authenticated"
    );

    // ---- Writer task: pull from outbox and send over WS ---------------------
    let writer_task = tokio::spawn(async move {
        while let Some(env) = rx.recv().await {
            match encode(&env) {
                Ok(bytes) => {
                    if writer.send(Message::Binary(bytes.into())).await.is_err() {
                        break;
                    }
                }
                Err(e) => {
                    tracing::error!(error = %e, "encode failed");
                    break;
                }
            }
        }
        let _ = writer.close().await;
    });

    // ---- Reader loop --------------------------------------------------------
    let read_result = read_loop(&mut reader, &session, &registry, &config).await;

    // ---- Cleanup ------------------------------------------------------------
    registry.unregister(&session.device_id, session_id);
    if role == DeviceRole::Consumer {
        registry.cancel_consumer_pending(session_id);
    }
    drop(tx);
    let _ = writer_task.await;

    let (consumers, providers) = registry.session_count();
    tracing::info!(
        device_id = %session.device_id,
        consumers,
        providers,
        "session closed"
    );

    read_result
}

fn validate_hello(hello: &Hello, config: &Config) -> Result<Uuid, (ErrorCode, String)> {
    if hello.protocol_version != PROTOCOL_VERSION {
        return Err((
            ErrorCode::InvalidProtocolVersion,
            format!(
                "unsupported protocol version {} (server expects {})",
                hello.protocol_version, PROTOCOL_VERSION
            ),
        ));
    }

    let now = now_secs();
    if let Err(diff) = auth::check_timestamp(hello.timestamp, now) {
        return Err((
            ErrorCode::AuthFailed,
            format!("clock skew too large: {}s", diff),
        ));
    }

    if !auth::verify_auth_token(
        config.psk_bytes(),
        &hello.device_id,
        &hello.nonce,
        hello.timestamp,
        &hello.auth_token,
    ) {
        return Err((ErrorCode::AuthFailed, "invalid auth token".into()));
    }

    let profile = config
        .devices
        .get(&hello.device_id)
        .ok_or((ErrorCode::AuthFailed, "device not registered".into()))?;

    let claimed_role = match hello.role {
        Role::Consumer => DeviceRole::Consumer,
        Role::Provider => DeviceRole::Provider,
    };
    if profile.role != claimed_role {
        return Err((
            ErrorCode::AuthFailed,
            format!(
                "role mismatch: device registered as {:?}, claimed {:?}",
                profile.role, claimed_role
            ),
        ));
    }

    Ok(Uuid::new_v4())
}

async fn read_loop<R>(
    reader: &mut R,
    session: &Session,
    registry: &Arc<Registry>,
    config: &Arc<Config>,
) -> anyhow::Result<()>
where
    R: futures_util::stream::Stream<
            Item = Result<Message, tokio_tungstenite::tungstenite::Error>,
        > + Unpin,
{
    let idle_timeout = Duration::from_secs(config.limits.idle_timeout_secs);

    loop {
        let msg = match tokio::time::timeout(idle_timeout, reader.next()).await {
            Err(_) => anyhow::bail!("idle timeout"),
            Ok(None) => return Ok(()),
            Ok(Some(Err(e))) => return Err(e.into()),
            Ok(Some(Ok(m))) => m,
        };

        match msg {
            Message::Binary(bytes) => {
                let env: Envelope = match decode(&bytes) {
                    Ok(e) => e,
                    Err(e) => {
                        tracing::warn!(device_id = %session.device_id, error = %e, "decode failed");
                        continue;
                    }
                };
                handle_message(env, session, registry, config).await;
            }
            Message::Ping(_) | Message::Pong(_) => {}
            Message::Close(_) => return Ok(()),
            Message::Text(_) => {
                tracing::warn!(device_id = %session.device_id, "ignoring text frame");
            }
            Message::Frame(_) => {}
        }
    }
}

async fn handle_message(
    env: Envelope,
    session: &Session,
    registry: &Arc<Registry>,
    config: &Arc<Config>,
) {
    match env.payload {
        Payload::Ping => {
            let pong = Envelope::reply_to(env.msg_id, Payload::Pong);
            let _ = session.outbox.send(pong).await;
        }
        Payload::Pong => {}
        Payload::SubmitTask { task } => {
            if session.role != DeviceRole::Consumer {
                send_error(
                    session,
                    Some(env.msg_id),
                    ErrorCode::InvalidTask,
                    "only consumers may submit tasks",
                )
                .await;
                return;
            }
            dispatch_task(task, session.clone(), Arc::clone(registry), Arc::clone(config)).await;
        }
        Payload::TaskResult { result } => {
            if session.role != DeviceRole::Provider {
                send_error(
                    session,
                    Some(env.msg_id),
                    ErrorCode::InvalidTask,
                    "only providers may submit results",
                )
                .await;
                return;
            }
            deliver_result(result, registry).await;
        }
        Payload::Hello { .. } | Payload::HelloAck { .. } => {
            tracing::warn!(device_id = %session.device_id, "unexpected hello message");
        }
        Payload::DispatchTask { .. } => {
            tracing::warn!(device_id = %session.device_id, "client must not send DispatchTask");
        }
        Payload::Error { error } => {
            tracing::warn!(
                device_id = %session.device_id,
                code = ?error.code,
                msg = %error.message,
                "client reported error"
            );
        }
    }
}

async fn dispatch_task(
    mut task: Task,
    consumer: Session,
    registry: Arc<Registry>,
    config: Arc<Config>,
) {
    // Clamp timeout.
    if task.timeout_ms == 0 {
        task.timeout_ms = config.limits.default_task_timeout_ms;
    }
    task.timeout_ms = task.timeout_ms.min(config.limits.max_task_timeout_ms);

    // Provider selection: prefer the device pinned in config.
    let preferred = config
        .devices
        .get(&consumer.device_id)
        .and_then(|p| p.paired_with.clone());

    let provider = match registry.find_provider(preferred.as_deref()) {
        Some(p) => p,
        None => {
            send_failed_result(&consumer, task.id, "no provider available").await;
            return;
        }
    };

    let task_id = task.id;
    let timeout_ms = task.timeout_ms as u64;

    let (tx, rx) = oneshot::channel::<TaskResult>();
    let pending = Arc::new(PendingTask::new(task_id, consumer.session_id, tx));
    registry.register_pending(Arc::clone(&pending));

    // Send DispatchTask to provider.
    let dispatch_env = Envelope::new(Payload::DispatchTask { task });
    if provider.outbox.send(dispatch_env).await.is_err() {
        registry.take_pending(task_id);
        send_failed_result(&consumer, task_id, "provider disconnected").await;
        return;
    }

    // Wait for result with timeout.
    tokio::spawn(async move {
        let result = match tokio::time::timeout(Duration::from_millis(timeout_ms), rx).await {
            Ok(Ok(r)) => r,
            Ok(Err(_)) => TaskResult {
                task_id,
                success: false,
                payload: None,
                error: Some("task channel closed".into()),
                server_timing: None,
            },
            Err(_) => {
                // Remove the pending entry so a late result doesn't try to use it.
                registry.take_pending(task_id);
                TaskResult {
                    task_id,
                    success: false,
                    payload: None,
                    error: Some("task timed out at server".into()),
                    server_timing: None,
                }
            }
        };
        let env = Envelope::new(Payload::TaskResult { result });
        let _ = consumer.outbox.send(env).await;
    });
}

async fn deliver_result(result: TaskResult, registry: &Arc<Registry>) {
    match registry.take_pending(result.task_id) {
        Some(pending) => {
            if let Some(sender) = pending.take_responder() {
                let _ = sender.send(result);
            } else {
                tracing::warn!(task_id = %result.task_id, "responder already taken");
            }
        }
        None => {
            tracing::warn!(task_id = %result.task_id, "received result for unknown task");
        }
    }
}

async fn send_error(session: &Session, related: Option<Uuid>, code: ErrorCode, msg: &str) {
    let env = Envelope::new(Payload::Error {
        error: ErrorMsg {
            code,
            message: msg.into(),
            related_msg_id: related,
        },
    });
    let _ = session.outbox.send(env).await;
}

async fn send_failed_result(consumer: &Session, task_id: Uuid, error: &str) {
    let env = Envelope::new(Payload::TaskResult {
        result: TaskResult {
            task_id,
            success: false,
            payload: None,
            error: Some(error.into()),
            server_timing: None,
        },
    });
    let _ = consumer.outbox.send(env).await;
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

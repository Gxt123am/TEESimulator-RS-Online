//! Live registry of connected sessions and pending tasks.

use std::sync::Mutex;

use dashmap::DashMap;
use omega_protocol::{Envelope, TaskResult};
use tokio::sync::{mpsc, oneshot};
use uuid::Uuid;

use crate::config::DeviceRole;

/// Channel used to send an outbound envelope to a session's writer task.
pub type Outbox = mpsc::Sender<Envelope>;

/// A connected, authenticated session.
#[derive(Clone)]
pub struct Session {
    pub session_id: Uuid,
    pub device_id: String,
    pub role: DeviceRole,
    pub outbox: Outbox,
}

/// In-flight task awaiting a result. The responder is wrapped in `Mutex<Option<_>>`
/// so we can move it out exactly once when the result arrives or the task times out.
pub struct PendingTask {
    pub task_id: Uuid,
    pub consumer_session_id: Uuid,
    pub responder: Mutex<Option<oneshot::Sender<TaskResult>>>,
}

impl PendingTask {
    pub fn new(
        task_id: Uuid,
        consumer_session_id: Uuid,
        sender: oneshot::Sender<TaskResult>,
    ) -> Self {
        Self {
            task_id,
            consumer_session_id,
            responder: Mutex::new(Some(sender)),
        }
    }

    /// Take the sender out of the slot (returns None if already taken).
    pub fn take_responder(&self) -> Option<oneshot::Sender<TaskResult>> {
        self.responder.lock().ok().and_then(|mut g| g.take())
    }
}

/// Server-wide registry. Pass around as `Arc<Registry>`.
pub struct Registry {
    sessions: DashMap<String, Session>,
    pending: DashMap<Uuid, std::sync::Arc<PendingTask>>,
}

impl Registry {
    pub fn new() -> Self {
        Self {
            sessions: DashMap::new(),
            pending: DashMap::new(),
        }
    }

    pub fn register(&self, session: Session) -> Option<Session> {
        self.sessions.insert(session.device_id.clone(), session)
    }

    pub fn unregister(&self, device_id: &str, session_id: Uuid) {
        self.sessions
            .remove_if(device_id, |_, s| s.session_id == session_id);
    }

    pub fn get(&self, device_id: &str) -> Option<Session> {
        self.sessions.get(device_id).map(|s| s.clone())
    }

    /// Find a provider session. If `preferred` is set, prefer that device;
    /// otherwise pick any provider.
    pub fn find_provider(&self, preferred: Option<&str>) -> Option<Session> {
        if let Some(id) = preferred {
            if let Some(s) = self.get(id) {
                if s.role == DeviceRole::Provider {
                    return Some(s);
                }
            }
        }
        self.sessions
            .iter()
            .find(|s| s.role == DeviceRole::Provider)
            .map(|s| s.clone())
    }

    pub fn register_pending(&self, task: std::sync::Arc<PendingTask>) {
        self.pending.insert(task.task_id, task);
    }

    pub fn take_pending(&self, task_id: Uuid) -> Option<std::sync::Arc<PendingTask>> {
        self.pending.remove(&task_id).map(|(_, v)| v)
    }

    /// Cancel all pending tasks belonging to a given consumer session.
    pub fn cancel_consumer_pending(&self, consumer_session_id: Uuid) {
        let to_remove: Vec<Uuid> = self
            .pending
            .iter()
            .filter(|e| e.value().consumer_session_id == consumer_session_id)
            .map(|e| *e.key())
            .collect();
        for id in to_remove {
            if let Some((_, p)) = self.pending.remove(&id) {
                if let Some(sender) = p.take_responder() {
                    let _ = sender.send(TaskResult {
                        task_id: id,
                        success: false,
                        payload: None,
                        error: Some("consumer disconnected".into()),
                        server_timing: None,
                    });
                }
            }
        }
    }

    pub fn session_count(&self) -> (usize, usize) {
        let mut consumers = 0;
        let mut providers = 0;
        for s in self.sessions.iter() {
            match s.role {
                DeviceRole::Consumer => consumers += 1,
                DeviceRole::Provider => providers += 1,
            }
        }
        (consumers, providers)
    }
}

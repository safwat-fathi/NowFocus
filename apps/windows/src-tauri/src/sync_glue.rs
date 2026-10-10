//! Connects the sync crate to the running app: the `Host` it reads and writes through (always under the app's
//! one state lock, and only for short steps, so the UI's 500 ms poll never waits on the network), and a
//! background loop that runs a pass every 30 seconds while signed in. Signed out, nothing here touches the
//! network.

use std::collections::HashSet;
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;

use chrono::{DateTime, Utc};
use now_focus_core::{FocusSession, Profile};
use now_focus_sync::api::{AccountSession, Api, ApiError, ApiPort, DeviceInfo};
use now_focus_sync::engine::{Engine, Host, SyncError};
use now_focus_sync::logic;
use now_focus_sync::model::{Local, Outgoing, Page, PushOutcome};
use now_focus_sync::session::{RemoteSession, SessionHost};
use tauri::{AppHandle, Manager};

use crate::commands::SharedState;
use crate::credentials::CredentialAuth;
use crate::dto::SyncStatusDto;
use crate::state::AppState;

const PASS_EVERY: Duration = Duration::from_secs(30);
/// A burst of edits (or a nudge from several commands) becomes one pass.
const DEBOUNCE: Duration = Duration::from_millis(1500);

pub struct AppHost {
    app: AppHandle,
}

impl AppHost {
    fn with<R>(&self, f: impl FnOnce(&mut AppState) -> R) -> Result<R, String> {
        let state = self.app.state::<SharedState>();
        let mut guard = state
            .lock()
            .map_err(|_| "app state lock poisoned".to_string())?;
        Ok(f(&mut guard))
    }
}

impl SessionHost for AppHost {
    fn local_session(&self) -> Result<Option<FocusSession>, String> {
        self.with(|s| s.sync_local_session())?
    }
    fn profile(&self, id: &str) -> Result<Option<Profile>, String> {
        self.with(|s| s.sync_profile(id))?
    }
    fn join_enabled(&self) -> Result<bool, String> {
        self.with(|s| s.join_remote())
    }
    fn join(&self, remote: &RemoteSession) -> Result<bool, String> {
        self.with(|s| s.sync_join(remote))?
    }
    fn end_local(&self, id: &str) -> Result<(), String> {
        self.with(|s| s.sync_end_local(id))?
    }
    fn extend_local(&self, id: &str, end: DateTime<Utc>) -> Result<(), String> {
        self.with(|s| s.sync_extend_local(id, end))?
    }
    fn device_name(&self) -> String {
        self.with(|s| s.device_name())
            .unwrap_or_else(|_| "Windows PC".to_string())
    }
    fn kv_get(&self, key: &str) -> Result<Option<String>, String> {
        self.with(|s| s.kv_get(key))?
    }
    fn kv_set(&self, key: &str, value: &str) -> Result<(), String> {
        self.with(|s| s.kv_set(key, value))?
    }
}

impl Host for AppHost {
    fn transact(&self, f: &mut dyn FnMut(Local) -> Local) -> Result<(), String> {
        self.with(|s| {
            let before = s.sync_read_local()?;
            let after = f(before.clone());
            s.sync_write_local(&before, &after)
        })?
    }
    fn referenced_policy_ids(&self) -> Result<HashSet<String>, String> {
        self.with(|s| s.sync_referenced_ids())
    }
}

#[derive(Clone)]
struct ApiHandle(Arc<Api>);

impl ApiPort for ApiHandle {
    fn pull(&self, cursor: i64, limit: u32) -> Result<Page, ApiError> {
        self.0.pull(cursor, limit)
    }
    fn push(&self, changes: &[Outgoing]) -> Result<Vec<PushOutcome>, ApiError> {
        self.0.push(changes)
    }
}

pub struct SyncController {
    app: AppHandle,
    api: Arc<Api>,
    engine: Engine<AppHost, ApiHandle>,
    nudge: Mutex<mpsc::Sender<()>>,
    status: Mutex<SyncStatusDto>,
}

static CONTROLLER: std::sync::OnceLock<Arc<SyncController>> = std::sync::OnceLock::new();

/// Something changed here that the other devices should hear about soon (a no-op when signed out).
pub fn nudge() {
    if let Some(c) = CONTROLLER.get() {
        c.nudge();
    }
}

/// Starts the background loop. The loop only does anything while a refresh token exists.
pub fn start(app: AppHandle) -> Arc<SyncController> {
    let base = std::env::var("NOWFOCUS_SYNC_URL")
        .unwrap_or_else(|_| "https://api.nowfocus.online".to_string());
    let user_agent = format!("NowFocus-Windows/{}", env!("CARGO_PKG_VERSION"));
    let api = Arc::new(Api::new(&base, &user_agent, Box::new(CredentialAuth)));
    let (tx, rx) = mpsc::channel::<()>();
    let controller = Arc::new(SyncController {
        app: app.clone(),
        engine: Engine::new(AppHost { app }, ApiHandle(api.clone())),
        api,
        nudge: Mutex::new(tx),
        status: Mutex::new(SyncStatusDto::default()),
    });
    controller.publish(|_| {});

    let worker = controller.clone();
    std::thread::spawn(move || loop {
        match rx.recv_timeout(PASS_EVERY) {
            Ok(()) => {
                std::thread::sleep(DEBOUNCE);
                while rx.try_recv().is_ok() {} // coalesce the burst
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
        worker.run_pass();
    });
    let _ = CONTROLLER.set(controller.clone());
    controller
}

impl SyncController {
    /// Something changed here that the other devices should hear about soon.
    pub fn nudge(&self) {
        if self.api.is_signed_in() {
            let _ = self.nudge.lock().unwrap().send(());
        }
    }

    /// Updates what the Devices screen shows (and keeps `join_remote` current).
    fn publish(&self, f: impl FnOnce(&mut SyncStatusDto)) {
        let mut status = self.status.lock().unwrap();
        status.signed_in = self.api.is_signed_in();
        status.email = self.api.stored().map(|a| a.email);
        f(&mut status);
        if let Ok(mut guard) = self.app.state::<SharedState>().lock() {
            status.join_remote = guard.join_remote();
            guard.set_sync_status(status.clone());
        }
    }

    fn run_pass(&self) {
        if !self.api.is_signed_in() {
            self.publish(|s| s.syncing = false);
            return;
        }
        self.publish(|s| s.syncing = true);
        let result = self.engine.sync_once();
        match result {
            Ok(report) => self.publish(|s| {
                s.syncing = false;
                s.last_synced_at = Some(Utc::now().to_rfc3339());
                s.problem = None;
                s.rejected = report.rejected;
            }),
            Err(SyncError::Api(ApiError::AuthExpired)) => {
                self.api.forget();
                self.publish(|s| {
                    s.syncing = false;
                    s.problem = Some("signedOut".to_string());
                });
            }
            Err(SyncError::Api(e)) => self.publish(|s| {
                s.syncing = false;
                s.problem = Some(match e {
                    ApiError::Network(_) => "offline".to_string(),
                    other => other.friendly(),
                });
            }),
            Err(SyncError::Host(m)) => self.publish(|s| {
                s.syncing = false;
                s.problem = Some(m);
            }),
        }
        crate::refresh_tray(); // a session may just have been joined or ended
    }

    /// Returns an error message for the user, or `Ok`.
    pub fn sign_in(&self, email: &str, password: &str) -> Result<(), String> {
        let session: AccountSession = self
            .api
            .login(email, password, &self.engine.host().device_name())
            .map_err(|e| e.friendly())?;
        // A different account than before starts clean; the same one resumes where it left off.
        self.engine.host().transact(&mut |mut l| {
            l.state = logic::link(&l.state, &session.user_id);
            l
        })?;
        self.publish(|s| s.problem = None);
        self.nudge();
        Ok(())
    }

    /// Asks the server to email a confirm link. Same outcome for a new and a taken address.
    pub fn create_account(&self, email: &str, password: &str) -> Result<(), String> {
        self.api
            .register(email, password, &self.engine.host().device_name())
            .map_err(|e| e.friendly())
    }

    /// Local data and the Commitment are never touched: signing out only forgets the tokens.
    pub fn sign_out(&self) {
        self.api.logout();
        self.publish(|s| {
            s.syncing = false;
            s.problem = None;
        });
    }

    /// Deletes the account and its server data; this PC keeps everything it has.
    pub fn delete_account(&self, password: &str) -> Result<(), String> {
        self.api
            .delete_account(password)
            .map_err(|e| e.friendly())?;
        self.engine.host().transact(&mut |mut l| {
            l.state = logic::unlink();
            l
        })?;
        self.publish(|s| {
            s.syncing = false;
            s.problem = None;
        });
        Ok(())
    }

    pub fn report_issue(&self, message: &str, contact: &str) -> Result<(), String> {
        let os = format!("windows {}", std::env::consts::ARCH);
        self.api
            .report_issue(message, contact, env!("CARGO_PKG_VERSION"), &os)
            .map_err(|e| e.friendly())
    }

    pub fn devices(&self) -> Result<Vec<DeviceInfo>, String> {
        self.api.devices().map_err(|e| e.friendly())
    }

    pub fn revoke_device(&self, id: &str) -> Result<(), String> {
        self.api.revoke_device(id).map_err(|e| e.friendly())
    }

    pub fn set_join_remote(&self, on: bool) -> Result<(), String> {
        self.app
            .state::<SharedState>()
            .lock()
            .map_err(|_| "app state lock poisoned".to_string())?
            .set_join_remote(on)?;
        self.publish(|_| {});
        self.nudge(); // turning it on picks up a session that is running now
        Ok(())
    }
}

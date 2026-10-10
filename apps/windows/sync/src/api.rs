//! HTTP client for services/api, blocking (the sync loop runs on its own thread). Access tokens live in memory
//! only; the refresh token goes through [`AuthStore`] (Windows Credential Manager in the app). Refresh is
//! single-flight, and the new refresh token is persisted BEFORE it is used, because refresh tokens are
//! single-use: losing one signs the device out.

use std::fmt;
use std::sync::Mutex;
use std::time::Duration;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::model::{Outgoing, Page, PushOutcome, ServerRecord};

#[derive(Debug, Clone)]
pub enum ApiError {
    /// Could not reach the server (offline, DNS, TLS, timeout). Always safe to retry later.
    Network(String),
    /// The server said no. `code` is the stable machine-readable one.
    Http {
        status: u16,
        code: Option<String>,
        message: String,
    },
    /// The refresh token is dead (device revoked or token lost): the user has to sign in again.
    AuthExpired,
}

impl fmt::Display for ApiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ApiError::Network(m) => write!(f, "Can't reach NowFocus ({m})"),
            ApiError::Http { message, .. } => write!(f, "{message}"),
            ApiError::AuthExpired => write!(f, "Signed out: this device's session ended"),
        }
    }
}

impl ApiError {
    /// What the Devices screen is told: a short code the UI translates (`err.*` in src/i18n), or the server's own
    /// message when it sent one we have no code for.
    pub fn friendly(&self) -> String {
        match self {
            ApiError::Network(_) => "net".into(),
            ApiError::AuthExpired => "sessionEnded".into(),
            ApiError::Http { code: Some(c), .. } if c == "invalid_credentials" => {
                "credentials".into()
            }
            ApiError::Http { code: Some(c), .. } if c == "wrong_password" => "wrongPassword".into(),
            ApiError::Http { status: 429, .. } => "rate".into(),
            ApiError::Http { status, .. } if *status >= 500 => "server".into(),
            ApiError::Http { message, .. } => message.clone(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StoredAuth {
    pub user_id: String,
    pub email: String,
    pub device_id: String,
    pub refresh_token: String,
}

pub trait AuthStore: Send + Sync {
    fn load(&self) -> Option<StoredAuth>;
    fn save(&self, auth: &StoredAuth) -> Result<(), String>;
    fn clear(&self);
}

#[derive(Debug, Clone)]
pub struct AccountSession {
    pub user_id: String,
    pub email: String,
    pub device_id: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct DeviceInfo {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub current: bool,
    pub revoked: bool,
}

/// What the sync engine needs from the network.
pub trait ApiPort {
    fn pull(&self, cursor: i64, limit: u32) -> Result<Page, ApiError>;
    fn push(&self, changes: &[Outgoing]) -> Result<Vec<PushOutcome>, ApiError>;
}

pub struct Api {
    base: String,
    user_agent: String,
    auth: Box<dyn AuthStore>,
    agent: ureq::Agent,
    access: Mutex<Option<String>>,
    refresh_lock: Mutex<()>,
}

fn credentials(email: &str, password: &str, device_name: &str) -> Value {
    json!({ "email": email.trim(), "password": password, "device": { "name": device_name, "platform": "windows" } })
}

fn s(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(Value::as_str).map(String::from)
}

fn time(v: &Value, key: &str) -> DateTime<Utc> {
    v.get(key)
        .and_then(Value::as_str)
        .and_then(|t| DateTime::parse_from_rfc3339(t).ok())
        .map(|t| t.with_timezone(&Utc))
        .unwrap_or_default()
}

pub fn record(o: &Value) -> ServerRecord {
    ServerRecord {
        typ: s(o, "type").unwrap_or_default(),
        id: s(o, "id").unwrap_or_default(),
        data: o.get("data").cloned().unwrap_or_else(|| json!({})),
        deleted: o.get("deleted").and_then(Value::as_bool).unwrap_or(false),
        revision: o.get("revision").and_then(Value::as_i64).unwrap_or(0),
        updated_at: time(o, "updatedAt"),
    }
}

impl Api {
    pub fn new(base_url: &str, user_agent: &str, auth: Box<dyn AuthStore>) -> Self {
        Self {
            base: base_url.trim_end_matches('/').to_string(),
            user_agent: user_agent.to_string(),
            auth,
            agent: ureq::AgentBuilder::new()
                .timeout_connect(Duration::from_secs(10))
                .timeout(Duration::from_secs(30))
                .build(),
            access: Mutex::new(None),
            refresh_lock: Mutex::new(()),
        }
    }

    pub fn is_signed_in(&self) -> bool {
        self.auth.load().is_some()
    }

    pub fn stored(&self) -> Option<StoredAuth> {
        self.auth.load()
    }

    // ---- account

    /// No session yet: the server emails a confirm link (the same answer for an address that already has an
    /// account, so this can't be used to probe), and the user signs in once they've confirmed.
    pub fn register(&self, email: &str, password: &str, device_name: &str) -> Result<(), ApiError> {
        let body = credentials(email, password, device_name);
        self.send("POST", "/v1/auth/register", Some(body), None)
            .map(|_| ())
    }

    pub fn login(
        &self,
        email: &str,
        password: &str,
        device_name: &str,
    ) -> Result<AccountSession, ApiError> {
        let body = credentials(email, password, device_name);
        let res = self.send("POST", "/v1/auth/login", Some(body), None)?;
        let user = res.get("user").cloned().unwrap_or_default();
        let stored = StoredAuth {
            user_id: s(&user, "id").unwrap_or_default(),
            email: s(&user, "email").unwrap_or_default(),
            device_id: res
                .get("device")
                .and_then(|d| s(d, "id"))
                .unwrap_or_default(),
            refresh_token: s(&res, "refreshToken").unwrap_or_default(),
        };
        self.auth.save(&stored).map_err(ApiError::Network)?;
        *self.access.lock().unwrap() = s(&res, "accessToken");
        Ok(AccountSession {
            user_id: stored.user_id,
            email: stored.email,
            device_id: stored.device_id,
        })
    }

    /// Best effort: whatever the network says, this device forgets its tokens.
    pub fn logout(&self) {
        let _ = self.authed("POST", "/v1/auth/logout", Some(json!({})));
        self.forget();
    }

    pub fn forget(&self) {
        *self.access.lock().unwrap() = None;
        self.auth.clear();
    }

    pub fn delete_account(&self, password: &str) -> Result<(), ApiError> {
        self.authed(
            "POST",
            "/v1/me/delete",
            Some(json!({ "password": password })),
        )?;
        self.forget();
        Ok(())
    }

    /// Anonymous and user-initiated: works signed out, so it never touches the tokens.
    pub fn report_issue(
        &self,
        message: &str,
        contact: &str,
        app_version: &str,
        os_version: &str,
    ) -> Result<(), ApiError> {
        self.send(
            "POST",
            "/v1/reports",
            Some(report_body(message, contact, app_version, os_version)),
            None,
        )
        .map(|_| ())
    }

    pub fn devices(&self) -> Result<Vec<DeviceInfo>, ApiError> {
        let res = self.authed("GET", "/v1/devices", None)?;
        Ok(res
            .as_array()
            .map(|a| {
                a.iter()
                    .map(|d| DeviceInfo {
                        id: s(d, "id").unwrap_or_default(),
                        name: s(d, "name").unwrap_or_default(),
                        platform: s(d, "platform").unwrap_or_default(),
                        current: d.get("current").and_then(Value::as_bool).unwrap_or(false),
                        revoked: d.get("revokedAt").is_some_and(|v| !v.is_null()),
                    })
                    .collect()
            })
            .unwrap_or_default())
    }

    pub fn revoke_device(&self, id: &str) -> Result<(), ApiError> {
        self.authed("DELETE", &format!("/v1/devices/{id}"), None)
            .map(|_| ())
    }

    // ---- plumbing

    /// An authenticated call: one transparent refresh on 401, then it gives up with `AuthExpired` or the server's error.
    fn authed(&self, method: &str, path: &str, body: Option<Value>) -> Result<Value, ApiError> {
        // Copy the token out first: a guard living in the `match` scrutinee would still be held in the `None`
        // arm, and `refresh` locks `access` again (a std Mutex isn't reentrant: deadlock).
        let current = self.access.lock().unwrap().clone();
        let token = match current {
            Some(t) => t,
            None => {
                self.refresh(None)?;
                self.access
                    .lock()
                    .unwrap()
                    .clone()
                    .ok_or(ApiError::AuthExpired)?
            }
        };
        match self.send(method, path, body.clone(), Some(&token)) {
            Err(ApiError::Http { status: 401, .. }) => {}
            other => return other,
        }
        self.refresh(Some(&token))?;
        let fresh = self
            .access
            .lock()
            .unwrap()
            .clone()
            .ok_or(ApiError::AuthExpired)?;
        match self.send(method, path, body, Some(&fresh)) {
            Err(ApiError::Http { status: 401, .. }) => {
                self.forget();
                Err(ApiError::AuthExpired)
            }
            other => other,
        }
    }

    /// `stale` is the access token that just failed: if someone else already replaced it, don't refresh again.
    fn refresh(&self, stale: Option<&str>) -> Result<(), ApiError> {
        let _guard = self.refresh_lock.lock().unwrap();
        if let (Some(stale), Some(current)) = (stale, self.access.lock().unwrap().as_deref()) {
            if stale != current {
                return Ok(());
            }
        }
        let stored = self.auth.load().ok_or(ApiError::AuthExpired)?;
        let res = match self.send(
            "POST",
            "/v1/auth/refresh",
            Some(json!({ "refreshToken": stored.refresh_token })),
            None,
        ) {
            Err(ApiError::Http { status: 401, .. }) => {
                self.forget();
                return Err(ApiError::AuthExpired);
            }
            other => other?,
        };
        // Persist the new single-use refresh token first.
        let next = StoredAuth {
            refresh_token: s(&res, "refreshToken").unwrap_or_default(),
            ..stored
        };
        self.auth.save(&next).map_err(ApiError::Network)?;
        *self.access.lock().unwrap() = s(&res, "accessToken");
        Ok(())
    }

    fn send(
        &self,
        method: &str,
        path: &str,
        body: Option<Value>,
        bearer: Option<&str>,
    ) -> Result<Value, ApiError> {
        let mut req = self
            .agent
            .request(method, &format!("{}{}", self.base, path))
            .set("User-Agent", &self.user_agent)
            .set("Accept", "application/json");
        if let Some(t) = bearer {
            req = req.set("Authorization", &format!("Bearer {t}"));
        }
        let result = match body {
            Some(b) => req.send_json(b),
            None => req.call(),
        };
        match result {
            Ok(resp) => {
                let text = resp.into_string().unwrap_or_default();
                Ok(if text.trim().is_empty() {
                    Value::Null
                } else {
                    serde_json::from_str(&text).unwrap_or(Value::Null)
                })
            }
            Err(ureq::Error::Status(status, resp)) => {
                let text = resp.into_string().unwrap_or_default();
                let o: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
                let message = o
                    .get("message")
                    .map(|m| match m {
                        Value::Array(a) => a
                            .iter()
                            .filter_map(Value::as_str)
                            .collect::<Vec<_>>()
                            .join("; "),
                        other => other.as_str().unwrap_or_default().to_string(),
                    })
                    .filter(|m| !m.is_empty())
                    .unwrap_or_else(|| format!("HTTP {status}"));
                Err(ApiError::Http {
                    status,
                    code: s(&o, "code"),
                    message,
                })
            }
            Err(ureq::Error::Transport(t)) => Err(ApiError::Network(t.to_string())),
        }
    }
}

fn report_body(message: &str, contact: &str, app_version: &str, os_version: &str) -> Value {
    let mut body = json!({
        "message": message.trim(),
        "platform": "windows",
        "appVersion": app_version,
        "osVersion": os_version,
    });
    if !contact.trim().is_empty() {
        body["contact"] = json!(contact.trim());
    }
    body
}

impl ApiPort for Api {
    fn pull(&self, cursor: i64, limit: u32) -> Result<Page, ApiError> {
        let res = self.authed(
            "GET",
            &format!("/v1/sync/pull?cursor={cursor}&limit={limit}"),
            None,
        )?;
        Ok(Page {
            changes: res
                .get("changes")
                .and_then(Value::as_array)
                .map(|a| a.iter().map(record).collect())
                .unwrap_or_default(),
            cursor: res.get("cursor").and_then(Value::as_i64).unwrap_or(cursor),
            has_more: res.get("hasMore").and_then(Value::as_bool).unwrap_or(false),
        })
    }

    fn push(&self, changes: &[Outgoing]) -> Result<Vec<PushOutcome>, ApiError> {
        let body = json!({
            "changes": changes.iter().map(|c| {
                let mut o = json!({
                    "type": c.typ, "id": c.id, "updatedAt": crate::wire::iso(c.updated_at),
                    "data": c.data.clone().unwrap_or_else(|| json!({})),
                });
                if c.deleted { o["deleted"] = json!(true); }
                o
            }).collect::<Vec<_>>()
        });
        let res = self.authed("POST", "/v1/sync/push", Some(body))?;
        Ok(res
            .get("results")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .map(|r| PushOutcome {
                        typ: s(r, "type").unwrap_or_default(),
                        id: s(r, "id").unwrap_or_default(),
                        status: s(r, "status").unwrap_or_else(|| "rejected".into()),
                        record: r.get("record").filter(|v| !v.is_null()).map(record),
                        code: s(r, "code"),
                    })
                    .collect()
            })
            .unwrap_or_default())
    }
}

#[cfg(test)]
mod report_tests {
    use super::report_body;

    #[test]
    fn report_body_trims_and_omits_a_blank_contact() {
        let b = report_body("  it crashes ", "  ", "0.4.2", "windows x86_64");
        assert_eq!(b["message"], "it crashes");
        assert_eq!(b["platform"], "windows");
        assert!(b.get("contact").is_none());
        assert_eq!(report_body("x", " a@b.co ", "1", "w")["contact"], "a@b.co");
    }
}

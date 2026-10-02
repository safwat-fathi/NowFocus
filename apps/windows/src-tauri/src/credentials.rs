//! Where the account's refresh token lives: Windows Credential Manager on Windows (never in the app's database
//! or a plain file), a process-lifetime stand-in elsewhere so the app can be built and run during development.

use now_focus_sync::api::{AuthStore, StoredAuth};

pub struct CredentialAuth;

#[cfg(windows)]
mod store {
    use keyring::Entry;

    fn entry() -> Result<Entry, String> {
        Entry::new("NowFocus", "account").map_err(|e| e.to_string())
    }

    pub fn load() -> Option<String> {
        entry().ok()?.get_password().ok()
    }

    pub fn save(text: &str) -> Result<(), String> {
        entry()?.set_password(text).map_err(|e| e.to_string())
    }

    pub fn clear() {
        if let Ok(e) = entry() {
            let _ = e.delete_credential();
        }
    }
}

#[cfg(not(windows))]
mod store {
    use std::sync::Mutex;

    static MEMORY: Mutex<Option<String>> = Mutex::new(None);

    pub fn load() -> Option<String> {
        MEMORY.lock().unwrap().clone()
    }

    pub fn save(text: &str) -> Result<(), String> {
        *MEMORY.lock().unwrap() = Some(text.to_string());
        Ok(())
    }

    pub fn clear() {
        *MEMORY.lock().unwrap() = None;
    }
}

impl AuthStore for CredentialAuth {
    fn load(&self) -> Option<StoredAuth> {
        serde_json::from_str(&store::load()?).ok()
    }

    fn save(&self, auth: &StoredAuth) -> Result<(), String> {
        store::save(&serde_json::to_string(auth).map_err(|e| e.to_string())?)
    }

    fn clear(&self) {
        store::clear();
    }
}

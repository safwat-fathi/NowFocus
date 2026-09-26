//! Windows-only. Mirrors `DaemonRegistrationStatus.swift`'s job — tell the
//! UI when the privileged background service is silently unavailable
//! instead of leaving it to a console nobody reads — but a narrower scope.
//!
//! macOS's `SMAppService.daemon(...).register()` is a single call a normal,
//! unelevated app can make; the OS handles prompting for approval. Windows
//! has no equivalent for a normal desktop process: installing a service
//! (`SC_MANAGER_CREATE_SERVICE`) needs an elevated token, which means
//! either running the whole app as admin (bad UX, and a bigger attack
//! surface for something that's mostly just a UI) or a separate elevated
//! install step. That install step belongs in the MSI/EXE installer
//! (Phase "Packaging"), which already runs elevated once at setup time —
//! not something to bolt onto every app launch here. So this module only
//! checks status and starts an installed-but-stopped service; it reports
//! `NotInstalled` rather than attempting to self-elevate and install.
//!
//! *** UNVERIFIED — no Windows machine in this build environment ***

use windows_service::service::{ServiceAccess, ServiceState};
use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};

pub const SERVICE_NAME: &str = "NowFocusService";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RegistrationState {
    Running,
    /// The installer hasn't registered the service (or it was removed) —
    /// the user needs to repair/reinstall, not something this app can fix
    /// at runtime without an elevation prompt on every launch.
    NotInstalled,
    Failed(String),
}

pub fn check_and_start() -> RegistrationState {
    let manager = match ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)
    {
        Ok(m) => m,
        Err(e) => {
            return RegistrationState::Failed(format!(
                "couldn't reach the Service Control Manager: {e}"
            ))
        }
    };

    let service = match manager.open_service(
        SERVICE_NAME,
        ServiceAccess::QUERY_STATUS | ServiceAccess::START,
    ) {
        Ok(s) => s,
        Err(_) => return RegistrationState::NotInstalled,
    };

    match service.query_status() {
        Ok(status) if status.current_state == ServiceState::Running => RegistrationState::Running,
        Ok(_stopped) => match service.start::<&str>(&[]) {
            Ok(()) => RegistrationState::Running,
            Err(e) => {
                RegistrationState::Failed(format!("service is installed but wouldn't start: {e}"))
            }
        },
        Err(e) => RegistrationState::Failed(format!("couldn't query service status: {e}")),
    }
}

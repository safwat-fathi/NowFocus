#[cfg(windows)]
mod network_enforcer;
#[cfg(windows)]
mod pipe_server;

#[cfg(windows)]
fn main() -> windows_service::Result<()> {
    windows_impl::run()
}

#[cfg(not(windows))]
fn main() {
    // This binary only does anything on Windows (Win32 Service Control
    // Manager integration, hosts-file editing, named pipes). It still needs
    // to compile everywhere so `cargo build --workspace` on any host proves
    // the platform-neutral parts of the workspace wire together.
    eprintln!("now-focus-service only runs on Windows.");
}

/// *** UNVERIFIED — no Windows machine in this build environment ***
/// Written against the documented `windows-service` 0.7 API from memory,
/// not compiled on Windows. Confirm on a real Windows box (or the planned
/// `windows-latest` CI job) before relying on any of this: service
/// registration, the control-event handler, and status reporting are all
/// exactly the kind of API-shape mistakes that only show up when the SCM
/// actually calls into this code.
#[cfg(windows)]
mod windows_impl {
    use std::ffi::OsString;
    use std::time::Duration;

    use windows_service::service::{
        ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus,
        ServiceType,
    };
    use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
    use windows_service::{define_windows_service, service_dispatcher};

    /// Must match the name used when the service is installed (see the
    /// Tauri app's `service_registration` module, Phase 3's counterpart to
    /// macOS's DaemonRegistrationStatus.swift).
    const SERVICE_NAME: &str = "NowFocusService";
    const SERVICE_TYPE: ServiceType = ServiceType::OWN_PROCESS;

    define_windows_service!(ffi_service_main, service_main);

    pub fn run() -> windows_service::Result<()> {
        service_dispatcher::start(SERVICE_NAME, ffi_service_main)
    }

    fn service_main(_args: Vec<OsString>) {
        if let Err(e) = run_service() {
            eprintln!("now-focus-service failed: {e}");
        }
    }

    fn run_service() -> windows_service::Result<()> {
        let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);

        let event_handler = move |control_event| -> ServiceControlHandlerResult {
            match control_event {
                ServiceControl::Stop | ServiceControl::Shutdown => {
                    let _ = shutdown_tx.send(true);
                    ServiceControlHandlerResult::NoError
                }
                ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
                _ => ServiceControlHandlerResult::NotImplemented,
            }
        };

        let status_handle = service_control_handler::register(SERVICE_NAME, event_handler)?;
        report_running(&status_handle)?;

        let rt =
            tokio::runtime::Runtime::new().expect("failed to start the service's tokio runtime");
        rt.block_on(crate::pipe_server::run(shutdown_rx));

        report_stopped(&status_handle)?;
        Ok(())
    }

    fn report_running(
        handle: &windows_service::service_control_handler::ServiceStatusHandle,
    ) -> windows_service::Result<()> {
        handle.set_service_status(ServiceStatus {
            service_type: SERVICE_TYPE,
            current_state: ServiceState::Running,
            controls_accepted: ServiceControlAccept::STOP,
            exit_code: ServiceExitCode::Win32(0),
            checkpoint: 0,
            wait_hint: Duration::default(),
            process_id: None,
        })
    }

    fn report_stopped(
        handle: &windows_service::service_control_handler::ServiceStatusHandle,
    ) -> windows_service::Result<()> {
        handle.set_service_status(ServiceStatus {
            service_type: SERVICE_TYPE,
            current_state: ServiceState::Stopped,
            controls_accepted: ServiceControlAccept::empty(),
            exit_code: ServiceExitCode::Win32(0),
            checkpoint: 0,
            wait_hint: Duration::default(),
            process_id: None,
        })
    }
}

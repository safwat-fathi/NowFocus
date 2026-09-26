use now_focus_core::BlockPolicy;

use crate::dto::{DeviceLayerDto, HealthDto};

/// What the app needs from whatever actually applies a policy to the OS.
/// The real implementation (hosts-file editing over a named pipe to the
/// Windows service, Phase 3) is `#[cfg(windows)]`-gated; `NoopEnforcer`
/// below is what runs everywhere else so the UI can be built and checked
/// against the design without a Windows machine.
pub trait Enforcer: Send {
    fn apply(&mut self, policy: &BlockPolicy) -> Result<(), String>;
    fn clear(&mut self) -> Result<(), String>;
    fn health(&self) -> HealthDto;
}

/// Dev-mode stand-in. Deliberately reports `"unknown"`, not `"active"` — it
/// isn't enforcing anything, and claiming otherwise is exactly the "silently
/// tell users they're protected" mistake the architecture doc warns against
/// (focus_app_technical_architecture.md §35). Phase 3/4 replace this with a
/// real `WindowsEnforcer` behind `#[cfg(windows)]`.
pub struct NoopEnforcer {
    applied: bool,
}

impl NoopEnforcer {
    pub fn new() -> Self {
        Self { applied: false }
    }
}

impl Enforcer for NoopEnforcer {
    fn apply(&mut self, policy: &BlockPolicy) -> Result<(), String> {
        eprintln!(
            "[noop-enforcer] would apply policy '{}' ({} domain(s), {} app(s)) — no real enforcement on this platform",
            policy.name,
            policy.domains.len(),
            policy.applications.len()
        );
        self.applied = true;
        Ok(())
    }

    fn clear(&mut self) -> Result<(), String> {
        eprintln!("[noop-enforcer] would clear the active policy");
        self.applied = false;
        Ok(())
    }

    fn health(&self) -> HealthDto {
        let state = if self.applied { "on" } else { "off" };
        HealthDto {
            website_blocking: "unknown".to_string(),
            app_blocking: "unknown".to_string(),
            layers: vec![
                DeviceLayerDto {
                    name: "NowFocus Service".to_string(),
                    state: "dev mode".to_string(),
                    healthy: false,
                },
                DeviceLayerDto {
                    name: "DNS filter".to_string(),
                    state: state.to_string(),
                    healthy: false,
                },
                DeviceLayerDto {
                    name: "Browser extensions".to_string(),
                    state: "not built yet".to_string(),
                    healthy: false,
                },
            ],
        }
    }
}

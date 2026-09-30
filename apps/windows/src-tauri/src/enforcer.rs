use now_focus_core::BlockPolicy;
use now_focus_ipc::CommitmentStatusWire;

use crate::dto::{DeviceLayerDto, HealthDto};

/// What the app needs from whatever actually applies a policy to the OS.
/// The real implementation (hosts-file editing over a named pipe to the
/// Windows service, Phase 3) is `#[cfg(windows)]`-gated; `NoopEnforcer`
/// below is what runs everywhere else so the UI can be built and checked
/// against the design without a Windows machine.
///
/// Commitment (the 14-day always-blocked shield) is owned by the service, not
/// the app — the app only relays these three calls. `clear_commitment` can be
/// refused by the service when past the grace window; that refusal comes back
/// as `Err`.
pub trait Enforcer: Send {
    fn apply(&mut self, policy: &BlockPolicy) -> Result<(), String>;
    fn clear(&mut self) -> Result<(), String>;
    fn health(&self) -> HealthDto;
    fn apply_commitment(&mut self, domains: &[String]) -> Result<(), String>;
    fn clear_commitment(&mut self) -> Result<(), String>;
    fn commitment_status(&self) -> Option<CommitmentStatusWire>;
}

/// Dev-mode stand-in. Deliberately reports `"unknown"`, not `"active"` — it
/// isn't enforcing anything, and claiming otherwise is exactly the "silently
/// tell users they're protected" mistake the architecture doc warns against
/// (focus_app_technical_architecture.md §35). Phase 3/4 replace this with a
/// real `WindowsEnforcer` behind `#[cfg(windows)]`.
#[cfg_attr(windows, allow(dead_code))] // only constructed off-Windows; the real enforcer is used there
pub struct NoopEnforcer {
    applied: bool,
    /// Dev-mode only: a synthesized commitment so the Commitment screen's
    /// locked/grace states can be previewed without the Windows service. Not
    /// time-anchored — real anchoring lives in the service's commitment_store.
    commitment: Option<CommitmentStatusWire>,
}

#[cfg_attr(windows, allow(dead_code))]
impl NoopEnforcer {
    pub fn new() -> Self {
        Self {
            applied: false,
            commitment: None,
        }
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

    fn apply_commitment(&mut self, domains: &[String]) -> Result<(), String> {
        eprintln!(
            "[noop-enforcer] would commit to {} domain(s)",
            domains.len()
        );
        // Synthesize a 14-day commitment, still in its grace window, so the
        // dev UI shows the locked state. No real time anchoring here.
        let end = chrono::Utc::now()
            + chrono::Duration::seconds(now_focus_core::commitment::DURATION_SECS);
        self.commitment = Some(CommitmentStatusWire {
            domains: domains.to_vec(),
            end_at: end.to_rfc3339(),
            can_cancel_now: true,
            remaining_secs: now_focus_core::commitment::DURATION_SECS,
        });
        Ok(())
    }

    fn clear_commitment(&mut self) -> Result<(), String> {
        eprintln!("[noop-enforcer] would clear the commitment (dev mode: always allowed)");
        self.commitment = None;
        Ok(())
    }

    fn commitment_status(&self) -> Option<CommitmentStatusWire> {
        self.commitment.clone()
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

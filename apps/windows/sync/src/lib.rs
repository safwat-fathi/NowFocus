//! Optional account sync for the Windows app: profiles, bedtime and cross-device sessions.
//! See services/api/WIRE_FORMAT.md. A signed-out device never makes a network call.

pub mod api;
pub mod engine;
pub mod logic;
pub mod model;
pub mod session;
pub mod wire;

//! Windows-only. Mirrors `DaemonClient.swift`'s shape (lazy connect,
//! apply/clear/health, one clear error path) but connects fresh per call
//! instead of holding a persistent connection — the protocol is stateless
//! (see now_focus_ipc's doc comment on why the service isn't session-aware),
//! and the service's pipe_server already accepts one client at a time in a
//! loop, so there's no persistent-connection state worth caching here.
//!
//! *** UNVERIFIED — no Windows machine in this build environment ***, same
//! caveat as service::pipe_server.

use std::io;
use std::time::Duration;

use now_focus_core::BlockPolicy;
use now_focus_ipc::{Request, Response, PIPE_NAME};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::net::windows::named_pipe::{ClientOptions, NamedPipeClient};

use crate::dto::{DeviceLayerDto, HealthDto};
use crate::enforcer::Enforcer;

const CONNECT_DEADLINE: Duration = Duration::from_millis(1500);
const RETRY_DELAY: Duration = Duration::from_millis(50);

pub struct WindowsServiceEnforcer;

impl WindowsServiceEnforcer {
    pub fn new() -> Self {
        Self
    }

    fn call(&self, request: &Request) -> io::Result<Response> {
        tauri::async_runtime::block_on(async {
            let client = connect_with_retry().await?;
            let (read_half, mut write_half) = tokio::io::split(client);
            let mut reader = BufReader::new(read_half);
            write_message(&mut write_half, request).await?;
            read_message(&mut reader).await
        })
    }
}

/// A named pipe client `open()` either succeeds immediately or fails with
/// "pipe busy" if the server hasn't posted a listening instance yet (this
/// can happen right after the service starts, or between this client's
/// previous call finishing and the server looping back to accept again) —
/// that's a normal race, not an error, so retry briefly rather than
/// surfacing it as "the service is down."
async fn connect_with_retry() -> io::Result<NamedPipeClient> {
    let deadline = tokio::time::Instant::now() + CONNECT_DEADLINE;
    loop {
        match ClientOptions::new().open(PIPE_NAME) {
            Ok(client) => return Ok(client),
            Err(e)
                if e.raw_os_error() == Some(ERROR_PIPE_BUSY)
                    && tokio::time::Instant::now() < deadline =>
            {
                tokio::time::sleep(RETRY_DELAY).await;
            }
            Err(e) => return Err(e),
        }
    }
}

const ERROR_PIPE_BUSY: i32 = 231;

impl Enforcer for WindowsServiceEnforcer {
    fn apply(&mut self, policy: &BlockPolicy) -> Result<(), String> {
        let policy_json = serde_json::to_string(policy).map_err(|e| e.to_string())?;
        match self.call(&Request::ApplyPolicy { policy_json }) {
            Ok(Response::Ack) => Ok(()),
            Ok(Response::Error { message }) => Err(message),
            Ok(other) => Err(format!("unexpected reply from NowFocusService: {other:?}")),
            Err(e) => Err(format!("couldn't reach NowFocusService: {e}")),
        }
    }

    fn clear(&mut self) -> Result<(), String> {
        match self.call(&Request::ClearPolicy) {
            Ok(Response::Ack) => Ok(()),
            Ok(Response::Error { message }) => Err(message),
            Ok(other) => Err(format!("unexpected reply from NowFocusService: {other:?}")),
            Err(e) => Err(format!("couldn't reach NowFocusService: {e}")),
        }
    }

    /// A live ping every call, not a cached flag — a stale "last known good"
    /// status is exactly the "silently tell users they're protected"
    /// mistake the architecture doc warns against.
    fn health(&self) -> HealthDto {
        let running = matches!(self.call(&Request::Ping), Ok(Response::Pong));
        HealthDto {
            website_blocking: if running { "active" } else { "unavailable" }.to_string(),
            app_blocking: "unknown".to_string(), // Phase 4: Win32 foreground hook, not this service.
            layers: vec![
                DeviceLayerDto {
                    name: "NowFocus Service".to_string(),
                    state: if running { "Running" } else { "Not responding" }.to_string(),
                    healthy: running,
                },
                DeviceLayerDto {
                    name: "DNS filter".to_string(),
                    state: if running { "On" } else { "Off" }.to_string(),
                    healthy: running,
                },
                DeviceLayerDto {
                    name: "Browser extensions".to_string(),
                    state: "Not installed".to_string(),
                    healthy: false,
                },
            ],
        }
    }
}

async fn write_message<W: AsyncWrite + Unpin, T: serde::Serialize>(
    w: &mut W,
    msg: &T,
) -> io::Result<()> {
    let json =
        serde_json::to_string(msg).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    w.write_all(json.as_bytes()).await?;
    w.write_all(b"\n").await?;
    w.flush().await
}

async fn read_message<R: AsyncRead + Unpin, T: serde::de::DeserializeOwned>(
    r: &mut BufReader<R>,
) -> io::Result<T> {
    let mut line = String::new();
    let n = r.read_line(&mut line).await?;
    if n == 0 {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "pipe closed before a full message arrived",
        ));
    }
    serde_json::from_str(line.trim_end()).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
}

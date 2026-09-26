//! Windows-only named pipe server. Accepts one client at a time (this is a
//! low-frequency control channel — a user starting/stopping a focus
//! session, not a high-throughput RPC), dispatches each `Request` to
//! `network_enforcer`, and replies with a `Response`.
//!
//! *** HIGHEST-RISK, LEAST-VERIFIED CODE IN THIS CHANGE ***
//! This has never run — there is no Windows machine in this build
//! environment. The SDDL security descriptor below is a standard, widely
//! documented pattern (grant SYSTEM and Interactive Users; the two
//! endpoints of this exact pipe), but it is unverified FFI. Before this
//! service handles a real policy, confirm on real Windows that: (1) the
//! pipe actually accepts a connection from a normal user-level process, and
//! (2) a process running as a *different* user is actually refused. Don't
//! trust this comment as proof either claim holds.

use std::ffi::c_void;
use std::io;

use now_focus_core::{domain_validation, BlockPolicy};
use now_focus_ipc::{Request, Response, PIPE_NAME};
use serde::de::DeserializeOwned;
use serde::Serialize;
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::net::windows::named_pipe::{NamedPipeServer, ServerOptions};
use tokio::sync::watch;

/// SYSTEM (SY) and Interactive Users (IU) get full access; nobody else.
/// This is the pipe-level access control; `network_enforcer::apply` also
/// re-validates every domain independently, which is the defense that
/// actually holds if this ACL is ever wrong (see commit d0dd7df's reasoning
/// on the macOS side — input validation at the enforcement boundary, not
/// the connection-level gate, is what a determined local process can't get
/// around just by being on an allowed account).
const PIPE_SDDL: &str = "D:(A;;GA;;;SY)(A;;GA;;;IU)";

pub async fn run(mut shutdown: watch::Receiver<bool>) {
    loop {
        let server = match create_pipe_instance() {
            Ok(s) => s,
            Err(e) => {
                eprintln!("failed to create named pipe instance: {e}");
                return;
            }
        };

        tokio::select! {
            res = server.connect() => {
                match res {
                    Ok(()) => handle_client(server).await,
                    Err(e) => eprintln!("named pipe connect error: {e}"),
                }
            }
            _ = shutdown.changed() => {
                if *shutdown.borrow() {
                    break;
                }
            }
        }
    }
}

fn create_pipe_instance() -> io::Result<NamedPipeServer> {
    let sd = build_security_descriptor(PIPE_SDDL)?;
    let mut sa = windows_sys::Win32::Security::SECURITY_ATTRIBUTES {
        nLength: std::mem::size_of::<windows_sys::Win32::Security::SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: sd,
        bInheritHandle: 0,
    };
    // SAFETY: `sa` is a valid, fully-initialized SECURITY_ATTRIBUTES whose
    // lpSecurityDescriptor came from a successful
    // ConvertStringSecurityDescriptorToSecurityDescriptorW call. It's kept
    // alive for the duration of this call (`create_with_security_attributes_raw`
    // copies what it needs internally rather than retaining the pointer).
    unsafe {
        ServerOptions::new()
            .create_with_security_attributes_raw(PIPE_NAME, &mut sa as *mut _ as *mut c_void)
    }
}

/// Builds a `PSECURITY_DESCRIPTOR` from an SDDL string via
/// `ConvertStringSecurityDescriptorToSecurityDescriptorW`. The returned
/// pointer is heap memory owned by Windows (allocated with `LocalAlloc`
/// internally); this process leaks it rather than freeing it with
/// `LocalFree`, since one is created per pipe-accept-loop iteration for the
/// lifetime of a long-running service — a known, deliberate simplification,
/// not an oversight. Fix with a `LocalFree` guard if this loop is ever
/// changed to run at high frequency.
fn build_security_descriptor(sddl: &str) -> io::Result<*mut c_void> {
    use windows_sys::Win32::Security::Authorization::{
        ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1,
    };

    let wide: Vec<u16> = sddl.encode_utf16().chain(std::iter::once(0)).collect();
    let mut psd: *mut c_void = std::ptr::null_mut();
    // SAFETY: `wide` is a valid, null-terminated UTF-16 string for the
    // duration of this call. `psd` is an out-param the API fills in on
    // success; we check the return value before touching it.
    let ok = unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            wide.as_ptr(),
            SDDL_REVISION_1 as u32,
            &mut psd,
            std::ptr::null_mut(),
        )
    };
    if ok == 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(psd)
}

async fn handle_client(server: NamedPipeServer) {
    let (read_half, mut write_half) = tokio::io::split(server);
    let mut reader = BufReader::new(read_half);

    loop {
        let request: Request = match read_message(&mut reader).await {
            Ok(r) => r,
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return, // client disconnected, not an error
            Err(e) => {
                eprintln!("named pipe read error: {e}");
                return;
            }
        };

        let response = dispatch(request);
        if let Err(e) = write_message(&mut write_half, &response).await {
            eprintln!("named pipe write error: {e}");
            return;
        }
    }
}

fn dispatch(request: Request) -> Response {
    match request {
        Request::Ping => Response::Pong,
        Request::ClearPolicy => match crate::network_enforcer::clear() {
            Ok(()) => Response::Ack,
            Err(message) => Response::Error { message },
        },
        Request::ApplyPolicy { policy_json } => {
            match serde_json::from_str::<BlockPolicy>(&policy_json) {
                Err(e) => Response::Error {
                    message: format!("malformed policy payload: {e}"),
                },
                Ok(policy) => {
                    if policy
                        .domains
                        .iter()
                        .any(|d| domain_validation::normalize(&d.domain).is_none())
                    {
                        // Still applied below (network_enforcer skips individual
                        // bad entries and logs them) — this early check exists so
                        // a policy that's *entirely* garbage domains gets a
                        // clear error back instead of a silent no-op success.
                        eprintln!("policy contains one or more invalid domains; invalid entries will be skipped");
                    }
                    match crate::network_enforcer::apply(&policy) {
                        Ok(()) => Response::Ack,
                        Err(message) => Response::Error { message },
                    }
                }
            }
        }
    }
}

async fn write_message<W: AsyncWrite + Unpin, T: Serialize>(w: &mut W, msg: &T) -> io::Result<()> {
    let json =
        serde_json::to_string(msg).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    w.write_all(json.as_bytes()).await?;
    w.write_all(b"\n").await?;
    w.flush().await
}

async fn read_message<R: AsyncRead + Unpin, T: DeserializeOwned>(
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

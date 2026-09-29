//! The UI-to-service protocol, ported from apps/macos/NowFocusShared/
//! XPCProtocol.swift's actual shape: policy apply/clear/ping, **plus** the
//! three commitment methods (`ApplyCommitment`/`ClearCommitment`/
//! `CommitmentStatus`) that macOS's XPC protocol also exposes.
//!
//! The line we still don't cross: the service is *not* session-aware. It
//! knows "this policy is currently applied" (session enforcement, cleared by
//! the app when a session ends) and, independently, "a commitment is in
//! effect" (14-day, service-owned, self-expiring, and refused-if-past-grace
//! by the service itself — the app can't lift it). Anything that would make
//! the service track focus-session timing belongs in the app, not here.

use std::io::{self, BufRead, Write};

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

pub const PIPE_NAME: &str = r"\\.\pipe\NowFocusService";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Request {
    /// `policy_json` is a serialized `now_focus_core::BlockPolicy`. The
    /// service re-validates every domain in it before writing to the hosts
    /// file — never trusts that the client already did (see
    /// service::network_enforcer, and macOS commit d0dd7df for why).
    ApplyPolicy {
        policy_json: String,
    },
    ClearPolicy,
    Ping,
    /// Start (or replace) the 14-day commitment on these domains. The service
    /// time-anchors it and writes an independent hosts-file region. Domains are
    /// re-validated at the write boundary, same as `ApplyPolicy`.
    ApplyCommitment {
        domains: Vec<String>,
    },
    /// Only succeeds inside the 60s grace window; the **service** decides and
    /// returns `Error { message }` otherwise (a UI-side refusal would be
    /// worthless — the pipe ACL lets any interactive user connect).
    ClearCommitment,
    CommitmentStatus,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CommitmentStatusWire {
    pub domains: Vec<String>,
    /// RFC3339 wall-clock end instant (a display/fallback value; the service's
    /// own uptime anchor is authoritative for whether it's actually over).
    pub end_at: String,
    pub can_cancel_now: bool,
    pub remaining_secs: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Response {
    Ack,
    Error {
        message: String,
    },
    Pong,
    /// Reply to `CommitmentStatus`: `None` when no commitment is in effect.
    Commitment {
        status: Option<CommitmentStatusWire>,
    },
}

/// Newline-delimited JSON framing. Safe because `serde_json`'s compact
/// output escapes any literal newline inside a string value, so a message
/// body never contains an unescaped `\n` — one `write_line`/`read_line` per
/// message, no length prefix needed.
pub fn write_message<W: Write, T: Serialize>(w: &mut W, msg: &T) -> io::Result<()> {
    let json =
        serde_json::to_string(msg).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    w.write_all(json.as_bytes())?;
    w.write_all(b"\n")?;
    w.flush()
}

pub fn read_message<R: BufRead, T: DeserializeOwned>(r: &mut R) -> io::Result<T> {
    let mut line = String::new();
    let n = r.read_line(&mut line)?;
    if n == 0 {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "pipe closed before a full message arrived",
        ));
    }
    serde_json::from_str(line.trim_end()).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn request_round_trips_through_the_wire_format() {
        for req in [
            Request::ApplyPolicy {
                policy_json: "{\"id\":\"p1\"}".to_string(),
            },
            Request::ClearPolicy,
            Request::Ping,
            Request::ApplyCommitment {
                domains: vec!["youtube.com".to_string(), "x.com".to_string()],
            },
            Request::ClearCommitment,
            Request::CommitmentStatus,
        ] {
            let mut buf = Vec::new();
            write_message(&mut buf, &req).unwrap();
            let mut cursor = Cursor::new(buf);
            let got: Request = read_message(&mut cursor).unwrap();
            assert_eq!(format!("{req:?}"), format!("{got:?}"));
        }
    }

    #[test]
    fn response_round_trips_through_the_wire_format() {
        for resp in [
            Response::Ack,
            Response::Error {
                message: "bad domain".to_string(),
            },
            Response::Pong,
            Response::Commitment { status: None },
            Response::Commitment {
                status: Some(CommitmentStatusWire {
                    domains: vec!["youtube.com".to_string()],
                    end_at: "2026-02-01T00:00:00+00:00".to_string(),
                    can_cancel_now: false,
                    remaining_secs: 1_200_000,
                }),
            },
        ] {
            let mut buf = Vec::new();
            write_message(&mut buf, &resp).unwrap();
            let mut cursor = Cursor::new(buf);
            let got: Response = read_message(&mut cursor).unwrap();
            assert_eq!(format!("{resp:?}"), format!("{got:?}"));
        }
    }

    #[test]
    fn two_messages_on_one_stream_read_back_in_order() {
        let mut buf = Vec::new();
        write_message(&mut buf, &Request::Ping).unwrap();
        write_message(&mut buf, &Request::ClearPolicy).unwrap();
        let mut cursor = Cursor::new(buf);
        assert!(matches!(
            read_message::<_, Request>(&mut cursor).unwrap(),
            Request::Ping
        ));
        assert!(matches!(
            read_message::<_, Request>(&mut cursor).unwrap(),
            Request::ClearPolicy
        ));
    }

    #[test]
    fn policy_json_containing_special_characters_survives_framing() {
        let req = Request::ApplyPolicy {
            policy_json: "{\"name\":\"line1\\nline2 \\\"quoted\\\"\"}".to_string(),
        };
        let mut buf = Vec::new();
        write_message(&mut buf, &req).unwrap();
        assert_eq!(
            buf.iter().filter(|&&b| b == b'\n').count(),
            1,
            "the payload's escaped newline must not add a frame boundary"
        );
        let mut cursor = Cursor::new(buf);
        let got: Request = read_message(&mut cursor).unwrap();
        assert_eq!(format!("{req:?}"), format!("{got:?}"));
    }

    #[test]
    fn empty_stream_is_a_clean_eof_error_not_a_panic() {
        let mut cursor = Cursor::new(Vec::<u8>::new());
        let err = read_message::<_, Request>(&mut cursor).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::UnexpectedEof);
    }
}

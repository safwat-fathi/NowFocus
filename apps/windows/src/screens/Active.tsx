import { api, endNormalAfterAsking } from "../lib/api";
import type { AppState } from "../types";

export function Active({
  state,
  onState,
  onBackToFocus,
}: {
  state: AppState;
  onState: (s: AppState) => void;
  onBackToFocus: () => void;
}) {
  const session = state.session;
  if (!session) {
    // The session ended (completed/cancelled) since this screen opened —
    // recovery already ran server-side on the last poll. Show completion.
    return (
      <div className="active-screen">
        <div className="done-panel">
          <h1 className="done-panel__title">Done. That was all yours.</h1>
          <p className="done-panel__body">This PC is back to normal.</p>
          <button className="btn" onClick={onBackToFocus} style={{ background: "var(--color-text)", color: "var(--color-bg)", minHeight: 56, minWidth: 260, justifyContent: "space-between", fontSize: 16, marginTop: 20, alignSelf: "flex-start" }}>
            Back to Focus
          </button>
        </div>
      </div>
    );
  }

  async function endEarly() {
    if (session!.mode === "normal") {
      const ended = await endNormalAfterAsking();
      if (ended) onState(ended);
    } else {
      onState(await api.beginUnlock());
    }
  }

  const timerSize = session.remainingLabel.length > 5 ? "168px" : "220px";

  return (
    <div className="active-screen">
      <div className="active-screen__header">
        <span>{session.profileName}</span>
        <span style={{ fontSize: 12, letterSpacing: "0.08em", textTransform: "uppercase" }}>{session.mode} mode</span>
      </div>
      <div className="active-grid">
        <div className="active-timer-col">
          <div style={{ fontSize: 17, fontWeight: 600, marginTop: 48 }}>You're in it. Keep going.</div>
          <div className="active-timer" style={{ fontSize: timerSize }}>{session.remainingLabel}</div>
          <div className="progress-track progress-track--thick">
            <div className="progress-track__fill" style={{ width: `${session.progressPct}%` }} />
          </div>
          <div style={{ display: "flex", justifyContent: "space-between", fontSize: 14, fontWeight: 600, marginTop: 10 }}>
            <span>Started {new Date(session.startAt).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })}</span>
            <span>Ends {new Date(session.endAt).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })}</span>
          </div>
        </div>
        <div className="active-side">
          <div className="active-side-list">
            <div className="active-side-row">
              <span className="active-side-row__label">This PC</span>
              <span>Protected</span>
            </div>
            <div className="active-side-row">
              <span className="active-side-row__label">Distractions turned away</span>
              <span>{state.stats.blockAttemptsToday}</span>
            </div>
          </div>
          {session.paused && (
            <p style={{ fontSize: 14, fontWeight: 600, margin: "14px 0 0" }}>Cheat day: blocking is paused until midnight.</p>
          )}
          <p style={{ fontSize: 13, color: "var(--color-neutral-800)", margin: "14px 0 0" }}>
            Blocked sites show “can’t be reached” until the session ends. That’s NowFocus, not your internet.
          </p>
          <div className="active-actions">
            <button
              className="btn"
              onClick={endEarly}
              style={{ background: "transparent", color: "var(--color-text)", border: "2px solid var(--color-text)", minHeight: 52, justifyContent: "space-between", fontSize: 15 }}
            >
              {session.mode === "locked" ? `Locked until ${new Date(session.endAt).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })}` : "End session early"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

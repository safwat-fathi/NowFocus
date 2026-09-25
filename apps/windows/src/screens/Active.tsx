import { useState } from "react";
import { api } from "../lib/api";
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
      onState(await api.endSessionNormal());
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

      {state.unlock && <UnlockDialog state={state} onState={onState} onBackToFocus={() => onState(state)} />}
    </div>
  );
}

function UnlockDialog({ state, onState }: { state: AppState; onState: (s: AppState) => void; onBackToFocus: () => void }) {
  const unlock = state.unlock!;
  const [typed, setTyped] = useState(unlock.typed);
  const [error, setError] = useState("");

  async function backToFocus() {
    onState(await api.cancelUnlock());
  }

  async function onType(v: string) {
    setTyped(v);
    onState(await api.updateUnlockText(v));
  }

  async function startWait() {
    try {
      onState(await api.startUnlockWait());
      setError("");
    } catch (e) {
      setError(String(e));
    }
  }

  async function confirm() {
    try {
      onState(await api.confirmUnlock());
    } catch (e) {
      setError(String(e));
    }
  }

  const waitSeconds = Math.ceil(unlock.waitRemainingMs / 1000);
  const waitPct = 100 - (unlock.waitRemainingMs / (unlock.waitTotalMs || 1)) * 100;

  return (
    <div className="dialog-scrim">
      <div className="unlock-dialog">
        <div className="unlock-dialog__header">
          <span className="unlock-dialog__title">End early?</span>
          <button className="btn btn-icon" onClick={backToFocus} style={{ width: 40, height: 40 }}>
            ✕
          </button>
        </div>

        {unlock.lockedMode ? (
          <div style={{ padding: 24 }}>
            <h2 style={{ fontSize: 30, lineHeight: 1.05, letterSpacing: "-0.02em", margin: "0 0 10px" }}>This one's locked, by you.</h2>
            <p style={{ fontSize: 15, color: "var(--color-neutral-800)", margin: 0 }}>
              You chose Locked when you started, so there's no early exit. {state.session?.remainingLabel} left, and you've got this.
            </p>
            <button className="btn btn-primary" onClick={backToFocus} style={{ width: "100%", minHeight: 52, justifyContent: "space-between", fontSize: 15, marginTop: 20 }}>
              Back to focus
            </button>
          </div>
        ) : (
          <div>
            <div className="unlock-steps">
              <div className="unlock-step" data-active={unlock.phase === "typing"}>1 · Say it</div>
              <div className="unlock-step" data-active={unlock.phase === "waiting"}>2 · Pause</div>
            </div>
            <div className="unlock-flow-body">
              {unlock.phase === "typing" ? (
                <>
                  <p style={{ fontSize: 15, color: "var(--color-neutral-800)", margin: "0 0 14px" }}>
                    No judgement. Type this out, word for word, so it's a choice and not a reflex.
                  </p>
                  <div className="unlock-sentence">"{unlock.sentence}"</div>
                  <input
                    className="input"
                    value={typed}
                    onChange={(e) => onType(e.target.value)}
                    onPaste={(e) => e.preventDefault()}
                    placeholder="Type the sentence…"
                    style={{ marginTop: 14, minHeight: 48, fontSize: 16 }}
                  />
                  <div style={{ fontSize: 13, marginTop: 8, color: unlock.matches ? "var(--color-text)" : "var(--color-neutral-700)", fontWeight: 600, minHeight: 18 }}>
                    {typed ? (unlock.matches ? "That's it. Now a short pause." : "Keep going, match it exactly") : ""}
                  </div>
                </>
              ) : (
                <>
                  <p style={{ fontSize: 15, color: "var(--color-neutral-800)", margin: "0 0 6px" }}>
                    Take a breath. If you still want out when this hits zero, it's yours.
                  </p>
                  <div className="unlock-wait-timer">{waitSeconds}</div>
                  <div style={{ height: 6, background: "var(--color-neutral-300)", marginTop: 12 }}>
                    <div style={{ height: "100%", width: `${waitPct}%`, background: "var(--color-accent)" }} />
                  </div>
                </>
              )}
              {error && <div className="error-line">{error}</div>}
              <div className="unlock-actions">
                <button className="btn btn-primary" onClick={backToFocus} style={{ minHeight: 50, justifyContent: "space-between", fontSize: 15 }}>
                  Stay focused
                </button>
                <button
                  className="btn btn-secondary"
                  onClick={unlock.phase === "typing" ? startWait : confirm}
                  disabled={unlock.phase === "typing" ? !unlock.matches : waitSeconds > 0}
                  style={{ minHeight: 50, justifyContent: "flex-start", fontSize: 14, borderWidth: 2 }}
                >
                  {unlock.phase === "typing" ? "Start 30s pause" : waitSeconds > 0 ? `End in ${waitSeconds}s` : "End session now"}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

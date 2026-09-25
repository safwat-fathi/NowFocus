import { useState } from "react";
import { api } from "../lib/api";
import type { AppState, SessionMode } from "../types";
import { ArrowRightIcon } from "../components/Icons";

const DURATIONS: [string, number][] = [
  ["25m", 25], ["45m", 45], ["1h", 60], ["1.5h", 90], ["2h", 120], ["3h", 180], ["4h", 240],
];
const MODES: [SessionMode, string][] = [["normal", "Normal"], ["strict", "Strict"], ["locked", "Locked"]];
const MODE_DESC: Record<SessionMode, string> = {
  normal: "You can end any time. Good for light days.",
  strict: "To leave early you'll type a short sentence, then wait 30 seconds.",
  locked: "No early exit until the timer ends.",
};

export function Focus({
  state,
  onState,
  onOpenSession,
}: {
  state: AppState;
  onState: (s: AppState) => void;
  onOpenSession: () => void;
}) {
  const [profileId, setProfileId] = useState(state.profiles[0]?.id ?? "");
  const [duration, setDuration] = useState(60);
  const [mode, setMode] = useState<SessionMode>("normal");
  const [starting, setStarting] = useState(false);

  const running = !!state.session;
  const healthy = state.health.websiteBlocking === "active";

  async function start() {
    if (!profileId || starting) return;
    setStarting(true);
    try {
      onState(await api.startSession(profileId, duration, mode));
      onOpenSession();
    } finally {
      setStarting(false);
    }
  }

  return (
    <div className="focus-grid">
      <div className="focus-left">
        <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13, color: "var(--color-neutral-700)" }}>
          <span style={{ fontSize: 11, letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--color-accent-700)", fontWeight: 600 }}>
            {running ? `${state.session!.profileName} · ${state.session!.remainingLabel} left` : "This PC"}
          </span>
          <span>{new Date().toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" })}</span>
        </div>
        <h1 className="focus-hero-title">{running ? "Focusing right now." : "This PC is ready."}</h1>

        <div className="device-strip">
          <div className="device-strip__cell">
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <span style={{ width: 12, height: 12, background: running ? "var(--color-accent)" : "var(--color-text)" }} />
              <span style={{ fontSize: 11, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--color-neutral-700)" }}>This PC</span>
            </div>
            <div style={{ marginTop: "auto" }}>
              <div style={{ fontWeight: 600, fontSize: 16 }}>{running ? "Enforcing" : healthy ? "Ready" : "Set up needed"}</div>
              <div style={{ fontSize: 13, color: "var(--color-neutral-700)", marginTop: 3 }}>
                {healthy ? "All layers active" : "Visit Devices to finish setup"}
              </div>
            </div>
          </div>
        </div>
        <div style={{ fontSize: 12, color: "var(--color-neutral-700)", padding: "10px 0 0" }}>
          No other devices linked yet.
        </div>

        <div className="stat-row">
          <div className="stat-row__cell">
            <div className="stat-row__label">Today</div>
            <div className="stat-row__value">
              {Math.floor(state.stats.todayMinutes / 60)}h {String(state.stats.todayMinutes % 60).padStart(2, "0")}m
            </div>
          </div>
          <div className="stat-row__cell">
            <div className="stat-row__label">Turned away</div>
            <div className="stat-row__value">{state.stats.blockAttemptsToday}</div>
          </div>
          <div className="stat-row__cell">
            <div className="stat-row__label">Sessions</div>
            <div className="stat-row__value">{state.stats.sessionsStarted}</div>
          </div>
        </div>
      </div>

      {!running ? (
        <div className="new-session">
          <div className="new-session__title">New focus session</div>

          <div className="field-label">Profile</div>
          {state.profiles.length === 0 && (
            <p style={{ fontSize: 13, color: "var(--color-neutral-700)" }}>Create a profile first — see Profiles.</p>
          )}
          {state.profiles.map((p) => (
            <button key={p.id} className="profile-pick" data-selected={p.id === profileId} onClick={() => setProfileId(p.id)}>
              <span className="profile-pick__dot" />
              <span className="profile-pick__name">{p.name}</span>
              <span className="profile-pick__meta">
                {p.domains.length} sites · {p.applications.length} apps
              </span>
            </button>
          ))}

          <div className="field-label">Duration</div>
          <div className="chip-grid chip-grid--7">
            {DURATIONS.map(([label, minutes]) => (
              <button key={minutes} className="chip" data-selected={duration === minutes} onClick={() => setDuration(minutes)}>
                {label}
              </button>
            ))}
          </div>

          <div className="field-label">If I want to stop early</div>
          <div className="chip-grid chip-grid--3">
            {MODES.map(([key, label]) => (
              <button key={key} className="chip" data-selected={mode === key} onClick={() => setMode(key)}>
                {label}
              </button>
            ))}
          </div>
          <p className="mode-desc">{MODE_DESC[mode]}</p>

          <button
            className="btn btn-primary"
            onClick={start}
            disabled={!profileId || starting}
            style={{ width: "100%", minHeight: 56, justifyContent: "space-between", fontSize: 16, marginTop: "auto" }}
          >
            {starting ? "Starting…" : `Start ${DURATIONS.find((d) => d[1] === duration)?.[0]}`}
            <ArrowRightIcon />
          </button>
          <div style={{ fontSize: 12, color: "var(--color-neutral-700)", marginTop: 8 }}>Shortcut: Win + Shift + F from anywhere</div>
        </div>
      ) : (
        <div className="session-summary" style={{ background: "var(--color-accent)" }}>
          <div className="session-summary__meta">
            {state.session!.profileName} · {state.session!.mode[0].toUpperCase() + state.session!.mode.slice(1)}
          </div>
          <div className="session-summary__timer">{state.session!.remainingLabel}</div>
          <div className="progress-track">
            <div className="progress-track__fill" style={{ width: `${state.session!.progressPct}%` }} />
          </div>
          <div style={{ fontSize: 13, fontWeight: 600, marginTop: 8 }}>Ends {new Date(state.session!.endAt).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })}</div>
          <button
            className="btn"
            onClick={onOpenSession}
            style={{ marginTop: "auto", background: "var(--color-text)", color: "var(--color-bg)", minHeight: 54, justifyContent: "space-between", fontSize: 15 }}
          >
            Open session
            <ArrowRightIcon />
          </button>
        </div>
      )}
    </div>
  );
}

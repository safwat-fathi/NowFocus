import { useState } from "react";
import { api } from "../lib/api";
import type { AppState } from "../types";
import { PlusIcon, RemoveIcon } from "../components/Icons";

const DURATION_SECS = 14 * 24 * 60 * 60;

/** The 14-day Commitment Shield: an always-blocked lock, owned and
 * time-anchored by the Windows service (survives quitting the app). Exactly
 * one 60s grace window to undo after starting; after that the service itself
 * refuses to clear it. Domains-only, matching macOS. */
export function Commitment({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const c = state.commitment;
  if (c) {
    return <ActiveCommitment state={state} onState={onState} />;
  }
  return <SetupCommitment onState={onState} />;
}

function ActiveCommitment({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const c = state.commitment!;
  const [error, setError] = useState("");
  const days = Math.ceil(c.remainingSecs / 86400);
  const graceLeft = Math.max(0, 60 - (DURATION_SECS - c.remainingSecs));

  async function undo() {
    try {
      setError("");
      onState(await api.clearCommitment());
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">Commitment</span>
        <span className={"tag " + (c.canCancelNow ? "tag-accent" : "tag-neutral")}>
          {c.canCancelNow ? "Grace period" : "Locked in"}
        </span>
      </div>

      <div className="stats-headline" style={{ marginTop: 22 }}>
        <span className="stats-headline__value">{days}d</span>
        <span style={{ fontSize: 16, color: "var(--color-neutral-700)" }}>
          left · ends {new Date(c.endAt).toLocaleDateString(undefined, { month: "short", day: "numeric" })}
        </span>
      </div>

      <div style={{ maxWidth: 480, marginTop: 20 }}>
        <div className="stat-row__label" style={{ marginBottom: 8 }}>Blocked for the duration</div>
        {c.domains.map((d) => (
          <div key={d} style={{ padding: "8px 0", borderBottom: "1px solid var(--color-divider)", fontWeight: 600 }}>{d}</div>
        ))}
      </div>

      {c.canCancelNow ? (
        <div style={{ marginTop: 24 }}>
          <p style={{ fontSize: 13, color: "var(--color-neutral-700)" }}>
            You can still undo this for {graceLeft}s. After that it can't be cancelled — not by quitting NowFocus,
            not by restarting the service, until the 14 days are up.
          </p>
          <button className="btn btn-secondary" onClick={undo} style={{ marginTop: 12 }}>
            Undo commitment ({graceLeft}s)
          </button>
        </div>
      ) : (
        <p style={{ fontSize: 13, color: "var(--color-neutral-700)", marginTop: 24 }}>
          This is locked in until it ends. You chose this — quitting NowFocus or restarting won't lift it. The
          only way out early is to fully uninstall NowFocus, which needs an administrator.
        </p>
      )}
      {error && <p style={{ fontSize: 13, color: "var(--color-accent-700)", marginTop: 12 }}>{error}</p>}
    </div>
  );
}

function SetupCommitment({ onState }: { onState: (s: AppState) => void }) {
  const [domains, setDomains] = useState<string[]>([]);
  const [input, setInput] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState("");

  function addDomain() {
    const raw = input.trim().toLowerCase();
    if (!raw) return;
    if (!raw.includes(".")) {
      setError("That doesn't look like a website. Try something like youtube.com");
      return;
    }
    if (domains.includes(raw)) {
      setError(`${raw} is already on the list`);
      return;
    }
    setDomains([...domains, raw]);
    setInput("");
    setError("");
  }

  async function start() {
    try {
      setError("");
      onState(await api.startCommitment(domains));
    } catch (e) {
      setError(String(e));
      setConfirming(false);
    }
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">Commitment</span>
      </div>
      <p className="screen-lede">
        Lock a set of sites away for 14 days. Once started you get 60 seconds to change your mind — after that it
        can't be cancelled until the two weeks are up, even if you quit NowFocus. For the sites you know you'll
        cave on.
      </p>

      <div style={{ maxWidth: 480, marginTop: 20 }}>
        <div className="field-label">Websites to lock away</div>
        <div style={{ display: "flex", gap: 8 }}>
          <input
            className="input"
            placeholder="youtube.com"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && addDomain()}
            style={{ flex: 1 }}
          />
          <button className="btn btn-secondary" onClick={addDomain} style={{ gap: 6 }}>
            <PlusIcon /> Add
          </button>
        </div>
        {error && <p style={{ fontSize: 13, color: "var(--color-accent-700)", marginTop: 8 }}>{error}</p>}

        {domains.map((d) => (
          <div key={d} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "8px 0", borderBottom: "1px solid var(--color-divider)" }}>
            <span style={{ fontWeight: 600 }}>{d}</span>
            <button className="btn btn-icon" onClick={() => setDomains(domains.filter((x) => x !== d))} title="Remove">
              <RemoveIcon />
            </button>
          </div>
        ))}

        {!confirming ? (
          <button
            className="btn btn-primary"
            onClick={() => setConfirming(true)}
            disabled={domains.length === 0}
            style={{ marginTop: 24, width: "100%", minHeight: 52 }}
          >
            Start 14-Day Commitment
          </button>
        ) : (
          <div style={{ marginTop: 24, padding: 16, border: "2px solid var(--color-accent)", background: "var(--color-accent-100, transparent)" }}>
            <div style={{ fontWeight: 600, marginBottom: 8 }}>Are you sure?</div>
            <p style={{ fontSize: 13, color: "var(--color-neutral-700)", marginBottom: 16 }}>
              {domains.length} site{domains.length === 1 ? "" : "s"} will be blocked for 14 days. You'll have 60
              seconds to undo, then it's locked in.
            </p>
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn btn-primary" onClick={start} style={{ flex: 1 }}>Yes, commit</button>
              <button className="btn btn-secondary" onClick={() => setConfirming(false)} style={{ flex: 1 }}>Back</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

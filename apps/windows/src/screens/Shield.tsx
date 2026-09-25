import { api } from "../lib/api";
import type { AppState } from "../types";
import { ArrowRightIcon } from "../components/Icons";

/** Full-screen takeover shown when a blocked site/app is opened during a
 * session. In the real (Phase 4) build this is triggered by the Win32
 * foreground hook via `simulate_block`'s same backend path; for now it's
 * only reachable through the Devices/dev "Try a blocked site" affordance
 * used to check this screen against the design (see Shield's call site). */
export function Shield({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const shield = state.shield!;
  const session = state.session;

  async function backToWork() {
    onState(await api.dismissShield());
  }

  async function endEarly() {
    if (!session) return;
    if (session.mode === "normal") onState(await api.endSessionNormal());
    else onState(await api.beginUnlock());
  }

  return (
    <div className="shield-grid">
      <div style={{ display: "flex", flexDirection: "column" }}>
        <div className="shield-kicker">
          <span className="shield-kicker__dot" />
          {shield.targetKind === "app" ? `${shield.targetName} · closed by NowFocus` : `${shield.targetName} · blocked by NowFocus`}
        </div>
        <div style={{ marginTop: "auto" }}>
          <h1 className="shield-title">{shield.targetName} can wait.</h1>
          <p className="shield-body">
            {session ? `You're in ${session.profileName}, with ${session.remainingLabel} to go.` : ""} Focus protects what you said mattered.
          </p>
        </div>
      </div>
      <div className="shield-side">
        <div className="shield-side-list">
          <div className="shield-side-row">
            <span className="shield-side-row__label">Left in session</span>
            <span className="shield-side-row__value">{session?.remainingLabel ?? "—"}</span>
          </div>
          <div className="shield-side-row" style={{ borderBottom: "2px solid var(--color-neutral-600)" }}>
            <span className="shield-side-row__label">Tries today</span>
            <span className="shield-side-row__value">{state.stats.blockAttemptsToday}</span>
          </div>
        </div>
        <button className="btn btn-primary" onClick={backToWork} style={{ minHeight: 58, justifyContent: "space-between", fontSize: 17, marginTop: 24 }}>
          Back to work
          <ArrowRightIcon size={22} />
        </button>
        <button className="btn" onClick={endEarly} style={{ minHeight: 48, justifyContent: "flex-start", color: "var(--color-neutral-300)", fontSize: 15, marginTop: 6, paddingLeft: 0 }}>
          I really need it
        </button>
      </div>
    </div>
  );
}

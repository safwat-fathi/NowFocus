import { api } from "../lib/api";
import type { AppState } from "../types";
import { Account } from "./Account";

export function Devices({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">Devices</span>
      </div>
      <p className="screen-lede">
        This PC, and the account that links it to your other devices. Signed out, NowFocus never contacts a server and
        nothing leaves this PC.
      </p>

      <div className="device-cards">
        <div className="device-card">
          <div className="device-card__head">
            <span className="device-card__dot" style={{ background: state.health.websiteBlocking === "active" ? "var(--color-text)" : "var(--color-accent)" }} />
            <span className="device-card__name">This PC</span>
            <span className={"tag " + (state.health.websiteBlocking === "active" ? "tag-neutral" : "tag-accent")}>
              {state.health.websiteBlocking === "active" ? "Active" : "Needs setup"}
            </span>
          </div>
          <div className="device-card__meta">This device</div>
          <div className="device-card__layers">
            {state.health.layers.map((l) => (
              <div className="device-card__layer" key={l.name}>
                <span>{l.name}</span>
                <span style={{ fontWeight: 600, color: l.healthy ? "var(--color-text)" : "var(--color-accent-700)" }}>{l.state}</span>
              </div>
            ))}
          </div>
          {state.health.websiteBlocking !== "active" && (
            <div className="device-card__fix">
              <div className="device-card__fix-note">
                {import.meta.env.DEV
                  ? "Running in development mode — there's no privileged service on this host, so nothing is really enforced."
                  : "The NowFocus background service isn't running, so website blocking is off. Reinstall or repair NowFocus to restore it."}
              </div>
            </div>
          )}
        </div>

      </div>

      <Account state={state} onState={onState} />

      {import.meta.env.DEV && <DevPanel />}
    </div>
  );
}

/** Dev-builds only — a clearly-labeled way to exercise the Shield screen
 * without the real Win32 foreground hook. Gated to debug builds so its
 * synthetic block events never pollute the real "most turned away" stats. */
function DevPanel() {
  async function trigger(kind: string, name: string) {
    await api.simulateBlock(kind, name);
  }
  return (
    <div style={{ marginTop: "auto", paddingTop: 24, borderTop: "1px dashed var(--color-divider)" }}>
      <div style={{ fontSize: 11, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--color-neutral-700)", marginBottom: 8 }}>
        Developer — test the Shield screen (start a session first)
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        <button className="btn btn-secondary" onClick={() => trigger("domain", "reddit.com")}>Simulate blocked site</button>
        <button className="btn btn-secondary" onClick={() => trigger("app", "Steam")}>Simulate blocked app</button>
      </div>
    </div>
  );
}

import { api } from "../lib/api";
import type { AppState } from "../types";
import { PlusIcon } from "../components/Icons";

export function Devices({ state }: { state: AppState }) {
  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">Devices</span>
        <button className="btn btn-secondary" disabled title="No pairing backend yet" style={{ minHeight: 40, gap: 6 }}>
          <PlusIcon />
          Link a device
        </button>
      </div>
      <p className="screen-lede">
        This PC only, for now — there's no cross-device sync yet, so nothing is shown here that isn't actually
        true of this machine.
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
                Running in development mode on a non-Windows host — there's no real enforcement to report yet.
                This becomes real once the Windows service (Phase 3) is built.
              </div>
            </div>
          )}
        </div>

        <div className="device-card device-card--placeholder">
          <div className="device-card__head">
            <span className="device-card__dot" style={{ background: "var(--color-neutral-400)" }} />
            <span className="device-card__name">Link a phone or laptop</span>
          </div>
          <div className="device-card__meta">Not available yet — no pairing/sync backend exists in this build</div>
        </div>
      </div>

      <DevPanel />
    </div>
  );
}

/** Not part of the design — a clearly-labeled way to exercise the Shield
 * screen before the real Win32 foreground hook exists (Phase 4). Remove
 * this panel once that lands and calls the same simulate_block path. */
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

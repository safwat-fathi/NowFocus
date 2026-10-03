import { open } from "@tauri-apps/plugin-dialog";
import { api } from "../lib/api";
import type { AppState } from "../types";

const CHOICES = [15, 30, 45, 60, 90, 120];

/** "30 minutes of Instagram a day." Past it, the app is closed until midnight. Lowering a limit counts at once;
 * raising or removing one only counts from midnight. Paused on a cheat day. */
export function Limits({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  async function add() {
    const picked = await open({ multiple: false, title: "Choose an application to limit" });
    if (!picked || Array.isArray(picked)) return;
    // The key is the exe path, lowercase-compared by the backend; a new limit starts at 30 minutes.
    onState(await api.setLimit(picked, picked.split(/[\\/]/).pop() ?? picked, 30));
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">Daily limits</span>
      </div>
      <p className="screen-lede">
        "30 minutes of Instagram a day." Past it, the app is closed until midnight. Lowering or removing a limit counts at once, unless it is
        already used up; raising one only counts from midnight. A cheat day pauses them.
      </p>
      <div style={{ maxWidth: 560, marginTop: 20 }}>
        {state.limits.filter((l) => !l.isSite).map((l) => (
          <div key={l.key} style={{ padding: "12px 0", borderBottom: "1px solid var(--color-neutral-600)" }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
              <strong>{l.label}</strong>
              {l.usedUp && <span className="field-label">Used up</span>}
            </div>
            <div style={{ fontSize: 13, color: "var(--color-neutral-400)", margin: "2px 0 8px" }}>
              {l.usedMinutes} of {l.minutes} min today{l.pending ? ` · ${l.pending}` : ""}
            </div>
            <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
              {CHOICES.map((m) => (
                <button key={m} className="btn btn-secondary" data-active={m === l.minutes} onClick={async () => onState(await api.setLimit(l.key, l.label, m))} style={{ minHeight: 34 }}>
                  {m}
                </button>
              ))}
              <button className="btn" onClick={async () => onState(await api.setLimit(l.key, l.label, 0))} style={{ minHeight: 34 }}>
                Remove
              </button>
            </div>
          </div>
        ))}
        <button className="btn btn-secondary" onClick={add} style={{ minHeight: 40, marginTop: 14 }}>
          Add application…
        </button>
      </div>
    </div>
  );
}

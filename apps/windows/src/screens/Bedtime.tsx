import { api } from "../lib/api";
import type { AppState, Bedtime as BedtimeSettings } from "../types";

function minutesToTime(min: number): string {
  const h = Math.floor(min / 60);
  const m = min % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

function timeToMinutes(value: string): number {
  const [h, m] = value.split(":").map((n) => parseInt(n, 10));
  return (h || 0) * 60 + (m || 0);
}

/** Bedtime Wind-Down. During the wind-down→wake window the chosen profile is
 * enforced as a LOCKED session (matches macOS BedtimeScheduler); at the sleep
 * moment the screen can lock. Ships lock-at-sleep only (macOS's 1-of-4
 * reduction — no clean unprivileged Windows API for DND/greyscale). This PC
 * only, and NowFocus must be running. */
export function Bedtime({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const b = state.bedtime;

  async function save(patch: Partial<BedtimeSettings>) {
    const next = { ...b, ...patch };
    onState(
      await api.setBedtime({
        enabled: next.enabled,
        windDownMinute: next.windDownMinute,
        sleepMinute: next.sleepMinute,
        wakeMinute: next.wakeMinute,
        lockAtSleep: next.lockAtSleep,
        policyId: next.policyId,
      }),
    );
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">Bedtime</span>
        <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 14 }}>
          <input type="checkbox" checked={b.enabled} onChange={(e) => save({ enabled: e.target.checked })} />
          {b.enabled ? "On every night" : "Off"}
        </label>
      </div>
      <p className="screen-lede">
        Each night, your chosen profile is blocked from wind-down until wake — as a locked session you can't end
        early. Optionally lock the screen at sleep time. This PC only, and NowFocus must be running.
      </p>

      <div style={{ maxWidth: 520, opacity: b.enabled ? 1 : 0.55, pointerEvents: b.enabled ? "auto" : "none" }}>
        <div className="field-label" style={{ marginTop: 20 }}>Profile to block</div>
        {state.profiles.length === 0 && (
          <p style={{ fontSize: 13, color: "var(--color-neutral-700)" }}>Create a profile first — see Profiles.</p>
        )}
        {state.profiles.map((p) => (
          <button
            key={p.id}
            className="profile-pick"
            data-selected={p.id === b.policyId}
            onClick={() => save({ policyId: p.id })}
          >
            <span className="profile-pick__dot" />
            <span className="profile-pick__name">{p.name}</span>
            <span className="profile-pick__meta">{p.domains.length} sites · {p.applications.length} apps</span>
          </button>
        ))}

        <div style={{ display: "flex", gap: 16, marginTop: 20, flexWrap: "wrap" }}>
          <div className="field" style={{ margin: 0 }}>
            <label>Wind-down</label>
            <input className="input" type="time" value={minutesToTime(b.windDownMinute)} onChange={(e) => save({ windDownMinute: timeToMinutes(e.target.value) })} />
          </div>
          <div className="field" style={{ margin: 0 }}>
            <label>Sleep</label>
            <input className="input" type="time" value={minutesToTime(b.sleepMinute)} onChange={(e) => save({ sleepMinute: timeToMinutes(e.target.value) })} />
          </div>
          <div className="field" style={{ margin: 0 }}>
            <label>Wake</label>
            <input className="input" type="time" value={minutesToTime(b.wakeMinute)} onChange={(e) => save({ wakeMinute: timeToMinutes(e.target.value) })} />
          </div>
        </div>

        <label style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 22, fontSize: 14 }}>
          <input type="checkbox" checked={b.lockAtSleep} onChange={(e) => save({ lockAtSleep: e.target.checked })} />
          Lock the screen at sleep time
        </label>

        {!b.policyId && b.enabled && (
          <p style={{ fontSize: 13, color: "var(--color-accent-700)", marginTop: 16 }}>
            Choose a profile above, or nothing will be blocked at bedtime.
          </p>
        )}
      </div>
    </div>
  );
}

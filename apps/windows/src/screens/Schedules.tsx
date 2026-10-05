import { useState } from "react";
import { ask } from "@tauri-apps/plugin-dialog";
import { api } from "../lib/api";
import { profileSummary } from "../lib/profile";
import type { AppState, Schedule, SessionMode } from "../types";

const DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const MODES: [SessionMode, string][] = [["normal", "Normal"], ["strict", "Strict"], ["locked", "Locked"]];

function minutesToTime(min: number): string {
  return `${String(Math.floor(min / 60)).padStart(2, "0")}:${String(min % 60).padStart(2, "0")}`;
}

function timeToMinutes(value: string): number {
  const [h, m] = value.split(":").map((n) => parseInt(n, 10));
  return (h || 0) * 60 + (m || 0);
}

/** Recurring sessions ("Mon-Fri 9-12") that start by themselves. They only run while NowFocus is running, and
 * never on a cheat day or while another session is running. */
export function Schedules({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const [editing, setEditing] = useState<Schedule | null>(null);
  const [error, setError] = useState("");

  function blank(): Schedule {
    return {
      id: "", name: "Work", days: [0, 1, 2, 3, 4], startMinute: 9 * 60, endMinute: 12 * 60,
      policyId: state.profiles[0]?.id ?? "", mode: "normal", enabled: true, daysLabel: "",
    };
  }

  async function save(s: Schedule) {
    try {
      onState(await api.saveSchedule(s));
      setEditing(null);
      setError("");
    } catch (e) {
      setError(String(e));
    }
  }

  async function remove(s: Schedule) {
    const yes = await ask(`Delete ${s.name}? A session it already started keeps running.`, { title: "Delete schedule?", kind: "warning", okLabel: "Delete", cancelLabel: "Keep" });
    if (!yes) return;
    onState(await api.deleteSchedule(s.id));
    setEditing(null);
  }

  if (editing) {
    const e = editing;
    const set = (patch: Partial<Schedule>) => setEditing({ ...e, ...patch });
    return (
      <div className="screen">
        <div className="screen-header">
          <span className="screen-title">{e.id ? "Edit schedule" : "New schedule"}</span>
        </div>
        <div style={{ maxWidth: 520 }}>
          <div className="field">
            <label>Name</label>
            <input className="input" value={e.name} onChange={(ev) => set({ name: ev.target.value.slice(0, 40) })} />
          </div>
          <div className="field-label">Days</div>
          <div className="chip-grid" style={{ marginBottom: 16 }}>
            {DAY_NAMES.map((d, i) => (
              <button
                key={d}
                className="chip"
                data-selected={e.days.includes(i)}
                onClick={() => set({ days: e.days.includes(i) ? e.days.filter((x) => x !== i) : [...e.days, i].sort() })}
              >
                {d}
              </button>
            ))}
          </div>
          <div style={{ display: "flex", gap: 16 }}>
            <div className="field" style={{ margin: 0 }}>
              <label>Starts</label>
              <input className="input" type="time" value={minutesToTime(e.startMinute)} onChange={(ev) => set({ startMinute: timeToMinutes(ev.target.value) })} />
            </div>
            <div className="field" style={{ margin: 0 }}>
              <label>Ends</label>
              <input className="input" type="time" value={minutesToTime(e.endMinute)} onChange={(ev) => set({ endMinute: timeToMinutes(ev.target.value) })} />
            </div>
          </div>
          {e.endMinute <= e.startMinute && <p style={{ fontSize: 13, color: "var(--color-neutral-700)" }}>Ends the next day.</p>}

          <div className="field-label" style={{ marginTop: 18 }}>Profile to block</div>
          {state.profiles.map((p) => (
            <button key={p.id} className="profile-pick" data-selected={p.id === e.policyId} onClick={() => set({ policyId: p.id })}>
              <span className="profile-pick__dot" />
              <span className="profile-pick__name">{p.name}</span>
              <span className="profile-pick__meta">{profileSummary(p)}</span>
            </button>
          ))}

          <div className="field-label" style={{ marginTop: 18 }}>If I want to stop early</div>
          <div className="chip-grid">
            {MODES.map(([m, label]) => (
              <button key={m} className="chip" data-selected={e.mode === m} onClick={() => set({ mode: m })}>{label}</button>
            ))}
          </div>
          {e.mode === "locked" && <p style={{ fontSize: 13, color: "var(--color-neutral-700)" }}>Locked has no early exit, and this one starts by itself.</p>}

          <label style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 18, fontSize: 14 }}>
            <input type="checkbox" checked={e.enabled} onChange={(ev) => set({ enabled: ev.target.checked })} />
            On
          </label>
          {error && <div className="error-line">{error}</div>}
          <div style={{ display: "flex", gap: 10, marginTop: 22 }}>
            <button className="btn btn-primary" onClick={() => save(e)} style={{ minHeight: 48 }}>Save</button>
            <button className="btn btn-secondary" onClick={() => { setEditing(null); setError(""); }} style={{ minHeight: 48 }}>Cancel</button>
            {e.id && <button className="btn" onClick={() => remove(e)} style={{ minHeight: 48 }}>Delete</button>}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">Schedules</span>
      </div>
      <p className="screen-lede">
        Sessions that start by themselves, like Mon-Fri 9-12. They won't start on a cheat day or while another session
        is running, and only while NowFocus is running.
      </p>
      <div style={{ maxWidth: 520, marginTop: 16 }}>
        {state.schedules.map((s) => (
          <button key={s.id} className="profile-pick" onClick={() => setEditing(s)}>
            <span className="profile-pick__name">{s.name}</span>
            <span className="profile-pick__meta">
              {s.daysLabel} · {minutesToTime(s.startMinute)}-{minutesToTime(s.endMinute)} ·{" "}
              {state.profiles.find((p) => p.id === s.policyId)?.name ?? "No profile"} · {s.mode} · {s.enabled ? "on" : "off"}
            </span>
          </button>
        ))}
        {state.profiles.length === 0 ? (
          <p style={{ fontSize: 13 }}>Create a profile first. A schedule needs one to block.</p>
        ) : (
          <button className="btn btn-secondary" onClick={() => setEditing(blank())} style={{ marginTop: 12, minHeight: 44 }}>
            + New schedule
          </button>
        )}
      </div>
    </div>
  );
}

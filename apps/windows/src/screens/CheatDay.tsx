import { useState } from "react";
import { ask } from "@tauri-apps/plugin-dialog";
import { api } from "../lib/api";
import type { AppState } from "../types";

const fmtDay = (iso: string) => new Date(iso).toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" });
const fmtTime = (iso: string) => new Date(iso).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });

/** Plan a day off from blocking, ahead of time: at least 24 hours ahead, one a week, and the Commitment (which
 * the background service holds) stays on. Sessions, Bedtime and schedules pause for the whole local day. */
export function CheatDay({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const cheat = state.cheatDay;
  const [error, setError] = useState("");

  async function plan(day: string) {
    const yes = await ask(`Make ${fmtDay(day)} your cheat day? Blocking pauses from midnight until the next midnight. You can cancel it before then.`, {
      title: "Plan a cheat day?", okLabel: "Yes, plan it", cancelLabel: "Cancel",
    });
    if (!yes) return;
    try {
      onState(await api.scheduleCheatDay(day));
      setError("");
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">Cheat day</span>
      </div>
      <p className="screen-lede">
        A whole day with blocking paused: sessions, Bedtime and schedules. Your Commitment stays on, always. You set it at
        least 24 hours ahead, and you get one a week, so it's a plan and not an impulse.
      </p>
      <div style={{ maxWidth: 520, marginTop: 20 }}>
        {cheat?.active ? (
          <>
            <h2 style={{ fontSize: 28, margin: "0 0 6px" }}>On now</h2>
            <p>Blocking is paused until {fmtTime(cheat.endAt)}.</p>
            <button className="btn btn-secondary" onClick={async () => onState(await api.cancelCheatDay())} style={{ minHeight: 44 }}>
              End it early
            </button>
          </>
        ) : cheat?.upcoming ? (
          <>
            <h2 style={{ fontSize: 28, margin: "0 0 6px" }}>Set for {fmtDay(cheat.startAt)}</h2>
            <p>Cancelling frees your week.</p>
            <button className="btn btn-secondary" onClick={async () => onState(await api.cancelCheatDay())} style={{ minHeight: 44 }}>
              Cancel it
            </button>
          </>
        ) : (
          <>
            <div className="field-label">Pick a day</div>
            {state.cheatOptions.map((d) => (
              <button key={d} className="profile-pick" onClick={() => plan(d)}>
                <span className="profile-pick__name">{fmtDay(d)}</span>
              </button>
            ))}
          </>
        )}
        {error && <div className="error-line">{error}</div>}
      </div>
    </div>
  );
}

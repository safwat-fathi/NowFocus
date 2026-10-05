import { useState } from "react";
import { ask } from "@tauri-apps/plugin-dialog";
import { api } from "../lib/api";
import { profileSummary } from "../lib/profile";
import { errorText, useI18n, type T } from "../i18n";
import type { AppState, Schedule, SessionMode } from "../types";

const DAY_KEYS = ["day.mon", "day.tue", "day.wed", "day.thu", "day.fri", "day.sat", "day.sun"] as const;
const MODES: SessionMode[] = ["normal", "strict", "locked"];

/** "Mon-Fri", "Every day", "Sat, Sun", "Mon, Wed, Fri". Days are 0 = Monday .. 6 = Sunday. */
function daysLabel(days: number[], t: T): string {
  const sorted = [...days].sort((a, b) => a - b);
  const names = sorted.map((d) => t(DAY_KEYS[d]));
  if (sorted.length === 7) return t("days.everyDay");
  if (sorted.length > 2 && sorted.every((d, i) => i === 0 || d - sorted[i - 1] === 1)) {
    return t("days.range", { a: names[0], b: names[names.length - 1] });
  }
  return names.join(t("days.join"));
}

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
  const { t } = useI18n();
  const [editing, setEditing] = useState<Schedule | null>(null);
  const [error, setError] = useState("");

  function blank(): Schedule {
    return {
      id: "", name: t("sched.defaultName"), days: [0, 1, 2, 3, 4], startMinute: 9 * 60, endMinute: 12 * 60,
      policyId: state.profiles[0]?.id ?? "", mode: "normal", enabled: true,
    };
  }

  async function save(s: Schedule) {
    try {
      onState(await api.saveSchedule(s));
      setEditing(null);
      setError("");
    } catch (e) {
      setError(errorText(e, t));
    }
  }

  async function remove(s: Schedule) {
    const yes = await ask(t("sched.deleteBody", { name: s.name }), { title: t("sched.deleteTitle"), kind: "warning", okLabel: t("common.delete"), cancelLabel: t("sched.keep") });
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
          <span className="screen-title">{e.id ? t("sched.edit") : t("sched.new")}</span>
        </div>
        <div style={{ maxWidth: 520 }}>
          <div className="field">
            <label>{t("common.name")}</label>
            <input className="input" value={e.name} onChange={(ev) => set({ name: ev.target.value.slice(0, 40) })} />
          </div>
          <div className="field-label">{t("sched.days")}</div>
          <div className="chip-grid" style={{ marginBottom: 16 }}>
            {DAY_KEYS.map((d, i) => (
              <button
                key={d}
                className="chip"
                data-selected={e.days.includes(i)}
                onClick={() => set({ days: e.days.includes(i) ? e.days.filter((x) => x !== i) : [...e.days, i].sort() })}
              >
                {t(d)}
              </button>
            ))}
          </div>
          <div style={{ display: "flex", gap: 16 }}>
            <div className="field" style={{ margin: 0 }}>
              <label>{t("sched.starts")}</label>
              <input className="input" type="time" value={minutesToTime(e.startMinute)} onChange={(ev) => set({ startMinute: timeToMinutes(ev.target.value) })} />
            </div>
            <div className="field" style={{ margin: 0 }}>
              <label>{t("sched.ends")}</label>
              <input className="input" type="time" value={minutesToTime(e.endMinute)} onChange={(ev) => set({ endMinute: timeToMinutes(ev.target.value) })} />
            </div>
          </div>
          {e.endMinute <= e.startMinute && <p style={{ fontSize: 13, color: "var(--color-neutral-700)" }}>{t("sched.nextDay")}</p>}

          <div className="field-label" style={{ marginTop: 18 }}>{t("sched.profile")}</div>
          {state.profiles.map((p) => (
            <button key={p.id} className="profile-pick" data-selected={p.id === e.policyId} onClick={() => set({ policyId: p.id })}>
              <span className="profile-pick__dot" />
              <span className="profile-pick__name">{p.name}</span>
              <span className="profile-pick__meta">{profileSummary(p, t)}</span>
            </button>
          ))}

          <div className="field-label" style={{ marginTop: 18 }}>{t("focus.stopEarly")}</div>
          <div className="chip-grid">
            {MODES.map((m) => (
              <button key={m} className="chip" data-selected={e.mode === m} onClick={() => set({ mode: m })}>{t(`mode.${m}`)}</button>
            ))}
          </div>
          {e.mode === "locked" && <p style={{ fontSize: 13, color: "var(--color-neutral-700)" }}>{t("sched.lockedNote")}</p>}

          <label style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 18, fontSize: 14 }}>
            <input type="checkbox" checked={e.enabled} onChange={(ev) => set({ enabled: ev.target.checked })} />
            {t("common.on")}
          </label>
          {error && <div className="error-line">{error}</div>}
          <div style={{ display: "flex", gap: 10, marginTop: 22 }}>
            <button className="btn btn-primary" onClick={() => save(e)} style={{ minHeight: 48 }}>{t("common.save")}</button>
            <button className="btn btn-secondary" onClick={() => { setEditing(null); setError(""); }} style={{ minHeight: 48 }}>{t("common.cancel")}</button>
            {e.id && <button className="btn" onClick={() => remove(e)} style={{ minHeight: 48 }}>{t("common.delete")}</button>}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">{t("sched.title")}</span>
      </div>
      <p className="screen-lede">
        {t("sched.lede")}
      </p>
      <div style={{ maxWidth: 520, marginTop: 16 }}>
        {state.schedules.map((s) => (
          <button key={s.id} className="profile-pick" onClick={() => setEditing(s)}>
            <span className="profile-pick__name">{s.name}</span>
            <span className="profile-pick__meta">
              {t("sched.row", {
                days: daysLabel(s.days, t), start: minutesToTime(s.startMinute), end: minutesToTime(s.endMinute),
                profile: state.profiles.find((p) => p.id === s.policyId)?.name ?? t("sched.noProfile"),
                mode: t(`mode.${s.mode}`), state: s.enabled ? t("common.on") : t("common.off"),
              })}
            </span>
          </button>
        ))}
        {state.profiles.length === 0 ? (
          <p style={{ fontSize: 13 }}>{t("sched.createProfile")}</p>
        ) : (
          <button className="btn btn-secondary" onClick={() => setEditing(blank())} style={{ marginTop: 12, minHeight: 44 }}>
            {t("sched.newButton")}
          </button>
        )}
      </div>
    </div>
  );
}

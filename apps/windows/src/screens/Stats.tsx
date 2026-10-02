import { useState } from "react";
import type { AppState } from "../types";

const DAY_LABELS = ["M", "T", "W", "T", "F", "S", "S"];

function fmt(mins: number): string {
  return `${Math.floor(mins / 60)}h ${String(mins % 60).padStart(2, "0")}m`;
}

/** Real numbers only, sourced from focus-only session rows (bedtime is excluded
 * server-side). Today's headline + this week's bar chart, streak, completion,
 * and "most turned away" (block events now carry the target). */
export function Stats({ state }: { state: AppState }) {
  const s = state.stats;
  const week = s.weekMinutes.length === 7 ? s.weekMinutes : [0, 0, 0, 0, 0, 0, 0];
  const peak = Math.max(...week, 1);
  const todayIdx = (new Date().getDay() + 6) % 7; // JS Sun=0 → Mon-first index
  const [copied, setCopied] = useState(false);

  async function copyWeek() {
    await navigator.clipboard.writeText(s.weekSummary);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">This week</span>
        <span className="screen-sub">
          {new Date().toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" })}
        </span>
      </div>

      <div className="stats-headline" style={{ marginTop: 22 }}>
        <span className="stats-headline__value">{fmt(s.todayMinutes)}</span>
        <span style={{ fontSize: 16, color: "var(--color-neutral-700)" }}>focused today</span>
      </div>

      {/* Weekly bar chart (Mon..Sun) */}
      <div style={{ display: "flex", alignItems: "flex-end", gap: 10, height: 140, marginTop: 22, maxWidth: 480 }}>
        {week.map((mins, i) => (
          <div key={i} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 8, height: "100%" }}>
            <div style={{ flex: 1, width: "100%", display: "flex", alignItems: "flex-end" }}>
              <div
                title={fmt(mins)}
                style={{
                  width: "100%",
                  height: `${Math.max((mins / peak) * 100, mins > 0 ? 4 : 0)}%`,
                  background: i === todayIdx ? "var(--color-accent)" : "var(--color-text)",
                  transition: "height 0.2s",
                }}
              />
            </div>
            <span style={{ fontSize: 12, color: i === todayIdx ? "var(--color-accent-700)" : "var(--color-neutral-700)", fontWeight: i === todayIdx ? 600 : 400 }}>
              {DAY_LABELS[i]}
            </span>
          </div>
        ))}
      </div>

      <div className="stat-cells" style={{ maxWidth: 480, marginTop: 22 }}>
        <div className="stat-cells__cell">
          <div className="stat-row__label">Sessions today</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{s.sessionsStarted}</div>
        </div>
        <div className="stat-cells__cell">
          <div className="stat-row__label">Completed (wk)</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{Math.round(s.completionRate * 100)}%</div>
        </div>
        <div className="stat-cells__cell">
          <div className="stat-row__label">Streak</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{s.streakDays}d</div>
        </div>
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 16, maxWidth: 480, marginTop: 14 }}>
        <div>
          <div className="stat-row__label">Focus score (wk)</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{s.focusScore ?? "-"}</div>
        </div>
        <button className="btn btn-secondary" onClick={copyWeek} style={{ marginLeft: "auto", minHeight: 40 }}>
          {copied ? "Copied" : "Copy this week"}
        </button>
      </div>

      {s.topTargets.length > 0 && (
        <div style={{ maxWidth: 480, marginTop: 24 }}>
          <div className="stat-row__label" style={{ marginBottom: 8 }}>Most turned away this week</div>
          {s.topTargets.map((t) => (
            <div key={t.name} style={{ display: "flex", justifyContent: "space-between", padding: "8px 0", borderBottom: "1px solid var(--color-divider)" }}>
              <span style={{ fontWeight: 600 }}>{t.name}</span>
              <span style={{ color: "var(--color-neutral-700)" }}>{t.count}×</span>
            </div>
          ))}
        </div>
      )}

      <p style={{ fontSize: 12, color: "var(--color-neutral-700)", marginTop: 24 }}>
        Counted on this PC. We never see what you browse. Bedtime sessions aren't counted here.
      </p>
    </div>
  );
}

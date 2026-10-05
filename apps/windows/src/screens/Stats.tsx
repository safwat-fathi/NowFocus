import { useState } from "react";
import { useI18n, type T } from "../i18n";
import type { AppState, Stats as StatsData } from "../types";

const DAY_LETTERS = ["daylet.mon", "daylet.tue", "daylet.wed", "daylet.thu", "daylet.fri", "daylet.sat", "daylet.sun"] as const;

/** The text "Copy this week" puts on the clipboard: counts only, in the app's language. */
function weekText(t: T, s: StatsData): string {
  const parts = [
    t("share.head", {
      count: s.weekSessions, sessions: s.weekSessions, completed: s.weekCompleted,
      h: Math.floor(s.weekMinutesTotal / 60), m: s.weekMinutesTotal % 60,
    }),
  ];
  if (s.focusScore !== null) parts.push(t("share.score", { score: s.focusScore }));
  if (s.streakDays > 0) parts.push(t("share.streak", { count: s.streakDays }));
  parts.push(t("share.turnedAway", { count: s.weekTurnedAway }));
  return parts.join(" ");
}

/** Real numbers only, sourced from focus-only session rows (bedtime is excluded
 * server-side). Today's headline + this week's bar chart, streak, completion,
 * and "most turned away" (block events now carry the target). */
export function Stats({ state }: { state: AppState }) {
  const { t, fmt } = useI18n();
  const s = state.stats;
  const week = s.weekMinutes.length === 7 ? s.weekMinutes : [0, 0, 0, 0, 0, 0, 0];
  const peak = Math.max(...week, 1);
  const todayIdx = (new Date().getDay() + 6) % 7; // JS Sun=0 → Mon-first index
  const [copied, setCopied] = useState(false);

  async function copyWeek() {
    await navigator.clipboard.writeText(weekText(t, s));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">{t("stats.thisWeek")}</span>
        <span className="screen-sub">
          {fmt.longDay(Date.now())}
        </span>
      </div>

      <div className="stats-headline" style={{ marginTop: 22 }}>
        <span className="stats-headline__value">{fmt.hm(s.todayMinutes)}</span>
        <span style={{ fontSize: 16, color: "var(--color-neutral-700)" }}>{t("stats.focusedToday")}</span>
      </div>

      {/* Weekly bar chart (Mon..Sun) */}
      <div style={{ display: "flex", alignItems: "flex-end", gap: 10, height: 140, marginTop: 22, maxWidth: 480 }}>
        {week.map((mins, i) => (
          <div key={i} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 8, height: "100%" }}>
            <div style={{ flex: 1, width: "100%", display: "flex", alignItems: "flex-end" }}>
              <div
                title={fmt.hm(mins)}
                style={{
                  width: "100%",
                  height: `${Math.max((mins / peak) * 100, mins > 0 ? 4 : 0)}%`,
                  background: i === todayIdx ? "var(--color-accent)" : "var(--color-text)",
                  transition: "height 0.2s",
                }}
              />
            </div>
            <span style={{ fontSize: 12, color: i === todayIdx ? "var(--color-accent-700)" : "var(--color-neutral-700)", fontWeight: i === todayIdx ? 600 : 400 }}>
              {t(DAY_LETTERS[i])}
            </span>
          </div>
        ))}
      </div>

      <div className="stat-cells" style={{ maxWidth: 480, marginTop: 22 }}>
        <div className="stat-cells__cell">
          <div className="stat-row__label">{t("stats.sessionsToday")}</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{s.sessionsStarted}</div>
        </div>
        <div className="stat-cells__cell">
          <div className="stat-row__label">{t("stats.completed")}</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{Math.round(s.completionRate * 100)}%</div>
        </div>
        <div className="stat-cells__cell">
          <div className="stat-row__label">{t("stats.streak")}</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{t("unit.d", { n: s.streakDays, count: s.streakDays })}</div>
        </div>
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 16, maxWidth: 480, marginTop: 14 }}>
        <div>
          <div className="stat-row__label">{t("stats.score")}</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{s.focusScore ?? "-"}</div>
        </div>
        <button className="btn btn-secondary" onClick={copyWeek} style={{ marginInlineStart: "auto", minHeight: 40 }}>
          {copied ? t("stats.copied") : t("stats.copy")}
        </button>
      </div>

      {s.topTargets.length > 0 && (
        <div style={{ maxWidth: 480, marginTop: 24 }}>
          <div className="stat-row__label" style={{ marginBottom: 8 }}>{t("stats.most")}</div>
          {s.topTargets.map((target) => (
            <div key={target.name} style={{ display: "flex", justifyContent: "space-between", padding: "8px 0", borderBottom: "1px solid var(--color-divider)" }}>
              <span style={{ fontWeight: 600 }}><bdi>{target.name}</bdi></span>
              <span style={{ color: "var(--color-neutral-700)" }}>{t("stats.times", { count: target.count })}</span>
            </div>
          ))}
        </div>
      )}

      <p style={{ fontSize: 12, color: "var(--color-neutral-700)", marginTop: 24 }}>
        {t("stats.counted")}
      </p>
    </div>
  );
}

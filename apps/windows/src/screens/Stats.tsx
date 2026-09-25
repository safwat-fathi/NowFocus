import type { AppState } from "../types";

/** The design's weekly bar chart and "most turned away" ranking need
 * per-day and per-target aggregation this build doesn't compute yet (the
 * latter needs BLOCK_ATTEMPT events to carry a target, which they don't
 * record today — see AppState::simulate_block in state.rs). Showing today's
 * real numbers only, rather than a chart built on data that doesn't exist,
 * per the plan's "only ship copy for mechanisms you build" rule. */
export function Stats({ state }: { state: AppState }) {
  const s = state.stats;
  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">Today</span>
        <span className="screen-sub">{new Date().toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" })}</span>
      </div>

      <div className="stats-headline" style={{ marginTop: 22 }}>
        <span className="stats-headline__value">
          {Math.floor(s.todayMinutes / 60)}h {String(s.todayMinutes % 60).padStart(2, "0")}m
        </span>
        <span style={{ fontSize: 16, color: "var(--color-neutral-700)" }}>focused</span>
      </div>

      <div className="stat-cells" style={{ maxWidth: 480 }}>
        <div className="stat-cells__cell">
          <div className="stat-row__label">Sessions</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{s.sessionsStarted}</div>
        </div>
        <div className="stat-cells__cell">
          <div className="stat-row__label">Completed</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{s.sessionsCompleted}</div>
        </div>
        <div className="stat-cells__cell">
          <div className="stat-row__label">Turned away</div>
          <div className="stat-row__value" style={{ fontSize: 30 }}>{s.blockAttemptsToday}</div>
        </div>
      </div>

      <p style={{ fontSize: 12, color: "var(--color-neutral-700)", marginTop: 24 }}>
        Counted on this PC. We never see what you browse.
      </p>
    </div>
  );
}

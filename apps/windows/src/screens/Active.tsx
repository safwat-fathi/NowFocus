import { api, endNormalAfterAsking } from "../lib/api";
import { useI18n } from "../i18n";
import type { AppState } from "../types";

export function Active({
  state,
  onState,
  onBackToFocus,
}: {
  state: AppState;
  onState: (s: AppState) => void;
  onBackToFocus: () => void;
}) {
  const { t, fmt } = useI18n();
  const session = state.session;
  if (!session) {
    // The session ended (completed/cancelled) since this screen opened —
    // recovery already ran server-side on the last poll. Show completion.
    return (
      <div className="active-screen">
        <div className="done-panel">
          <h1 className="done-panel__title">{t("active.done")}</h1>
          <p className="done-panel__body">{t("active.backToNormal")}</p>
          <button className="btn" onClick={onBackToFocus} style={{ background: "var(--color-text)", color: "var(--color-bg)", minHeight: 56, minWidth: 260, justifyContent: "space-between", fontSize: 16, marginTop: 20, alignSelf: "flex-start" }}>
            {t("active.backToFocus")}
          </button>
        </div>
      </div>
    );
  }

  async function endEarly() {
    if (session!.mode === "normal") {
      const ended = await endNormalAfterAsking(t);
      if (ended) onState(ended);
    } else {
      onState(await api.beginUnlock());
    }
  }

  const timerSize = session.remainingLabel.length > 5 ? "168px" : "220px";

  return (
    <div className="active-screen">
      <div className="active-screen__header">
        <span>{session.profileName}</span>
        <span style={{ fontSize: 12, letterSpacing: "0.08em", textTransform: "uppercase" }}>{t("active.mode", { mode: t(`mode.${session.mode}`) })}</span>
      </div>
      <div className="active-grid">
        <div className="active-timer-col">
          <div style={{ fontSize: 17, fontWeight: 600, marginTop: 48 }}>{t("active.keepGoing")}</div>
          <div className="active-timer" style={{ fontSize: timerSize }}>{session.remainingLabel}</div>
          <div className="progress-track progress-track--thick">
            <div className="progress-track__fill" style={{ width: `${session.progressPct}%` }} />
          </div>
          <div style={{ display: "flex", justifyContent: "space-between", fontSize: 14, fontWeight: 600, marginTop: 10 }}>
            <span>{t("active.started", { time: fmt.time(session.startAt) })}</span>
            <span>{t("active.ends", { time: fmt.time(session.endAt) })}</span>
          </div>
        </div>
        <div className="active-side">
          <div className="active-side-list">
            <div className="active-side-row">
              <span className="active-side-row__label">{t("common.thisPc")}</span>
              <span>{t("active.protected")}</span>
            </div>
            <div className="active-side-row">
              <span className="active-side-row__label">{t("active.turnedAway")}</span>
              <span>{state.stats.blockAttemptsToday}</span>
            </div>
          </div>
          {session.paused && (
            <p style={{ fontSize: 14, fontWeight: 600, margin: "14px 0 0" }}>{t("active.cheat")}</p>
          )}
          <p style={{ fontSize: 13, color: "var(--color-neutral-800)", margin: "14px 0 0" }}>
            {t("active.note")}
          </p>
          <div className="active-actions">
            <button
              className="btn"
              onClick={endEarly}
              style={{ background: "transparent", color: "var(--color-text)", border: "2px solid var(--color-text)", minHeight: 52, justifyContent: "space-between", fontSize: 15 }}
            >
              {session.mode === "locked" ? t("active.lockedUntil", { time: fmt.time(session.endAt) }) : t("active.endEarly")}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

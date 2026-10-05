import { useState } from "react";
import { api } from "../lib/api";
import { canStart, profileSummary } from "../lib/profile";
import type { AppState, SessionMode } from "../types";
import { ArrowRightIcon } from "../components/Icons";
import { useI18n } from "../i18n";

const DURATIONS = [25, 45, 60, 90, 120, 180, 240] as const;
type Duration = (typeof DURATIONS)[number];
const MODES: SessionMode[] = ["normal", "strict", "locked"];

export function Focus({
  state,
  onState,
  onOpenSession,
}: {
  state: AppState;
  onState: (s: AppState) => void;
  onOpenSession: () => void;
}) {
  const { t, fmt } = useI18n();
  const [profileId, setProfileId] = useState(state.profiles[0]?.id ?? "");
  const [duration, setDuration] = useState<Duration>(60);
  const [mode, setMode] = useState<SessionMode>("normal");
  const [starting, setStarting] = useState(false);

  const running = !!state.session;
  const healthy = state.health.websiteBlocking === "active";

  async function start() {
    if (!profileId || starting) return;
    setStarting(true);
    try {
      onState(await api.startSession(profileId, duration, mode));
      onOpenSession();
    } finally {
      setStarting(false);
    }
  }

  return (
    <div className="focus-grid">
      <div className="focus-left">
        <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13, color: "var(--color-neutral-700)" }}>
          <span style={{ fontSize: 11, letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--color-accent-700)", fontWeight: 600 }}>
            {running ? t("focus.left", { profile: state.session!.profileName, left: state.session!.remainingLabel }) : t("common.thisPc")}
          </span>
          <span>{fmt.longDay(Date.now())}</span>
        </div>
        <h1 className="focus-hero-title">{running ? t("focus.now") : t("focus.ready")}</h1>

        <div className="device-strip">
          <div className="device-strip__cell">
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <span style={{ width: 12, height: 12, background: running ? "var(--color-accent)" : "var(--color-text)" }} />
              <span style={{ fontSize: 11, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--color-neutral-700)" }}>{t("common.thisPc")}</span>
            </div>
            <div style={{ marginTop: "auto" }}>
              <div style={{ fontWeight: 600, fontSize: 16 }}>{running ? t("focus.enforcing") : healthy ? t("focus.readyShort") : t("focus.setupNeeded")}</div>
              <div style={{ fontSize: 13, color: "var(--color-neutral-700)", marginTop: 3 }}>
                {healthy ? t("focus.allLayers") : t("focus.visitDevices")}
              </div>
            </div>
          </div>
        </div>
        <div style={{ fontSize: 12, color: "var(--color-neutral-700)", padding: "10px 0 0" }}>
          {t("focus.noOther")}
        </div>

        <div className="stat-row">
          <div className="stat-row__cell">
            <div className="stat-row__label">{t("focus.today")}</div>
            <div className="stat-row__value">
              {fmt.hm(state.stats.todayMinutes)}
            </div>
          </div>
          <div className="stat-row__cell">
            <div className="stat-row__label">{t("focus.turnedAway")}</div>
            <div className="stat-row__value">{state.stats.blockAttemptsToday}</div>
          </div>
          <div className="stat-row__cell">
            <div className="stat-row__label">{t("focus.sessions")}</div>
            <div className="stat-row__value">{state.stats.sessionsStarted}</div>
          </div>
        </div>
      </div>

      {!running ? (
        <div className="new-session">
          <div className="new-session__title">{t("focus.newSession")}</div>

          <div className="field-label">{t("focus.profile")}</div>
          {state.profiles.length === 0 && (
            <p style={{ fontSize: 13, color: "var(--color-neutral-700)" }}>{t("focus.createFirst")}</p>
          )}
          {state.profiles.map((p) => (
            <button key={p.id} className="profile-pick" data-selected={p.id === profileId} onClick={() => setProfileId(p.id)}>
              <span className="profile-pick__dot" />
              <span className="profile-pick__name">{p.name}</span>
              <span className="profile-pick__meta">{profileSummary(p, t)}</span>
            </button>
          ))}

          <div className="field-label">{t("focus.duration")}</div>
          <div className="chip-grid chip-grid--7">
            {DURATIONS.map((minutes) => (
              <button key={minutes} className="chip" data-selected={duration === minutes} onClick={() => setDuration(minutes)}>
                {t(`dur.${minutes}`)}
              </button>
            ))}
          </div>

          <div className="field-label">{t("focus.stopEarly")}</div>
          <div className="chip-grid chip-grid--3">
            {MODES.map((key) => (
              <button key={key} className="chip" data-selected={mode === key} onClick={() => setMode(key)}>
                {t(`mode.${key}`)}
              </button>
            ))}
          </div>
          <p className="mode-desc">{t(`focus.mode.${mode}`)}</p>
          {!canStart(state.profiles.find((p) => p.id === profileId)) && (
            <p className="mode-desc">{t("focus.allowOne")}</p>
          )}

          <button
            className="btn btn-primary"
            onClick={start}
            disabled={!canStart(state.profiles.find((p) => p.id === profileId)) || starting}
            style={{ width: "100%", minHeight: 56, justifyContent: "space-between", fontSize: 16, marginTop: "auto" }}
          >
            {starting ? t("focus.starting") : t("focus.start", { duration: t(`dur.${duration}`) })}
            <ArrowRightIcon />
          </button>
        </div>
      ) : (
        <div className="session-summary" style={{ background: "var(--color-accent)" }}>
          <div className="session-summary__meta">
            {t("focus.sessionMeta", { profile: state.session!.profileName, mode: t(`mode.${state.session!.mode}`) })}
          </div>
          <div className="session-summary__timer">{state.session!.remainingLabel}</div>
          <div className="progress-track">
            <div className="progress-track__fill" style={{ width: `${state.session!.progressPct}%` }} />
          </div>
          <div style={{ fontSize: 13, fontWeight: 600, marginTop: 8 }}>{t("focus.ends", { time: fmt.time(state.session!.endAt) })}</div>
          <button
            className="btn"
            onClick={onOpenSession}
            style={{ marginTop: "auto", background: "var(--color-text)", color: "var(--color-bg)", minHeight: 54, justifyContent: "space-between", fontSize: 15 }}
          >
            {t("focus.openSession")}
            <ArrowRightIcon />
          </button>
        </div>
      )}
    </div>
  );
}

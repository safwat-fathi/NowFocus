import { useState } from "react";
import { api, endNormalAfterAsking } from "../lib/api";
import { useI18n } from "../i18n";
import type { AppState } from "../types";

const QUICK_DURATIONS = [25, 60, 120] as const;
type Quick = (typeof QUICK_DURATIONS)[number];

/** The design's tray popup is a small window anchored to the system tray
 * icon; this build's tray click just shows/hides the main window (see
 * setup_tray in lib.rs), so this renders as a normal screen instead of a
 * true anchored popup — a Phase 2 scope cut noted in the plan. */
export function Tray({ state, onState, onOpen }: { state: AppState; onState: (s: AppState) => void; onOpen: () => void }) {
  const { t, fmt } = useI18n();
  const [profileId, setProfileId] = useState(state.profiles[0]?.id ?? "");
  const [duration, setDuration] = useState<Quick>(60);
  const running = !!state.session;

  async function start() {
    if (!profileId) return;
    onState(await api.startSession(profileId, duration, "normal"));
    onOpen();
  }

  async function endEarly() {
    if (state.session!.mode !== "normal") return onState(await api.beginUnlock());
    const ended = await endNormalAfterAsking(t);
    if (ended) onState(ended);
  }

  return (
    <div className="tray-screen">
      <div style={{ flex: 1, position: "relative" }}>
        <div style={{ position: "absolute", insetInlineStart: 40, top: 40, fontSize: 13, color: "var(--color-neutral-700)", maxWidth: 320 }}>
          {t("tray.hint")}
        </div>
        <div style={{ position: "absolute", insetInlineEnd: 12, bottom: 12 }}>
          <div className="tray-popup">
            <div className="tray-popup__header">
              <span className="tray-popup__title">NowFocus</span>
              <span className={"tag " + (running ? "tag-accent" : "tag-neutral")}>{running ? t("tray.focusing") : t("tray.ready")}</span>
            </div>
            {running ? (
              <div style={{ padding: "16px 18px 18px", display: "flex", flexDirection: "column" }}>
                <div style={{ fontSize: 13, fontWeight: 600 }}>{state.session!.profileName}</div>
                <div style={{ fontFamily: "var(--font-heading)", fontWeight: 800, fontSize: 56, lineHeight: 1, letterSpacing: "-0.04em", fontVariantNumeric: "tabular-nums", color: "var(--color-accent)", margin: "8px 0 10px" }}>
                  {state.session!.remainingLabel}
                </div>
                <div style={{ height: 4, background: "var(--color-neutral-300)" }}>
                  <div style={{ height: "100%", width: `${state.session!.progressPct}%`, background: "var(--color-accent)" }} />
                </div>
                <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, marginTop: 16 }}>
                  <button className="btn btn-primary" onClick={onOpen} style={{ minHeight: 42, justifyContent: "flex-start" }}>{t("tray.open")}</button>
                  <button
                    className="btn btn-secondary"
                    onClick={endEarly}
                    style={{ minHeight: 42, justifyContent: "flex-start" }}
                  >
                    {t("tray.endEarly")}
                  </button>
                </div>
              </div>
            ) : (
              <div style={{ padding: "6px 18px 18px", display: "flex", flexDirection: "column" }}>
                {state.profiles.map((p) => (
                  <button key={p.id} className="profile-pick" data-selected={p.id === profileId} onClick={() => setProfileId(p.id)}>
                    <span className="profile-pick__dot" style={{ width: 14, height: 14 }} />
                    <span className="profile-pick__name">{p.name}</span>
                  </button>
                ))}
                <div className="chip-grid chip-grid--3" style={{ marginTop: 12 }}>
                  {QUICK_DURATIONS.map((m) => (
                    <button key={m} className="chip" data-selected={duration === m} onClick={() => setDuration(m)}>{t(`dur.${m}`)}</button>
                  ))}
                </div>
                <button className="btn btn-primary" onClick={start} disabled={!profileId} style={{ minHeight: 46, justifyContent: "space-between", marginTop: 12 }}>
                  {t("tray.start", { duration: t(`dur.${duration}`) })}
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
      <div style={{ height: 48, flex: "none", background: "var(--color-text)", color: "var(--color-bg)", display: "flex", alignItems: "center", padding: "0 12px", gap: 6 }}>
        <span style={{ flex: 1 }} />
        <button onClick={onOpen} title={t("tray.openTitle")} style={{ width: 36, height: 36, border: 0, background: "var(--color-neutral-800)", display: "grid", placeItems: "center", cursor: "pointer" }}>
          <span style={{ width: 12, height: 12, background: "var(--color-accent)" }} />
        </button>
        <span style={{ fontSize: 12, textAlign: "end", lineHeight: 1.3, padding: "0 8px", fontVariantNumeric: "tabular-nums" }}>
          {fmt.time(Date.now())}
        </span>
      </div>
    </div>
  );
}

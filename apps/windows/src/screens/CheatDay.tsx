import { useState } from "react";
import { ask } from "@tauri-apps/plugin-dialog";
import { api } from "../lib/api";
import { errorText, useI18n } from "../i18n";
import type { AppState } from "../types";

/** Plan a day off from blocking, ahead of time: at least 24 hours ahead, one a week, and the Commitment (which
 * the background service holds) stays on. Sessions, Bedtime and schedules pause for the whole local day. */
export function CheatDay({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const { t, fmt } = useI18n();
  const cheat = state.cheatDay;
  const [error, setError] = useState("");

  async function plan(day: string) {
    const yes = await ask(t("cheat.planBody", { day: fmt.longDay(day) }), {
      title: t("cheat.planTitle"), okLabel: t("cheat.yes"), cancelLabel: t("common.cancel"),
    });
    if (!yes) return;
    try {
      onState(await api.scheduleCheatDay(day));
      setError("");
    } catch (e) {
      setError(errorText(e, t));
    }
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">{t("cheat.title")}</span>
      </div>
      <p className="screen-lede">
        {t("cheat.lede")}
      </p>
      <div style={{ maxWidth: 520, marginTop: 20 }}>
        {cheat?.active ? (
          <>
            <h2 style={{ fontSize: 28, margin: "0 0 6px" }}>{t("cheat.onNow")}</h2>
            <p>{t("cheat.pausedUntil", { time: fmt.time(cheat.endAt) })}</p>
            <button className="btn btn-secondary" onClick={async () => onState(await api.cancelCheatDay())} style={{ minHeight: 44 }}>
              {t("cheat.endEarly")}
            </button>
          </>
        ) : cheat?.upcoming ? (
          <>
            <h2 style={{ fontSize: 28, margin: "0 0 6px" }}>{t("cheat.setFor", { day: fmt.longDay(cheat.startAt) })}</h2>
            <p>{t("cheat.cancelNote")}</p>
            <button className="btn btn-secondary" onClick={async () => onState(await api.cancelCheatDay())} style={{ minHeight: 44 }}>
              {t("cheat.cancel")}
            </button>
          </>
        ) : (
          <>
            <div className="field-label">{t("cheat.pick")}</div>
            {state.cheatOptions.map((d) => (
              <button key={d} className="profile-pick" onClick={() => plan(d)}>
                <span className="profile-pick__name">{fmt.longDay(d)}</span>
              </button>
            ))}
          </>
        )}
        {error && <div className="error-line">{error}</div>}
      </div>
    </div>
  );
}

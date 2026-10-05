import { open } from "@tauri-apps/plugin-dialog";
import { api } from "../lib/api";
import { useT } from "../i18n";
import type { AppState } from "../types";

const CHOICES = [15, 30, 45, 60, 90, 120];

/** "30 minutes of Instagram a day." Past it, the app is closed until midnight. Lowering a limit counts at once;
 * raising or removing one only counts from midnight. Paused on a cheat day. */
export function Limits({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const t = useT();
  async function add() {
    const picked = await open({ multiple: false, title: t("limits.choose") });
    if (!picked || Array.isArray(picked)) return;
    // The key is the exe path, lowercase-compared by the backend; a new limit starts at 30 minutes.
    onState(await api.setLimit(picked, picked.split(/[\\/]/).pop() ?? picked, 30));
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">{t("limits.title")}</span>
      </div>
      <p className="screen-lede">
        {t("limits.lede")}
      </p>
      <div style={{ maxWidth: 560, marginTop: 20 }}>
        {state.limits.filter((l) => !l.isSite).map((l) => (
          <div key={l.key} style={{ padding: "12px 0", borderBottom: "1px solid var(--color-neutral-600)" }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
              <strong><bdi>{l.label}</bdi></strong>
              {l.usedUp && <span className="field-label">{t("limits.usedUp")}</span>}
            </div>
            <div style={{ fontSize: 13, color: "var(--color-neutral-400)", margin: "2px 0 8px" }}>
              {t("limits.usage", { used: l.usedMinutes, minutes: l.minutes })}
              {l.pending !== null && ` · ${l.pending === 0 ? t("limits.removedMidnight") : t("limits.fromMidnight", { n: l.pending })}`}
            </div>
            <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
              {CHOICES.map((m) => (
                <button key={m} className="btn btn-secondary" data-active={m === l.minutes} onClick={async () => onState(await api.setLimit(l.key, l.label, m))} style={{ minHeight: 34 }}>
                  {m}
                </button>
              ))}
              <button className="btn" onClick={async () => onState(await api.setLimit(l.key, l.label, 0))} style={{ minHeight: 34 }}>
                {t("common.remove")}
              </button>
            </div>
          </div>
        ))}
        <button className="btn btn-secondary" onClick={add} style={{ minHeight: 40, marginTop: 14 }}>
          {t("limits.add")}
        </button>
      </div>
    </div>
  );
}

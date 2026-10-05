import { api } from "../lib/api";
import { tOr, useT } from "../i18n";
import type { AppState } from "../types";
import { Account } from "./Account";

export function Devices({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const t = useT();
  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">{t("dev.title")}</span>
      </div>
      <p className="screen-lede">
        {t("dev.lede")}
      </p>

      <div className="device-cards">
        <div className="device-card">
          <div className="device-card__head">
            <span className="device-card__dot" style={{ background: state.health.websiteBlocking === "active" ? "var(--color-text)" : "var(--color-accent)" }} />
            <span className="device-card__name">{t("common.thisPc")}</span>
            <span className={"tag " + (state.health.websiteBlocking === "active" ? "tag-neutral" : "tag-accent")}>
              {state.health.websiteBlocking === "active" ? t("dev.active") : t("dev.needsSetup")}
            </span>
          </div>
          <div className="device-card__meta">{t("dev.thisDevice")}</div>
          <div className="device-card__layers">
            {state.health.layers.map((l) => (
              <div className="device-card__layer" key={l.name}>
                <span>{tOr(t, `layer.${l.name}`, l.name)}</span>
                <span style={{ fontWeight: 600, color: l.healthy ? "var(--color-text)" : "var(--color-accent-700)" }}>{tOr(t, `layerState.${l.state}`, l.state)}</span>
              </div>
            ))}
          </div>
          {state.health.websiteBlocking !== "active" && (
            <div className="device-card__fix">
              <div className="device-card__fix-note">
                {import.meta.env.DEV
                  ? t("dev.devMode")
                  : t("dev.noService")}
              </div>
            </div>
          )}
        </div>

      </div>

      <Account state={state} onState={onState} />

      {import.meta.env.DEV && <DevPanel />}
    </div>
  );
}

/** Dev-builds only — a clearly-labeled way to exercise the Shield screen
 * without the real Win32 foreground hook. Gated to debug builds so its
 * synthetic block events never pollute the real "most turned away" stats. */
function DevPanel() {
  async function trigger(kind: string, name: string) {
    await api.simulateBlock(kind, name);
  }
  return (
    <div style={{ marginTop: "auto", paddingTop: 24, borderTop: "1px dashed var(--color-divider)" }}>
      <div style={{ fontSize: 11, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--color-neutral-700)", marginBottom: 8 }}>
        Developer — test the Shield screen (start a session first)
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        <button className="btn btn-secondary" onClick={() => trigger("domain", "reddit.com")}>Simulate blocked site</button>
        <button className="btn btn-secondary" onClick={() => trigger("app", "Steam")}>Simulate blocked app</button>
      </div>
    </div>
  );
}

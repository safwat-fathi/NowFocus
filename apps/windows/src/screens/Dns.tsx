import { useState } from "react";
import { api } from "../lib/api";
import { errorText, useI18n } from "../i18n";
import type { AppState } from "../types";

const SERVERS = [
  ["system", "dns.system", "dns.systemSub"],
  ["adguard_family", "dns.adguard", "dns.adguardSub"],
  ["cloudflare_family", "dns.cloudflare", "dns.cloudflareSub"],
  ["cleanbrowsing_family", "dns.cleanbrowsing", "dns.cleanbrowsingSub"],
  ["quad9", "dns.quad9", "dns.quad9Sub"],
  ["custom", "dns.custom", "dns.customSub"],
] as const;

/**
 * The DNS this PC uses while NowFocus runs, and whether it also runs outside focus sessions. Top to bottom: is it
 * working (a status and one sentence), which server, when. The decisions live in the app (`dns_resolvers::status`).
 */
export function Dns({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const { t } = useI18n();
  const [pick, setPick] = useState(state.dnsProvider);
  const [custom, setCustom] = useState(state.dnsCustom);
  const [error, setError] = useState("");

  const save = async (provider: string, customText: string, alwaysOn: boolean) => {
    setError("");
    try {
      onState(await api.setDns(provider, customText, alwaysOn));
    } catch (e) {
      setError(errorText(e, t));
      setPick(state.dnsProvider);
    }
  };

  const name = t(SERVERS.find(([id]) => id === state.dnsProvider)?.[1] ?? "dns.system");
  const status = state.dnsStatus;
  const attention = status === "notApplied" || status === "unavailable";

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">{t("dns.title")}</span>
      </div>
      <p className="screen-lede">{t("dns.lede")}</p>

      <div style={{ maxWidth: 520 }}>
        <span className={attention ? "tag tag-accent" : "tag tag-neutral"}>{t(`dns.status.${status}`)}</span>
        <p style={{ fontSize: 15, marginTop: 8 }}>{t(`dns.sentence.${status}`, { name })}</p>

        <div className="field-label" style={{ marginTop: 28 }}>{t("dns.server")}</div>
        {SERVERS.map(([id, label, sub]) => (
          <button
            key={id}
            className="profile-pick"
            data-selected={pick === id}
            onClick={() => {
              setPick(id);
              if (id !== "custom") save(id, custom, state.dnsAlwaysOn);
            }}
          >
            <span className="profile-pick__name">{t(label)}</span>
            <span style={{ display: "block", fontSize: 13, color: "var(--color-neutral-700)" }}>{t(sub)}</span>
          </button>
        ))}
        {pick === "custom" && (
          <div className="field">
            <label>{t("dns.customLabel")}</label>
            <input className="input" dir="ltr" maxLength={120} value={custom} onChange={(e) => setCustom(e.target.value)} />
            <button className="btn btn-secondary" disabled={!custom.trim()} onClick={() => save("custom", custom, state.dnsAlwaysOn)} style={{ marginTop: 8 }}>
              {t("dns.save")}
            </button>
          </div>
        )}

        <div className="field-label" style={{ marginTop: 28 }}>{t("dns.when")}</div>
        <div className="chip-grid chip-grid--3">
          <button className="chip" data-selected={!state.dnsAlwaysOn} onClick={() => save(state.dnsProvider, state.dnsCustom, false)}>
            {t("dns.whenSession")}
          </button>
          <button className="chip" data-selected={state.dnsAlwaysOn} onClick={() => save(state.dnsProvider, state.dnsCustom, true)}>
            {t("dns.whenAlways")}
          </button>
        </div>
        <p style={{ fontSize: 13, color: "var(--color-neutral-700)" }}>{t(state.dnsAlwaysOn ? "dns.noteAlways" : "dns.noteSession")}</p>
        {error && <div className="error-line">{error}</div>}

        <p style={{ fontSize: 13, color: "var(--color-neutral-700)", marginTop: 20 }}>{t("dns.limits")}</p>
      </div>
    </div>
  );
}

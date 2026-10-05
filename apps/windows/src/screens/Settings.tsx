import { useEffect, useState } from "react";
import { getVersion } from "@tauri-apps/api/app";
import { openUrl } from "@tauri-apps/plugin-opener";
import { api } from "../lib/api";
import { useI18n, type LangPref } from "../i18n";
import type { AppState } from "../types";

const LANGUAGES: [LangPref, "set.system" | "set.english" | "set.arabic"][] = [
  ["system", "set.system"],
  ["en", "set.english"],
  ["ar", "set.arabic"],
];

const LINKS = [
  ["about.website", "https://nowfocus.online/"],
  ["about.privacy", "https://nowfocus.online/privacy/"],
  ["about.terms", "https://nowfocus.online/terms/"],
  ["about.source", "https://github.com/safwat-fathi/NowFocus"],
  ["about.support", "mailto:safwat.rashwan@gmail.com"],
] as const;

/** App-wide settings: the language, and About (version, links, license). */
export function Settings({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const { t } = useI18n();
  const [version, setVersion] = useState("");
  useEffect(() => {
    getVersion().then(setVersion).catch(() => {});
  }, []);

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">{t("set.title")}</span>
      </div>

      <div style={{ maxWidth: 520, marginTop: 20 }}>
        <div className="field-label" style={{ marginTop: 0 }}>{t("set.language")}</div>
        <div className="chip-grid chip-grid--3">
          {LANGUAGES.map(([value, label]) => (
            <button
              key={value}
              className="chip"
              data-selected={state.language === value}
              onClick={async () => onState(await api.setLanguage(value))}
              // Each language names itself in its own script, so it can be found from the wrong one.
              lang={value === "ar" ? "ar" : value === "en" ? "en" : undefined}
            >
              {t(label)}
            </button>
          ))}
        </div>

        <div className="field-label" style={{ marginTop: 32 }}>{t("about.title")}</div>
        <p className="screen-lede" style={{ margin: "0 0 12px" }}>{t("about.lede")}</p>
        <h2 style={{ fontSize: 28, margin: "0 0 6px" }}>NowFocus</h2>
        <p>{version && t("about.version", { v: version })}</p>
        {LINKS.map(([label, url]) => (
          <button key={url} className="profile-pick" onClick={() => openUrl(url)}>
            <span className="profile-pick__name">{t(label)}</span>
          </button>
        ))}
        <p style={{ marginTop: 20 }}>{t("about.license")}</p>
        <p><bdi dir="ltr">{t("about.copyright")}</bdi></p>
      </div>
    </div>
  );
}

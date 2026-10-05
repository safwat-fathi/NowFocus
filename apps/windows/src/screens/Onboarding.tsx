import { useState } from "react";
import { api } from "../lib/api";
import type { AppState } from "../types";
import { ArrowRightIcon } from "../components/Icons";
import { errorText, useT } from "../i18n";

const FIRST_PROFILE = "First focus";

const LAYERS = [
  { title: "onb.layer1", sub: "onb.layer1Sub" },
  { title: "onb.layer2", sub: "onb.layer2Sub" },
  { title: "onb.layer3", sub: "onb.layer3Sub" },
] as const;

/** Simplified from the design: no phone-pairing step (no pairing backend
 * exists yet — see the plan's cross-device-honesty note), and the layer
 * checklist is informational rather than install buttons, since the real
 * install actions (Windows service, DNS filter, browser extension) land in
 * later phases. Revisit once those exist. */
export function Onboarding({
  state,
  onState,
  onStarted,
  onDone,
}: {
  state: AppState;
  onState: (s: AppState) => void;
  onStarted: () => void;
  onDone: () => void;
}) {
  const t = useT();
  const [site, setSite] = useState("");
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState("");

  // A new profile, not the starter "Deep Work": sync drops an untouched starter on first sign-in, and adding a
  // site to it would make nearly every starter "touched" and duplicate it on the user's next device.
  async function startFirstSession() {
    if (starting) return;
    setStarting(true);
    setError("");
    try {
      let next = state;
      let profile = next.profiles.find((p) => p.name === FIRST_PROFILE);
      if (!profile) {
        next = await api.createProfile(FIRST_PROFILE);
        profile = next.profiles.find((p) => p.name === FIRST_PROFILE);
      }
      if (!profile) throw new Error(t("onb.noProfile"));
      // A retry after a failed start (say the service wasn't running) finds the site already added.
      await api.addDomain(profile.id, site.trim()).catch((e) => {
        if (!String(e).startsWith("listed|")) throw e;
      });
      onState(await api.startSession(profile.id, 10, "normal"));
      onStarted();
    } catch (e) {
      setError(errorText(e, t));
    } finally {
      setStarting(false);
    }
  }

  return (
    <div className="onboard">
      <div className="onboard__pane onboard__pane--left">
        <div className="onboard__kicker">{t("onb.kicker")}</div>
        <h1 className="onboard__title">{t("onb.title")}</h1>
        <p className="onboard__body">
          {t("onb.body")}
        </p>
      </div>
      <div className="onboard__pane">
        <div className="onboard__kicker" style={{ color: "var(--color-neutral-700)" }}>
          {t("onb.protection")}
        </div>
        <h2 style={{ fontSize: 30, letterSpacing: "-0.02em", margin: "14px 0 18px" }}>{t("onb.layersTitle")}</h2>
        <div style={{ borderTop: "2px solid var(--color-divider)" }}>
          {LAYERS.map((l, i) => (
            <div className="layer-row" key={l.title}>
              <span className="layer-row__num">{String(i + 1).padStart(2, "0")}</span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="layer-row__title">{t(l.title)}</div>
                <div className="layer-row__sub">{t(l.sub)}</div>
              </div>
            </div>
          ))}
        </div>
        <p style={{ fontSize: 13, color: "var(--color-neutral-700)", margin: "14px 0 0" }}>
          {t("onb.admin")}
        </p>
        <div className="onboard__kicker" style={{ color: "var(--color-neutral-700)", marginTop: 18 }}>
          {t("onb.first")}
        </div>
        <input
          className="input"
          value={site}
          onChange={(e) => setSite(e.target.value)}
          placeholder={t("onb.site")}
          style={{ marginTop: 8, minHeight: 48, fontSize: 16 }}
        />
        {error && <div className="error-line">{error}</div>}
        <button
          className="btn btn-primary"
          onClick={startFirstSession}
          disabled={!site.trim() || starting}
          style={{ marginTop: "auto", minHeight: 56, justifyContent: "space-between", fontSize: 16 }}
        >
          {t("onb.start")}
          <ArrowRightIcon />
        </button>
        <button className="btn btn-secondary" onClick={onDone} style={{ marginTop: 8, minHeight: 44, justifyContent: "flex-start", fontSize: 14 }}>
          {t("onb.notNow")}
        </button>
      </div>
    </div>
  );
}

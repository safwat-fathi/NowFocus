import { useState } from "react";
import { api } from "../lib/api";
import type { AppState } from "../types";
import { ArrowRightIcon } from "../components/Icons";

const FIRST_PROFILE = "First focus";

const LAYERS = [
  { title: "NowFocus Service", sub: "Runs as a Windows service, restarts itself, survives closing the app." },
  { title: "DNS filter", sub: "Blocks sites for every browser and app on this PC." },
  { title: "Browser extensions", sub: "Chrome, Edge and Firefox. Needed for feed-only blocking." },
];

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
      if (!profile) throw new Error("Couldn't create a profile");
      // A retry after a failed start (say the service wasn't running) finds the site already added.
      await api.addDomain(profile.id, site.trim()).catch((e) => {
        if (!String(e).includes("already on the list")) throw e;
      });
      onState(await api.startSession(profile.id, 10, "normal"));
      onStarted();
    } catch (e) {
      setError(String(e));
    } finally {
      setStarting(false);
    }
  }

  return (
    <div className="onboard">
      <div className="onboard__pane onboard__pane--left">
        <div className="onboard__kicker">Welcome to NowFocus on this PC</div>
        <h1 className="onboard__title">One session. Every screen you own.</h1>
        <p className="onboard__body">
          Start a focus session and NowFocus blocks the sites and apps you choose. Blocking runs as a Windows
          service, so it holds even when this app is closed.
        </p>
      </div>
      <div className="onboard__pane">
        <div className="onboard__kicker" style={{ color: "var(--color-neutral-700)" }}>
          Protection on this PC
        </div>
        <h2 style={{ fontSize: 30, letterSpacing: "-0.02em", margin: "14px 0 18px" }}>Three layers, so it actually holds.</h2>
        <div style={{ borderTop: "2px solid var(--color-divider)" }}>
          {LAYERS.map((l, i) => (
            <div className="layer-row" key={l.title}>
              <span className="layer-row__num">{String(i + 1).padStart(2, "0")}</span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="layer-row__title">{l.title}</div>
                <div className="layer-row__sub">{l.sub}</div>
              </div>
            </div>
          ))}
        </div>
        <p style={{ fontSize: 13, color: "var(--color-neutral-700)", margin: "14px 0 0" }}>
          Windows will ask for admin approval once. Your browsing never leaves this PC.
        </p>
        <div className="onboard__kicker" style={{ color: "var(--color-neutral-700)", marginTop: 18 }}>
          Your first session
        </div>
        <input
          className="input"
          value={site}
          onChange={(e) => setSite(e.target.value)}
          placeholder="A site that steals your time, e.g. youtube.com"
          style={{ marginTop: 8, minHeight: 48, fontSize: 16 }}
        />
        {error && <div className="error-line">{error}</div>}
        <button
          className="btn btn-primary"
          onClick={startFirstSession}
          disabled={!site.trim() || starting}
          style={{ marginTop: "auto", minHeight: 56, justifyContent: "space-between", fontSize: 16 }}
        >
          Start 10 minutes
          <ArrowRightIcon />
        </button>
        <button className="btn btn-secondary" onClick={onDone} style={{ marginTop: 8, minHeight: 44, justifyContent: "flex-start", fontSize: 14 }}>
          Not now
        </button>
      </div>
    </div>
  );
}

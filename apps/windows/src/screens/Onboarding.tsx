import { ArrowRightIcon } from "../components/Icons";

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
export function Onboarding({ onDone }: { onDone: () => void }) {
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
        <button className="btn btn-primary" onClick={onDone} style={{ marginTop: "auto", minHeight: 56, justifyContent: "space-between", fontSize: 16 }}>
          Get started
          <ArrowRightIcon />
        </button>
      </div>
    </div>
  );
}

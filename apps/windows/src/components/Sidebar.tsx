import type { AppState, ScreenId } from "../types";
import { BedtimeIcon, CommitmentIcon, DevicesIcon, FocusIcon, ProfilesIcon, StatsIcon } from "./Icons";

export function Sidebar({ state, screen, onNavigate }: { state: AppState; screen: ScreenId; onNavigate: (s: ScreenId) => void }) {
  const running = !!state.session;

  return (
    <div className="sidebar">
      <div className="sidebar__brand">NowFocus</div>
      <button className="nav-item" data-active={screen === "focus"} onClick={() => onNavigate("focus")}>
        <FocusIcon />
        <span className="nav-item__label">Focus</span>
        {running && <span className="nav-item__timer">{state.session!.remainingLabel}</span>}
      </button>
      <button className="nav-item" data-active={screen === "profiles"} onClick={() => onNavigate("profiles")}>
        <ProfilesIcon />
        <span className="nav-item__label">Profiles</span>
      </button>
      <button className="nav-item" data-active={screen === "devices"} onClick={() => onNavigate("devices")}>
        <DevicesIcon />
        <span className="nav-item__label">Devices</span>
        {state.health.websiteBlocking !== "active" && <span className="nav-item__dot" />}
      </button>
      <button className="nav-item" data-active={screen === "stats"} onClick={() => onNavigate("stats")}>
        <StatsIcon />
        <span className="nav-item__label">Stats</span>
      </button>

      <div className="sidebar__section-label">Always on</div>
      <button className="nav-item" data-active={screen === "commitment"} onClick={() => onNavigate("commitment")}>
        <CommitmentIcon />
        <span className="nav-item__label">Commitment</span>
        {state.commitment && <span className="nav-item__dot" />}
      </button>
      <button className="nav-item" data-active={screen === "bedtime"} onClick={() => onNavigate("bedtime")}>
        <BedtimeIcon />
        <span className="nav-item__label">Bedtime</span>
        {state.bedtime.enabled && <span className="nav-item__dot" />}
      </button>

      <div className="sidebar__footer">
        <div className="sidebar__footer-title">This PC</div>
        <div className="sidebar__footer-sub">
          {state.health.websiteBlocking === "active" ? "Enforcing" : "No pairing yet — this device only"}
        </div>
      </div>
    </div>
  );
}

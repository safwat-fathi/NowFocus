import { useT } from "../i18n";
import type { AppState, ScreenId } from "../types";
import { BedtimeIcon, CommitmentIcon, DevicesIcon, FocusIcon, GlobeIcon, ProfilesIcon, StatsIcon } from "./Icons";

export function Sidebar({ state, screen, onNavigate }: { state: AppState; screen: ScreenId; onNavigate: (s: ScreenId) => void }) {
  const t = useT();
  const running = !!state.session;

  return (
    <div className="sidebar">
      <div className="sidebar__brand">NowFocus</div>
      <button className="nav-item" data-active={screen === "focus"} onClick={() => onNavigate("focus")}>
        <FocusIcon />
        <span className="nav-item__label">{t("nav.focus")}</span>
        {running && <span className="nav-item__timer">{state.session!.remainingLabel}</span>}
      </button>
      <button className="nav-item" data-active={screen === "profiles"} onClick={() => onNavigate("profiles")}>
        <ProfilesIcon />
        <span className="nav-item__label">{t("nav.profiles")}</span>
      </button>
      <button className="nav-item" data-active={screen === "devices"} onClick={() => onNavigate("devices")}>
        <DevicesIcon />
        <span className="nav-item__label">{t("nav.devices")}</span>
        {state.health.websiteBlocking !== "active" && <span className="nav-item__dot" />}
      </button>
      <button className="nav-item" data-active={screen === "stats"} onClick={() => onNavigate("stats")}>
        <StatsIcon />
        <span className="nav-item__label">{t("nav.stats")}</span>
      </button>

      <div className="sidebar__section-label">{t("nav.alwaysOn")}</div>
      <button className="nav-item" data-active={screen === "commitment"} onClick={() => onNavigate("commitment")}>
        <CommitmentIcon />
        <span className="nav-item__label">{t("nav.commitment")}</span>
        {state.commitment && <span className="nav-item__dot" />}
      </button>
      <button className="nav-item" data-active={screen === "bedtime"} onClick={() => onNavigate("bedtime")}>
        <BedtimeIcon />
        <span className="nav-item__label">{t("nav.bedtime")}</span>
        {state.bedtime.enabled && <span className="nav-item__dot" />}
      </button>

      <button className="nav-item" data-active={screen === "dns"} onClick={() => onNavigate("dns")}>
        <GlobeIcon />
        <span className="nav-item__label">{t("nav.dns")}</span>
        {state.dnsAlwaysOn && state.dnsProvider !== "system" && <span className="nav-item__dot" />}
      </button>

      <button className="nav-item" data-active={screen === "schedules"} onClick={() => onNavigate("schedules")}>
        <FocusIcon />
        <span className="nav-item__label">{t("nav.schedules")}</span>
        {state.schedules.some((s) => s.enabled) && <span className="nav-item__dot" />}
      </button>
      <button className="nav-item" data-active={screen === "limits"} onClick={() => onNavigate("limits")}>
        <StatsIcon />
        <span className="nav-item__label">{t("nav.limits")}</span>
        {state.limits.some((l) => l.usedUp) && <span className="nav-item__dot" />}
      </button>
      <button className="nav-item" data-active={screen === "cheatday"} onClick={() => onNavigate("cheatday")}>
        <StatsIcon />
        <span className="nav-item__label">{t("nav.cheatday")}</span>
        {state.cheatDay?.active && <span className="nav-item__dot" />}
      </button>

      <button className="nav-item" data-active={screen === "settings"} onClick={() => onNavigate("settings")}>
        <FocusIcon />
        <span className="nav-item__label">{t("nav.settings")}</span>
      </button>

      <div className="sidebar__footer">
        <div className="sidebar__footer-title">{t("nav.footerTitle")}</div>
        <div className="sidebar__footer-sub">
          {state.health.websiteBlocking === "active" ? t("nav.enforcing") : t("nav.noPairing")}
        </div>
      </div>
    </div>
  );
}

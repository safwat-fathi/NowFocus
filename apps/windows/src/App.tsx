import { useCallback, useEffect, useState } from "react";
import { TitleBar } from "./components/TitleBar";
import { Sidebar } from "./components/Sidebar";
import { UnlockDialog } from "./components/UnlockDialog";
import { api } from "./lib/api";
import { I18nProvider, storedPref, useI18n, type LangPref } from "./i18n";
import type { AppState, ScreenId } from "./types";

import { Settings } from "./screens/Settings";
import { CheatDay } from "./screens/CheatDay";
import { Limits } from "./screens/Limits";
import { Onboarding } from "./screens/Onboarding";
import { Schedules } from "./screens/Schedules";
import { Focus } from "./screens/Focus";
import { Active } from "./screens/Active";
import { Shield } from "./screens/Shield";
import { Profiles } from "./screens/Profiles";
import { Devices } from "./screens/Devices";
import { Stats } from "./screens/Stats";
import { Commitment } from "./screens/Commitment";
import { Bedtime } from "./screens/Bedtime";
import { Tray } from "./screens/Tray";

/** Tells the backend what the tray icon's menu and tooltip say, in the current language (it holds no translations). */
function TrayLabelsSync() {
  const { t, lang } = useI18n();
  useEffect(() => {
    api.setTrayLabels(t("tray.menuOpen"), t("tray.menuQuit"), t("tray.tooltipIdle"), t("tray.tooltipLeft")).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps -- t only changes with lang
  }, [lang]);
  return null;
}

const ONBOARDING_SEEN_KEY = "nowfocus.onboardingSeen";

export default function App() {
  const [state, setState] = useState<AppState | null>(null);
  const [screen, setScreen] = useState<ScreenId>(() =>
    localStorage.getItem(ONBOARDING_SEEN_KEY) ? "focus" : "onboard",
  );

  const refresh = useCallback((next: AppState) => setState(next), []);

  useEffect(() => {
    let cancelled = false;
    api.getState().then((s) => !cancelled && setState(s));
    const id = setInterval(() => {
      api.getState().then((s) => !cancelled && setState(s));
    }, 500);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, []);

  const navigate = useCallback((s: ScreenId) => {
    if (s !== "onboard") localStorage.setItem(ONBOARDING_SEEN_KEY, "1");
    setScreen(s);
  }, []);

  // The backend's language wins once it has answered; until then the last one it reported, so the first paint is right.
  const pref = (state?.language as LangPref | undefined) ?? storedPref();
  if (!state) {
    return <I18nProvider pref={pref}><div className="app-shell" /></I18nProvider>;
  }

  // An open unlock flow wins over everything (it is the way out of the shield);
  // otherwise a real block event takes over the screen no matter what the user
  // was looking at — matches the design's `full` takeover for "shield".
  const view: ScreenId | "shield" = state.unlock ? "active" : state.shield ? "shield" : screen;
  const showChrome = view !== "shield" && view !== "tray";
  const showSidebar = showChrome && view !== "onboard";

  const titleSuffix = state.session ? ` · ${state.session.profileName} · ${state.session.remainingLabel}` : "";

  return (
    <I18nProvider pref={pref}>
    <TrayLabelsSync />
    <div className="app-shell">
      {showChrome && <TitleBar suffix={titleSuffix} />}
      <div className="app-body">
        {showSidebar && <Sidebar state={state} screen={screen} onNavigate={navigate} />}
        <div
          className={
            "main" +
            (view === "active" ? " main--accent" : "") +
            (view === "shield" || view === "tray" ? " main--dark" : "")
          }
        >
          {view === "onboard" && <Onboarding state={state} onState={refresh} onStarted={() => navigate("active")} onDone={() => navigate("focus")} />}
          {view === "focus" && <Focus state={state} onState={refresh} onOpenSession={() => navigate("active")} />}
          {view === "active" && <Active state={state} onState={refresh} onBackToFocus={() => navigate("focus")} />}
          {view === "profiles" && <Profiles state={state} onState={refresh} />}
          {view === "devices" && <Devices state={state} onState={refresh} />}
          {view === "stats" && <Stats state={state} />}
          {view === "commitment" && <Commitment state={state} onState={refresh} />}
          {view === "bedtime" && <Bedtime state={state} onState={refresh} />}
          {view === "schedules" && <Schedules state={state} onState={refresh} />}
          {view === "limits" && <Limits state={state} onState={refresh} />}
          {view === "cheatday" && <CheatDay state={state} onState={refresh} />}
          {view === "settings" && <Settings state={state} onState={refresh} />}
          {view === "tray" && <Tray state={state} onState={refresh} onOpen={() => navigate(state.session ? "active" : "focus")} />}
          {view === "shield" && <Shield state={state} onState={refresh} />}
        </div>
      </div>
      {state.unlock && <UnlockDialog state={state} onState={refresh} />}
    </div>
    </I18nProvider>
  );
}

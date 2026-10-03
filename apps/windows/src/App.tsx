import { useCallback, useEffect, useState } from "react";
import { TitleBar } from "./components/TitleBar";
import { Sidebar } from "./components/Sidebar";
import { UnlockDialog } from "./components/UnlockDialog";
import { api } from "./lib/api";
import type { AppState, ScreenId } from "./types";

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

  if (!state) {
    return <div className="app-shell" />;
  }

  // An open unlock flow wins over everything (it is the way out of the shield);
  // otherwise a real block event takes over the screen no matter what the user
  // was looking at — matches the design's `full` takeover for "shield".
  const view: ScreenId | "shield" = state.unlock ? "active" : state.shield ? "shield" : screen;
  const showChrome = view !== "shield" && view !== "tray";
  const showSidebar = showChrome && view !== "onboard";

  const titleSuffix = state.session ? ` · ${state.session.profileName} · ${state.session.remainingLabel}` : "";

  return (
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
          {view === "tray" && <Tray state={state} onState={refresh} onOpen={() => navigate(state.session ? "active" : "focus")} />}
          {view === "shield" && <Shield state={state} onState={refresh} />}
        </div>
      </div>
      {state.unlock && <UnlockDialog state={state} onState={refresh} />}
    </div>
  );
}

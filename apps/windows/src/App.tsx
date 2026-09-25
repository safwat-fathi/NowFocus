import { useCallback, useEffect, useState } from "react";
import { TitleBar } from "./components/TitleBar";
import { Sidebar } from "./components/Sidebar";
import { api } from "./lib/api";
import type { AppState, ScreenId } from "./types";

import { Onboarding } from "./screens/Onboarding";
import { Focus } from "./screens/Focus";
import { Active } from "./screens/Active";
import { Shield } from "./screens/Shield";
import { Profiles } from "./screens/Profiles";
import { Devices } from "./screens/Devices";
import { Stats } from "./screens/Stats";
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

  // A real block event takes over the screen no matter what the user was
  // looking at — matches the design's `full` takeover for "shield".
  const view: ScreenId | "shield" = state.shield ? "shield" : screen;
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
          {view === "onboard" && <Onboarding onDone={() => navigate("focus")} />}
          {view === "focus" && <Focus state={state} onState={refresh} onOpenSession={() => navigate("active")} />}
          {view === "active" && <Active state={state} onState={refresh} onBackToFocus={() => navigate("focus")} />}
          {view === "profiles" && <Profiles state={state} onState={refresh} />}
          {view === "devices" && <Devices state={state} />}
          {view === "stats" && <Stats state={state} />}
          {view === "tray" && <Tray state={state} onState={refresh} onOpen={() => navigate(state.session ? "active" : "focus")} />}
          {view === "shield" && <Shield state={state} onState={refresh} />}
        </div>
      </div>
    </div>
  );
}

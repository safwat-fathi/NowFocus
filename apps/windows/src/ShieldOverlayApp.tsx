import { useEffect, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { api } from "./lib/api";
import type { AppState } from "./types";
import { Shield } from "./screens/Shield";

/** Rendered instead of the full <App/> in each per-monitor overlay window
 * (see src-tauri/src/overlay.rs) — one Shield screen, no sidebar or title
 * bar, and no ownership of dismissal: it polls the same shared state as the
 * main window and closes itself the moment `shield` clears, however that
 * happened (the user dismissed it from any monitor's copy, or the session
 * ended). Whichever window's button the user clicks updates the one shared
 * backend state; every overlay reacts to that, not just the one clicked. */
export default function ShieldOverlayApp() {
  const [state, setState] = useState<AppState | null>(null);

  useEffect(() => {
    let cancelled = false;
    const poll = () => api.getState().then((s) => !cancelled && setState(s));
    poll();
    const id = setInterval(poll, 500);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, []);

  useEffect(() => {
    if (state && !state.shield) {
      getCurrentWindow().close();
    }
  }, [state]);

  if (!state || !state.shield) {
    return <div className="app-shell main main--dark" />;
  }

  return (
    <div className="app-shell">
      <div className="main main--dark" style={{ flex: 1 }}>
        <Shield state={state} onState={setState} />
      </div>
    </div>
  );
}

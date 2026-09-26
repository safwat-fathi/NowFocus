import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import ShieldOverlayApp from "./ShieldOverlayApp";
import "./styles/theme.css";
import "./styles/app.css";

// The per-monitor Shield overlay windows (src-tauri/src/overlay.rs) load
// this same bundle at "index.html#/shield-overlay" instead of a second
// entry point, so there's one build to keep in sync, not two.
const isShieldOverlay = window.location.hash === "#/shield-overlay";

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>{isShieldOverlay ? <ShieldOverlayApp /> : <App />}</React.StrictMode>,
);

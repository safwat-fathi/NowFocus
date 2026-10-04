import { useEffect, useState } from "react";
import { getVersion } from "@tauri-apps/api/app";
import { openUrl } from "@tauri-apps/plugin-opener";

const LINKS: [string, string][] = [
  ["Website", "https://nowfocus.online/"],
  ["Privacy policy", "https://nowfocus.online/privacy/"],
  ["Terms", "https://nowfocus.online/terms/"],
  ["Source code", "https://github.com/safwat-fathi/NowFocus"],
  ["Contact support", "mailto:safwat.rashwan@gmail.com"],
];

export function About() {
  const [version, setVersion] = useState("");
  useEffect(() => {
    getVersion().then(setVersion).catch(() => {});
  }, []);

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">About</span>
      </div>
      <p className="screen-lede">
        NowFocus blocks the apps that pull you away, and keeps them blocked until you're done.
      </p>
      <div style={{ maxWidth: 520, marginTop: 20 }}>
        <h2 style={{ fontSize: 28, margin: "0 0 6px" }}>NowFocus</h2>
        <p>{version && `Version ${version}`}</p>
        {LINKS.map(([label, url]) => (
          <button key={url} className="profile-pick" onClick={() => openUrl(url)}>
            <span className="profile-pick__name">{label}</span>
          </button>
        ))}
        <p style={{ marginTop: 20 }}>Source available under the FSL-1.1-ALv2 license.</p>
        <p>© 2026 Safwat Fathi</p>
      </div>
    </div>
  );
}

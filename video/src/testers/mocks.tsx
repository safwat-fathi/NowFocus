import React from "react";
import { C, FONT, useScene } from "../common";

// Stylized stand-ins for the real screens. Copy is the shipped copy (apps/windows/src/i18n, apps/android strings_*.xml).
const COPY = {
  en: { title: "NowFocus closed Instagram", wait: "This can wait.", tries: "Tries today", back: "Back to focus", lang: "LANGUAGE", pick: "English" },
  ar: { title: "أغلق NowFocus Instagram", wait: "هذا يمكن أن ينتظر.", tries: "المحاولات اليوم", back: "العودة إلى التركيز", lang: "اللغة", pick: "العربية" },
};

/** The Android block screen. `ar` flips it to Arabic and right-to-left. */
export const AndroidBlockMock: React.FC<{ w: number; ar?: boolean; showLang?: boolean }> = ({ w, ar = false, showLang = false }) => {
  const { u } = useScene();
  const t = COPY[ar ? "ar" : "en"];
  const k = w / 633; // design width of the old Android shots
  return (
    <div style={{ width: w + 32 * u, height: w * 2 + 32 * u, borderRadius: 74 * k * u, background: C.ink, padding: 16 * u, boxShadow: `0 ${40 * u}px ${120 * u}px rgba(0,0,0,0.55), 0 0 ${90 * u}px rgba(236,48,19,0.25)` }}>
      <div
        dir={ar ? "rtl" : "ltr"}
        style={{ width: w, height: w * 2, borderRadius: 60 * k * u, background: C.ground, fontFamily: FONT, color: C.ink, padding: `${90 * k * u}px ${44 * k * u}px`, display: "flex", flexDirection: "column", gap: 28 * k * u, boxSizing: "border-box" }}
      >
        <div style={{ width: 90 * k * u, height: 90 * k * u, background: C.accent }} />
        <div style={{ fontWeight: 900, fontSize: 64 * k * u, lineHeight: 1.05, letterSpacing: ar ? 0 : "-0.03em" }}>{t.title}</div>
        <div style={{ fontWeight: 600, fontSize: 40 * k * u, color: C.mute }}>{t.wait}</div>
        <div style={{ display: "flex", justifyContent: "space-between", fontWeight: 700, fontSize: 32 * k * u, borderTop: `${3 * u}px solid ${C.rule}`, paddingTop: 22 * k * u }}>
          <span>{t.tries}</span>
          <span>3</span>
        </div>
        {showLang && (
          <div style={{ background: C.surface, border: `${3 * u}px solid ${C.rule}`, padding: `${18 * k * u}px ${24 * k * u}px`, display: "flex", justifyContent: "space-between", fontWeight: 700, fontSize: 32 * k * u }}>
            <span style={{ color: C.mute, fontSize: 26 * k * u }}>{t.lang}</span>
            <span>{t.pick}</span>
          </div>
        )}
        <div style={{ marginTop: "auto", background: C.accent, color: "#fff", textAlign: "center", fontWeight: 900, fontSize: 38 * k * u, padding: `${28 * k * u}px 0` }}>{t.back}</div>
      </div>
    </div>
  );
};

const NAV = ["Focus", "Profiles", "Devices", "Stats", "Settings"];

/** A Windows window (dark chrome, nav on the left) showing the update banner from Settings. `bannerP` 0..1 slides it in. */
export const WindowsWindowMock: React.FC<{ w: number; bannerP: number; installed: boolean }> = ({ w, bannerP, installed }) => {
  const { u } = useScene();
  const bar = 54 * u;
  return (
    <div style={{ width: w, background: "#202020", border: `${2 * u}px solid #3a3a3a`, boxShadow: `0 ${40 * u}px ${100 * u}px rgba(0,0,0,0.6)`, fontFamily: FONT }}>
      <div style={{ height: bar, display: "flex", alignItems: "center", justifyContent: "space-between", paddingLeft: 20 * u, color: "#e5e5e5", fontSize: 24 * u, fontWeight: 600 }}>
        <span>NowFocus</span>
        <span style={{ display: "flex", fontSize: 26 * u }}>
          {["—", "▢", "✕"].map((g) => <span key={g} style={{ width: 76 * u, textAlign: "center" }}>{g}</span>)}
        </span>
      </div>
      <div style={{ display: "flex", height: w * 0.52, background: C.ground }}>
        <div style={{ width: "28%", background: C.surface, borderRight: `${2 * u}px solid ${C.rule}`, padding: `${28 * u}px ${20 * u}px`, display: "flex", flexDirection: "column", gap: 14 * u }}>
          {NAV.map((n) => (
            <div key={n} style={{ fontWeight: 800, fontSize: 28 * u, padding: `${10 * u}px ${14 * u}px`, color: n === "Settings" ? "#fff" : C.ink, background: n === "Settings" ? C.accent : "transparent" }}>{n}</div>
          ))}
        </div>
        <div style={{ flex: 1, padding: 32 * u, color: C.ink }}>
          <div style={{ fontWeight: 900, fontSize: 44 * u, letterSpacing: "-0.02em" }}>Updates</div>
          <div
            style={{ marginTop: 26 * u, background: C.white, border: `${4 * u}px solid ${installed ? C.ink : C.accent}`, padding: 24 * u, opacity: bannerP, translate: `0 ${(1 - bannerP) * 40 * u}px` }}
          >
            <div style={{ fontWeight: 700, fontSize: 30 * u }}>{installed ? "Updating… NowFocus will restart." : "Version 0.4.2 is available."}</div>
            {!installed && <div style={{ marginTop: 18 * u, display: "inline-block", background: C.accent, color: "#fff", fontWeight: 900, fontSize: 28 * u, padding: `${10 * u}px ${28 * u}px` }}>Update now</div>}
          </div>
        </div>
      </div>
    </div>
  );
};

const Device: React.FC<{ w: number; h: number; label: string; radius: number; bg: string; fg: string }> = ({ w, h, label, radius, bg, fg }) => {
  const { u } = useScene();
  return (
    <div style={{ width: w, height: h, borderRadius: radius, background: bg, border: `${10 * u}px solid ${C.ink}`, boxSizing: "border-box", display: "flex", alignItems: "flex-end", justifyContent: "center", padding: 24 * u, fontFamily: FONT, fontWeight: 800, fontSize: 34 * u, color: fg }}>
      {label}
    </div>
  );
};

/** Phone and PC joined by a session pill. `p` 0..1 draws the link. */
export const SyncMock: React.FC<{ size: number; p: number; vertical: boolean }> = ({ size, p, vertical }) => {
  const { u } = useScene();
  const link = size * 0.5;
  return (
    <div style={{ display: "flex", flexDirection: vertical ? "column" : "row", alignItems: "center" }}>
      <Device w={size * 0.38} h={size * 0.7} label="Phone" radius={34 * u} bg={C.white} fg={C.ink} />
      <div style={{ position: "relative", [vertical ? "height" : "width"]: link, [vertical ? "width" : "height"]: 8 * u, background: C.rule, display: "flex", alignItems: "center", justifyContent: "center" }}>
        <div style={{ position: "absolute", [vertical ? "height" : "width"]: "100%", [vertical ? "width" : "height"]: "100%", background: C.accent, transformOrigin: vertical ? "top" : "left", scale: vertical ? `1 ${p}` : `${p} 1` }} />
        <div style={{ position: "relative", background: C.ink, color: "#fff", fontFamily: FONT, fontWeight: 900, fontSize: 30 * u, padding: `${10 * u}px ${24 * u}px`, whiteSpace: "nowrap", opacity: p > 0.9 ? 1 : 0 }}>Focus session</div>
      </div>
      <Device w={size * 0.7} h={size * 0.48} label="This PC" radius={10 * u} bg={C.ink} fg="#fff" />
    </div>
  );
};

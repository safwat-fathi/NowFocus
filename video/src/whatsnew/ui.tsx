import React, { createContext, useContext } from "react";
import { Easing, interpolate, useCurrentFrame } from "remotion";
import { C } from "../common";
import { Sfx } from "../challenger/fx";

// The Android app's design tokens (Theme.kt) in dp, drawn as 1 dp = 1 css px on a 360 x 720 screen.
// Everything here is a stand-in for a real Compose screen: copy comes from res/values*/strings_*.xml.
export const NF = {
  bg: "#F3F2F2", surface: "#EAE9E9", text: "#201E1D", accent: "#EC3013", divider: "rgba(32,30,29,0.4)",
  n100: "#F8F4F4", n300: "#D7D3D3", n400: "#BAB6B6", n500: "#9B9797", n600: "#7D7979", n700: "#605D5D", n800: "#444141",
  a100: "#FFF2EF", a400: "#FF9783", a700: "#AE1800", a800: "#7C1405",
};
export const SW = 360;
export const SH = 720;
const FAM = 'Archivo, "Geeza Pro", "Noto Sans Arabic", sans-serif';

const Rtl = createContext(false);
export const useRtl = () => useContext(Rtl);

/** The screen itself: fixed size, clipped, `rtl` flips the whole layout like the app does. */
export const Device: React.FC<{ rtl?: boolean; bg?: string; children: React.ReactNode }> = ({ rtl = false, bg = NF.bg, children }) => (
  <Rtl.Provider value={rtl}>
    <div dir={rtl ? "rtl" : "ltr"} style={{ width: SW, height: SH, background: bg, position: "relative", overflow: "hidden", fontFamily: FAM, color: NF.text, fontSize: 15, lineHeight: 1.2 }}>
      {children}
    </div>
  </Rtl.Provider>
);

export const StatusBar: React.FC<{ dark?: boolean }> = ({ dark = false }) => {
  const c = dark ? NF.bg : NF.text;
  return (
    <div style={{ height: 28, padding: "0 18px", display: "flex", alignItems: "center", justifyContent: "space-between", fontSize: 12, fontWeight: 600, color: c, direction: "ltr" }}>
      <span>9:41</span>
      <span style={{ display: "flex", gap: 5, alignItems: "center" }}>
        <span style={{ width: 12, height: 8, background: c, opacity: 0.85, clipPath: "polygon(0 100%,100% 100%,100% 0)" }} />
        <span style={{ width: 18, height: 9, border: `1.5px solid ${c}`, padding: 1, boxSizing: "border-box" }}><span style={{ display: "block", width: "70%", height: "100%", background: c }} /></span>
      </span>
    </div>
  );
};

/** A scrolling page body: `y` is the scroll offset in dp. */
export const Body: React.FC<{ y?: number; children: React.ReactNode; style?: React.CSSProperties }> = ({ y = 0, children, style }) => (
  <div style={{ padding: "0 16px", transform: `translateY(${-y}px)`, ...style }}>{children}</div>
);

export const Kicker: React.FC<{ color?: string; children: React.ReactNode }> = ({ color = NF.a700, children }) => {
  const rtl = useRtl();
  return <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: rtl ? 0 : 1.1, color }}>{children}</div>;
};

export const H: React.FC<{ size: number; color?: string; children: React.ReactNode; style?: React.CSSProperties }> = ({ size, color = NF.text, children, style }) => {
  const rtl = useRtl();
  return <div style={{ fontSize: size, fontWeight: 800, color, lineHeight: 1.1, letterSpacing: rtl ? 0 : (-0.02 * size) / 16, ...style }}>{children}</div>;
};

export const T: React.FC<{ size?: number; weight?: number; color?: string; children: React.ReactNode; style?: React.CSSProperties }> = ({ size = 13, weight = 400, color = NF.n700, children, style }) => (
  <div style={{ fontSize: size, fontWeight: weight, color, ...style }}>{children}</div>
);

export const Rule: React.FC<{ thick?: boolean; color?: string; style?: React.CSSProperties }> = ({ thick, color = NF.divider, style }) => (
  <div style={{ height: thick ? 2 : 1, background: color, ...style }} />
);

export const Pill: React.FC<{ accent?: boolean; children: React.ReactNode }> = ({ accent = true, children }) => (
  <span style={{ display: "inline-block", background: accent ? NF.a100 : NF.n100, color: accent ? NF.a800 : NF.n800, fontSize: 11, padding: "3px 10px" }}>{children}</span>
);

/** On/off track from the app. `p` 0..1 animates the knob. */
export const Toggle: React.FC<{ p: number; dark?: boolean }> = ({ p, dark }) => {
  const on = p > 0.5;
  return (
    <div style={{ width: 46, height: 26, boxSizing: "border-box", border: `2px solid ${on ? NF.accent : NF.n500}`, background: on ? NF.accent : "transparent", flex: "none", position: "relative" }}>
      <div style={{ position: "absolute", top: 3, insetInlineStart: 3 + 20 * p, width: 16, height: 16, background: on ? NF.bg : dark ? NF.n400 : NF.n600 }} />
    </div>
  );
};

export const ToggleRow: React.FC<{ label: string; sub: string; p: number; dark?: boolean; mark?: boolean }> = ({ label, sub, p, dark, mark }) => (
  <div style={{ display: "flex", alignItems: "center", padding: "12px 0", background: mark ? "rgba(236,48,19,0.08)" : undefined }}>
    <div style={{ flex: 1, paddingInlineEnd: 12 }}>
      <T size={15} weight={600} color={dark ? NF.bg : NF.text}>{label}</T>
      <T size={12} color={dark ? NF.n400 : NF.n700}>{sub}</T>
    </div>
    <Toggle p={p} dark={dark} />
  </div>
);

/** Segmented control; `sel` may be fractional-free index. */
export const Seg: React.FC<{ options: string[]; sel: number }> = ({ options, sel }) => (
  <div style={{ display: "flex", border: `1px solid ${NF.divider}` }}>
    {options.map((o, i) => (
      <div key={o} style={{ flex: 1, textAlign: "center", padding: "8px 4px", fontSize: 13, fontWeight: 800, background: i === sel ? NF.accent : "transparent", color: i === sel ? NF.bg : NF.text, borderInlineStart: i ? `1px solid ${NF.divider}` : undefined }}>{o}</div>
    ))}
  </div>
);

export const Primary: React.FC<{ children: React.ReactNode; style?: React.CSSProperties }> = ({ children, style }) => (
  <div style={{ background: NF.accent, color: NF.bg, fontSize: 16, fontWeight: 800, padding: "12px 16px", ...style }}>{children}</div>
);

export const Ghost: React.FC<{ children: React.ReactNode; color?: string; style?: React.CSSProperties }> = ({ children, color = NF.accent, style }) => (
  <div style={{ fontSize: 14, fontWeight: 600, color, padding: "8px 0", ...style }}>{children}</div>
);

export const Outline: React.FC<{ children: React.ReactNode; dark?: boolean; style?: React.CSSProperties }> = ({ children, dark, style }) => (
  <div style={{ border: `1px solid ${dark ? NF.n400 : NF.divider}`, color: dark ? NF.bg : NF.text, fontSize: dark ? 16 : 14, fontWeight: 800, padding: dark ? "12px 8px" : "8px 12px", textAlign: "center", ...style }}>{children}</div>
);

/** A list row with a title, a sub line and an optional pill, like the Rules screen. */
export const ListRow: React.FC<{ title: string; sub?: string; tag?: React.ReactNode; right?: React.ReactNode; hl?: number }> = ({ title, sub, tag, right, hl = 0 }) => (
  <>
    <div style={{ display: "flex", alignItems: "center", padding: "12px 0", background: hl ? `rgba(236,48,19,${0.1 * hl})` : undefined }}>
      <div style={{ flex: 1 }}>
        <T size={17} weight={600} color={NF.text}>{title}</T>
        {sub && <T size={13}>{sub}</T>}
      </div>
      {tag}
      {right}
    </div>
    <Rule />
  </>
);

export const TabBar: React.FC<{ labels: string[]; sel: number }> = ({ labels, sel }) => (
  <div style={{ position: "absolute", insetInline: 0, bottom: 0, background: NF.bg }}>
    <Rule thick />
    <div style={{ display: "flex" }}>
      {labels.map((l, i) => (
        <div key={l} style={{ flex: 1, textAlign: "center" }}>
          <div style={{ margin: "0 16px", height: 3, background: i === sel ? NF.accent : "transparent" }} />
          <div style={{ padding: "12px 0", fontSize: 11, fontWeight: 600, color: i === sel ? NF.text : NF.n600 }}>{l}</div>
        </div>
      ))}
    </div>
  </div>
);

/** Material AlertDialog on a scrim. `p` 0..1 fades it in. */
export const Dialog: React.FC<{ p: number; title: string; children: React.ReactNode }> = ({ p, title, children }) => (
  <div style={{ position: "absolute", inset: 0, background: `rgba(0,0,0,${0.5 * p})`, display: "flex", alignItems: "center", justifyContent: "center", opacity: p > 0 ? 1 : 0 }}>
    <div style={{ width: 308, background: NF.bg, padding: 24, translate: `0 ${(1 - p) * 24}px`, opacity: p }}>
      <H size={20}>{title}</H>
      <div style={{ marginTop: 16 }}>{children}</div>
    </div>
  </div>
);

export const Field: React.FC<{ label: string; value: string; focus?: boolean }> = ({ label, value, focus }) => {
  const rtl = useRtl();
  return (
    <div>
      <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: rtl ? 0 : 1.1, color: NF.n700, marginBottom: 4 }}>{label.toUpperCase()}</div>
      <div style={{ background: NF.surface, border: `1px solid ${focus ? NF.accent : NF.divider}`, padding: 12, fontSize: 15, minHeight: 20 }}>{value}</div>
    </div>
  );
};

/** Ease between camera keyframes [frame, scale, focusX, focusY]. */
export type Cam = [number, number, number, number][];
export const useCam = (keys: Cam) => {
  const f = useCurrentFrame();
  const ease = Easing.inOut(Easing.cubic);
  let i = 0;
  while (i < keys.length - 2 && f >= keys[i + 1][0]) i++;
  const [a, s0, x0, y0] = keys[i];
  const [b, s1, x1, y1] = keys[Math.min(i + 1, keys.length - 1)];
  const t = b === a ? 1 : ease(interpolate(f, [a, b], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" }));
  return { s: s0 + (s1 - s0) * t, x: x0 + (x1 - x0) * t, y: y0 + (y1 - y0) * t };
};

/** A finger tap at (x, y) dp on the screen, starting at frame `at`. */
export const Tap: React.FC<{ x: number; y: number; at: number; dark?: boolean }> = ({ x, y, at, dark }) => {
  const f = useCurrentFrame() - at;
  if (f < -6 || f > 18) return <Sfx name="tick" at={at} volume={0.5} />;
  const reach = interpolate(f, [-6, 0], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const ring = interpolate(f, [0, 16], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const col = dark ? "255,255,255" : "32,30,29";
  return (
    <>
      <Sfx name="tick" at={at} volume={0.5} />
      <div style={{ position: "absolute", left: x - 24, top: y - 24, width: 48, height: 48, borderRadius: 48, border: `2px solid rgba(${col},${0.5 * (1 - ring)})`, scale: String(0.4 + ring * 1.2), opacity: f >= 0 ? 1 : 0, pointerEvents: "none" }} />
      <div style={{ position: "absolute", left: x - 14, top: y - 14, width: 28, height: 28, borderRadius: 28, background: `rgba(${col},0.28)`, border: `1.5px solid rgba(${col},0.55)`, scale: String(f < 0 ? 1.5 - 0.5 * reach : 0.9 + 0.1 * (1 - Math.min(f / 6, 1))), opacity: (f < 0 ? reach : 1 - ring) * 0.9, pointerEvents: "none" }} />
    </>
  );
};

/** Accent frame that pulses in around a region, to point at the thing the voice is naming. */
export const Ring: React.FC<{ x: number; y: number; w: number; h: number; at: number; len?: number }> = ({ x, y, w, h, at, len = 50 }) => {
  const f = useCurrentFrame() - at;
  const a = interpolate(f, [0, 8, len - 10, len], [0, 1, 1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const pulse = 1 + 0.015 * Math.sin(f / 3);
  return <div style={{ position: "absolute", left: x - 4, top: y - 4, width: w + 8, height: h + 8, border: `3px solid ${C.accent}`, opacity: a, scale: String(pulse), pointerEvents: "none" }} />;
};

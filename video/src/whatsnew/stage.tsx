import { Audio } from "@remotion/media";
import React from "react";
import { AbsoluteFill, interpolate, staticFile, useCurrentFrame } from "remotion";
import { C, FONT, LEAD, Line, useScene } from "../common";
import { Cam, NF, SH, SW, useCam } from "./ui";

export const Vo: React.FC<{ id: string }> = ({ id }) => <Audio src={staticFile(`voiceover-wn/${id}.wav`)} from={LEAD} />;

/** Shows the page whose start frame is the latest one reached; the new page slides and fades in over the old one. */
export const Pages: React.FC<{ pages: [number, React.ReactNode][] }> = ({ pages }) => {
  const f = useCurrentFrame();
  let i = 0;
  pages.forEach(([s], k) => {
    if (f >= s) i = k;
  });
  const t = i ? interpolate(f, [pages[i][0], pages[i][0] + 9], [0, 1], { extrapolateRight: "clamp" }) : 1;
  return (
    <>
      {i > 0 && t < 1 && <AbsoluteFill>{pages[i - 1][1]}</AbsoluteFill>}
      <AbsoluteFill style={{ opacity: t, translate: `${(1 - t) * 22}px 0` }}>{pages[i][1]}</AbsoluteFill>
    </>
  );
};

/** Left (or top, in portrait) text panel: a kicker, big lines, one supporting line. */
const Panel: React.FC<{ kicker: string; lines: string[]; sub?: string; dark: boolean; w: number; h: number }> = ({ kicker, lines, sub, dark, w, h }) => {
  const { u, portrait, ramp } = useScene();
  const fg = dark ? "#f4f4f5" : C.ink;
  return (
    <div style={{ position: "absolute", left: 0, top: 0, width: w, height: h, background: dark ? C.dark : C.ground, zIndex: 2, display: "flex", flexDirection: "column", justifyContent: "center", padding: `0 ${80 * u}px`, boxSizing: "border-box", fontFamily: FONT }}>
      <div style={{ fontSize: 26 * u, fontWeight: 800, letterSpacing: "0.12em", color: C.accent, marginBottom: 16 * u, opacity: ramp(0, 10, 0, 1) }}>{kicker}</div>
      {lines.map((l, i) => (
        <Line key={l} at={4 + i * 6} size={portrait ? 104 : 88} color={i === lines.length - 1 && lines.length > 1 ? C.accent : fg}>{l}</Line>
      ))}
      {sub && <div style={{ marginTop: 22 * u, fontSize: (portrait ? 36 : 32) * u, fontWeight: 600, color: dark ? "#a1a1aa" : C.mute, opacity: ramp(20, 34, 0, 1), lineHeight: 1.25 }}>{sub}</div>}
    </div>
  );
};

/**
 * A phone with a keyframed camera: `cam` is [frame, zoom, focusX, focusY] in screen dp, so the video can zoom into one
 * row of the screen. Children are drawn in screen dp (360 x 720) and zoom with the phone.
 */
export const PhoneStage: React.FC<{ cam: Cam; dark?: boolean; kicker: string; lines: string[]; sub?: string; children: React.ReactNode }> = ({ cam, dark = false, kicker, lines, sub, children }) => {
  const { width, height, portrait, u } = useScene();
  const c = useCam(cam);
  const panelW = portrait ? width : Math.round(width * 0.36);
  const panelH = portrait ? Math.round(height * 0.2) : height;
  const rw = portrait ? width : width - panelW;
  const rh = portrait ? height - panelH : height;
  const k = portrait ? Math.min((rw * 0.74) / (SW + 16), (rh * 0.9) / (SH + 16)) : (rh * 0.86) / (SH + 16);
  const zoom = portrait ? 1 + (c.s - 1) * 0.5 : c.s; // a phone this wide would overflow a portrait frame
  const bg = dark ? C.dark : C.ground;
  return (
    <AbsoluteFill style={{ background: bg }}>
      <Panel kicker={kicker} lines={lines} sub={sub} dark={dark} w={panelW} h={panelH} />
      <div style={{ position: "absolute", left: portrait ? 0 : panelW, top: portrait ? panelH : 0, width: rw, height: rh, overflow: "hidden" }}>
        <div
          style={{
            position: "absolute", left: rw / 2 - (SW + 16) / 2, top: rh / 2 - (SH + 16) / 2, width: SW + 16, height: SH + 16, boxSizing: "border-box", padding: 8,
            borderRadius: 40, background: "#0b0b0c", boxShadow: `0 40px 120px rgba(0,0,0,${dark ? 0.7 : 0.35}), 0 0 0 2px ${dark ? "#27272a" : "#2a2a2c"}`,
            transform: `scale(${k * zoom}) translate(${-(c.x - SW / 2)}px, ${-(c.y - SH / 2)}px)`,
          }}
        >
          <div style={{ position: "relative", width: SW, height: SH, borderRadius: 32, overflow: "hidden", background: NF.bg }}>
            {children}
            <div style={{ position: "absolute", top: 8, left: SW / 2 - 6, width: 12, height: 12, borderRadius: 12, background: "#000" }} />
          </div>
        </div>
      </div>
      <AbsoluteFill style={{ boxShadow: `inset 0 0 ${120 * u}px rgba(0,0,0,${dark ? 0.35 : 0.08})`, pointerEvents: "none" }} />
    </AbsoluteFill>
  );
};

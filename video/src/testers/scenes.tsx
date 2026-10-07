import { Audio } from "@remotion/media";
import React from "react";
import { staticFile, useVideoConfig } from "remotion";
import { C, FONT, LEAD, Line, Mark, Stage, useScene } from "../common";
import { Flash, Glitch, RgbText, Scanlines, Sfx, Shake, Shockwave, Tilt, useSlam } from "../challenger/fx";
import { AndroidBlockMock, SyncMock, WindowsWindowMock } from "./mocks";

const Vo: React.FC<{ id: string }> = ({ id }) => <Audio src={staticFile(`voiceover-tr/${id}.wav`)} from={LEAD} />;

const Tag: React.FC<{ at: number; size: number; children: React.ReactNode }> = ({ at, size, children }) => {
  const { u, ramp } = useScene();
  return (
    <div style={{ overflow: "hidden" }}>
      <div style={{ background: C.accent, color: "#fff", fontFamily: FONT, fontWeight: 900, fontSize: size * u, padding: `${8 * u}px ${28 * u}px`, letterSpacing: "-0.02em", display: "inline-block", translate: `0 ${ramp(at, at + 12, 110, 0)}%` }}>{children}</div>
    </div>
  );
};

// ---------------------------------------------------------------- 1. Hook
export const Hook: React.FC = () => {
  const { u, portrait } = useScene();
  const a = useSlam(4);
  const b = useSlam(22);
  const size = portrait ? 150 : 210;
  return (
    <Shake hits={[[4, 16], [22, 22]]}>
      <Stage bg={C.dark}>
        <Vo id="hook" />
        <Sfx name="hit" at={4} />
        <Sfx name="hit" at={22} volume={0.8} />
        <Flash at={22} len={8} />
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 18 * u, fontFamily: FONT, fontWeight: 900, letterSpacing: "-0.04em", color: "#f4f4f5", textAlign: "center" }}>
          <RgbText amount={(1 - a.p) * 26 * u} style={{ fontSize: size * u, lineHeight: 0.95, opacity: a.on ? 1 : 0, scale: 1 + (1 - Math.min(a.p, 1)) * 0.9 }}>OUT FOR</RgbText>
          <RgbText amount={(1 - b.p) * 26 * u} style={{ fontSize: size * u, lineHeight: 0.95, color: C.accent, opacity: b.on ? 1 : 0, scale: 1 + (1 - Math.min(b.p, 1)) * 0.9 }}>TESTING</RgbText>
          <div style={{ display: "flex", gap: 24 * u, marginTop: 30 * u, fontSize: (portrait ? 54 : 64) * u, opacity: b.on ? 1 : 0 }}>
            <span style={{ border: `${5 * u}px solid #f4f4f5`, padding: `${4 * u}px ${24 * u}px` }}>ANDROID</span>
            <span style={{ border: `${5 * u}px solid #f4f4f5`, padding: `${4 * u}px ${24 * u}px` }}>WINDOWS</span>
          </div>
        </div>
        <Scanlines />
      </Stage>
    </Shake>
  );
};

// ---------------------------------------------------------------- 2. Free
export const Free: React.FC = () => {
  const { u, portrait } = useScene();
  const s = useSlam(6);
  return (
    <Stage bg={C.ground}>
      <Vo id="free" />
      <Sfx name="hit" at={6} volume={0.8} />
      <Shockwave at={6} color={C.accent} size={1600} />
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center" }}>
        <div style={{ fontFamily: FONT, fontWeight: 900, fontSize: (portrait ? 400 : 480) * u, lineHeight: 0.9, letterSpacing: "-0.05em", color: C.accent, opacity: s.on ? 1 : 0, scale: 1 + (1 - Math.min(s.p, 1)) * 0.8 }}>FREE</div>
        <Line at={26} size={portrait ? 78 : 96}>No account needed.</Line>
      </div>
    </Stage>
  );
};

// ---------------------------------------------------------------- 3. Android
export const Android: React.FC = () => {
  const { u, portrait } = useScene();
  const w = (portrait ? 480 : 400) * u;
  return (
    <Stage bg={C.dark} gap={portrait ? 60 : 110}>
      <Vo id="android" />
      <Sfx name="whoosh" at={0} />
      <Sfx name="hit" at={14} volume={0.7} />
      <Tilt enter={0} ry={-14} rx={4}><AndroidBlockMock w={w} /></Tilt>
      <div style={{ display: "flex", flexDirection: "column", gap: 24 * u }}>
        <Line at={8} size={portrait ? 130 : 150} color="#f4f4f5">ANDROID</Line>
        <Tag at={22} size={portrait ? 56 : 64}>Google Play testing</Tag>
      </div>
      <Scanlines />
    </Stage>
  );
};

// ---------------------------------------------------------------- 4. Arabic
export const Arabic: React.FC = () => {
  const { u, portrait } = useScene();
  const w = (portrait ? 480 : 400) * u;
  const { durationInFrames: dur } = useVideoConfig();
  const flip = useSlam(Math.floor(dur * 0.3));
  return (
    <Stage bg={C.ground} gap={portrait ? 60 : 110}>
      <Vo id="arabic" />
      <Sfx name="whoosh" at={0} />
      <Sfx name="hit" at={Math.floor(dur * 0.3)} volume={0.7} />
      <Tilt enter={0} ry={-12} rx={4}><AndroidBlockMock w={w} ar showLang /></Tilt>
      <div style={{ display: "flex", flexDirection: "column", gap: 20 * u }}>
        <div dir="rtl" style={{ fontFamily: FONT, fontWeight: 900, fontSize: (portrait ? 190 : 230) * u, lineHeight: 1.1, color: C.accent, opacity: flip.on ? 1 : 0, scale: 0.7 + 0.3 * Math.min(flip.p, 1.05), transformOrigin: "left center" }}>العربية</div>
        <Line at={10} size={portrait ? 90 : 104}>Right to left.</Line>
        <Line at={18} size={portrait ? 50 : 56} color={C.mute}>Settings → Language</Line>
      </div>
    </Stage>
  );
};

// ---------------------------------------------------------------- 5. Windows
export const Windows: React.FC = () => {
  const { u, portrait, ramp, frame } = useScene();
  const { durationInFrames: dur } = useVideoConfig();
  const inst = Math.floor(dur * 0.6);
  const w = (portrait ? 860 : 820) * u;
  return (
    <Stage bg={C.dark} gap={portrait ? 60 : 100}>
      <Vo id="windows" />
      <Sfx name="whoosh" at={0} />
      <Sfx name="hit" at={inst} volume={0.7} />
      <Tilt enter={0} ry={-12} rx={4}><WindowsWindowMock w={w} bannerP={ramp(Math.floor(dur * 0.3), Math.floor(dur * 0.3) + 14, 0, 1)} installed={frame >= inst} /></Tilt>
      <div style={{ display: "flex", flexDirection: "column", gap: 24 * u }}>
        <Line at={8} size={portrait ? 130 : 140} color="#f4f4f5">WINDOWS</Line>
        <Tag at={22} size={portrait ? 56 : 64}>Early access</Tag>
        <Line at={Math.floor(dur * 0.45)} size={portrait ? 60 : 68} color="#a1a1aa">Updates itself.</Line>
      </div>
      <Scanlines />
    </Stage>
  );
};

// ---------------------------------------------------------------- 6. Sync
export const Sync: React.FC = () => {
  const { u, portrait, ramp } = useScene();
  const size = (portrait ? 760 : 780) * u;
  return (
    <Stage bg={C.ground} gap={portrait ? 70 : 120}>
      <Vo id="sync" />
      <Sfx name="whoosh" at={0} />
      <Sfx name="hit" at={30} volume={0.7} />
      <SyncMock size={size} p={ramp(10, 34, 0, 1)} vertical={portrait} />
      <div>
        <Line at={8} size={portrait ? 110 : 120}>Start here.</Line>
        <Line at={16} size={portrait ? 110 : 120} color={C.accent}>Join there.</Line>
        <Line at={30} size={portrait ? 46 : 52} color={C.mute}>Account optional.</Line>
      </div>
    </Stage>
  );
};

// ---------------------------------------------------------------- 7. CTA
export const Cta: React.FC = () => {
  const { u, portrait, ramp } = useScene();
  const { durationInFrames: dur } = useVideoConfig();
  const out = Math.max(0, ramp(dur - 14, dur - 2, 0, 1));
  return (
    <Glitch amount={out * 120 * u}>
      <Stage bg={C.ground} gap={portrait ? 40 : 90}>
        <Vo id="cta" />
        <Sfx name="hit" at={10} volume={0.8} />
        <Shockwave at={10} color={C.accent} size={1500} />
        <Mark size={(portrait ? 300 : 380) * u} progress={ramp(2, 22, 0, 1)} pop={ramp(16, 28, 0, 1.15)} />
        <div>
          <Line at={14} size={portrait ? 150 : 190}>NowFocus</Line>
          <div style={{ display: "flex", flexDirection: "column", gap: 10 * u, margin: `${6 * u}px 0 ${12 * u}px` }}>
            <Line at={26} size={portrait ? 54 : 64} color={C.mute}>Android · Google Play testing</Line>
            <Line at={32} size={portrait ? 54 : 64} color={C.mute}>Windows · Early access</Line>
          </div>
          <Tag at={42} size={portrait ? 74 : 96}>nowfocus.online</Tag>
          <Line at={52} size={portrait ? 30 : 34} color={C.mute}>Windows tested on Windows 11.</Line>
        </div>
      </Stage>
    </Glitch>
  );
};

import React from "react";
import { AbsoluteFill, Img, interpolate, staticFile, useVideoConfig } from "remotion";
import { C, FONT, Line, Mark, Stage, useScene } from "../common";
import { CLAIMS, FOOTNOTE } from "./claims";
import { Flash, Glitch, RgbText, Scanlines, Screens, Shake, Shockwave, Sfx, SpeedLines, Tilt, useSlam, Vo } from "./fx";

const ANDROID = { w: 633, h: 1280 };
const MAC = { w: 1884, h: 1142 };
const POPOVER = { w: 726, h: 1138 };

// ---------------------------------------------------------------- 1. Hook
export const Hook: React.FC = () => {
  const { u, portrait, ramp, frame } = useScene();
  const { durationInFrames: dur } = useVideoConfig();
  const size = portrait ? 165 : 230;
  const names: [string, number][] = [["OPAL", 6], ["FREEDOM", 20], ["STAYFREE", 34]];
  const stamp = Math.floor(dur * 0.55);
  const s = useSlam(stamp);
  return (
    <Shake hits={[...names.map(([, at]) => [at, 16] as [number, number]), [stamp, 24]]}>
      <Stage bg={C.dark}>
        <Vo id="hook" />
        {names.map(([, at]) => <Sfx key={at} name="hit" at={at} />)}
        <Sfx name="hit" at={stamp} volume={0.8} />
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 10 * u }}>
          {names.map(([n, at]) => (
            <HookName key={n} at={at} size={size}>{n}</HookName>
          ))}
          <div style={{ marginTop: 34 * u, display: "flex", gap: 30 * u, alignItems: "center", fontFamily: FONT, fontWeight: 800, fontSize: (portrait ? 54 : 70) * u, letterSpacing: "-0.02em" }}>
            <span style={{ color: "#a1a1aa", opacity: ramp(Math.floor(dur * 0.35), Math.floor(dur * 0.35) + 8, 0, 1) }}>SUBSCRIPTION</span>
            <span
              style={{
                background: C.accent,
                color: "#fff",
                padding: `${6 * u}px ${24 * u}px`,
                rotate: "-5deg",
                opacity: s.on ? 1 : 0,
                scale: 1 + (1 - s.p) * 1.2,
              }}
            >
              + ACCOUNT?
            </span>
          </div>
        </div>
        <Scanlines />
        <div style={{ position: "absolute", inset: 0, background: "#000", opacity: frame % 17 === 0 ? 0.25 : 0 }} />
      </Stage>
    </Shake>
  );
};

const HookName: React.FC<{ at: number; size: number; children: string }> = ({ at, size, children }) => {
  const { u } = useScene();
  const s = useSlam(at);
  return (
    <Glitch amount={s.on ? Math.max(0, (1 - s.p) * 90 * u) : 0}>
      <RgbText
        amount={(1 - s.p) * 26 * u + Math.abs(s.kick) * 6 * u}
        style={{
          fontFamily: FONT,
          fontWeight: 900,
          fontSize: size * u,
          lineHeight: 0.95,
          letterSpacing: "-0.04em",
          color: "#f4f4f5",
          opacity: s.on ? 1 : 0,
          scale: 1 + (1 - Math.min(s.p, 1)) * 0.9,
        }}
      >
        {children}
      </RgbText>
    </Glitch>
  );
};

// ---------------------------------------------------------------- 2. Receipts
const Price: React.FC<{ start: number; value: number }> = ({ start, value }) => {
  const { frame } = useScene();
  const t = interpolate(frame, [start, start + 28], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return <>${(value * (1 - (1 - t) ** 3)).toFixed(2)}</>;
};

const Card: React.FC<{ at: number; fromX: number; strike: number; stamp?: { at: number; text: string }; claim: { name: string; price: number; per: string; note: string } }> = ({
  at,
  fromX,
  strike,
  stamp,
  claim,
}) => {
  const { u, ramp, portrait } = useScene();
  const s = useSlam(at);
  const st = useSlam(stamp?.at ?? 99999);
  return (
    <div
      style={{
        position: "relative",
        width: (portrait ? 800 : 680) * u,
        background: C.white,
        border: `${4 * u}px solid ${C.ink}`,
        padding: `${40 * u}px ${44 * u}px`,
        opacity: s.on ? 1 : 0,
        translate: `${(1 - Math.min(s.p, 1)) * fromX * u}px 0`,
        rotate: `${(1 - Math.min(s.p, 1)) * 10 * Math.sign(fromX)}deg`,
        boxShadow: `${16 * u}px ${16 * u}px 0 ${C.ink}`,
      }}
    >
      <div style={{ fontSize: 40 * u, fontWeight: 700, color: C.mute }}>{claim.name}</div>
      <div style={{ position: "relative", fontSize: (portrait ? 140 : 140) * u, fontWeight: 900, letterSpacing: "-0.04em", lineHeight: 1.05 }}>
        <Price start={at + 4} value={claim.price} />
        <span style={{ fontSize: 56 * u, color: C.mute, letterSpacing: 0 }}>/{claim.per}</span>
        <div style={{ position: "absolute", left: -10 * u, top: "52%", height: 20 * u, background: C.accent, width: `${ramp(strike, strike + 8, 0, 112)}%`, rotate: "-3deg" }} />
      </div>
      <div style={{ fontSize: 42 * u, fontWeight: 600, marginTop: 8 * u }}>{claim.note}</div>
      {stamp && (
        <div
          style={{
            position: "absolute",
            right: -30 * u,
            top: -34 * u,
            border: `${6 * u}px solid ${C.accent}`,
            color: C.accent,
            background: C.white,
            padding: `${6 * u}px ${20 * u}px`,
            fontSize: 38 * u,
            fontWeight: 900,
            letterSpacing: "0.04em",
            rotate: "8deg",
            opacity: st.on ? 1 : 0,
            scale: 1 + (1 - Math.min(st.p, 1)) * 1.6,
          }}
        >
          {stamp.text}
        </div>
      )}
    </div>
  );
};

export const Receipts: React.FC = () => {
  const { u, portrait } = useScene();
  const { durationInFrames: dur } = useVideoConfig();
  const second = Math.floor(dur * 0.42);
  const strike = Math.floor(dur * 0.84);
  return (
    <Shake hits={[[6, 10], [second, 10], [strike, 18]]}>
      <Stage bg={C.ground} gap={portrait ? 70 : 90}>
        <Vo id="receipts" />
        <Sfx name="hit" at={6} />
        <Sfx name="tick" at={10} />
        <Sfx name="hit" at={second} />
        <Sfx name="tick" at={second + 4} />
        <Sfx name="hit" at={Math.floor(dur * 0.6)} volume={0.7} />
        <Sfx name="hit" at={strike} volume={0.9} />
        <Card at={6} fromX={-900} strike={strike} claim={CLAIMS.opal} />
        <Card at={second} fromX={900} strike={strike + 6} stamp={{ at: Math.floor(dur * 0.6), text: "ACCOUNT" }} claim={CLAIMS.freedom} />
        <div style={{ position: "absolute", bottom: 46 * u, left: 0, right: 0, textAlign: "center", fontSize: 24 * u, color: C.mute, fontFamily: FONT }}>{FOOTNOTE}</div>
      </Stage>
    </Shake>
  );
};

// ---------------------------------------------------------------- 3. Reveal
export const Reveal: React.FC = () => {
  const { u, portrait, ramp } = useScene();
  const size = portrait ? 92 : 112;
  return (
    <Shake hits={[[22, 22]]}>
      <Stage bg={C.accent} gap={portrait ? 50 : 90}>
        <Vo id="reveal" />
        <Sfx name="whoosh" at={0} />
        <Sfx name="hit" at={22} volume={0.9} />
        <SpeedLines color="#fff" from={0} to={40} />
        <Shockwave at={22} color="#fff" />
        <Shockwave at={30} color={C.ink} size={1300} />
        <Mark size={(portrait ? 380 : 480) * u} stroke="#fff" fill={C.ink} progress={ramp(4, 26, 0, 1)} pop={ramp(22, 34, 0, 1.15)} />
        <div>
          <Line at={32} size={size} color="#fff">FREE.</Line>
          <Line at={48} size={size} color={C.ink}>NO ACCOUNT</Line>
          <Line at={56} size={size} color={C.ink}>NEEDED.</Line>
          <Line at={76} size={size} color="#fff">STATS STAY</Line>
          <Line at={84} size={size} color="#fff">ON-DEVICE.</Line>
        </div>
        <Flash at={0} />
      </Stage>
    </Shake>
  );
};

// ---------------------------------------------------------------- 4. Android
const Callouts: React.FC<{ items: [string, number][]; size: number }> = ({ items, size }) => {
  const { frame, u, ramp } = useScene();
  const active = items.reduce((a, [, at], i) => (frame >= at ? i : a), 0);
  return (
    <div>
      {items.map(([label, at], i) => (
        <div key={label} style={{ opacity: ramp(at, at + 6, 0, i === active ? 1 : 0.22), translate: `${ramp(at, at + 16, -60, i === active ? 24 : 0) * u}px 0` }}>
          <Line at={at} size={size} color={i === active ? C.accent : "#f4f4f5"}>{label}</Line>
        </div>
      ))}
    </div>
  );
};

export const Android: React.FC = () => {
  const { u, portrait } = useScene();
  const { durationInFrames: dur } = useVideoConfig();
  const t2 = Math.floor(dur * 0.3);
  const t3 = Math.floor(dur * 0.58);
  const h = (portrait ? 1000 : 850) * u;
  const w = (h * ANDROID.w) / ANDROID.h;
  return (
    <Shake hits={[[t2, 8], [t3, 8]]}>
      <Stage bg={C.dark} gap={portrait ? 40 : 120}>
        <Vo id="android" />
        <Sfx name="whoosh" at={0} />
        <Sfx name="whoosh" at={t2} volume={0.4} />
        <Sfx name="whoosh" at={t3} volume={0.4} />
        <Tilt enter={0} ry={-18} rx={6}>
          <Screens
            w={w}
            h={h}
            radius={46 * u}
            border={16 * u}
            borderColor="#111"
            shots={[
              { src: "screens/android/focus.jpg", at: 0 },
              { src: "screens/android/partial.jpg", at: t2, zoom: [t2 + 10, 40, 1.35, 50, 62] },
              { src: "screens/android/urges.jpg", at: t3, zoom: [t3 + 10, 50, 1.8, 40, 66] },
            ]}
          />
        </Tilt>
        <Callouts size={portrait ? 96 : 110} items={[["BLOCK APPS", 6], ["CUT THE SHORTS", t2], ["SEE YOUR URGES", t3]]} />
        <Scanlines />
      </Stage>
    </Shake>
  );
};

// ---------------------------------------------------------------- 5. macOS
export const MacOS: React.FC = () => {
  const { u, portrait, ramp, frame } = useScene();
  const { durationInFrames: dur } = useVideoConfig();
  const t2 = Math.floor(dur * 0.3);
  const t3 = Math.floor(dur * 0.6);
  const w = (portrait ? 920 : 1280) * u;
  const h = (w * MAC.h) / MAC.w;
  const popH = (portrait ? 1000 : 820) * u;
  const popW = (popH * POPOVER.w) / POPOVER.h;
  const pop = ramp(t3, t3 + 18, 0, 1);
  const label = frame >= t3 ? "STRICT. LOCKED." : frame >= t2 ? "BEDTIME WIND-DOWN" : "PROFILES";
  const lblAt = frame >= t3 ? t3 : frame >= t2 ? t2 : 0;
  return (
    <Shake hits={[[t2, 8], [t3, 14]]}>
      <Stage bg={C.ground}>
        <Vo id="macos" />
        <Sfx name="whoosh" at={0} />
        <Sfx name="whoosh" at={t2} volume={0.4} />
        <Sfx name="hit" at={t3} volume={0.8} />
        <div style={{ position: "absolute", top: (portrait ? 220 : 70) * u, left: "50%", translate: "-50% 0", scale: 1 - 0.14 * pop, filter: `blur(${6 * pop * u}px)`, opacity: 1 - 0.5 * pop }}>
          <Tilt enter={0} ry={-10} rx={5}>
            <Screens
              w={w}
              h={h}
              radius={20 * u}
              shots={[
                { src: "screens/macos/profiles.png", at: 0 },
                { src: "screens/macos/bedtime.png", at: t2 },
              ]}
            />
          </Tilt>
        </div>
        <div style={{ position: "absolute", top: "50%", left: "50%", translate: `-50% ${-50 + (1 - pop) * 14}%`, opacity: pop, scale: 0.8 + 0.2 * pop }}>
          <Screens
            w={popW}
            h={popH}
            radius={18 * u}
            shots={[
              {
                src: "screens/macos/popover.png",
                at: 0,
                zoom: [t3 + 22, 40, 1.18, 50, 57],
                overlay: (
                  <div
                    style={{
                      position: "absolute",
                      left: "4.5%",
                      width: "91%",
                      top: "54.6%",
                      height: "5.4%",
                      border: `${5 * u}px solid ${C.accent}`,
                      opacity: ramp(t3 + 24, t3 + 32, 0, 1),
                      boxShadow: `0 0 ${40 * u}px ${C.accent}`,
                    }}
                  />
                ),
              },
            ]}
          />
        </div>
        <div style={{ position: "absolute", bottom: (portrait ? 150 : 60) * u, left: 90 * u, overflow: "hidden" }}>
          <div
            key={label}
            style={{ background: C.accent, color: "#fff", fontFamily: FONT, fontWeight: 900, fontSize: (portrait ? 70 : 78) * u, padding: `${8 * u}px ${30 * u}px`, letterSpacing: "-0.02em", translate: `0 ${ramp(lblAt, lblAt + 12, 110, 0)}%` }}
          >
            {label}
          </div>
        </div>
      </Stage>
    </Shake>
  );
};

// ---------------------------------------------------------------- 6. Windows
export const Windows: React.FC = () => {
  const { u, portrait } = useScene();
  const w = (portrait ? 860 : 760) * u;
  const bar = 54 * u;
  return (
    <Stage bg={C.dark} gap={portrait ? 60 : 100}>
      <Vo id="windows" />
      <Sfx name="whoosh" at={0} />
      <Sfx name="hit" at={14} volume={0.7} />
      <Tilt enter={0} ry={-14} rx={4}>
        <div style={{ width: w, background: "#202020", border: `${2 * u}px solid #3a3a3a`, boxShadow: `0 ${40 * u}px ${100 * u}px rgba(0,0,0,0.6)` }}>
          <div style={{ height: bar, display: "flex", alignItems: "center", justifyContent: "space-between", paddingLeft: 20 * u, color: "#e5e5e5", fontFamily: FONT, fontSize: 24 * u, fontWeight: 600 }}>
            <span>NowFocus</span>
            <span style={{ display: "flex", letterSpacing: 0, fontSize: 26 * u }}>
              {["—", "▢", "✕"].map((g) => <span key={g} style={{ width: 76 * u, textAlign: "center" }}>{g}</span>)}
            </span>
          </div>
          <Img src={staticFile("screens/windows-splash.png")} style={{ display: "block", width: "100%" }} />
        </div>
      </Tilt>
      <div>
        <Line at={8} size={portrait ? 130 : 140} color="#f4f4f5">WINDOWS</Line>
      </div>
      <Scanlines />
    </Stage>
  );
};
// ---------------------------------------------------------------- 7. Privacy
export const Privacy: React.FC = () => {
  const { u, portrait, ramp } = useScene();
  const { durationInFrames: dur } = useVideoConfig();
  const sweep = Math.floor(dur * 0.3);
  const h = (portrait ? 760 : 850) * u;
  const w = (h * ANDROID.w) / ANDROID.h;
  const card = (portrait ? 860 : 800) * u;
  // The caption line of urges.jpg: x 2%..84%, y 89.5%..94.5%; the magnifier shows exactly that crop.
  const bgW = card / 0.82;
  const pop = useSlam(sweep + 10);
  return (
    <Stage bg={C.ground} gap={portrait ? 50 : 100}>
      <Vo id="privacy" />
      <Sfx name="whoosh" at={0} />
      <Sfx name="hit" at={sweep + 10} volume={0.7} />
      <Tilt enter={0} ry={-10} rx={4}>
        <Screens
          w={w}
          h={h}
          radius={46 * u}
          border={16 * u}
          borderColor={C.ink}
          shots={[
            {
              src: "screens/android/urges.jpg",
              at: 0,
              overlay: (
                <div
                  style={{
                    position: "absolute",
                    left: "3%",
                    width: "80%",
                    top: "89.8%",
                    height: "4.4%",
                    background: C.accent,
                    mixBlendMode: "multiply",
                    opacity: 0.85,
                    transformOrigin: "left",
                    scale: `${ramp(sweep, sweep + 14, 0, 1)} 1`,
                  }}
                />
              ),
            },
          ]}
        />
      </Tilt>
      <div style={{ display: "flex", flexDirection: "column", gap: 40 * u }}>
        <div>
          <Line at={10} size={portrait ? 96 : 110}>Counted on</Line>
          <Line at={16} size={portrait ? 96 : 110}>your phone.</Line>
          <Line at={Math.floor(dur * 0.5)} size={portrait ? 96 : 110} color={C.accent}>We never see</Line>
          <Line at={Math.floor(dur * 0.5) + 6} size={portrait ? 96 : 110} color={C.accent}>what you browse.</Line>
        </div>
        <div
          style={{
            position: "relative",
            overflow: "hidden",
            width: card,
            height: (card * (0.05 * ANDROID.h)) / (0.82 * ANDROID.w),
            border: `${6 * u}px solid ${C.accent}`,
            background: C.white,
            boxShadow: `${12 * u}px ${12 * u}px 0 ${C.ink}`,
            opacity: pop.on ? 1 : 0,
            scale: 0.6 + 0.4 * Math.min(pop.p, 1.05),
            transformOrigin: "left center",
          }}
        >
          <Img
            src={staticFile("screens/android/urges.jpg")}
            style={{ position: "absolute", width: bgW, height: (bgW * ANDROID.h) / ANDROID.w, left: -0.02 * bgW, top: -0.895 * ((bgW * ANDROID.h) / ANDROID.w) }}
          />
        </div>
      </div>
    </Stage>
  );
};

// ---------------------------------------------------------------- 8. CTA
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
        <Mark size={(portrait ? 340 : 400) * u} progress={ramp(2, 22, 0, 1)} pop={ramp(16, 28, 0, 1.15)} />
        <div>
          <Line at={14} size={portrait ? 150 : 190}>NowFocus</Line>
          <Line at={28} size={portrait ? 60 : 72} color={C.mute}>Join the waitlist</Line>
          <div style={{ marginTop: 18 * u, overflow: "hidden" }}>
            <div style={{ background: C.accent, color: "#fff", fontFamily: FONT, fontWeight: 900, fontSize: (portrait ? 74 : 96) * u, padding: `${8 * u}px ${30 * u}px`, letterSpacing: "-0.02em", display: "inline-block", translate: `0 ${ramp(40, 54, 110, 0)}%` }}>
              nowfocus.online
            </div>
          </div>
        </div>
        <AbsoluteFill />
      </Stage>
    </Glitch>
  );
};

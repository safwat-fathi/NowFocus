import { Audio } from "@remotion/media";
import React, { useId } from "react";
import { AbsoluteFill, Easing, Img, interpolate, random, Sequence, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { C, LEAD, useScene } from "../common";

/** Voice line from public/voiceover-vs/. */
export const Vo: React.FC<{ id: string }> = ({ id }) => (
  <Audio src={staticFile(`voiceover-vs/${id}.wav`)} from={LEAD} />
);

/** One-shot sound effect from public/audio/ (see scripts/generate-sfx.mjs). */
export const Sfx: React.FC<{ name: "whoosh" | "hit" | "tick"; at?: number; volume?: number }> = ({ name, at = 0, volume = 0.55 }) => (
  <Sequence from={at} durationInFrames={45} layout="none">
    <Audio src={staticFile(`audio/${name}.wav`)} volume={name === "whoosh" ? volume * 0.3 : volume} />
  </Sequence>
);

/** Spring pop `p` (0..1, overshoots) and a decaying shake `kick`, both starting at frame `at`. */
export const useSlam = (at: number) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const f = frame - at;
  return {
    on: f >= 0,
    p: f < 0 ? 0 : spring({ frame: f, fps, config: { damping: 11, stiffness: 220, mass: 0.5 } }),
    kick: f < 0 ? 0 : Math.exp(-f / 5) * Math.sin(f * 2.7),
  };
};

/** Camera shake: each hit is [frame, amplitude in layout units]. */
export const Shake: React.FC<{ hits: [number, number][]; children: React.ReactNode }> = ({ hits, children }) => {
  const frame = useCurrentFrame();
  const { u } = useScene();
  let x = 0;
  let y = 0;
  for (const [at, amp] of hits) {
    const f = frame - at;
    if (f < 0 || f > 24) continue;
    const d = Math.exp(-f / 5) * amp * u;
    x += d * Math.sin(f * 2.7);
    y += d * Math.cos(f * 3.3);
  }
  return <AbsoluteFill style={{ translate: `${x}px ${y}px` }}>{children}</AbsoluteFill>;
};

/** Chromatic-aberration text: red and cyan ghosts offset by `amount` px around the real text. */
export const RgbText: React.FC<{ amount: number; children: React.ReactNode; style?: React.CSSProperties }> = ({ amount, children, style }) => (
  <div style={{ position: "relative", ...style }}>
    {amount > 0.5 && (
      <>
        <div style={{ position: "absolute", inset: 0, color: "#00e5ff", translate: `${-amount}px 0`, mixBlendMode: "screen" }}>{children}</div>
        <div style={{ position: "absolute", inset: 0, color: C.accent, translate: `${amount}px 0`, mixBlendMode: "screen" }}>{children}</div>
      </>
    )}
    <div style={{ position: "relative" }}>{children}</div>
  </div>
);

/** Horizontal tear/jitter via an SVG displacement filter; `amount` 0 turns it off. */
export const Glitch: React.FC<{ amount: number; children: React.ReactNode; style?: React.CSSProperties }> = ({ amount, children, style }) => {
  const frame = useCurrentFrame();
  const id = `g${useId().replace(/:/g, "")}`;
  return (
    <>
      {amount > 0 && (
        <svg width="0" height="0" style={{ position: "absolute" }}>
          <filter id={id} x="-10%" y="-10%" width="120%" height="120%">
            <feTurbulence type="fractalNoise" baseFrequency={`0.001 ${0.15 + (frame % 4) * 0.06}`} numOctaves={1} seed={frame} result="n" />
            <feDisplacementMap in="SourceGraphic" in2="n" scale={amount} xChannelSelector="R" yChannelSelector="G" />
          </filter>
        </svg>
      )}
      <div style={{ ...style, filter: amount > 0 ? `url(#${id})` : undefined }}>{children}</div>
    </>
  );
};

/** Manga-style radial speed lines that flicker every other frame, fading out between `from` and `to`. */
export const SpeedLines: React.FC<{ color: string; from: number; to: number }> = ({ color, from, to }) => {
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const k = Math.floor(frame / 2);
  const fade = interpolate(frame, [from, from + 4, to], [0, 1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  if (fade <= 0) return null;
  const cx = width / 2;
  const cy = height / 2;
  const R = Math.hypot(width, height) / 2;
  return (
    <svg width={width} height={height} style={{ position: "absolute", inset: 0, opacity: fade * 0.55 }}>
      {Array.from({ length: 56 }, (_, i) => {
        const a = random(`a${i}`) * Math.PI * 2;
        const w = 0.008 + random(`w${i}-${k}`) * 0.018;
        const inner = R * (0.28 + random(`i${i}-${k}`) * 0.3);
        const p = (r: number, ang: number) => `${cx + Math.cos(ang) * r},${cy + Math.sin(ang) * r}`;
        return <polygon key={i} points={`${p(inner, a)} ${p(R * 1.1, a - w)} ${p(R * 1.1, a + w)}`} fill={color} />;
      })}
    </svg>
  );
};

/** Expanding ring that fades as it grows. */
export const Shockwave: React.FC<{ at: number; color: string; size?: number }> = ({ at, color, size = 1800 }) => {
  const frame = useCurrentFrame();
  const { u } = useScene();
  const t = interpolate(frame - at, [0, 26], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: Easing.out(Easing.cubic) });
  if (frame < at || t >= 1) return null;
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", pointerEvents: "none" }}>
      <div style={{ width: size * u * t, height: size * u * t, borderRadius: "50%", border: `${14 * (1 - t) * u}px solid ${color}`, opacity: 1 - t }} />
    </AbsoluteFill>
  );
};

/** White flash that decays over `len` frames from `at`. */
export const Flash: React.FC<{ at: number; len?: number; color?: string }> = ({ at, len = 10, color = "#fff" }) => {
  const frame = useCurrentFrame();
  const o = interpolate(frame - at, [0, len], [1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return o > 0 && frame >= at ? <AbsoluteFill style={{ background: color, opacity: o, pointerEvents: "none" }} /> : null;
};

/** CRT scanlines, laid over dark scenes. */
export const Scanlines: React.FC = () => {
  const { u } = useScene();
  return (
    <AbsoluteFill
      style={{ pointerEvents: "none", opacity: 0.16, backgroundImage: `repeating-linear-gradient(0deg, rgba(0,0,0,0.55) 0 ${2 * u}px, transparent ${2 * u}px ${5 * u}px)` }}
    />
  );
};

export type Shot = {
  src: string;
  at: number; // frame it slides in
  /** Optional camera push on this image: [startFrame, durationFrames, scale, originX%, originY%]. */
  zoom?: [number, number, number, number, number];
  /** Drawn on top of the screenshot and zoomed with it; position in % of the screenshot. */
  overlay?: React.ReactNode;
};

/** A rounded screen that swaps between real screenshots, with optional camera pushes. Size is set by w/h in px. */
export const Screens: React.FC<{ shots: Shot[]; w: number; h: number; radius: number; border?: number; borderColor?: string }> = ({
  shots,
  w,
  h,
  radius,
  border = 0,
  borderColor = "#000",
}) => {
  const frame = useCurrentFrame();
  const { u } = useScene();
  const ease = Easing.bezier(0.16, 1, 0.3, 1);
  return (
    <div
      style={{
        width: w + border * 2,
        height: h + border * 2,
        borderRadius: radius + border,
        background: borderColor,
        padding: border,
        boxShadow: `0 ${40 * u}px ${120 * u}px rgba(0,0,0,0.55), 0 0 ${90 * u}px rgba(236,48,19,0.25)`,
      }}
    >
      <div style={{ position: "relative", width: w, height: h, borderRadius: radius, overflow: "hidden", background: "#000" }}>
        {shots.map((s, i) => {
          const o = interpolate(frame, [s.at, s.at + 6], [i === 0 ? 1 : 0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
          const ty = interpolate(frame, [s.at, s.at + 14], [i === 0 ? 0 : 5, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease });
          const [zs, zd, zscale, zx, zy] = s.zoom ?? [0, 1, 1, 50, 50];
          const scale = interpolate(frame, [zs, zs + zd], [1, zscale], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease });
          return (
            <div
              key={s.src}
              style={{ position: "absolute", inset: 0, opacity: o, translate: `0 ${ty}%`, scale, transformOrigin: `${zx}% ${zy}%` }}
            >
              <Img src={staticFile(s.src)} style={{ width: "100%", height: "100%", objectFit: "cover" }} />
              {s.overlay}
            </div>
          );
        })}
      </div>
    </div>
  );
};

/** CSS-3D perspective wrapper with a slow idle float. `enter` is the frame the swing-in starts. */
export const Tilt: React.FC<{ enter: number; ry?: number; rx?: number; children: React.ReactNode }> = ({ enter, ry = -24, rx = 8, children }) => {
  const frame = useCurrentFrame();
  const { u } = useScene();
  const ease = Easing.bezier(0.16, 1, 0.3, 1);
  const t = interpolate(frame, [enter, enter + 26], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease });
  const float = Math.sin(frame / 22);
  return (
    <div style={{ perspective: 2400 * u }}>
      <div
        style={{
          transform: `rotateY(${(ry + 70 * (1 - t)) * 1 + float * 3}deg) rotateX(${rx + float * 1.5}deg) translateY(${(1 - t) * 160 * u}px) scale(${0.7 + 0.3 * t})`,
          opacity: t,
        }}
      >
        {children}
      </div>
    </div>
  );
};

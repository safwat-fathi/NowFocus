import { loadFont } from "@remotion/fonts";
import { Audio } from "@remotion/media";
import React from "react";
import { AbsoluteFill, Easing, interpolate, staticFile, useCurrentFrame, useVideoConfig } from "remotion";

export const C = {
  ink: "#201e1d",
  accent: "#ec3013",
  ground: "#f3f2f2",
  surface: "#EAE9E9",
  rule: "#D7D3D3",
  mute: "#7D7979",
  white: "#ffffff",
  dark: "#09090b",
  darkCard: "#18181b",
  darkRule: "#27272a",
};

export const FONT = "Archivo, sans-serif";
export const LEAD = 8; // frames before the voice starts in each scene

loadFont({ family: "Archivo", url: staticFile("archivo.ttf"), weight: "100 900" });

const out = Easing.bezier(0.16, 1, 0.3, 1);

/** Frame, layout unit `u` (1 at 1080px on the short side) and a clamped ease-out ramp. */
export const useScene = () => {
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const portrait = height > width;
  const u = Math.min(width, height) / 1080;
  const ramp = (a: number, b: number, from: number, to: number) =>
    interpolate(frame, [a, b], [from, to], {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
      easing: out,
    });
  return { frame, width, height, portrait, u, ramp };
};

export const Voice: React.FC<{ id: string }> = ({ id }) => (
  <Audio src={staticFile(`voiceover/${id}.wav`)} from={LEAD} />
);

export const Stage: React.FC<{ bg: string; children: React.ReactNode; gap?: number }> = ({ bg, children, gap = 0 }) => {
  const { portrait, u } = useScene();
  return (
    <AbsoluteFill
      style={{
        backgroundColor: bg,
        fontFamily: FONT,
        padding: `${110 * u}px ${90 * u}px`,
        display: "flex",
        flexDirection: portrait ? "column" : "row",
        alignItems: "center",
        justifyContent: "center",
        gap: gap * u,
      }}
    >
      {children}
    </AbsoluteFill>
  );
};

/** One headline line that slides up out of a mask. */
export const Line: React.FC<{ at: number; size?: number; color?: string; children: React.ReactNode }> = ({
  at,
  size = 110,
  color = C.ink,
  children,
}) => {
  const { u, ramp } = useScene();
  return (
    <div style={{ overflow: "hidden", paddingBottom: 0.08 * size * u }}>
      <div
        style={{
          fontSize: size * u,
          lineHeight: 1.02,
          fontWeight: 800,
          letterSpacing: "-0.03em",
          color,
          translate: `0px ${ramp(at, at + 18, 110, 0)}%`,
        }}
      >
        {children}
      </div>
    </div>
  );
};

/** Focus reticle: four brackets around a square. */
export const Mark: React.FC<{ size: number; stroke?: string; fill?: string; progress: number; pop: number }> = ({
  size,
  stroke = C.ink,
  fill = C.accent,
  progress,
  pop,
}) => (
  <svg width={size} height={size} viewBox="0 0 100 100" style={{ overflow: "visible" }}>
    <path
      d="M9 36V9h27M64 9h27v27M91 64v27H64M36 91H9V64"
      fill="none"
      stroke={stroke}
      strokeWidth={10}
      strokeLinecap="square"
      pathLength={1}
      strokeDasharray={1}
      strokeDashoffset={1 - progress}
      opacity={progress > 0 ? 1 : 0}
    />
    <rect
      x={37}
      y={37}
      width={26}
      height={26}
      fill={fill}
      style={{ transformOrigin: "50px 50px", transformBox: "view-box", scale: pop }}
    />
  </svg>
);

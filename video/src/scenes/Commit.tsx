import React from "react";
import { AbsoluteFill } from "remotion";
import { C, FONT, useScene, Voice } from "../common";

export const Commit: React.FC = () => {
  const { u, ramp, portrait } = useScene();
  const days = Math.round(ramp(10, 40, 0, 14));
  return (
    <AbsoluteFill style={{ fontFamily: FONT, background: C.ink, display: "flex", flexDirection: portrait ? "column" : "row" }}>
      <Voice id="commit" />
      <div
        style={{
          flex: 1,
          background: C.ground,
          color: C.ink,
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          padding: 90 * u,
          opacity: ramp(0, 10, 0, 1),
        }}
      >
        <div style={{ fontSize: 40 * u, fontWeight: 600, color: C.mute }}>Commitment Shield</div>
        <div style={{ fontSize: 300 * u, fontWeight: 800, letterSpacing: "-0.05em", lineHeight: 0.95, color: C.accent }}>{days}</div>
        <div style={{ fontSize: 84 * u, fontWeight: 800, letterSpacing: "-0.03em" }}>days, locked.</div>
      </div>
      <div
        style={{
          flex: 1,
          background: C.ink,
          color: C.white,
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          padding: 90 * u,
          opacity: ramp(66, 78, 0, 1),
          translate: portrait ? `0px ${ramp(66, 86, 60, 0) * u}px` : `${ramp(66, 86, 60, 0) * u}px 0px`,
        }}
      >
        <div style={{ fontSize: 40 * u, fontWeight: 600, color: C.rule }}>Bedtime Wind-Down</div>
        <div style={{ fontSize: 240 * u, fontWeight: 800, letterSpacing: "-0.05em", lineHeight: 0.95 }}>22:30</div>
        <div style={{ fontSize: 84 * u, fontWeight: 800, letterSpacing: "-0.03em", color: C.accent }}>Phone locks itself.</div>
      </div>
    </AbsoluteFill>
  );
};

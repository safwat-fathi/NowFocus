import React from "react";
import { C, FONT, Line, Mark, Stage, useScene, Voice } from "../common";

export const Cta: React.FC = () => {
  const { u, ramp } = useScene();
  return (
    <Stage bg={C.accent}>
      <Voice id="cta" />
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 50 * u, fontFamily: FONT }}>
        <Mark size={260 * u} stroke={C.white} fill={C.ink} progress={ramp(0, 20, 0, 1)} pop={ramp(12, 26, 0, 1)} />
        <Line at={16} size={190} color={C.white}>NowFocus</Line>
        <Line at={40} size={90} color={C.ink}>Your focus, your rules.</Line>
      </div>
    </Stage>
  );
};

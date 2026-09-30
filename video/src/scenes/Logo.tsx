import React from "react";
import { C, FONT, Line, Mark, Stage, useScene, Voice } from "../common";

export const Logo: React.FC = () => {
  const { u, ramp } = useScene();
  return (
    <Stage bg={C.ground} gap={0}>
      <Voice id="logo" />
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 50 * u, fontFamily: FONT }}>
        <Mark size={360 * u} progress={ramp(0, 24, 0, 1)} pop={ramp(16, 30, 0, 1)} />
        <Line at={20} size={150}>NowFocus</Line>
        <Line at={30} size={84} color={C.accent}>gives it back.</Line>
      </div>
    </Stage>
  );
};

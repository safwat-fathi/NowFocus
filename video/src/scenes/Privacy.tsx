import React from "react";
import { C, Line, Stage, useScene, Voice } from "../common";

const PLATFORMS = ["Android", "macOS", "Windows"];

export const Privacy: React.FC = () => {
  const { u, ramp } = useScene();
  return (
    <Stage bg={C.ground} gap={100}>
      <Voice id="privacy" />
      <div>
        <Line at={6} size={120}>Stays on</Line>
        <Line at={12} size={120} color={C.accent}>your device.</Line>
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 24 * u, width: 560 * u }}>
        {PLATFORMS.map((p, i) => (
          <div
            key={p}
            style={{
              border: `${3 * u}px solid ${C.ink}`,
              background: C.white,
              padding: `${30 * u}px ${36 * u}px`,
              fontSize: 64 * u,
              fontWeight: 800,
              letterSpacing: "-0.03em",
              opacity: ramp(66 + i * 10, 76 + i * 10, 0, 1),
              translate: `${ramp(66 + i * 10, 86 + i * 10, 60, 0) * u}px 0px`,
            }}
          >
            {p}
          </div>
        ))}
      </div>
    </Stage>
  );
};

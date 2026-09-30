import React from "react";
import { C, Line, Stage, useScene, Voice } from "../common";

const NOTES = ["12 new notifications", "Just one more video", "Trending now", "You won't believe this", "Someone replied"];

export const Hook: React.FC = () => {
  const { u, ramp, frame } = useScene();
  return (
    <Stage bg={C.dark} gap={90}>
      <Voice id="hook" />
      <div style={{ display: "flex", flexDirection: "column", gap: 22 * u, width: 640 * u }}>
        {NOTES.map((t, i) => (
          <div
            key={t}
            style={{
              background: C.darkCard,
              border: `${2 * u}px solid ${C.darkRule}`,
              padding: `${26 * u}px ${30 * u}px`,
              color: "#f4f4f5",
              fontSize: 40 * u,
              fontWeight: 600,
              display: "flex",
              alignItems: "center",
              gap: 20 * u,
              opacity: ramp(i * 7, i * 7 + 10, 0, 1),
              translate: `${ramp(i * 7, i * 7 + 18, i % 2 ? 80 : -80, 0) * u}px ${ramp(i * 7, i * 7 + 18, -40, 0) * u}px`,
            }}
          >
            <div style={{ width: 20 * u, height: 20 * u, background: C.accent, opacity: frame % 30 < 15 ? 1 : 0.4 }} />
            {t}
          </div>
        ))}
      </div>
      <div>
        <Line at={6} size={104} color="#f4f4f5">Your phone is</Line>
        <Line at={12} size={104} color="#f4f4f5">built to take</Line>
        <Line at={18} size={104} color={C.accent}>your attention.</Line>
      </div>
    </Stage>
  );
};

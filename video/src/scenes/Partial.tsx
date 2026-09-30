import React from "react";
import { C, Line, Stage, useScene, Voice } from "../common";

const Card: React.FC<{ u: number }> = ({ u }) => (
  <div>
    <div style={{ height: 190 * u, background: C.surface }} />
    <div style={{ height: 20 * u, width: "80%", background: C.rule, marginTop: 16 * u }} />
    <div style={{ height: 20 * u, width: "50%", background: C.rule, marginTop: 10 * u }} />
  </div>
);

export const Partial: React.FC = () => {
  const { u, ramp, frame } = useScene();
  const cut = ramp(50, 66, 0, 1);
  return (
    <Stage bg={C.ground} gap={100}>
      <Voice id="partial" />
      <div>
        <Line at={6} size={120}>Keep YouTube.</Line>
        <Line at={40} size={120} color={C.accent}>Lose the Shorts.</Line>
      </div>
      <div style={{ width: 620 * u, background: C.white, border: `${3 * u}px solid ${C.ink}`, padding: 32 * u }}>
        <div style={{ fontSize: 34 * u, fontWeight: 800, marginBottom: 24 * u }}>Home</div>
        <Card u={u} />
        <div
          style={{
            overflow: "hidden",
            height: (230 * (1 - cut) + 0) * u,
            opacity: 1 - cut,
            marginTop: 28 * u * (1 - cut),
            position: "relative",
          }}
        >
          <div style={{ fontSize: 28 * u, fontWeight: 800, marginBottom: 12 * u }}>Shorts</div>
          <div style={{ display: "flex", gap: 14 * u }}>
            {[0, 1, 2].map((i) => (
              <div key={i} style={{ flex: 1, height: 170 * u, background: C.surface }} />
            ))}
          </div>
          {frame >= 44 && (
            <div style={{ position: "absolute", left: 0, top: "50%", height: 6 * u, width: `${ramp(44, 52, 0, 100)}%`, background: C.accent }} />
          )}
        </div>
        <div style={{ marginTop: 28 * u }}>
          <Card u={u} />
        </div>
      </div>
    </Stage>
  );
};

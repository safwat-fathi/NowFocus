import React from "react";
import { C, Line, Stage, useScene, Voice } from "../common";

const SITES = ["youtube.com", "reddit.com", "x.com", "Instagram", "TikTok"];

export const Block: React.FC = () => {
  const { u, ramp, frame } = useScene();
  const started = ramp(62, 66, 0, 1);
  return (
    <Stage bg={C.ground} gap={100}>
      <Voice id="block" />
      <div>
        <Line at={8} size={130}>Pick.</Line>
        <Line at={46} size={130}>Start.</Line>
        <Line at={86} size={130} color={C.accent}>Gone.</Line>
      </div>
      <div style={{ width: 700 * u, background: C.white, border: `${3 * u}px solid ${C.ink}` }}>
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            padding: `${24 * u}px ${32 * u}px`,
            borderBottom: `${3 * u}px solid ${C.ink}`,
            fontSize: 34 * u,
            fontWeight: 800,
            letterSpacing: "-0.02em",
          }}
        >
          <span>Focus session</span>
          <span style={{ color: started ? C.accent : C.mute }}>{started ? "24:59" : "25:00"}</span>
        </div>
        {SITES.map((s, i) => {
          const picked = ramp(10 + i * 7, 14 + i * 7, 0, 1);
          const blocked = ramp(84 + i * 5, 96 + i * 5, 0, 1);
          return (
            <div
              key={s}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 26 * u,
                padding: `${22 * u}px ${32 * u}px`,
                borderBottom: `${2 * u}px solid ${C.rule}`,
                fontSize: 40 * u,
                fontWeight: 600,
              }}
            >
              <div
                style={{
                  width: 34 * u,
                  height: 34 * u,
                  border: `${3 * u}px solid ${C.ink}`,
                  background: picked ? C.accent : "transparent",
                }}
              />
              <span
                style={{
                  opacity: 1 - 0.55 * blocked,
                  backgroundImage: `linear-gradient(${C.accent}, ${C.accent})`,
                  backgroundSize: `${blocked * 100}% ${5 * u}px`,
                  backgroundPosition: "0 58%",
                  backgroundRepeat: "no-repeat",
                }}
              >
                {s}
              </span>
              <span
                style={{
                  marginLeft: "auto",
                  fontSize: 24 * u,
                  fontWeight: 800,
                  letterSpacing: "0.08em",
                  color: C.accent,
                  opacity: blocked,
                }}
              >
                BLOCKED
              </span>
            </div>
          );
        })}
        <div
          style={{
            padding: `${28 * u}px`,
            textAlign: "center",
            fontSize: 38 * u,
            fontWeight: 800,
            background: frame >= 62 ? C.accent : C.ink,
            color: C.white,
            scale: 1 - 0.03 * (frame >= 62 && frame < 68 ? 1 : 0),
          }}
        >
          {frame >= 62 ? "Session running" : "Start session"}
        </div>
      </div>
    </Stage>
  );
};

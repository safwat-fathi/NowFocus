import { Audio } from "@remotion/media";
import { linearTiming, TransitionSeries, TransitionPresentation } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { flip } from "@remotion/transitions/flip";
import { slide } from "@remotion/transitions/slide";
import { wipe } from "@remotion/transitions/wipe";
import React from "react";
import { interpolate, staticFile, useVideoConfig } from "remotion";
import { TR_FADE, TestersProps } from "./calculate-metadata";
import { Android, Arabic, Cta, Free, Hook, Sync, Windows } from "./testers/scenes";

type Enter = TransitionPresentation<Record<string, unknown>>;

// Order must match src/script-tr.json. `enter` is the transition INTO that scene (ignored for the first).
export const TR_SCENES: { id: string; Scene: React.FC; enter: Enter }[] = [
  { id: "hook", Scene: Hook, enter: fade() as Enter },
  { id: "free", Scene: Free, enter: slide({ direction: "from-right" }) as Enter },
  { id: "android", Scene: Android, enter: flip({ direction: "from-right" }) as Enter },
  { id: "arabic", Scene: Arabic, enter: wipe({ direction: "from-right" }) as Enter },
  { id: "windows", Scene: Windows, enter: slide({ direction: "from-bottom" }) as Enter },
  { id: "sync", Scene: Sync, enter: flip({ direction: "from-left" }) as Enter },
  { id: "cta", Scene: Cta, enter: fade() as Enter },
];

export const Testers: React.FC<TestersProps> = ({ scenes }) => {
  const { durationInFrames: total } = useVideoConfig();
  return (
    <>
      <Audio
        src={staticFile("audio/music.wav")}
        loop
        volume={(f) => interpolate(f, [0, 20, total - 40, total], [0, 0.3, 0.3, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" })}
      />
      <TransitionSeries>
        {TR_SCENES.flatMap(({ id, Scene, enter }, i) => [
          ...(i ? [<TransitionSeries.Transition key={`t-${id}`} presentation={enter} timing={linearTiming({ durationInFrames: TR_FADE })} />] : []),
          <TransitionSeries.Sequence key={id} name={id} durationInFrames={scenes[i]}>
            <Scene />
          </TransitionSeries.Sequence>,
        ])}
      </TransitionSeries>
    </>
  );
};

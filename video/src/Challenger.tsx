import { Audio } from "@remotion/media";
import { linearTiming, TransitionSeries, TransitionPresentation } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { flip } from "@remotion/transitions/flip";
import { slide } from "@remotion/transitions/slide";
import { wipe } from "@remotion/transitions/wipe";
import React from "react";
import { interpolate, staticFile, useVideoConfig } from "remotion";
import { Android, Cta, Hook, MacOS, Privacy, Receipts, Reveal, Windows } from "./challenger/scenes";
import { CH_FADE, ChallengerProps } from "./calculate-metadata";

type Enter = TransitionPresentation<Record<string, unknown>>;

// Order must match src/script-vs.json. `enter` is the transition INTO that scene (ignored for the first).
export const CH_SCENES: { id: string; Scene: React.FC; enter: Enter }[] = [
  { id: "hook", Scene: Hook, enter: fade() as Enter },
  { id: "receipts", Scene: Receipts, enter: slide({ direction: "from-right" }) as Enter },
  { id: "reveal", Scene: Reveal, enter: fade() as Enter },
  { id: "android", Scene: Android, enter: flip({ direction: "from-right" }) as Enter },
  { id: "macos", Scene: MacOS, enter: wipe({ direction: "from-left" }) as Enter },
  { id: "windows", Scene: Windows, enter: slide({ direction: "from-bottom" }) as Enter },
  { id: "privacy", Scene: Privacy, enter: flip({ direction: "from-left" }) as Enter },
  { id: "cta", Scene: Cta, enter: fade() as Enter },
];

export const Challenger: React.FC<ChallengerProps> = ({ scenes }) => {
  const { durationInFrames: total } = useVideoConfig();
  return (
    <>
      <Audio
        src={staticFile("audio/music.wav")}
        loop
        volume={(f) => interpolate(f, [0, 20, total - 40, total], [0, 0.3, 0.3, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" })}
      />
      <TransitionSeries>
        {CH_SCENES.flatMap(({ id, Scene, enter }, i) => [
          ...(i ? [<TransitionSeries.Transition key={`t-${id}`} presentation={enter} timing={linearTiming({ durationInFrames: CH_FADE })} />] : []),
          <TransitionSeries.Sequence key={id} name={id} durationInFrames={scenes[i]}>
            <Scene />
          </TransitionSeries.Sequence>,
        ])}
      </TransitionSeries>
    </>
  );
};

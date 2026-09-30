import { linearTiming, TransitionSeries } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import React from "react";
import { FADE, PromoProps } from "./calculate-metadata";
import { Block } from "./scenes/Block";
import { Commit } from "./scenes/Commit";
import { Cta } from "./scenes/Cta";
import { Hook } from "./scenes/Hook";
import { Logo } from "./scenes/Logo";
import { Partial } from "./scenes/Partial";
import { Privacy } from "./scenes/Privacy";

// Order must match src/script.json.
export const SCENES = [
  { id: "hook", Scene: Hook },
  { id: "logo", Scene: Logo },
  { id: "block", Scene: Block },
  { id: "partial", Scene: Partial },
  { id: "commit", Scene: Commit },
  { id: "privacy", Scene: Privacy },
  { id: "cta", Scene: Cta },
];

export const Promo: React.FC<PromoProps> = ({ scenes }) => (
  <TransitionSeries>
    {SCENES.flatMap(({ id, Scene }, i) => [
      ...(i ? [<TransitionSeries.Transition key={`t-${id}`} presentation={fade()} timing={linearTiming({ durationInFrames: FADE })} />] : []),
      <TransitionSeries.Sequence key={id} name={id} durationInFrames={scenes[i]}>
        <Scene />
      </TransitionSeries.Sequence>,
    ])}
  </TransitionSeries>
);

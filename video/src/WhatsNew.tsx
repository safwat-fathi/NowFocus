import { linearTiming, TransitionSeries, TransitionPresentation } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { slide } from "@remotion/transitions/slide";
import { wipe } from "@remotion/transitions/wipe";
import React from "react";
import { WN_FADE, WhatsNewProps } from "./calculate-metadata";
import { Allow, Arabic, Bedtime, Block, Cta, Dns, DnsOn, Groups, Hook, Partial } from "./whatsnew/scenes";

type Enter = TransitionPresentation<Record<string, unknown>> | null;

// Order must match src/script-wn.json. `enter` is the transition INTO that scene; null is a straight cut
// (Dns -> DnsOn is one continuous take). No background music: voice and UI sounds only.
export const WN_SCENES: { id: string; Scene: React.FC; enter: Enter }[] = [
  { id: "hook", Scene: Hook, enter: null },
  { id: "dns", Scene: Dns, enter: fade() as Enter },
  { id: "dnson", Scene: DnsOn, enter: null },
  { id: "groups", Scene: Groups, enter: slide({ direction: "from-right" }) as Enter },
  { id: "allow", Scene: Allow, enter: wipe({ direction: "from-right" }) as Enter },
  { id: "partial", Scene: Partial, enter: slide({ direction: "from-bottom" }) as Enter },
  { id: "block", Scene: Block, enter: fade() as Enter },
  { id: "bedtime", Scene: Bedtime, enter: fade() as Enter },
  { id: "arabic", Scene: Arabic, enter: wipe({ direction: "from-left" }) as Enter },
  { id: "cta", Scene: Cta, enter: fade() as Enter },
];

export const WhatsNew: React.FC<WhatsNewProps> = ({ scenes }) => (
  <TransitionSeries>
    {WN_SCENES.flatMap(({ id, Scene, enter }, i) => [
      ...(i && enter ? [<TransitionSeries.Transition key={`t-${id}`} presentation={enter} timing={linearTiming({ durationInFrames: WN_FADE })} />] : []),
      <TransitionSeries.Sequence key={id} name={id} durationInFrames={scenes[i]}>
        <Scene />
      </TransitionSeries.Sequence>,
    ])}
  </TransitionSeries>
);

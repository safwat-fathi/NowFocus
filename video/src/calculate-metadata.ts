import { ALL_FORMATS, Input, UrlSource } from "mediabunny";
import { CalculateMetadataFunction, staticFile } from "remotion";
import { LEAD } from "./common";
import script from "./script.json";

export const FPS = 30;
export const FADE = 10;
const TAIL = 24;

export type PromoProps = { scenes: number[] };

const audioSeconds = (src: string) =>
  new Input({ formats: ALL_FORMATS, source: new UrlSource(src) }).computeDuration();

// Each scene lasts as long as its voice line (plus lead-in and tail); the total drops the fade overlaps.
export const calculateMetadata: CalculateMetadataFunction<PromoProps> = async () => {
  const secs = await Promise.all(script.map((s) => audioSeconds(staticFile(`voiceover/${s.id}.wav`))));
  const scenes = secs.map((s) => Math.ceil(s * FPS) + LEAD + TAIL);
  return {
    props: { scenes },
    durationInFrames: scenes.reduce((a, b) => a + b, 0) - FADE * (scenes.length - 1),
  };
};

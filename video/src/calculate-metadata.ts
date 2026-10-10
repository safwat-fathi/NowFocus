import { ALL_FORMATS, Input, UrlSource } from "mediabunny";
import { CalculateMetadataFunction, staticFile } from "remotion";
import { LEAD } from "./common";
import script from "./script.json";
import scriptTr from "./script-tr.json";
import scriptVs from "./script-vs.json";
import scriptWn from "./script-wn.json";

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

// The Challenger video: same idea, own script, voice files and (shorter) transitions.
export const CH_FADE = 8;
export type ChallengerProps = { scenes: number[] };

export const calculateChallengerMetadata: CalculateMetadataFunction<ChallengerProps> = async () => {
  const secs = await Promise.all(scriptVs.map((s) => audioSeconds(staticFile(`voiceover-vs/${s.id}.wav`))));
  const scenes = secs.map((s) => Math.ceil(s * FPS) + LEAD + TAIL);
  return {
    props: { scenes },
    durationInFrames: scenes.reduce((a, b) => a + b, 0) - CH_FADE * (scenes.length - 1),
  };
};

// The test-releases video: Android + Windows early access.
export const TR_FADE = 8;
export type TestersProps = { scenes: number[] };

export const calculateTestersMetadata: CalculateMetadataFunction<TestersProps> = async () => {
  const secs = await Promise.all(scriptTr.map((s) => audioSeconds(staticFile(`voiceover-tr/${s.id}.wav`))));
  const scenes = secs.map((s) => Math.ceil(s * FPS) + LEAD + TAIL);
  return {
    props: { scenes },
    durationInFrames: scenes.reduce((a, b) => a + b, 0) - TR_FADE * (scenes.length - 1),
  };
};

// The "what's new on Android" video: screen scenes get a few extra frames so the last tap can land.
export const WN_FADE = 8;
export type WhatsNewProps = { scenes: number[] };
const WN_HOLD: Record<string, number> = { hook: 0, cta: 0 };
const WN_CUTS = new Set(["hook", "dnson"]); // scenes entered with a straight cut, no overlap

export const calculateWhatsNewMetadata: CalculateMetadataFunction<WhatsNewProps> = async () => {
  const secs = await Promise.all(scriptWn.map((s) => audioSeconds(staticFile(`voiceover-wn/${s.id}.wav`))));
  const scenes = secs.map((s, i) => Math.ceil(s * FPS) + LEAD + TAIL + (WN_HOLD[scriptWn[i].id] ?? 36));
  const overlaps = scriptWn.filter((s) => !WN_CUTS.has(s.id)).length * WN_FADE;
  return { props: { scenes }, durationInFrames: scenes.reduce((a, b) => a + b, 0) - overlaps };
};

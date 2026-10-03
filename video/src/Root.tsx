import React from "react";
import { Composition, Folder } from "remotion";
import { calculateChallengerMetadata, calculateMetadata, FPS } from "./calculate-metadata";
import { CH_SCENES, Challenger } from "./Challenger";
import "./index.css";
import { Promo, SCENES } from "./Promo";

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="Promo16x9" component={Promo} width={1920} height={1080} fps={FPS} durationInFrames={900} defaultProps={{ scenes: SCENES.map(() => 120) }} calculateMetadata={calculateMetadata} />
    <Composition id="Promo9x16" component={Promo} width={1080} height={1920} fps={FPS} durationInFrames={900} defaultProps={{ scenes: SCENES.map(() => 120) }} calculateMetadata={calculateMetadata} />
    <Composition id="Challenger16x9" component={Challenger} width={1920} height={1080} fps={FPS} durationInFrames={1250} defaultProps={{ scenes: CH_SCENES.map(() => 150) }} calculateMetadata={calculateChallengerMetadata} />
    <Composition id="Challenger9x16" component={Challenger} width={1080} height={1920} fps={FPS} durationInFrames={1250} defaultProps={{ scenes: CH_SCENES.map(() => 150) }} calculateMetadata={calculateChallengerMetadata} />
    <Folder name="Scenes">
      {SCENES.map(({ id, Scene }) => (
        <Composition key={id} id={`Scene-${id}`} component={Scene} width={1920} height={1080} fps={FPS} durationInFrames={150} />
      ))}
    </Folder>
  </>
);

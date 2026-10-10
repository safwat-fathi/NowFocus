import React from "react";
import { Composition, Folder } from "remotion";
import { calculateChallengerMetadata, calculateMetadata, calculateTestersMetadata, calculateWhatsNewMetadata, FPS } from "./calculate-metadata";
import { CH_SCENES, Challenger } from "./Challenger";
import "./index.css";
import { Promo, SCENES } from "./Promo";
import { TR_SCENES, Testers } from "./Testers";
import { WN_SCENES, WhatsNew } from "./WhatsNew";

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="Promo16x9" component={Promo} width={1920} height={1080} fps={FPS} durationInFrames={900} defaultProps={{ scenes: SCENES.map(() => 120) }} calculateMetadata={calculateMetadata} />
    <Composition id="Promo9x16" component={Promo} width={1080} height={1920} fps={FPS} durationInFrames={900} defaultProps={{ scenes: SCENES.map(() => 120) }} calculateMetadata={calculateMetadata} />
    <Composition id="Challenger16x9" component={Challenger} width={1920} height={1080} fps={FPS} durationInFrames={1250} defaultProps={{ scenes: CH_SCENES.map(() => 150) }} calculateMetadata={calculateChallengerMetadata} />
    <Composition id="Challenger9x16" component={Challenger} width={1080} height={1920} fps={FPS} durationInFrames={1250} defaultProps={{ scenes: CH_SCENES.map(() => 150) }} calculateMetadata={calculateChallengerMetadata} />
    <Composition id="Testers16x9" component={Testers} width={1920} height={1080} fps={FPS} durationInFrames={900} defaultProps={{ scenes: TR_SCENES.map(() => 130) }} calculateMetadata={calculateTestersMetadata} />
    <Composition id="Testers9x16" component={Testers} width={1080} height={1920} fps={FPS} durationInFrames={900} defaultProps={{ scenes: TR_SCENES.map(() => 130) }} calculateMetadata={calculateTestersMetadata} />
    <Composition id="WhatsNew16x9" component={WhatsNew} width={1920} height={1080} fps={FPS} durationInFrames={1900} defaultProps={{ scenes: WN_SCENES.map(() => 150) }} calculateMetadata={calculateWhatsNewMetadata} />
    <Composition id="WhatsNew9x16" component={WhatsNew} width={1080} height={1920} fps={FPS} durationInFrames={1900} defaultProps={{ scenes: WN_SCENES.map(() => 150) }} calculateMetadata={calculateWhatsNewMetadata} />
    <Folder name="Scenes">
      {SCENES.map(({ id, Scene }) => (
        <Composition key={id} id={`Scene-${id}`} component={Scene} width={1920} height={1080} fps={FPS} durationInFrames={150} />
      ))}
    </Folder>
  </>
);

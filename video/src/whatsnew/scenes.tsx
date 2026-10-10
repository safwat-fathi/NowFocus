import React from "react";
import { interpolate, useCurrentFrame } from "remotion";
import { C, FONT, Line, Mark, Stage, useScene } from "../common";
import { Flash, Glitch, RgbText, Scanlines, Sfx, Shake, Shockwave, useSlam } from "../challenger/fx";
import { BedtimeScreen, BlockScreen, DnsScreen, EditorScreen, HomeScreen, RulesScreen, SettingsScreen, DnsState } from "./screens";
import { Pages, PhoneStage, Vo } from "./stage";
import { Ring, Tap } from "./ui";

// Frame helpers: 0 -> 1 between a and b, and a step that is 1 from frame a on.
const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
const r = (f: number, a: number, b: number) => interpolate(f, [a, b], [0, 1], clamp);
const pulse = (f: number, a: number, len: number) => interpolate(f, [a, a + 8, a + len - 10, a + len], [0, 1, 1, 0], clamp);

// Scene lengths are voice + 32 frames (+ HOLD) and the voice is fixed, so beats below are absolute frames.
// Screen coordinates are dp on the 360 x 720 phone screen; y positions were checked against stills.

// ---------------------------------------------------------------- 1. Hook
export const Hook: React.FC = () => {
  const { u, portrait } = useScene();
  const a = useSlam(4);
  const b = useSlam(20);
  const size = portrait ? 170 : 220;
  return (
    <Shake hits={[[4, 14], [20, 20]]}>
      <Stage bg={C.dark}>
        <Vo id="hook" />
        <Sfx name="hit" at={4} />
        <Sfx name="hit" at={20} volume={0.8} />
        <Flash at={20} len={8} />
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 18 * u, fontFamily: FONT, fontWeight: 900, letterSpacing: "-0.04em", color: "#f4f4f5", textAlign: "center" }}>
          <RgbText amount={(1 - a.p) * 26 * u} style={{ fontSize: size * u, lineHeight: 0.95, opacity: a.on ? 1 : 0, scale: 1 + (1 - Math.min(a.p, 1)) * 0.9 }}>WHAT'S NEW</RgbText>
          <RgbText amount={(1 - b.p) * 26 * u} style={{ fontSize: size * u, lineHeight: 0.95, color: C.accent, opacity: b.on ? 1 : 0, scale: 1 + (1 - Math.min(b.p, 1)) * 0.9 }}>ON ANDROID</RgbText>
          <div style={{ marginTop: 30 * u, fontSize: (portrait ? 54 : 64) * u, opacity: b.on ? 1 : 0, border: `${5 * u}px solid #f4f4f5`, padding: `${4 * u}px ${24 * u}px` }}>VERSION 0.9</div>
        </div>
        <Scanlines />
      </Stage>
    </Shake>
  );
};

// ---------------------------------------------------------------- 2. DNS: pick a server
export const Dns: React.FC = () => {
  const f = useCurrentFrame();
  const scroll = 250 * r(f, 12, 44);
  const server = f >= 112 ? 1 : 0;
  const state: DnsState = f >= 112 ? "waiting" : "off";
  return (
    <PhoneStage
      kicker="DNS"
      lines={["Pick your", "DNS server"]}
      sub="Your blocklist always applies first."
      cam={[[0, 1, 180, 360], [8, 1, 180, 360], [30, 1.35, 180, 330], [62, 1.35, 180, 330], [84, 1.9, 180, 300], [200, 1.9, 180, 300]]}
    >
      <Vo id="dns" />
      <Sfx name="whoosh" at={0} />
      <Pages
        pages={[
          [0, <RulesScreen key="r" scroll={scroll} hlDns={pulse(f, 46, 24)} />],
          [74, <DnsScreen key="d" server={server} always={false} state={state} hlServer={pulse(f, 100, 40)} />],
        ]}
      />
      <Tap x={150} y={366} at={64} />
      <Tap x={150} y={330} at={112} />
    </PhoneStage>
  );
};

// ---------------------------------------------------------------- 3. DNS: always on -> Focus screen
export const DnsOn: React.FC = () => {
  const f = useCurrentFrame();
  const scroll = 80 * r(f, 0, 14) * (1 - r(f, 62, 76));
  const always = f >= 28;
  const state: DnsState = f < 28 ? "waiting" : f < 44 ? "starting" : "active";
  return (
    <PhoneStage
      kicker="ALWAYS ON"
      lines={["Always on,", "on Focus"]}
      sub="Shown under Always on."
      cam={[[0, 1.9, 180, 300], [14, 1.5, 180, 330], [60, 1.5, 180, 330], [78, 1, 180, 360], [96, 1, 180, 360], [116, 1.9, 180, 440], [190, 1.9, 180, 440]]}
    >
      <Vo id="dnson" />
      <Pages
        pages={[
          [0, <DnsScreen key="d" server={1} always={always} state={state} scroll={scroll} hlSeg={pulse(f, 6, 30)} />],
          [82, <RulesScreen key="r" dnsSub="AdGuard Family, always on" dnsOn />],
          [104, <HomeScreen key="h" dns hl={pulse(f, 118, 60)} />],
        ]}
      />
      <Tap x={270} y={536} at={26} />
      <Tap x={36} y={52} at={76} />
      <Tap x={36} y={700} at={98} />
      {f >= 106 && <Ring x={16} y={461} w={328} h={43} at={114} len={64} />}
    </PhoneStage>
  );
};

// ---------------------------------------------------------------- 4. Saved groups
export const Groups: React.FC = () => {
  const f = useCurrentFrame();
  const added = f >= 150;
  const fresh = added ? 1 - r(f, 150, 200) : 0;
  return (
    <PhoneStage
      kicker="SAVED GROUPS"
      lines={["Saved", "groups"]}
      sub="Add it to any profile in one tap."
      cam={[[0, 1, 180, 360], [12, 1, 180, 360], [32, 1.7, 180, 470], [74, 1.7, 180, 470], [96, 1.3, 180, 400], [150, 1.3, 180, 400], [172, 1.2, 180, 380], [230, 1.2, 180, 380]]}
    >
      <Vo id="groups" />
      <Pages
        pages={[
          [0, <EditorScreen key="a" name="Work" sites={["youtube.com", "reddit.com", "instagram.com"]} apps={["Instagram", "TikTok"]} saved={f >= 46} hlSave={pulse(f, 40, 36)} />],
          [86, (
            <EditorScreen
              key="b"
              name="Evenings"
              sites={added ? ["x.com", "netflix.com", "youtube.com", "reddit.com", "instagram.com"] : ["x.com", "netflix.com"]}
              apps={added ? ["YouTube", "Instagram", "TikTok"] : ["YouTube"]}
              freshFrom={2}
              fresh={fresh}
              hlAdd={pulse(f, 104, 24)}
              dialog={r(f, 116, 124) * (1 - r(f, 144, 150))}
              dialogPick={f >= 138 ? 0 : -1}
            />
          )],
        ]}
      />
      <Tap x={70} y={531} at={44} />
      <Tap x={110} y={419} at={112} />
      <Tap x={180} y={341} at={136} />
    </PhoneStage>
  );
};

// ---------------------------------------------------------------- 5. Allow only these
export const Allow: React.FC = () => {
  const f = useCurrentFrame();
  const apps = ["Notion", "Kindle", "Anki"].filter((_, i) => f >= 84 + i * 12);
  return (
    <PhoneStage
      kicker="ALLOW-ONLY PROFILES"
      lines={["Allow only", "these apps"]}
      sub="Everything else closes."
      cam={[[0, 1, 180, 360], [10, 1, 180, 360], [22, 1.7, 250, 150], [34, 1.7, 250, 150], [48, 1.45, 180, 340], [62, 1.45, 180, 340], [76, 1.6, 180, 200], [128, 1.6, 180, 200], [146, 1, 180, 360], [230, 1, 180, 360]]}
    >
      <Vo id="allow" />
      <Pages
        pages={[
          [0, <RulesScreen key="r" dialog={r(f, 24, 32) * (1 - r(f, 56, 62))} dialogPick={f >= 52 ? 1 : -1} />],
          [64, <EditorScreen key="e" mode="allow" name="Study" apps={apps} freshFrom={0} fresh={1 - r(f, 100, 130)} />],
          [136, <BlockScreen key="b" app="Instagram" reason="Only the apps you allowed are open until 5:00 PM." timeLabel="Left in session" time="2h 10m" tries={1} goal="Finish the chapter." hlReason={pulse(f, 146, 50)} />],
        ]}
      />
      <Tap x={294} y={67} at={22} />
      <Tap x={180} y={390} at={50} />
    </PhoneStage>
  );
};

// ---------------------------------------------------------------- 6. Partial blocking
export const Partial: React.FC = () => {
  const f = useCurrentFrame();
  const partial = [0, 1, 2, 3, 4, 5, 6].map((i) => r(f, 24 + i * 9, 32 + i * 9));
  return (
    <PhoneStage
      kicker="PARTIAL BLOCKING"
      lines={["Seven feeds,", "one tap each"]}
      sub="Now with the TikTok feed."
      cam={[[0, 1, 180, 360], [14, 1, 180, 360], [34, 1.25, 180, 360], [96, 1.25, 180, 360], [120, 2.2, 180, 500], [210, 2.2, 180, 500]]}
    >
      <Vo id="partial" />
      <Sfx name="whoosh" at={0} />
      <EditorScreen name="Work" sites={["youtube.com", "reddit.com"]} apps={["TikTok"]} partial={partial} hlPartial={f >= 96 ? 6 : -1} scroll={406} />
      {[0, 1, 2, 3, 4, 5, 6].map((i) => <Tap key={i} x={316} y={132 + i * 57} at={22 + i * 9} />)}
    </PhoneStage>
  );
};

// ---------------------------------------------------------------- 7. Block screen
export const Block: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <PhoneStage
      dark
      kicker="BLOCK SCREEN"
      lines={["Every block", "says why"]}
      sub="Streak passes grow, 5 to 15 min."
      cam={[[0, 1, 180, 360], [14, 1, 180, 360], [40, 1.7, 180, 140], [100, 1.7, 180, 140], [124, 1.8, 180, 640], [250, 1.8, 180, 650]]}
    >
      <Vo id="block" />
      <Sfx name="hit" at={2} volume={0.7} />
      <BlockScreen
        app="Instagram"
        reason="You've used your 30 minutes of Instagram today. It's back at midnight."
        timeLabel="Back in"
        time="5h 12m"
        tries={f < 18 ? 2 : 3}
        goal="Be there for dinner."
        pass="Open Instagram for 9 min (2 left)"
        passNote="Your 4-day streak adds 4 min."
        need={false}
        hlReason={pulse(f, 52, 50)}
        hlPass={pulse(f, 140, 90)}
      />
    </PhoneStage>
  );
};

// ---------------------------------------------------------------- 8. Bedtime
export const Bedtime: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <PhoneStage
      dark
      kicker="BEDTIME WIND-DOWN"
      lines={["Gently", "dimmed"]}
      sub="A calmer, darker evening screen."
      cam={[[0, 1.1, 180, 300], [8, 1.1, 180, 300], [30, 1.6, 180, 150], [56, 1.6, 180, 150], [74, 1.8, 180, 360], [98, 1.8, 180, 360], [116, 1.7, 180, 480], [188, 1.7, 180, 480]]}
    >
      <Vo id="bedtime" />
      <Sfx name="whoosh" at={0} />
      <BedtimeScreen on={1} quiet={r(f, 108, 118)} lock={r(f, 126, 136)} hlHero={pulse(f, 24, 40)} hlTime={f < 76 ? -1 : f < 84 ? 0 : f < 92 ? 1 : f < 100 ? 2 : -1} />
      <Tap x={321} y={441} at={106} dark />
      <Tap x={321} y={499} at={124} dark />
    </PhoneStage>
  );
};

// ---------------------------------------------------------------- 9. Arabic
export const Arabic: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <PhoneStage
      kicker="العربية · ARABIC"
      lines={["Now in", "Arabic"]}
      sub="Right to left. Settings → Language."
      cam={[[0, 1, 180, 360], [10, 1, 180, 360], [26, 1.6, 180, 150], [100, 1.6, 180, 150], [124, 1, 180, 360], [220, 1, 180, 360]]}
    >
      <Vo id="arabic" />
      <Sfx name="whoosh" at={0} />
      <Pages
        pages={[
          [0, <SettingsScreen key="en" lang="en" sel={0} hl={pulse(f, 14, 30)} />],
          [34, <SettingsScreen key="ar" lang="ar" sel={2} />],
          [112, <HomeScreen key="h" lang="ar" dns />],
        ]}
      />
      <Tap x={289} y={140} at={30} />
      <Tap x={315} y={700} at={106} />
    </PhoneStage>
  );
};

// ---------------------------------------------------------------- 10. CTA
const Tag: React.FC<{ at: number; size: number; children: React.ReactNode }> = ({ at, size, children }) => {
  const { u, ramp } = useScene();
  return (
    <div style={{ overflow: "hidden" }}>
      <div style={{ background: C.accent, color: "#fff", fontFamily: FONT, fontWeight: 900, fontSize: size * u, padding: `${8 * u}px ${28 * u}px`, letterSpacing: "-0.02em", display: "inline-block", translate: `0 ${ramp(at, at + 12, 110, 0)}%` }}>{children}</div>
    </div>
  );
};

export const Cta: React.FC = () => {
  const { u, portrait, ramp } = useScene();
  const out = Math.max(0, ramp(170, 198, 0, 1));
  return (
    <Glitch amount={out * 120 * u}>
      <Stage bg={C.ground} gap={portrait ? 40 : 90}>
        <Vo id="cta" />
        <Sfx name="hit" at={10} volume={0.8} />
        <Shockwave at={10} color={C.accent} size={1500} />
        <Mark size={(portrait ? 300 : 380) * u} progress={ramp(2, 22, 0, 1)} pop={ramp(16, 28, 0, 1.15)} />
        <div>
          <Line at={14} size={portrait ? 150 : 190}>NowFocus</Line>
          <div style={{ display: "flex", flexDirection: "column", gap: 10 * u, margin: `${6 * u}px 0 ${12 * u}px` }}>
            <Line at={26} size={portrait ? 54 : 64} color={C.mute}>Android · version 0.9</Line>
          </div>
          <Tag at={42} size={portrait ? 74 : 96}>nowfocus.online</Tag>
        </div>
      </Stage>
    </Glitch>
  );
};

import React from "react";
import { Body, Device, Dialog, Field, Ghost, H, Kicker, ListRow, NF, Outline, Pill, Primary, Rule, Seg, SH, StatusBar, SW, T, TabBar, Toggle, ToggleRow } from "./ui";

// Shipped copy, English and Arabic (res/values/strings_*.xml, res/values-ar/strings_*.xml).
export const L = {
  en: {
    tabs: ["Focus", "Rules", "Devices", "Stats", "Settings"],
    ready: "Ready when you are", hero: "Your phone is ready.", web: "Website blocking: ready", apps: "App blocking: ready",
    today: "Today", away: "Turned away", sessions: "Sessions", hm: "1h 25m", start: "Start focus session", date: "Saturday, Oct 10",
    always: "ALWAYS ON", on: "On", bedtime: "Bedtime Wind-Down", tonight: "Tonight 9:30 PM",
    settings: "Settings", language: "LANGUAGE", langs: ["System default", "English", "العربية"],
    about: "About", aboutSub: "Version 0.9.0, privacy, terms, support", report: "Report an issue", reportSub: "Tell us what went wrong",
  },
  ar: {
    tabs: ["التركيز", "القواعد", "الأجهزة", "الإحصاءات", "الإعدادات"],
    ready: "جاهز حين تكون جاهزًا", hero: "هاتفك جاهز.", web: "حجب المواقع: جاهز", apps: "حجب التطبيقات: جاهز",
    today: "اليوم", away: "محاولات محجوبة", sessions: "الجلسات", hm: "1 س 25 د", start: "ابدأ جلسة تركيز", date: "السبت، 10 أكتوبر",
    always: "قيد التشغيل دائمًا", on: "مفعّل", bedtime: "تهدئة وقت النوم", tonight: "الليلة 9:30 م",
    settings: "الإعدادات", language: "اللغة", langs: ["لغة الجهاز", "English", "العربية"],
    about: "حول التطبيق", aboutSub: "الإصدار 0.9.0، الخصوصية، الشروط، الدعم", report: "الإبلاغ عن مشكلة", reportSub: "أخبرنا بما حدث",
  },
};
export type Lang = keyof typeof L;

const Top: React.FC<{ title: string; right?: React.ReactNode }> = ({ title, right }) => (
  <>
    <div style={{ display: "flex", alignItems: "center", padding: "8px 0" }}>
      <H size={28} style={{ flex: 1 }}>{title}</H>
      {right}
    </div>
  </>
);

// ------------------------------------------------------------------ Home
export const HomeScreen: React.FC<{ lang?: Lang; dns?: boolean; hl?: number }> = ({ lang = "en", dns = false, hl = 0 }) => {
  const t = L[lang];
  return (
    <Device rtl={lang === "ar"}>
      <StatusBar />
      <Body style={{ paddingTop: 8 }}>
        <div style={{ display: "flex", alignItems: "flex-end", padding: "12px 0" }}>
          <H size={20} style={{ flex: 1 }}>NowFocus</H>
          <T size={13}>{t.date}</T>
        </div>
        <Rule thick />
        <div style={{ height: 24 }} />
        <Kicker>{t.ready}</Kicker>
        <div style={{ height: 8 }} />
        <H size={38} style={{ lineHeight: 1.05 }}>{t.hero}</H>
        <div style={{ height: 24 }} />
        <Rule />
        <div style={{ height: 12 }} />
        <T size={13} color={NF.text}>{t.web}</T>
        <T size={13} color={NF.text}>{t.apps}</T>
        <div style={{ height: 12 }} />
        <Rule />
        <div style={{ display: "flex" }}>
          {[[t.today, t.hm], [t.away, "6"], [t.sessions, "2"]].map(([k, v]) => (
            <div key={k} style={{ flex: 1, padding: "12px 0" }}>
              <Kicker color={NF.n700}>{k}</Kicker>
              <H size={24}>{v}</H>
            </div>
          ))}
        </div>
        <Rule />
        <div style={{ height: 24 }} />
        <Primary>{t.start}</Primary>
        <div style={{ height: 24 }} />
        <Kicker color={NF.n700}>{t.always}</Kicker>
        {dns && (
          <>
            <div style={{ display: "flex", alignItems: "center", padding: "12px 0", background: hl ? `rgba(236,48,19,${0.12 * hl})` : undefined }}>
              <T size={15} weight={600} color={NF.text} style={{ flex: 1 }}>DNS</T>
              <Pill accent={false}>{t.on}</Pill>
            </div>
            <Rule />
          </>
        )}
        <div style={{ display: "flex", alignItems: "center", padding: "12px 0" }}>
          <T size={15} weight={600} color={NF.text} style={{ flex: 1 }}>{t.bedtime}</T>
          <Pill accent={false}>{t.tonight}</Pill>
        </div>
        <Rule />
      </Body>
      <TabBar labels={t.tabs} sel={0} />
    </Device>
  );
};

// ------------------------------------------------------------------ Rules
export type Dlg = { kind: "new"; p: number } | null;

export const RulesScreen: React.FC<{
  scroll?: number; dnsSub?: string; dnsOn?: boolean; groups?: string; extraProfile?: string; hlDns?: number; hlGroups?: number; dialog?: number; dialogPick?: number;
}> = ({ scroll = 0, dnsSub = "Not set up", dnsOn = false, groups = "None yet", extraProfile, hlDns = 0, hlGroups = 0, dialog = 0, dialogPick = -1 }) => (
  <Device>
    <StatusBar />
    <div style={{ position: "absolute", top: 28, bottom: 43, insetInline: 0, overflow: "hidden" }}>
      <Body y={scroll} style={{ paddingTop: 8 }}>
        <Top title="Rules" right={<Outline>+ New profile</Outline>} />
        <div style={{ height: 16 }} />
        <Kicker color={NF.n700}>FOCUS PROFILES</Kicker>
        <div style={{ height: 4 }} />
        <Rule />
        <ListRow title="Work" sub="3 sites · 2 apps" right={<Ghost>Remove</Ghost>} />
        <ListRow title="Evenings" sub="2 sites · 1 app" right={<Ghost>Remove</Ghost>} />
        {extraProfile && <ListRow title={extraProfile} sub="No apps allowed on this phone yet" right={<Ghost>Remove</Ghost>} />}
        <ListRow title="Saved groups" sub={groups} hl={hlGroups} />
        <div style={{ height: 24 }} />
        <Kicker color={NF.n700}>PROTECTIONS</Kicker>
        <div style={{ height: 4 }} />
        <Rule />
        <ListRow title="Commitment Shield" sub="Not set up" />
        <ListRow title="Bedtime Wind-Down" sub="Every night" tag={<Pill accent={false}>On</Pill>} />
        <ListRow title="People who matter" sub="Not set up" />
        <ListRow title="Your goals" sub="Not set up" />
        <ListRow title="DNS" sub={dnsSub} tag={dnsOn ? <Pill accent={false}>On</Pill> : undefined} hl={hlDns} />
        <ListRow title="Schedules" sub="Not set up" />
        <ListRow title="Daily limits" sub="2 limits" />
        <ListRow title="Opening friction" sub="Not set up" />
        <ListRow title="Cheat day" sub="A planned day off from blocking" />
      </Body>
    </div>
    <TabBar labels={L.en.tabs} sel={1} />
    {dialog > 0 && (
      <Dialog p={dialog} title="New profile">
        {[["Block these", "Close the apps and sites you pick."], ["Allow only these", "Close everything except the apps you pick."]].map(([a, b], i) => (
          <div key={a} style={{ padding: "8px 0", marginBottom: i ? 0 : 12, background: dialogPick === i ? "rgba(236,48,19,0.12)" : undefined }}>
            <T size={17} weight={600} color={NF.text}>{a}</T>
            <T size={13}>{b}</T>
          </div>
        ))}
        <Ghost color={NF.accent} style={{ textAlign: "end", marginTop: 8 }}>Cancel</Ghost>
      </Dialog>
    )}
  </Device>
);

// ------------------------------------------------------------------ DNS
const SERVERS: [string, string][] = [
  ["My network's DNS", "NowFocus changes nothing."],
  ["AdGuard Family", "Blocks adult sites, ads and malware"],
  ["Cloudflare Family", "Blocks adult sites and malware"],
  ["CleanBrowsing Family", "Blocks adult sites"],
  ["Quad9", "Blocks malware"],
  ["Other server", "Any DNS-over-TLS server you type"],
];
export type DnsState = "off" | "waiting" | "starting" | "active";

export const DnsScreen: React.FC<{ server: number; always: boolean; state: DnsState; scroll?: number; hlServer?: number; hlSeg?: number }> = ({ server, always, state, scroll = 0, hlServer = 0, hlSeg = 0 }) => {
  const name = SERVERS[server][0];
  const label = { off: "Off", waiting: "Waiting", starting: "Starting", active: "Active" }[state];
  const sentence = {
    off: "NowFocus is not changing your DNS.",
    waiting: `${name} switches on when a focus session starts.`,
    starting: "Starting…",
    active: `Lookups go to ${name}.`,
  }[state];
  return (
    <Device>
      <StatusBar />
      <Body y={scroll} style={{ paddingTop: 8 }}>
        <Ghost style={{ paddingBottom: 0 }}>‹ Back</Ghost>
        <H size={28} style={{ padding: "8px 0" }}>DNS</H>
        <T size={13}>Choose the server NowFocus uses for lookups. Your blocklist always applies first.</T>
        <div style={{ height: 12 }} />
        <Rule thick />
        <div style={{ height: 12 }} />
        <Pill accent={state === "starting"}>{label}</Pill>
        <div style={{ height: 8 }} />
        <T size={15} color={NF.text}>{sentence}</T>
        <div style={{ height: 12 }} />
        <Rule />
        <div style={{ height: 12 }} />
        <Kicker color={NF.n700}>Server</Kicker>
        {SERVERS.map(([n, s], i) => (
          <div key={n} style={{ display: "flex", alignItems: "center", padding: "8px 0", background: hlServer && i === 1 ? `rgba(236,48,19,${0.12 * hlServer})` : undefined }}>
            <T size={13} color={NF.text} style={{ paddingInlineEnd: 12 }}>{server === i ? "●" : "○"}</T>
            <div>
              <T size={15} weight={600} color={NF.text}>{n}</T>
              <T size={13}>{s}</T>
            </div>
          </div>
        ))}
        <div style={{ height: 12 }} />
        <Rule />
        <div style={{ height: 12 }} />
        <Kicker color={NF.n700}>When it runs</Kicker>
        <div style={{ height: 8 }} />
        <div style={{ outline: hlSeg ? `3px solid rgba(236,48,19,${hlSeg})` : undefined, outlineOffset: 3 }}>
          <Seg options={["Only during sessions", "Always on"]} sel={always ? 1 : 0} />
        </div>
        <div style={{ height: 8 }} />
        <T size={13}>
          {always
            ? "NowFocus keeps this DNS running all the time. Only one VPN app can run at once, and Private DNS must be Automatic or Off."
            : "The server is used while a session or the Shield is live."}
        </T>
        <div style={{ height: 12 }} />
        <Rule />
        <div style={{ height: 12 }} />
        <T size={13}>Browsers with their own secure DNS ignore this. Ad and adult filtering is done by the server you pick, not by NowFocus.</T>
      </Body>
    </Device>
  );
};

// ------------------------------------------------------------------ Groups + profile editor
const Section: React.FC<{ title: string; children?: React.ReactNode }> = ({ title, children }) => (
  <>
    <div style={{ height: 20 }} />
    <Kicker color={NF.n700}>{title}</Kicker>
    <div style={{ height: 4 }} />
    <Rule />
    {children}
  </>
);
const Item: React.FC<{ text: string; tag?: string; fresh?: number }> = ({ text, tag, fresh = 0 }) => (
  <>
    <div style={{ display: "flex", alignItems: "center", padding: "10px 0", background: fresh ? `rgba(236,48,19,${0.14 * fresh})` : undefined, opacity: 1 }}>
      <T size={15} weight={600} color={NF.text} style={{ flex: 1 }}>{text}</T>
      {tag && <T size={12}>{tag}</T>}
    </div>
    <Rule />
  </>
);

export const PARTIAL: [string, string][] = [
  ["YouTube Shorts", "Backs out of the Shorts player and tab"],
  ["YouTube Home feed", "Hides recommended videos on the Home tab"],
  ["YouTube up next / related", "Hides the list under a playing video"],
  ["Facebook Reels", "Backs out of the Reels tab and viewer"],
  ["Instagram Reels & Explore", "Backs out of the Reels tab, viewer and Explore tab"],
  ["X “For you” feed", "Hides the For you timeline; Following stays open"],
  ["TikTok feed", "Hides the feed and Explore, backs out of videos; Inbox and Me stay open"],
];

export const EditorScreen: React.FC<{
  mode?: "block" | "allow"; name: string; sites?: string[]; apps?: string[]; freshFrom?: number; fresh?: number; saved?: boolean; hlSave?: number; hlAdd?: number;
  partial?: number[]; hlPartial?: number; scroll?: number; dialog?: number; dialogPick?: number; tabSel?: number;
}> = ({ mode = "block", name, sites = [], apps = [], freshFrom = 99, fresh = 0, saved, hlSave = 0, hlAdd = 0, partial, hlPartial = -1, scroll = 0, dialog = 0, dialogPick = -1 }) => (
  <Device>
    <StatusBar />
    <div style={{ position: "absolute", top: 28, bottom: 43, insetInline: 0, overflow: "hidden" }}>
      <Body y={scroll} style={{ paddingTop: 8 }}>
        <Ghost style={{ paddingBottom: 0 }}>‹ Back</Ghost>
        <div style={{ height: 8 }} />
        <Field label="Profile name" value={name} />
        {mode === "block" ? (
          <>
            <Section title="BLOCKED WEBSITES">
              {sites.map((s, i) => <Item key={s} text={s} tag="+ subdomains" fresh={i >= freshFrom ? fresh : 0} />)}
              <T size={13} style={{ padding: "10px 0" }}>Add a site, e.g. youtube.com</T>
              <Rule />
            </Section>
            <Section title="BLOCKED APPLICATIONS">
              {apps.map((s, i) => <Item key={s} text={s} fresh={sites.length + i >= freshFrom ? fresh : 0} />)}
              <Ghost>+ Add application…</Ghost>
              <div style={{ background: hlAdd ? `rgba(236,48,19,${0.12 * hlAdd})` : undefined }}><Ghost>+ Add a saved group…</Ghost></div>
              <div style={{ background: hlSave ? `rgba(236,48,19,${0.12 * hlSave})` : undefined }}><Ghost>{saved ? "Saved to your groups" : "Save as group"}</Ghost></div>
            </Section>
          </>
        ) : (
          <>
            <Section title="ALLOWED APPLICATIONS">
              {apps.map((s, i) => <Item key={s} text={s} fresh={i >= freshFrom ? fresh : 0} />)}
              <Ghost>+ Add application…</Ghost>
              <T size={13} style={{ padding: "4px 0 8px" }}>Everything else closes during a session, except your launcher, phone, messages, Settings and keyboard. Websites aren't filtered: allow a browser and every site works.</T>
            </Section>
          </>
        )}
        {partial && (
          <Section title="PARTIAL BLOCKING · EXPERIMENTAL">
            {PARTIAL.map(([a, b], i) => (
              <React.Fragment key={a}>
                <ToggleRow label={a} sub={b} p={partial[i] ?? 0} mark={hlPartial === i} />
                <Rule />
              </React.Fragment>
            ))}
          </Section>
        )}
      </Body>
    </div>
    <TabBar labels={L.en.tabs} sel={1} />
    {dialog > 0 && (
      <Dialog p={dialog} title="Add a group">
        {["Work", "Weekend"].map((g, i) => (
          <div key={g} style={{ padding: "8px 0", background: dialogPick === i ? "rgba(236,48,19,0.12)" : undefined }}>
            <T size={17} weight={600} color={NF.text}>{g}</T>
            <T size={13}>{i ? "2 sites · 1 app" : "3 sites · 2 apps"}</T>
          </div>
        ))}
        <Ghost style={{ textAlign: "end", marginTop: 8 }}>Cancel</Ghost>
      </Dialog>
    )}
  </Device>
);

// ------------------------------------------------------------------ Block screen (dark)
export const BlockScreen: React.FC<{
  app: string; reason: string; timeLabel: string; time: string; tries: number; goal?: string; pass?: string; passNote?: string; need?: boolean; hlReason?: number; hlPass?: number;
}> = ({ app, reason, timeLabel, time, tries, goal, pass, passNote, need = true, hlReason = 0, hlPass = 0 }) => (
  <Device bg={NF.text}>
    <StatusBar dark />
    <div style={{ padding: "16px 24px 0", display: "flex", flexDirection: "column", height: SH - 28, boxSizing: "border-box" }}>
      <div style={{ flex: 1 }}>
        <Kicker color={NF.n400}>{`NowFocus closed ${app}`.toUpperCase()}</Kicker>
        <div style={{ height: 32 }} />
        <H size={48} color={NF.bg}>This can wait.</H>
        <div style={{ height: 12 }} />
        <div style={{ fontSize: 17, color: NF.n300, outline: hlReason ? `3px solid rgba(255,151,131,${hlReason})` : undefined, outlineOffset: 6 }}>{reason}</div>
        <div style={{ height: 16 }} />
        {[[timeLabel, time], ["Tries today", String(tries)]].map(([k, v]) => (
          <div key={k} style={{ display: "flex", padding: "4px 0" }}>
            <Kicker color={NF.n400}>{k.toUpperCase()}</Kicker>
            <div style={{ flex: 1 }} />
            <T size={15} weight={600} color={NF.bg}>{v}</T>
          </div>
        ))}
        {goal && (
          <>
            <div style={{ height: 32 }} />
            <Kicker color={NF.n400}>REMEMBER</Kicker>
            <div style={{ height: 8 }} />
            <T size={20} weight={600} color={NF.bg}>{goal}</T>
          </>
        )}
      </div>
      <Primary>Back to focus</Primary>
      {pass && (
        <>
          <div style={{ height: 8 }} />
          <Outline dark style={{ outline: hlPass ? `3px solid rgba(255,151,131,${hlPass})` : undefined, outlineOffset: 4 }}>{pass}</Outline>
          {passNote && <T size={13} color={NF.n400} style={{ marginTop: 4 }}>{passNote}</T>}
        </>
      )}
      {need && <Ghost style={{ marginTop: 8 }}>I really need it</Ghost>}
      <div style={{ height: 16 }} />
    </div>
  </Device>
);

// ------------------------------------------------------------------ Bedtime Wind-Down (dark)
export const BedtimeScreen: React.FC<{ on: number; quiet: number; lock: number; hlTime?: number; hlHero?: number }> = ({ on, quiet, lock, hlTime = -1, hlHero = 0 }) => {
  const rule = NF.n700;
  return (
    <Device bg={NF.text}>
      <StatusBar dark />
      <Body style={{ paddingTop: 0 }}>
        <div style={{ display: "flex", alignItems: "center", padding: "8px 0" }}>
          <svg width={22} height={22} viewBox="0 0 24 24" fill="none" stroke={NF.bg} strokeWidth={2} style={{ margin: "0 11px", transform: "none" }}><path d="m12 19-7-7 7-7M19 12H5" /></svg>
          <H size={20} color={NF.bg} style={{ flex: 1 }}>Bedtime Wind-Down</H>
          <svg width={20} height={20} viewBox="0 0 24 24" fill="none" stroke={NF.bg} strokeWidth={2}><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" /></svg>
        </div>
        <Rule thick color={NF.n600} />
        <div style={{ height: 24 }} />
        <H size={32} color={NF.bg} style={{ lineHeight: "34px", textShadow: hlHero ? `0 0 ${24 * hlHero}px rgba(255,151,131,${0.8 * hlHero})` : undefined }}>Your evening, gently dimmed.</H>
        <div style={{ height: 8 }} />
        <T size={14} color={NF.n300}>Each night from wind-down until wake, your chosen profile is blocked as a locked session you can't end early. Tap a time to change it.</T>
        <div style={{ height: 16 }} />
        <ToggleRow dark label="On every night" sub="Applies automatically, no need to start it" p={on} />
        <Rule color={rule} />
        <div style={{ height: 12 }} />
        <Rule thick color={NF.bg} />
        <div style={{ display: "flex" }}>
          {[["Wind-down", "9:30", "PM"], ["Sleep", "10:30", "PM"], ["Wake", "6:30", "AM"]].map(([k, v, ap], i) => (
            <React.Fragment key={k}>
              {i > 0 && <div style={{ width: 1, background: NF.n600 }} />}
              <div style={{ flex: 1, padding: "12px 6px", whiteSpace: "nowrap", background: hlTime === i ? "rgba(255,151,131,0.16)" : undefined }}>
                <Kicker color={NF.n400}>{k}</Kicker>
                <H size={25} color={i === 1 ? NF.a400 : NF.bg}>{v}<span style={{ fontSize: 12 }}> {ap}</span></H>
              </div>
            </React.Fragment>
          ))}
        </div>
        <Rule thick color={NF.bg} />
        <div style={{ height: 24 }} />
        <Kicker color={NF.n400}>DURING WIND-DOWN</Kicker>
        <Rule color={rule} style={{ marginTop: 4 }} />
        <ToggleRow dark label="Quiet notifications" sub="Only priority notifications come through" p={quiet} />
        <ToggleRow dark label="Lock phone at sleep time" sub="Locks once, at your sleep time" p={lock} />
      </Body>
    </Device>
  );
};

// ------------------------------------------------------------------ Settings (language switch)
export const SettingsScreen: React.FC<{ lang: Lang; sel: number; hl?: number }> = ({ lang, sel, hl = 0 }) => {
  const t = L[lang];
  return (
    <Device rtl={lang === "ar"}>
      <StatusBar />
      <Body style={{ paddingTop: 8 }}>
        <H size={28} style={{ padding: "8px 0" }}>{t.settings}</H>
        <Rule thick />
        <div style={{ height: 16 }} />
        <Kicker color={NF.n700}>{t.language}</Kicker>
        <div style={{ height: 8 }} />
        <div style={{ outline: hl ? `3px solid rgba(236,48,19,${hl})` : undefined, outlineOffset: 3 }}><Seg options={t.langs} sel={sel} /></div>
        <div style={{ height: 24 }} />
        <Rule />
        <div style={{ padding: "12px 0" }}>
          <T size={17} weight={600} color={NF.text}>{t.about}</T>
          <T size={13}>{t.aboutSub}</T>
        </div>
        <div style={{ padding: "12px 0" }}>
          <T size={17} weight={600} color={NF.text}>{t.report}</T>
          <T size={13}>{t.reportSub}</T>
        </div>
        <Rule />
      </Body>
      <TabBar labels={t.tabs} sel={4} />
    </Device>
  );
};

export { SW, Toggle };

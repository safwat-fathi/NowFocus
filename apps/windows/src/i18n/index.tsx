import { createContext, useContext, useEffect, useMemo, type ReactNode } from "react";
import { en, type Key, type Message } from "./en";
import { ar } from "./ar";

export type Lang = "en" | "ar";
/** What the user picked in Settings. "system" follows the Windows display language. */
export type LangPref = "system" | Lang;
export type Params = Record<string, string | number>;
export type T = (key: Key, params?: Params) => string;

const STORED_KEY = "nowfocus.language";
const dictionaries: Record<Lang, Record<Key, Message>> = { en, ar };

export function resolveLang(pref: LangPref): Lang {
  if (pref !== "system") return pref;
  return navigator.language.toLowerCase().startsWith("ar") ? "ar" : "en";
}

/** The last language the backend reported. Read before the first state poll returns, so the first paint is right. */
export function storedPref(): LangPref {
  try {
    const v = localStorage.getItem(STORED_KEY);
    return v === "en" || v === "ar" ? v : "system";
  } catch {
    return "system";
  }
}

function storePref(pref: LangPref) {
  try {
    localStorage.setItem(STORED_KEY, pref);
  } catch {
    /* private mode: the next state poll corrects it */
  }
}

/** lang/dir on <html>: CSS logical properties and :lang(ar) rules key off these. */
export function applyDocument(lang: Lang) {
  document.documentElement.lang = lang;
  document.documentElement.dir = lang === "ar" ? "rtl" : "ltr";
}

/** Intl locale for dates and numbers. Arabic with Latin digits (nu-latn), as on the website. English follows the
 * Windows regional format when it is English too, so a UK clock stays 24-hour. */
export function intlLocale(lang: Lang): string | undefined {
  if (lang === "ar") return "ar-u-nu-latn";
  return navigator.language.toLowerCase().startsWith("en") ? undefined : "en";
}

function render(message: Message, lang: Lang, params?: Params): string {
  const text =
    typeof message === "string" ? message : (message[new Intl.PluralRules(lang).select(Number(params?.count ?? 0))] ?? message.other);
  return params ? text.replace(/\{(\w+)\}/g, (_, k: string) => String(params[k] ?? `{${k}}`)) : text;
}

interface Fmt {
  /** "3:45 PM" / "15:45" */
  time(ms: number | string): string;
  /** "Monday, Oct 5" */
  longDay(ms: number | string): string;
  /** "Oct 5" */
  shortDay(ms: number | string): string;
  /** "1h 05m" from minutes. */
  hm(minutes: number): string;
}

interface I18n {
  lang: Lang;
  t: T;
  fmt: Fmt;
}

const Ctx = createContext<I18n | null>(null);

export function I18nProvider({ pref, children }: { pref: LangPref; children: ReactNode }) {
  const lang = resolveLang(pref);
  useEffect(() => {
    storePref(pref);
    applyDocument(lang);
  }, [pref, lang]);

  const value = useMemo<I18n>(() => {
    const dict = dictionaries[lang];
    const t: T = (key, params) => render(dict[key], lang, params);
    const locale = intlLocale(lang);
    const date = (ms: number | string, opts: Intl.DateTimeFormatOptions) => new Date(ms).toLocaleDateString(locale, opts);
    const fmt: Fmt = {
      time: (ms) => new Date(ms).toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" }),
      longDay: (ms) => date(ms, { weekday: "long", month: "short", day: "numeric" }),
      shortDay: (ms) => date(ms, { month: "short", day: "numeric" }),
      hm: (minutes) => t("unit.hm", { h: Math.floor(minutes / 60), m: String(minutes % 60).padStart(2, "0") }),
    };
    return { lang, t, fmt };
  }, [lang]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useI18n(): I18n {
  const v = useContext(Ctx);
  if (!v) throw new Error("useI18n outside <I18nProvider>");
  return v;
}

/** `const t = useT()` for components that only need words. */
export function useT(): T {
  return useI18n().t;
}

/** For keys built from backend ids: the translation, or [fallback] when the id is newer than this build. */
export function tOr(t: T, key: string, fallback: string, params?: Params): string {
  return key in en ? t(key as Key, params) : fallback;
}

/** The backend sends refusals as English text or as `code|arg|...`. Known codes are translated; anything else
 * (a server message, an OS error) is shown as it arrived. */
export function errorText(e: unknown, t: T): string {
  const raw = String(e).replace(/^Error: /, "");
  const [code, ...args] = raw.split("|");
  const key = `err.${code}` as Key;
  if (!(key in en)) return raw;
  return t(key, Object.fromEntries(args.map((a, i) => [String(i), a])));
}

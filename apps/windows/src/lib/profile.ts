import type { T } from "../i18n";
import type { Profile } from "../types";

/** What a profile holds, in a line: "3 sites · 2 apps" for a blocklist, "2 apps allowed" for a whitelist. */
export function profileSummary(p: Profile, t: T): string {
  if (p.mode === "blocklist") {
    return t("prof.summary", {
      sites: t("count.sites", { count: p.domains.length }),
      apps: t("count.apps", { count: p.applications.length }),
    });
  }
  return p.applications.length === 0 ? t("prof.noneAllowed") : t("prof.allowedCount", { count: p.applications.length });
}

/** False for a whitelist with no app on this PC (it would close everything) and for any whitelist on a Free account. */
export function canStart(p: Profile | undefined, pro: boolean): boolean {
  return !!p && (p.mode === "blocklist" || (pro && p.applications.length > 0));
}

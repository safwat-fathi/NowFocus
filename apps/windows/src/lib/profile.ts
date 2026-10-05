import type { Profile } from "../types";

/** What a profile holds, in a line: "3 sites · 2 apps" for a blocklist, "2 apps allowed" for a whitelist. */
export function profileSummary(p: Profile): string {
  if (p.mode === "blocklist") return `${p.domains.length} sites · ${p.applications.length} apps`;
  return p.applications.length === 0
    ? "No apps allowed yet"
    : `${p.applications.length} ${p.applications.length === 1 ? "app" : "apps"} allowed`;
}

/** False for a whitelist with no app on this PC: it would close everything, so it can't start. */
export function canStart(p: Profile | undefined): boolean {
  return !!p && (p.mode === "blocklist" || p.applications.length > 0);
}

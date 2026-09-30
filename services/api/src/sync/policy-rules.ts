type Data = Record<string, any>;

const isObj = (v: unknown): v is Data => typeof v === 'object' && v !== null && !Array.isArray(v);
const live = (rules: unknown): Data[] => (Array.isArray(rules) ? rules.filter(isObj).filter((r) => r.enabled !== false) : []);
const domainKey = (r: Data) => String(r.domain ?? '').trim().toLowerCase();
const appKey = (r: Data) => `${r.platform}\u0000${r.nativeIdentifier}`; // identifiers are case-sensitive (Android packages)

/**
 * Mid-session policy editing (focus_app_technical_architecture.md §4.5): while a session runs on a policy,
 * additions are fine but no existing block may be removed, disabled or weakened — in every enforcement mode.
 * Returns why the edit is refused, or null. `prev`/`next` are the stored and the incoming policy `data`.
 */
export function checkPolicyEdit(prev: Data, next: Data): string | null {
  if (prev.mode !== undefined && next.mode !== prev.mode) return 'mode cannot change while a session is running on this policy';

  const nextDomains = new Map(live(next.domainRules).map((r) => [domainKey(r), r]));
  for (const r of live(prev.domainRules)) {
    const n = nextDomains.get(domainKey(r));
    if (!n) return `"${r.domain}" cannot be removed or disabled while a session is running on this policy`;
    if (r.includeSubdomains !== false && n.includeSubdomains === false) return `subdomain blocking for "${r.domain}" cannot be turned off while a session is running`;
  }

  const nextApps = new Set(live(next.applicationRules).map(appKey));
  for (const r of live(prev.applicationRules)) {
    if (!nextApps.has(appKey(r))) return `"${r.nativeIdentifier}" (${r.platform}) cannot be removed or disabled while a session is running on this policy`;
  }

  // Feed/partial rules are opaque to the server: any entry that was there must still be there.
  for (const k of ['feedRules', 'partial']) {
    const have = new Set((Array.isArray(next[k]) ? next[k] : []).map((v: unknown) => JSON.stringify(v)));
    for (const v of Array.isArray(prev[k]) ? prev[k] : []) {
      if (!have.has(JSON.stringify(v))) return `${k} entry ${JSON.stringify(v)} cannot be removed while a session is running on this policy`;
    }
  }
  return null;
}

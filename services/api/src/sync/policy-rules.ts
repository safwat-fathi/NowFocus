type Data = Record<string, any>;

const isObj = (v: unknown): v is Data => typeof v === 'object' && v !== null && !Array.isArray(v);
const live = (rules: unknown): Data[] => (Array.isArray(rules) ? rules.filter(isObj).filter((r) => r.enabled !== false) : []);
const domainKey = (r: Data) => String(r.domain ?? '').trim().toLowerCase();
const appKey = (r: Data) => `${r.platform}\u0000${r.nativeIdentifier}`; // identifiers are case-sensitive (Android packages)

/**
 * Allowlist: the rules are what stays open, so the direction flips. Narrowing (removing, disabling) is fine;
 * allowing one more app or site, re-enabling one, or widening a site to its subdomains would weaken the session.
 */
function checkAllowlistEdit(prev: Data, next: Data): string | null {
  const prevDomains = new Map(live(prev.domainRules).map((r) => [domainKey(r), r]));
  for (const r of live(next.domainRules)) {
    const p = prevDomains.get(domainKey(r));
    if (!p) return `"${r.domain}" cannot be allowed while a session is running on this policy`;
    if (p.includeSubdomains === false && r.includeSubdomains !== false) return `subdomain access for "${r.domain}" cannot be turned on while a session is running`;
  }
  const prevApps = new Set(live(prev.applicationRules).map(appKey));
  for (const r of live(next.applicationRules)) {
    if (!prevApps.has(appKey(r))) return `"${r.nativeIdentifier}" (${r.platform}) cannot be allowed while a session is running on this policy`;
  }
  // A device with no allowed app on its platform enforces nothing, so emptying a platform would end the session there.
  const nextPlatforms = new Set(live(next.applicationRules).map((r) => r.platform));
  for (const p of new Set(live(prev.applicationRules).map((r) => r.platform))) {
    if (!nextPlatforms.has(p)) return `the last allowed ${p} app cannot be removed while a session is running on this policy`;
  }
  return null;
}

/**
 * Mid-session policy editing (focus_app_technical_architecture.md §4.5): while a session runs on a policy,
 * nothing may be loosened — in every enforcement mode. For a blocklist that means additions are fine but no
 * existing block may be removed, disabled or weakened; for an allowlist it is the other way round.
 * Returns why the edit is refused, or null. `prev`/`next` are the stored and the incoming policy `data`.
 */
export function checkPolicyEdit(prev: Data, next: Data): string | null {
  if (prev.mode !== undefined && next.mode !== prev.mode) return 'mode cannot change while a session is running on this policy';

  if (prev.mode === 'allowlist') {
    const loosened = checkAllowlistEdit(prev, next);
    if (loosened) return loosened;
    return checkOpaqueRules(prev, next);
  }

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

  return checkOpaqueRules(prev, next);
}

// Feed/partial rules are opaque to the server and block inside an app, in either mode: any entry that was there must still be there.
function checkOpaqueRules(prev: Data, next: Data): string | null {
  for (const k of ['feedRules', 'partial']) {
    const have = new Set((Array.isArray(next[k]) ? next[k] : []).map((v: unknown) => JSON.stringify(v)));
    for (const v of Array.isArray(prev[k]) ? prev[k] : []) {
      if (!have.has(JSON.stringify(v))) return `${k} entry ${JSON.stringify(v)} cannot be removed while a session is running on this policy`;
    }
  }
  return null;
}

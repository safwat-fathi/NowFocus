import { checkPolicyEdit } from './policy-rules.js';

const policy = (over: Record<string, any> = {}) => ({
  id: 'p1', name: 'Work', mode: 'blocklist',
  domainRules: [
    { id: 'd1', domain: 'youtube.com', includeSubdomains: true, enabled: true },
    { id: 'd2', domain: 'x.com', includeSubdomains: true, enabled: true },
  ],
  applicationRules: [{ id: 'a1', platform: 'macos', nativeIdentifier: 'com.apple.Music', displayName: 'Music', enabled: true }],
  feedRules: ['SHORTS'],
  ...over,
});

describe('editing a policy while a session runs on it', () => {
  it('allows additions, renames, and fields it does not know about', () => {
    const next = policy({
      name: 'Renamed', futureField: { a: 1 }, feedRules: ['SHORTS', 'REELS'],
      domainRules: [...policy().domainRules, { id: 'd3', domain: 'reddit.com', enabled: true }],
      applicationRules: [...policy().applicationRules, { id: 'a2', platform: 'windows', nativeIdentifier: 'Steam.exe', enabled: true }],
    });
    expect(checkPolicyEdit(policy(), next)).toBeNull();
    expect(checkPolicyEdit(policy(), policy())).toBeNull();
  });

  it('rejects removing a domain', () => {
    expect(checkPolicyEdit(policy(), policy({ domainRules: [policy().domainRules[0]] }))).toMatch(/x\.com/);
  });

  it('rejects disabling a domain, or turning off its subdomain blocking', () => {
    const [a, b] = policy().domainRules;
    expect(checkPolicyEdit(policy(), policy({ domainRules: [a, { ...b, enabled: false }] }))).toMatch(/x\.com/);
    expect(checkPolicyEdit(policy(), policy({ domainRules: [a, { ...b, includeSubdomains: false }] }))).toMatch(/subdomain/);
  });

  it('identifies a domain by its name, not its rule id or case', () => {
    const renamedIds = policy({ domainRules: [{ id: 'new1', domain: ' YouTube.com ', enabled: true }, { id: 'new2', domain: 'X.COM' }] });
    expect(checkPolicyEdit(policy(), renamedIds)).toBeNull();
  });

  it('identifies an app rule by platform + native identifier', () => {
    expect(checkPolicyEdit(policy(), policy({ applicationRules: [] }))).toMatch(/com\.apple\.Music/);
    const otherPlatform = [{ id: 'a1', platform: 'windows', nativeIdentifier: 'com.apple.Music', enabled: true }];
    expect(checkPolicyEdit(policy(), policy({ applicationRules: otherPlatform }))).toMatch(/com\.apple\.Music/);
  });

  it('lets an already-disabled rule be removed', () => {
    const prev = policy({ domainRules: [{ id: 'd1', domain: 'old.com', enabled: false }] });
    expect(checkPolicyEdit(prev, policy({ domainRules: [] }))).toBeNull();
  });

  it('rejects dropping a feed/partial rule, including ones it has never heard of', () => {
    expect(checkPolicyEdit(policy(), policy({ feedRules: [] }))).toMatch(/feedRules/);
    expect(checkPolicyEdit(policy({ partial: ['YT_SHORTS', 'FUTURE_X'] }), policy({ partial: ['YT_SHORTS'] }))).toMatch(/partial/);
  });

  it('rejects flipping blocklist/allowlist mode', () => {
    expect(checkPolicyEdit(policy(), policy({ mode: 'allowlist' }))).toMatch(/mode/);
  });
});

describe('editing an allowlist policy while a session runs on it', () => {
  // The rules are what stays open, so the direction flips: removing one narrows the session, adding one widens it.
  const allow = (over: Record<string, any> = {}) => policy({
    mode: 'allowlist',
    domainRules: [{ id: 'd1', domain: 'github.com', includeSubdomains: true, enabled: true }],
    applicationRules: [
      { id: 'a1', platform: 'android', nativeIdentifier: 'com.android.chrome', enabled: true },
      { id: 'a2', platform: 'windows', nativeIdentifier: 'C:\\Apps\\Code.exe', enabled: true },
    ],
    ...over,
  });

  it('allows removing or disabling an allowed app or site, renames, and unknown fields', () => {
    const [chrome, code] = allow().applicationRules;
    expect(checkPolicyEdit(allow(), allow({ applicationRules: [chrome] }))).toBeNull();
    expect(checkPolicyEdit(allow(), allow({ applicationRules: [chrome, { ...code, enabled: false }] }))).toBeNull();
    expect(checkPolicyEdit(allow(), allow({ domainRules: [] }))).toBeNull();
    expect(checkPolicyEdit(allow(), allow({ name: 'Renamed', futureField: 1 }))).toBeNull();
    expect(checkPolicyEdit(allow(), allow())).toBeNull();
  });

  it('rejects allowing one more app or site', () => {
    const more = { id: 'a3', platform: 'android', nativeIdentifier: 'com.instagram.android', enabled: true };
    expect(checkPolicyEdit(allow(), allow({ applicationRules: [...allow().applicationRules, more] }))).toMatch(/com\.instagram\.android/);
    const site = { id: 'd2', domain: 'reddit.com', enabled: true };
    expect(checkPolicyEdit(allow(), allow({ domainRules: [...allow().domainRules, site] }))).toMatch(/reddit\.com/);
  });

  it('rejects re-enabling an app that was switched off, or widening a site to its subdomains', () => {
    const [chrome, code] = allow().applicationRules;
    const off = allow({ applicationRules: [chrome, { ...code, enabled: false }] });
    expect(checkPolicyEdit(off, allow())).toMatch(/Code\.exe/);
    const narrow = allow({ domainRules: [{ id: 'd1', domain: 'github.com', includeSubdomains: false, enabled: true }] });
    expect(checkPolicyEdit(narrow, allow())).toMatch(/subdomain/);
  });

  it('still refuses to drop a partial rule, which blocks inside an allowed app', () => {
    expect(checkPolicyEdit(allow({ partial: ['YT_SHORTS'] }), allow({ partial: [] }))).toMatch(/partial/);
  });

  it('still rejects flipping the mode', () => {
    expect(checkPolicyEdit(allow(), allow({ mode: 'blocklist' }))).toMatch(/mode/);
  });
});

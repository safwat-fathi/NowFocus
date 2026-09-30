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

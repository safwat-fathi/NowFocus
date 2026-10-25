import { isPro } from './plan.js';

const now = new Date('2026-11-01T00:00:00Z');
const at = (iso: string) => new Date(iso);

describe('isPro', () => {
  it('is false for a free account, even with a future end date', () => {
    expect(isPro({ plan: 'free', planExpiresAt: null }, now)).toBe(false);
    expect(isPro({ plan: 'free', planExpiresAt: at('2027-01-01T00:00:00Z') }, now)).toBe(false);
  });
  it('never lapses without an end date', () => expect(isPro({ plan: 'pro', planExpiresAt: null }, now)).toBe(true));
  it('holds until the end date', () => expect(isPro({ plan: 'pro', planExpiresAt: at('2026-11-01T00:00:01Z') }, now)).toBe(true));
  it('lapses at and after the end date', () => {
    expect(isPro({ plan: 'pro', planExpiresAt: now }, now)).toBe(false);
    expect(isPro({ plan: 'pro', planExpiresAt: at('2026-10-31T23:59:59Z') }, now)).toBe(false);
  });
});
